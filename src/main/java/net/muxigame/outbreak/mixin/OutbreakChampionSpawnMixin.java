package net.muxigame.outbreak.mixin;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.muxigame.outbreak.infected.InfectedFactory;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Optional compatibility hook verified against Champions 21.1.1.7. */
@Pseudo
@Mixin(targets="top.theillusivec4.champions.common.champion.ChampionSpawnHandler",remap=false)
public abstract class OutbreakChampionSpawnMixin {
    @Inject(method="trySpawn",at=@At("HEAD"),cancellable=true,require=0)
    private static void outbreak$directorOwnsInfected(LivingEntity entity,ServerLevel level,CallbackInfo ci){
        if(level.dimension().location().toString().equals("muxi_outbreak:campaign")&&entity.getPersistentData().contains(InfectedFactory.TAG_SESSION))ci.cancel();
    }
}
