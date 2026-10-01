package net.muxigame.outbreak;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/** Stored in player persistent data before any inventory/mode changes; recoverable on login. */
final class PlayerSnapshot {
    private static final String KEY = "muxi_outbreak_return_v1";
    private PlayerSnapshot() {}

    static void capture(ServerPlayer player) {
        checkEligible(player);
        captureChecked(player);
    }
    static void checkEligible(ServerPlayer player){net.muxigame.minigames.PlayerReturns.check(player);}
    private static void captureChecked(ServerPlayer player){net.muxigame.minigames.PlayerReturns.capture(player,KEY);}
    static boolean restore(ServerPlayer player) {
        if(!player.getPersistentData().contains(KEY,Tag.TAG_COMPOUND))return false;
        com.tacz.guns.api.entity.IGunOperator.fromLivingEntity(player).cancelReload();
        return net.muxigame.minigames.PlayerReturns.restore(player,KEY);
    }

    static void kit(ServerPlayer player) {
        net.muxigame.outbreak.equipment.CampaignInventory.kit(player);
    }
}
