package net.muxigame.outbreak.interactionqa.mixin;
import net.muxigame.outbreak.interactionqa.InteractionServerQA;
import net.minecraft.server.level.ServerPlayer;
import net.muxigame.minigames.GameRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(GameRuntime.class)
public abstract class InteractionRequestQAMixin {
    @Inject(method="request",at=@At("HEAD"))
    private void qa$count(ServerPlayer player,String game,String action,String value,CallbackInfo ci){
        if(game.equals("outbreak")&&action.equals("interact"))InteractionServerQA.packets++;
    }
}
