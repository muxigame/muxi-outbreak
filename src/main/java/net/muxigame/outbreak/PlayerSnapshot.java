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
        if (player.getPersistentData().contains(KEY, Tag.TAG_COMPOUND))
            throw new IllegalStateException("尚有未恢复的小游戏状态");
        CompoundTag tag = new CompoundTag();
        tag.put("inventory",player.getInventory().save(new ListTag()));
        tag.putInt("selected",player.getInventory().selected);
        tag.putString("dimension",player.level().dimension().location().toString());
        tag.putDouble("x",player.getX()); tag.putDouble("y",player.getY()); tag.putDouble("z",player.getZ());
        tag.putFloat("yaw",player.getYRot()); tag.putFloat("pitch",player.getXRot());
        tag.putInt("mode",player.gameMode.getGameModeForPlayer().getId());
        tag.putFloat("health",player.getHealth());
        tag.putInt("xpLevel",player.experienceLevel); tag.putInt("xpTotal",player.totalExperience);
        tag.putFloat("xpProgress",player.experienceProgress);
        CompoundTag food = new CompoundTag(); player.getFoodData().addAdditionalSaveData(food); tag.put("food",food);
        ListTag effects = new ListTag();
        for (var effect : player.getActiveEffects()) effects.add(effect.save());
        tag.put("effects",effects);
        player.getPersistentData().put(KEY,tag);
        // Persist the original inventory with its recovery tag before handing out a temporary kit.
        player.server.getPlayerList().saveAll();
    }

    static boolean restore(ServerPlayer player) {
        if (!player.getPersistentData().contains(KEY, Tag.TAG_COMPOUND)) return false;
        CompoundTag tag = player.getPersistentData().getCompound(KEY);
        player.closeContainer();
        player.getInventory().load(tag.getList("inventory",Tag.TAG_COMPOUND));
        player.getInventory().selected = Math.max(0, Math.min(8,tag.getInt("selected")));
        player.removeAllEffects();
        for (var value : tag.getList("effects",Tag.TAG_COMPOUND)) {
            var effect = MobEffectInstance.load((CompoundTag)value);
            if (effect != null) player.addEffect(effect);
        }
        player.setGameMode(GameType.byId(tag.getInt("mode")));
        player.setHealth(Math.min(player.getMaxHealth(),Math.max(1,tag.getFloat("health"))));
        player.getFoodData().readAdditionalSaveData(tag.getCompound("food"));
        player.experienceLevel=tag.getInt("xpLevel"); player.totalExperience=tag.getInt("xpTotal");
        player.experienceProgress=tag.getFloat("xpProgress");
        var level=player.server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(tag.getString("dimension"))));
        if (level==null) level=player.server.overworld();
        player.teleportTo(level,tag.getDouble("x"),tag.getDouble("y"),tag.getDouble("z"),tag.getFloat("yaw"),tag.getFloat("pitch"));
        player.fallDistance=0;
        player.getPersistentData().remove(KEY);
        player.containerMenu.broadcastChanges();
        player.server.getPlayerList().saveAll();
        return true;
    }

    static void kit(ServerPlayer player) {
        player.closeContainer();
        player.getInventory().clearContent();
        player.removeAllEffects();
        player.getInventory().add(new ItemStack(Items.IRON_SWORD));
        player.getInventory().add(new ItemStack(Items.BOW));
        player.getInventory().add(new ItemStack(Items.SHIELD));
        player.getInventory().add(new ItemStack(Items.ARROW,64));
        player.getInventory().add(new ItemStack(Items.ARROW,64));
        player.getInventory().add(new ItemStack(Items.COOKED_BEEF,16));
        player.getInventory().add(new ItemStack(Items.GOLDEN_APPLE,4));
        player.setGameMode(GameType.ADVENTURE);
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(10);
        player.containerMenu.broadcastChanges();
    }
}
