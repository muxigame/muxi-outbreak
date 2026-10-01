package net.muxigame.outbreak.infected;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.core.Holder;

import java.util.UUID;

public final class InfectedFactory {
    public static final String TAG_SESSION = "muxi_outbreak_session";
    public static final String TAG_KIND = "muxi_outbreak_kind";
    private InfectedFactory() {}

    public static Mob create(ServerLevel level, InfectedKind kind, UUID sessionId, int playerCount, int difficulty) {
        Mob mob = switch (kind) {
            case WITCH, SPITTER -> EntityType.WITCH.create(level);
            case CHARGER -> EntityType.RAVAGER.create(level);
            default -> EntityType.ZOMBIE.create(level);
        };
        if (mob == null) throw new IllegalStateException("Unable to create " + kind);
        mob.getPersistentData().putString(TAG_SESSION, sessionId.toString());
        mob.getPersistentData().putString(TAG_KIND, kind.name());
        mob.addTag("muxi_outbreak");
        mob.addTag("muxi_outbreak_" + kind.name().toLowerCase(java.util.Locale.ROOT));
        mob.setPersistenceRequired();
        mob.setCanPickUpLoot(false);
        mob.setCustomName(Component.literal(display(kind)));
        mob.setCustomNameVisible(kind.special());

        if (mob instanceof Zombie zombie) {
            zombie.setBaby(kind == InfectedKind.HUNTER || kind == InfectedKind.JOCKEY);
            zombie.setCanBreakDoors(false);
        }
        double party = 1.0 + Math.max(0, playerCount - 1) * 0.16;
        double diff = 0.85 + Math.max(0, Math.min(3, difficulty)) * 0.17;
        switch (kind) {
            case COMMON -> tune(mob, 20 * diff, 4 * diff, 0.31, 48);
            case HUNTER -> tune(mob, 38 * party * diff, 7 * diff, 0.38, 64);
            case SMOKER -> tune(mob, 45 * party * diff, 5 * diff, 0.30, 80);
            case BOOMER -> tune(mob, 32 * party * diff, 3 * diff, 0.28, 64);
            case TANK -> {
                tune(mob, 220 * party * diff, 15 * diff, 0.33, 96);
                attribute(mob, Attributes.KNOCKBACK_RESISTANCE, 0.85);
            }
            case WITCH -> tune(mob, 85 * party * diff, 11 * diff, 0.32, 72);
            case SPITTER -> tune(mob, 48 * party * diff, 4 * diff, 0.31, 80);
            case CHARGER -> {
                tune(mob, 110 * party * diff, 12 * diff, 0.34, 80);
                attribute(mob, Attributes.KNOCKBACK_RESISTANCE, 0.55);
            }
            case JOCKEY -> tune(mob, 36 * party * diff, 5 * diff, 0.41, 64);
        }
        mob.setHealth(mob.getMaxHealth());
        return mob;
    }

    private static void tune(Mob mob, double health, double damage, double speed, double follow) {
        attribute(mob, Attributes.MAX_HEALTH, health);
        attribute(mob, Attributes.ATTACK_DAMAGE, damage);
        attribute(mob, Attributes.MOVEMENT_SPEED, speed);
        attribute(mob, Attributes.FOLLOW_RANGE, follow);
    }

    private static void attribute(Mob mob, Holder<Attribute> key, double value) {
        var attribute = mob.getAttribute(key);
        if (attribute != null) {
            attribute.removeModifiers();
            attribute.setBaseValue(value);
        }
    }

    private static String display(InfectedKind kind) {
        return switch (kind) {
            case COMMON -> "感染者";
            case HUNTER -> "猎手";
            case SMOKER -> "烟鬼";
            case BOOMER -> "呕吐者";
            case TANK -> "坦克";
            case WITCH -> "女巫";
            case SPITTER -> "喷吐者";
            case CHARGER -> "冲锋者";
            case JOCKEY -> "骑手";
        };
    }
}
