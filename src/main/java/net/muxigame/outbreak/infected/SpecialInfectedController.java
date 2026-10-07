package net.muxigame.outbreak.infected;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

public final class SpecialInfectedController {
    private static final String NEXT_ACTION = "muxi_outbreak_next_action";
    private SpecialInfectedController() {}

    public static boolean tick(Mob mob, InfectedKind kind, net.minecraft.world.entity.LivingEntity target, int now) {
        if (kind == InfectedKind.COMMON || target == null || !target.isAlive()) return false;
        mob.setTarget(target);
        int next = mob.getPersistentData().getInt(NEXT_ACTION);
        double distance = mob.distanceTo(target);
        if (now < next) return false;
        switch (kind) {
            case HUNTER -> {
                if (distance >= 4 && distance <= 12 && mob.onGround()) {
                    Vec3 direction = target.position().subtract(mob.position()).normalize();
                    mob.setDeltaMovement(direction.x * 0.85, 0.58, direction.z * 0.85);
                    mob.getPersistentData().putInt(NEXT_ACTION, now + 45);
                }
            }
            case SMOKER -> {
                if (distance >= 4 && distance <= 16 && mob.hasLineOfSight(target)) {
                    Vec3 pull = mob.position().subtract(target.position()).normalize().scale(0.42);
                    target.push(pull.x, Math.max(0.04, pull.y + 0.05), pull.z);
                    target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 28, 1));
                    mob.getPersistentData().putInt(NEXT_ACTION, now + 18);
                }
            }
            case BOOMER -> {
                if (distance <= 3.2 && mob.hasLineOfSight(target)) {
                    target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 100, 0));
                    target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 80, 0));
                    mob.getPersistentData().putInt(NEXT_ACTION, now + 200);
                    return true;
                }
            }
            case TANK -> {
                if (distance <= 4.2) {
                    Vec3 knock = target.position().subtract(mob.position()).normalize().scale(1.25);
                    target.push(knock.x, 0.38, knock.z);
                    mob.getPersistentData().putInt(NEXT_ACTION, now + 36);
                }
            }
            case SPITTER -> {
                if (distance <= 13 && mob.hasLineOfSight(target)) {
                    target.addEffect(new MobEffectInstance(MobEffects.POISON, 80, 1));
                    mob.getPersistentData().putInt(NEXT_ACTION, now + 70);
                }
            }
            case CHARGER -> {
                if (distance >= 4 && distance <= 14 && mob.hasLineOfSight(target)) {
                    Vec3 direction = target.position().subtract(mob.position()).normalize();
                    mob.setDeltaMovement(direction.x * 1.15, 0.12, direction.z * 1.15);
                    mob.getPersistentData().putInt(NEXT_ACTION, now + 60);
                }
            }
            case JOCKEY -> {
                if (distance <= 2.4) {
                    target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 45, 2));
                    Vec3 steer = mob.getLookAngle().scale(0.28);
                    target.push(steer.x, 0, steer.z);
                    mob.getPersistentData().putInt(NEXT_ACTION, now + 20);
                }
            }
            case WITCH -> mob.getPersistentData().putInt(NEXT_ACTION, now + 30);
            case COMMON -> {}
        }
        return false;
    }
}
