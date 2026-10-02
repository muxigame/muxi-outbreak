package net.muxigame.outbreak.mixin;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.muxigame.outbreak.infected.InfectedFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** The room's explicit difficulty owns its infected; leave ambient world mobs alone. */
@Mixin(value=Monster.class,remap=false)
public abstract class OutbreakPeacefulMixin {
    @Inject(method="shouldDespawnInPeaceful",at=@At("HEAD"),cancellable=true)
    private void outbreak$keepSessionInfected(CallbackInfoReturnable<Boolean> ci) {
        Entity entity=(Entity)(Object)this;
        if(entity.getPersistentData().contains(InfectedFactory.TAG_SESSION)&&entity.level().dimension().location().toString().equals("muxi_outbreak:campaign"))ci.setReturnValue(false);
    }
}
