package net.muxigame.outbreak.ai;

import com.github.tartaricacid.touhoulittlemaid.compat.gun.common.task.TaskGunAttack;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.entity.IGunOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.muxigame.outbreak.*;
import net.muxigame.outbreak.equipment.*;
import java.util.*;

/** Bounded per-room coordinator. TLM supplies Brain navigation and native TaCZ gun operation. */
public final class CampaignBots {
    public static final String SESSION="muxi_outbreak_ai_session";
    private static final net.minecraft.server.level.TicketType<UUID> TICKET=net.minecraft.server.level.TicketType.create("muxi_outbreak_ai",UUID::compareTo,80);
    private final Map<UUID,net.minecraft.world.level.ChunkPos> chunks=new HashMap<>();
    private final OutbreakSession room;
    private final Map<UUID,CampaignMaid> entities=new LinkedHashMap<>();
    private final Set<UUID> eliminated=new HashSet<>();
    private final Map<UUID,UUID> rescuing=new HashMap<>();
    public CampaignBots(OutbreakSession room) { this.room=room; }
    public static void registerCombat(net.neoforged.bus.api.IEventBus bus) {
        var explosionTargets=new WeakHashMap<net.neoforged.neoforge.event.level.ExplosionEvent.Detonate,List<Entity>>();
        bus.addListener(net.neoforged.bus.api.EventPriority.HIGHEST,(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate event)->{
            explosionTargets.put(event,event.getAffectedEntities().stream().filter(CampaignBots::isCampaignEntity).toList());
        });
        bus.addListener(net.neoforged.bus.api.EventPriority.LOWEST,(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate event)->{
            // TLM removes all tamed maids from native explosion victims. Restore only our original campaign victims.
            var originals=explosionTargets.remove(event);if(originals==null)return;
            for(var entity:originals)if(isCampaignEntity(entity)&&!event.getAffectedEntities().contains(entity))event.getAffectedEntities().add(entity);
        });
        bus.addListener(net.neoforged.bus.api.EventPriority.LOWEST,true,(com.github.tartaricacid.touhoulittlemaid.api.event.MaidHurtEvent event)->{
            if(!(event.getMaid() instanceof CampaignMaid maid))return;
            var room=maid.room();var source=event.getSource();
            boolean bullet=source.is(com.tacz.guns.init.ModDamageTypes.BULLETS_TAG)||source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)&&source.getDirectEntity() instanceof com.tacz.guns.entity.EntityKineticBullet;
            // TLM cancels all gun damage on tamed maids before vanilla living damage events.
            // Remove only this campaign maid's bullet immunity; the room's damage events remain authoritative.
            if(bullet&&room.phase==OutbreakSession.Phase.RUNNING&&!room.downed.contains(maid.getUUID())&&room.bots!=null&&room.bots.find(maid.getUUID())==maid)event.setCanceled(false);
        });
    }
    public static boolean isCampaignEntity(Entity entity) {
        return entity instanceof CampaignMaid maid&&maid.room()!=null&&maid.room().bots!=null&&maid.room().bots.entities.get(entity.getUUID())==entity;
    }
    public List<LivingEntity> living() { return entities.values().stream().filter(m->m.isAlive()&&!m.isRemoved()&&!eliminated.contains(m.getUUID())).map(m->(LivingEntity)m).toList(); }
    /** Identity must survive the zero-health instant so LivingDeathEvent can incapacitate the maid. */
    public LivingEntity find(UUID id) { return eliminated.contains(id)?null:entities.get(id); }
    public boolean owns(UUID id) { return entities.containsKey(id); }
    public boolean rescuing(UUID helper,UUID target) { return target.equals(rescuing.get(helper)); }
    public void spawn(ServerLevel level) {
        if(!entities.isEmpty()||room.aiSeats.isEmpty())return;
        if(room.aiModelId.isBlank()||room.aiTexture.isBlank())throw new IllegalStateException("AI YSM model identity has not been verified");
        try {
            int index=0;
            for(UUID seat:room.aiSeats) {
                var maid=new CampaignMaid(level,room);
                maid.setUUID(seat);maid.setOwnerUUID(room.host);maid.setTame(true,false);
                maid.setPersistenceRequired();maid.setHomeModeEnable(false);maid.setPickup(false);maid.setRideable(false);
                maid.setSchedule(MaidSchedule.ALL);maid.setHunger(20);maid.setCanClimb(true);
                maid.setCustomName(Component.literal("酒狐队友 "+(++index)));maid.setCustomNameVisible(true);
                maid.setYsmModel(room.aiModelId,room.aiTexture,Component.literal("酒狐女仆"));maid.setIsYsmModel(true);
                maid.getPersistentData().putString(SESSION,room.id.toString());maid.getPersistentData().putString("muxi_outbreak_ai_seat",seat.toString());maid.addTag("muxi_outbreak_ai");
                maid.setTask(new TaskGunAttack() {
                    @Override public boolean canSee(EntityMaid actor,LivingEntity target) { return actor.canAttack(target)&&clearShot(actor,target)&&super.canSee(actor,target); }
                    @Override public void performRangedAttack(EntityMaid actor,LivingEntity target,float distance) {
                        if(actor.canAttack(target)&&clearShot(actor,target))super.performRangedAttack(actor,target,distance);
                    }
                });
                place(level,maid,room.map.sectionStart(room.section));
                entities.put(seat,maid);retainChunk(level,maid);
                if(!level.addFreshEntity(maid))throw new IllegalStateException("Could not spawn AI seat "+seat);
            }
        } catch(RuntimeException error) { cleanup();throw error; }
    }
    private boolean clearShot(EntityMaid maid,LivingEntity target) {
        Vec3 from=maid.getEyePosition(),to=target.getEyePosition();
        for(LivingEntity ally:survivors())if(ally!=maid&&ally.getBoundingBox().inflate(.25).clip(from,to).isPresent())return false;
        return maid.hasLineOfSight(target);
    }
    private List<LivingEntity> survivors() {
        List<LivingEntity> result=new ArrayList<>(living());
        for(UUID id:room.alive){var player=roomServer().getPlayerList().getPlayer(id);if(player!=null)result.add(player);}return result;
    }
    private net.minecraft.server.MinecraftServer roomServer() { return entities.values().iterator().next().getServer(); }
    public void tick(ServerLevel level,int now) {
        rescuing.clear();if(entities.isEmpty())return;
        boolean active=room.phase==OutbreakSession.Phase.RUNNING;
        for(var maid:entities.values()){
            if(maid.isRemoved()||!maid.isAlive())throw new IllegalStateException("Missing AI teammate "+maid.getUUID());
            if(now%20==0)retainChunk(level,maid);
        }
        List<LivingEntity> party=survivors();
        for(LivingEntity living:living()) {
            CampaignMaid maid=(CampaignMaid)living;
            if(maid.level()!=level)throw new IllegalStateException("AI left campaign dimension");
            boolean down=room.downed.contains(maid.getUUID());
            maid.setNoAi(down);maid.setInSittingPose(down);maid.setHunger(20);
            if(down){maid.getNavigation().stop();continue;}
            float temporary=room.temporaryHealth.getOrDefault(maid.getUUID(),0f);
            if(temporary>0){float loss=Math.min(temporary,Math.min(Math.max(0,maid.getHealth()-1),maid.getMaxHealth()*.0027f/20f));maid.setHealth(maid.getHealth()-loss);room.temporaryHealth.put(maid.getUUID(),temporary-loss);}
            LivingEntity rescue=active?party.stream().filter(p->room.downed.contains(p.getUUID())).min(Comparator.comparingDouble(maid::distanceToSqr)).orElse(null):null;
            if(rescue!=null) {
                maid.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
                walk(maid,rescue.position(),1);
                if(SurvivorAiRules.canRescue(true,false,true,maid.distanceToSqr(rescue),maid.hasLineOfSight(rescue))) {
                    maid.getNavigation().stop();maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);rescuing.put(maid.getUUID(),rescue.getUUID());
                }
                continue;
            }
            if(now%10!=0)continue;
            ItemStack current=maid.getMainHandItem(),backup=maid.getMaidInv().getStackInSlot(0);
            IGun gun=IGun.getIGunOrNull(current),other=IGun.getIGunOrNull(backup);
            if(gun!=null&&other!=null&&gun.getCurrentAmmoCount(current)==0&&!gun.hasBulletInBarrel(current)&&reserve(maid,CampaignInventory.ammoId(current))==0&&
                (other.getCurrentAmmoCount(backup)>0||other.hasBulletInBarrel(backup)||reserve(maid,CampaignInventory.ammoId(backup))>0)) {
                IGunOperator.fromLivingEntity(maid).cancelReload();maid.getMaidInv().setStackInSlot(0,current);maid.setItemSlot(EquipmentSlot.MAINHAND,backup);
            }
            // All pickups use actual map nodes and shared stock; no initial free equipment.
            var supply=room.supplies.botTarget(maid,maid.getMaidInv());
            if(supply!=null&&(!active||maid.getTarget()==null)) {
                walk(maid,supply.pos.getBottomCenter(),1);
                if(maid.distanceToSqr(supply.pos.getCenter())<=12.25)room.supplies.takeBot(maid,maid.getMaidInv(),supply.id);
            }
            if(active) {
                LivingEntity target=room.infected.stream().map(level::getEntity).filter(e->e instanceof LivingEntity).map(e->(LivingEntity)e)
                    .filter(maid::canAttack).filter(e->maid.distanceToSqr(e)<=32*32).filter(e->clearShot(maid,e))
                    .min(Comparator.comparingDouble(e->SurvivorAiRules.targetScore(maid.distanceToSqr(e),room.infectedKinds.get(e.getUUID())!=net.muxigame.outbreak.infected.InfectedKind.COMMON,e instanceof Mob mob&&mob.getTarget()!=null&&party.contains(mob.getTarget())))).orElse(null);
                if(target!=null){maid.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET,target);continue;}
            }
            maid.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
            if(supply!=null)continue;
            LivingEntity leader=party.stream().filter(p->p!=maid&&!room.downed.contains(p.getUUID()))
                .filter(p->p instanceof net.minecraft.server.level.ServerPlayer).min(Comparator.comparingDouble(maid::distanceToSqr)).orElse(null);
            if(leader!=null) {
                boolean entering=room.map.safeRooms().stream().anyMatch(s->s.nextSection()==room.section+1&&s.contains(leader.position())&&!s.contains(maid.position()));
                if(maid.distanceToSqr(leader)>(entering?1:9))walk(maid,leader.position(),entering?0:1);
            } else if(active) {
                var safe=room.map.safeRooms().stream().filter(s->s.nextSection()==room.section+1).findFirst().orElse(null);
                if(safe!=null){
                    boolean allInside=living().stream().allMatch(e->safe.contains(e.position()));
                    if(allInside){
                        if(!room.checkpointDoors.closed(level,safe)&&!safe.doors().isEmpty())room.checkpointDoors.interact(level,safe.doors().getFirst(),room.map,room.section,true);
                        continue;
                    }
                    if(room.checkpointDoors.closed(level,safe)&&safe.doors().stream().anyMatch(p->maid.distanceToSqr(p.getCenter())<=16)&&!safe.doors().isEmpty())room.checkpointDoors.interact(level,safe.doors().getFirst(),room.map,room.section,true);
                }
                // Remaining AI can reach the next safe room and restore eliminated humans there.
                var route=room.map.chapters().get(room.section).route();
                if(!route.isEmpty()){
                    int nearest=0;double distance=Double.MAX_VALUE;
                    for(int i=0;i<route.size();i++){double d=maid.distanceToSqr(route.get(i).getCenter());if(d<distance){distance=d;nearest=i;}}
                    Vec3 destination=route.get(Math.min(route.size()-1,nearest+1)).getBottomCenter();
                    if(safe!=null&&nearest>=route.size()-2)destination=new Vec3((safe.min().getX()+safe.max().getX()+1)*.5,Math.min(safe.min().getY(),safe.max().getY()),(safe.min().getZ()+safe.max().getZ()+1)*.5);
                    walk(maid,destination,0);
                }
            }
        }
    }
    private void retainChunk(ServerLevel level,EntityMaid maid) {
        var current=new net.minecraft.world.level.ChunkPos(maid.blockPosition());var previous=chunks.put(maid.getUUID(),current);
        if(previous!=null&&!previous.equals(current))level.getChunkSource().removeRegionTicket(TICKET,previous,2,maid.getUUID());
        level.getChunkSource().addRegionTicket(TICKET,current,2,maid.getUUID());
    }
    private static int reserve(EntityMaid maid,String id) {
        int count=0;for(int i=1;i<maid.getMaidInv().getSlots();i++){ItemStack ammo=maid.getMaidInv().getStackInSlot(i);IAmmo type=IAmmo.getIAmmoOrNull(ammo);if(type!=null&&type.getAmmoId(ammo).toString().equals(id))count+=ammo.getCount();}return count;
    }
    private static void walk(EntityMaid maid,Vec3 position,int tolerance) {
        maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET,new WalkTarget(position,1f,tolerance));
        maid.getNavigation().moveTo(position.x,position.y,position.z,1);
    }
    private static void place(ServerLevel level,EntityMaid maid,BlockPos start) {
        for(int radius=0;radius<=4;radius++)for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++){
            BlockPos at=start.offset(x,0,z);maid.moveTo(at.getX()+.5,at.getY(),at.getZ()+.5,0,0);
            if(!level.getBlockState(at.below()).getCollisionShape(level,at.below()).isEmpty()&&level.noCollision(maid,maid.getBoundingBox())&&level.getEntitiesOfClass(LivingEntity.class,maid.getBoundingBox().inflate(.1),e->e!=maid&&e.isAlive()).isEmpty()){maid.fallDistance=0;return;}
        }
        throw new IllegalStateException("No supported AI spawn position near "+start);
    }
    public void eliminate(UUID id) {
        var maid=entities.get(id);if(maid==null)return;eliminated.add(id);maid.setNoAi(true);maid.setInSittingPose(true);maid.getNavigation().stop();
        maid.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);IGunOperator.fromLivingEntity(maid).cancelReload();
        maid.setInvulnerable(true);maid.setInvisible(true);maid.noPhysics=true;maid.setNoGravity(true);maid.setHealth(1);
    }
    public void nextSection(ServerLevel level) {
        for(var maid:entities.values()) {
            if(maid.isRemoved())throw new IllegalStateException("Missing campaign AI entity");
            if(eliminated.remove(maid.getUUID())){maid.setHealth(maid.getMaxHealth()*.5f);room.incapCount.remove(maid.getUUID());room.temporaryHealth.remove(maid.getUUID());}
            maid.setInvisible(false);maid.setInvulnerable(false);maid.noPhysics=false;maid.setNoGravity(false);maid.setNoAi(false);maid.setInSittingPose(false);
            maid.removeEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN);maid.removeEffect(net.minecraft.world.effect.MobEffects.WEAKNESS);
            maid.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);place(level,maid,room.map.sectionStart(room.section));retainChunk(level,maid);
        }
    }
    public void cleanup() {
        for(var maid:entities.values()){
            try{IGunOperator.fromLivingEntity(maid).cancelReload();}
            catch(RuntimeException failed){MuxiOutbreak.LOG.warn("AI reload cleanup failed for {}",maid.getUUID(),failed);}
            finally{
                var chunk=chunks.remove(maid.getUUID());
                if(chunk!=null&&maid.level() instanceof ServerLevel level)level.getChunkSource().removeRegionTicket(TICKET,chunk,2,maid.getUUID());
                maid.discard();
            }
        }
        entities.clear();chunks.clear();eliminated.clear();rescuing.clear();
    }
}
