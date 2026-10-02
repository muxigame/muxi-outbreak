package net.muxigame.outbreak.qa.mixin;
import net.muxigame.outbreak.qa.NativePlayfeelQA;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** QA ONLY: observe the actual restore transaction before normal MC health/physics ticks. */
@Mixin(targets="net.muxigame.outbreak.PlayerSnapshot",remap=false)
public abstract class RestoreReceiptQAMixin {
  @Inject(method="restore",at=@At("RETURN"),remap=false)
  private static void receipt(ServerPlayer player,CallbackInfoReturnable<Boolean> result){
    if(Boolean.TRUE.equals(result.getReturnValue()))NativePlayfeelQA.restored(player);
  }
}
