package net.muxigame.outbreak.aiqa;
import com.google.gson.*;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tacz.guns.api.item.*;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.muxigame.outbreak.*;
import net.muxigame.outbreak.ai.*;
import net.muxigame.outbreak.equipment.*;
import net.muxigame.outbreak.infected.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/** Assisted, isolated world fixtures; actual TLM movement, TaCZ bullets and lifecycle events execute. */
@Mod(value="outbreak_ai_qa",dist=Dist.DEDICATED_SERVER)
public final class AiServerQA {
    int last=-1,bullets,hits;MinecraftServer server;ServerPlayer host;OutbreakSession room;BlockPos arena;UUID target;
    public AiServerQA(){
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e)->{
            if(e.getEntity() instanceof EntityKineticBullet b&&b.getOwner()!=null&&!b.getOwner().getPersistentData().getString(CampaignBots.SESSION).isBlank())bullets++;
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post e)->{
            if(e.getSource().getEntity()!=null&&!e.getSource().getEntity().getPersistentData().getString(CampaignBots.SESSION).isBlank()&&!e.getEntity().getPersistentData().getString(InfectedFactory.TAG_SESSION).isBlank()&&e.getNewDamage()>0)hits++;
        });
    }
    OutbreakSession session()throws Exception{var f=OutbreakGame.class.getDeclaredField("sessions");f.setAccessible(true);return ((List<OutbreakSession>)f.get(OutbreakGame.active(host))).stream().filter(s->s.host.equals(host.getUUID())).findFirst().orElse(null);}
    void clearInfected(){for(UUID id:Set.copyOf(room.infected)){var entity=server.getLevel(room.map.dimension()).getEntity(id);if(entity!=null)entity.discard();}room.infected.clear();room.infectedKinds.clear();}
    void move(LivingEntity entity,Vec3 at){if(entity instanceof ServerPlayer player)player.teleportTo(server.getLevel(room.map.dimension()),at.x,at.y,at.z,0,0);else entity.teleportTo(at.x,at.y,at.z);entity.setDeltaMovement(Vec3.ZERO);entity.fallDistance=0;}
    EntityMaid bot(int index){return (EntityMaid)room.bots.living().get(index);}
    void arena(){
        clearInfected();room.directorEnabled=false;room.phase=OutbreakSession.Phase.RUNNING;
        arena=room.map.chapters().get(room.section).route().get(8);var level=server.getLevel(room.map.dimension());
        for(int x=-16;x<=16;x++)for(int z=-12;z<=12;z++){var p=arena.offset(x,0,z);level.setBlock(p.below(),Blocks.STONE.defaultBlockState(),3);for(int y=0;y<5;y++)level.setBlock(p.above(y),Blocks.AIR.defaultBlockState(),3);}
        move(host,arena.getBottomCenter().add(-8,0,-5));int n=0;for(var entity:room.bots.living())move(entity,arena.getBottomCenter().add(-4+n++*3,0,0));
    }
    JsonObject inspect(){
        var result=new JsonObject();int owned=0;for(var level:server.getAllLevels())for(var entity:level.getAllEntities())if(!entity.getPersistentData().getString(CampaignBots.SESSION).isBlank())owned++;result.addProperty("ownedAiEntities",owned);result.addProperty("bullets",bullets);result.addProperty("hits",hits);result.addProperty("supportsAi",OutbreakGame.active(host).supportsAiTeammates());
        var skin=WineFoxSkin.resolve(server);if(skin!=null){result.addProperty("resolvedModel",skin.model());result.addProperty("resolvedTexture",skin.texture());}
        result.addProperty("phase",room==null?"ABSENT":room.phase.name());if(room==null)return result;
        result.addProperty("session",room.id.toString());result.addProperty("section",room.section);result.addProperty("downedCount",room.downed.size());result.addProperty("finaleWaves",room.finaleWaves);result.addProperty("finaleStarted",room.finaleStarted);result.addProperty("finaleTankSpawned",room.finaleTankSpawned);result.addProperty("finaleTankDefeated",room.finaleTankDefeated);result.addProperty("survivors",room.survivorCount());result.addProperty("humans",room.players.size());result.addProperty("seats",room.team.aiSeats().size());result.addProperty("locked",room.team.aiLocked());
        result.addProperty("humanDowned",room.downed.contains(host.getUUID()));result.addProperty("humanHealth",host.getHealth());result.addProperty("humanDimension",host.level().dimension().location().toString());result.addProperty("infected",room.infected.size());
        var list=new JsonArray();if(room.bots!=null)for(var entity:room.bots.living()){
            var maid=(EntityMaid)entity;var row=new JsonObject();row.addProperty("id",maid.getUUID().toString());row.addProperty("entityId",maid.getId());row.addProperty("x",maid.getX());row.addProperty("y",maid.getY());row.addProperty("z",maid.getZ());row.addProperty("health",maid.getHealth());row.addProperty("downed",room.downed.contains(maid.getUUID()));row.addProperty("reviveProgress",room.reviveProgress.getOrDefault(maid.getUUID(),0));row.addProperty("hostDistance",maid.distanceTo(host));row.addProperty("task",maid.getTask().getClass().getName());row.addProperty("model",maid.getYsmModelId());row.addProperty("texture",maid.getYsmModelTexture());row.addProperty("ysm",maid.isYsmModel());row.addProperty("sessionTag",maid.getPersistentData().getString(CampaignBots.SESSION));row.addProperty("seatTag",maid.getPersistentData().getString("muxi_outbreak_ai_seat"));
            var gun=IGun.getIGunOrNull(maid.getMainHandItem());if(gun!=null){row.addProperty("gun",CampaignInventory.gunId(maid.getMainHandItem()));row.addProperty("magazine",gun.getCurrentAmmoCount(maid.getMainHandItem()));row.addProperty("capacity",CampaignInventory.capacity(maid.getMainHandItem()));int ammo=0;for(int i=0;i<maid.getMaidInv().getSlots();i++){var stack=maid.getMaidInv().getStackInSlot(i);var a=IAmmo.getIAmmoOrNull(stack);if(a!=null)ammo+=stack.getCount();}row.addProperty("reserve",ammo);}
            list.add(row);
        }result.add("bots",list);if(target!=null){var victim=server.getLevel(room.map.dimension()).getEntity(target);result.addProperty("targetAlive",victim!=null&&victim.isAlive());if(victim instanceof LivingEntity living)result.addProperty("targetHealth",living.getHealth());}
        return result;
    }
    void tick(ServerTickEvent.Post event){
        server=event.getServer();host=server.getPlayerList().getPlayerByName("DebugHost");if(host==null||OutbreakGame.active(host)==null)return;
        var command=AiFiles.read("ai-command-server.json");if(command==null||command.get("id").getAsInt()<=last)return;last=command.get("id").getAsInt();var result=new JsonObject();result.addProperty("id",last);
        try{
            room=session();String type=command.get("type").getAsString();
            switch(type){
                case "observe"->{}
                case "suggest"->{String query=command.get("query").getAsString();var dispatcher=server.getCommands().getDispatcher();var suggestions=dispatcher.getCompletionSuggestions(dispatcher.parse(query,server.createCommandSourceStack())).getNow(null);var values=new JsonArray();if(suggestions!=null)for(var item:suggestions.getList())values.add(item.getText());result.add("suggestions",values);}
                case "arena"->arena();
                case "target"->{clearInfected();var level=server.getLevel(room.map.dimension());var mob=InfectedFactory.create(level,InfectedKind.COMMON,room.id,room.survivorCount(),room.difficulty);mob.setNoAi(true);float health=command.has("health")?command.get("health").getAsFloat():20;mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(health);mob.setHealth(health);mob.moveTo(arena.getBottomCenter().add(4,0,4));room.infected.add(mob.getUUID());room.infectedKinds.put(mob.getUUID(),InfectedKind.COMMON);if(!level.addFreshEntity(mob))throw new IllegalStateException("Fixture target rejected");target=mob.getUUID();}
                case "near-empty"->{for(var living:room.bots.living()){var gun=IGun.getIGunOrNull(living.getMainHandItem());gun.setCurrentAmmoCount(living.getMainHandItem(),2);gun.setBulletInBarrel(living.getMainHandItem(),false);}}
                case "follow"->{clearInfected();move(host,arena.getBottomCenter().add(9,0,-5));}
                case "down-human"->{clearInfected();move(host,arena.getBottomCenter().add(7,0,-5));host.invulnerableTime=0;host.hurt(host.damageSources().generic(),1000);}
                case "down-bot"->{clearInfected();var bot=bot(0);move(bot,host.position().add(1,0,0));bot.invulnerableTime=0;bot.hurt(bot.damageSources().generic(),1000);}
                case "friendly"->{clearInfected();room.difficulty=command.get("difficulty").getAsInt();boolean humanTarget=command.get("humanTarget").getAsBoolean();var victim=humanTarget?host:bot(0);var attacker=humanTarget?bot(0):host;victim.setHealth(victim.getMaxHealth());victim.invulnerableTime=0;victim.hurt(com.tacz.guns.init.ModDamageTypes.Sources.bullet(victim.registryAccess(),attacker,attacker,false),10);result.addProperty("damage",victim.getMaxHealth()-victim.getHealth());}
                case "safe-room"->{clearInfected();var safe=room.map.safeRooms().stream().filter(s->s.nextSection()==room.section+1).findFirst().orElseThrow();var center=new Vec3((safe.min().getX()+safe.max().getX()+1)*.5,Math.min(safe.min().getY(),safe.max().getY()),(safe.min().getZ()+safe.max().getZ()+1)*.5);for(UUID id:room.alive){var human=server.getPlayerList().getPlayer(id);if(human!=null)move(human,center);}for(var living:room.bots.living())move(living,center);}
                case "all-down"->{clearInfected();for(var living:new ArrayList<>(room.bots.living())){living.invulnerableTime=0;living.hurt(living.damageSources().generic(),1000);}for(UUID id:room.alive){var human=server.getPlayerList().getPlayer(id);if(human!=null){human.invulnerableTime=0;human.hurt(human.damageSources().generic(),1000);}}}
                case "finale"->{clearInfected();var at=room.map.finale().pos().getBottomCenter();for(UUID id:room.alive){var human=server.getPlayerList().getPlayer(id);if(human!=null)move(human,at);}for(var living:room.bots.living())move(living,at);room.phase=OutbreakSession.Phase.RUNNING;room.directorEnabled=false;}
                case "clear-infected"->clearInfected();
                case "boss-fixture"->{for(UUID id:Set.copyOf(room.infected)){var entity=server.getLevel(room.map.dimension()).getEntity(id);if(entity instanceof LivingEntity living&&room.infectedKinds.get(id)==InfectedKind.TANK){living.invulnerableTime=0;living.hurt(living.damageSources().generic(),100000);}}}
                case "heal-standing"->{for(UUID id:room.alive){var human=server.getPlayerList().getPlayer(id);if(human!=null&&!room.downed.contains(id))human.setHealth(human.getMaxHealth());}for(var living:room.bots.living())if(!room.downed.contains(living.getUUID()))living.setHealth(living.getMaxHealth());}
                default->throw new IllegalArgumentException(type);
            }
            result.add("status",inspect());result.addProperty("ok",true);
        }catch(Throwable error){result.addProperty("ok",false);result.addProperty("error",error.toString());}
        try{AiFiles.write("ai-result-server-"+last+".json",result);}catch(Exception failed){throw new RuntimeException(failed);}
    }
}
