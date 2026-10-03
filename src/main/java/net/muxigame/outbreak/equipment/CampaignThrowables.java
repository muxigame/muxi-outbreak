package net.muxigame.outbreak.equipment;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import net.muxigame.outbreak.*;
import net.muxigame.outbreak.infected.InfectedKind;
import net.muxigame.minigames.equipment.SharedProjectiles;
import java.util.*;
/** Campaign membership/Director adapter around common projectile physics and lifecycle. */
public final class CampaignThrowables {
    public static final String TAG=SharedProjectiles.TAG;
    private final OutbreakSession session;
    private final SharedProjectiles shared;
    public CampaignThrowables(OutbreakSession session){this.session=session;shared=new SharedProjectiles(session.id,p->session.alive.contains(p.getUUID())&&!session.downed.contains(p.getUUID())&&p.level().dimension().equals(session.map.dimension())&&(session.phase==OutbreakSession.Phase.RUNNING||session.phase==OutbreakSession.Phase.START_ROOM),()->session.infected.stream().filter(id->session.infectedKinds.get(id)==InfectedKind.COMMON).map(id->sessionLevelEntity(id)).filter(Mob.class::isInstance).map(Mob.class::cast).toList());}
    private ServerLevel level;
    private Entity sessionLevelEntity(UUID id){return level==null?null:level.getEntity(id);}
    public boolean launch(ServerPlayer p,String kind,ItemStack stack){level=p.serverLevel();return shared.launch(p,kind,stack);}
    public boolean impact(UUID id,HitResult hit,int now){return shared.impact(id,hit,now);}
    public void tick(ServerLevel level,int now){this.level=level;shared.tick(level,now);}
    public boolean isDistracted(Mob mob,int now){return shared.isDistracted(mob,now);}
    public void cleanup(){shared.cleanup();}
}
