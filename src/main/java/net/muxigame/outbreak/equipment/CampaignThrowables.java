package net.muxigame.outbreak.equipment;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.*;
import net.minecraft.sounds.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import net.muxigame.outbreak.*;
import net.muxigame.outbreak.infected.InfectedKind;
import java.util.*;

/** Native pipe/bile behavior; LR's real M67/molotov retain their own render/physics implementation. */
public final class CampaignThrowables {
    public static final String TAG="muxi_outbreak_throw_session";
    private static final class Thrown {
        final String kind;final Snowball projectile;final UUID owner;final int thrownAt;
        Vec3 impact;UUID hit;int expires;
        Thrown(String kind,Snowball projectile,UUID owner,int now){this.kind=kind;this.projectile=projectile;this.owner=owner;this.thrownAt=now;expires=now+120;}
        Vec3 position(){return impact==null?projectile.position():impact;}
    }
    private final OutbreakSession session;
    private final Map<UUID,Thrown> active=new LinkedHashMap<>();
    public CampaignThrowables(OutbreakSession session){this.session=session;}
    public boolean launch(ServerPlayer player,String kind,ItemStack stack){
        if(!session.alive.contains(player.getUUID())||session.downed.contains(player.getUUID())||
            session.phase!=OutbreakSession.Phase.RUNNING||active.size()>=32)return false;
        Snowball ball=new Snowball(player.serverLevel(),player);ball.setItem(stack.copyWithCount(1));
        ball.shootFromRotation(player,player.getXRot(),player.getYRot(),-8,1.15f,.5f);
        ball.getPersistentData().putString(TAG,session.id.toString());
        ball.addTag("muxi_outbreak_throwable");
        if(!player.serverLevel().addFreshEntity(ball))return false;
        active.put(ball.getUUID(),new Thrown(kind,ball,player.getUUID(),player.server.getTickCount()));
        MuxiOutbreak.LOG.info("OUTBREAK_THROW session={} kind={} player={}",session.shortId(),kind,player.getScoreboardName());return true;
    }
    public boolean impact(UUID projectile,HitResult result,int now){
        Thrown thrown=active.get(projectile);if(thrown==null||thrown.impact!=null)return false;
        thrown.impact=result.getLocation().add(0,.05,0);
        if(result instanceof EntityHitResult entity)thrown.hit=entity.getEntity().getUUID();
        thrown.projectile.setPos(thrown.impact);thrown.projectile.setDeltaMovement(Vec3.ZERO);thrown.projectile.setNoGravity(true);
        if(thrown.kind.equals("bile_bomb")){thrown.expires=now+400;thrown.projectile.discard();}
        return true;
    }
    public void tick(ServerLevel level,int now){
        for(var entry:List.copyOf(active.entrySet())){
            Thrown thrown=entry.getValue();
            if(thrown.impact==null&&thrown.projectile.isRemoved())thrown.impact=thrown.projectile.position();
            Vec3 at=thrown.position();
            if(thrown.hit!=null&&thrown.kind.equals("bile_bomb")){
                Entity victim=level.getEntity(thrown.hit);if(victim!=null&&victim.isAlive())at=victim.position();
            }
            if(now>=thrown.expires){
                if(thrown.kind.equals("pipe_bomb")){
                    ServerPlayer owner=level.getServer().getPlayerList().getPlayer(thrown.owner);
                    level.explode(owner,at.x,at.y,at.z,3.5f,Level.ExplosionInteraction.NONE);
                    MuxiOutbreak.LOG.info("OUTBREAK_PIPE_EXPLODED session={} pos={}",session.shortId(),at);
                }
                thrown.projectile.discard();active.remove(entry.getKey());continue;
            }
            if(thrown.kind.equals("pipe_bomb")&&now%10==0){
                level.playSound(null,at.x,at.y,at.z,SoundEvents.NOTE_BLOCK_HAT.value(),SoundSource.PLAYERS,1.4f,1.8f);
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK,at.x,at.y+.2,at.z,3,.1,.1,.1,0);
            }else if(thrown.kind.equals("bile_bomb")&&thrown.impact!=null&&now%10==0)
                level.sendParticles(ParticleTypes.SNEEZE,at.x,at.y+.5,at.z,18,2,.5,2,.01);
            if(now%5==0&&(thrown.kind.equals("pipe_bomb")||thrown.impact!=null)){
                for(UUID id:session.infected){
                    if(session.infectedKinds.get(id)!=InfectedKind.COMMON)continue;
                    Entity entity=level.getEntity(id);if(!(entity instanceof Mob mob)||mob.distanceToSqr(at)>40*40)continue;
                    Entity marked=thrown.hit==null?null:level.getEntity(thrown.hit);
                    if(marked instanceof LivingEntity target&&target!=mob&&target.isAlive())mob.setTarget(target);
                    else{mob.setTarget(null);mob.getNavigation().moveTo(at.x,at.y,at.z,1.25);}
                }
            }
        }
    }
    public boolean isDistracted(Mob mob,int now){
        return active.values().stream().anyMatch(t->now<t.expires&&(t.kind.equals("pipe_bomb")||t.impact!=null)&&mob.distanceToSqr(t.position())<1600);
    }
    public void cleanup(){for(Thrown t:active.values())t.projectile.discard();active.clear();}
}
