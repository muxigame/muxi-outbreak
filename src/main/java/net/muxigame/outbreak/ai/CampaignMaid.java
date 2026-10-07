package net.muxigame.outbreak.ai;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.muxigame.outbreak.OutbreakSession;

/** Same registered TLM entity type and client renderer, with campaign-only admission. */
final class CampaignMaid extends EntityMaid {
    private final OutbreakSession room;
    private boolean campaignBrainTick;
    /** TLM's far-owner behavior stops navigation even when teleportToOwner fails.
     * CampaignBots owns physical follow/rescue; suppress that built-in owner behavior
     * only while Brain tasks execute. Taming ownership stays available outside this step.
     */
    @Override protected void customServerAiStep() {
        campaignBrainTick=true;
        try { super.customServerAiStep(); }
        finally { campaignBrainTick=false; }
    }
    private com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask campaignTask;
    @Override public com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask getTask() {
        return campaignTask==null?super.getTask():campaignTask;
    }
    @Override public void setTask(com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask task) {
        campaignTask=task;super.setTask(task);
    }
    CampaignMaid(net.minecraft.server.level.ServerLevel level,OutbreakSession room) { super(level);this.room=room; }
    @Override public boolean isAlliedTo(net.minecraft.world.entity.Entity other) {
        if(room!=null&&(room.players.contains(other.getUUID())||room.aiSeats.contains(other.getUUID())))return other==this;
        return super.isAlliedTo(other);
    }
    @Override public boolean canAttack(LivingEntity target) {
        return room!=null&&room.phase==OutbreakSession.Phase.RUNNING&&!room.downed.contains(getUUID())
            &&armed()&&target!=null&&target.isAlive()&&target.level()==level()&&room.infected.contains(target.getUUID())&&super.canAttack(target);
    }
    private boolean armed() {
        var stack=getMainHandItem();var gun=com.tacz.guns.api.item.IGun.getIGunOrNull(stack);
        if(gun==null)return false;if(gun.getCurrentAmmoCount(stack)>0||gun.hasBulletInBarrel(stack))return true;
        for(int i=1;i<getMaidInv().getSlots();i++){var ammo=getMaidInv().getStackInSlot(i);var type=com.tacz.guns.api.item.IAmmo.getIAmmoOrNull(ammo);if(type!=null&&!ammo.isEmpty()&&type.isAmmoOfGun(stack,ammo))return true;}return false;
    }
    @Override public LivingEntity getOwner() {
        if(campaignBrainTick)return null;
        if(room==null||getServer()==null)return super.getOwner();
        if(room.phase==OutbreakSession.Phase.RUNNING&&!room.downed.contains(getUUID())){
            LivingEntity nearest=null;double distance=Double.MAX_VALUE;
            for(java.util.UUID id:room.downed){
                LivingEntity candidate=getServer().getPlayerList().getPlayer(id);
                if(candidate==null&&room.bots!=null)candidate=room.bots.find(id);
                if(candidate==null||candidate==this||candidate.level()!=level())continue;
                double d=distanceToSqr(candidate);if(d<distance){distance=d;nearest=candidate;}
            }
            if(nearest!=null)return nearest;
        }
        var owner=super.getOwner();if(owner!=null&&room.alive.contains(owner.getUUID()))return owner;
        for(java.util.UUID id:room.alive){var player=getServer().getPlayerList().getPlayer(id);if(player!=null&&player.level()==level())return player;}
        return null;
    }
    OutbreakSession room() { return room; }
    @Override public boolean isPickable() { return !isInvisible()&&super.isPickable(); }
    @Override public InteractionResult mobInteract(Player player,InteractionHand hand) { return InteractionResult.CONSUME; }
    @Override public boolean canPickup(net.minecraft.world.entity.Entity entity) { return false; }
    @Override public boolean canPickup(net.minecraft.world.entity.Entity entity,boolean auto) { return false; }
    @Override public boolean canDestroyBlock(net.minecraft.core.BlockPos pos) { return false; }
    @Override public boolean canPlaceBlock(net.minecraft.core.BlockPos pos) { return false; }
    // A campaign-owned maid must not use TLM's distance recovery through closed checkpoints.
    @Override public boolean teleportToOwner(LivingEntity owner) { return false; }
}
