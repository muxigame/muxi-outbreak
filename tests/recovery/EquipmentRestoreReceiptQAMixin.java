package net.muxigame.outbreak.equipmentqa.mixin;
import net.muxigame.outbreak.qa.EquipmentServerQA;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** QA ONLY: inspect actual restore before subsequent normal physics/health ticks. */
@Mixin(targets="net.muxigame.outbreak.PlayerSnapshot",remap=false)
public abstract class EquipmentRestoreReceiptQAMixin {
    @Inject(method="restore",at=@At("RETURN"),remap=false)
    private static void receipt(ServerPlayer p,CallbackInfoReturnable<Boolean> result){if(Boolean.TRUE.equals(result.getReturnValue()))EquipmentServerQA.receipt(p);}
}
