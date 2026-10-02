package net.muxigame.outbreak.mixin;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.muxigame.outbreak.infected.InfectedFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=DamageSource.class,remap=false)
public abstract class OutbreakDamageMixin {
    @Inject(method="scalesWithDifficulty",at=@At("HEAD"),cancellable=true)
    private void outbreak$roomDifficulty(CallbackInfoReturnable<Boolean> ci) {
        var attacker=((DamageSource)(Object)this).getEntity();
        if(attacker!=null&&attacker.level().getDifficulty()==Difficulty.PEACEFUL&&attacker.getPersistentData().contains(InfectedFactory.TAG_SESSION)&&attacker.level().dimension().location().toString().equals("muxi_outbreak:campaign"))ci.setReturnValue(false);
    }
}
