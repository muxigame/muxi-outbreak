package net.muxigame.outbreak.director;

import java.util.random.RandomGenerator;

public final class Director {
    public enum Pace { RELAX, BUILD, PEAK, FADE }
    public record Sample(
        int players,
        double averageHealth,
        double recentDamage,
        double separation,
        double progress,
        int livingInfected,
        int downed
    ) {}
    public record Decision(
        Pace pace,
        double intensity,
        int desiredCommon,
        boolean hordePulse,
        boolean special,
        boolean boss
    ) {}

    private Pace pace = Pace.RELAX;
    private int stateSeconds;
    private int specialCooldown = 8;
    private int hordeCooldown = 12;
    private int bossCooldown = 80;
    private boolean forcedPanic;

    public Pace pace() { return pace; }
    public void reset() {pace=Pace.RELAX;stateSeconds=0;specialCooldown=8;hordeCooldown=12;bossCooldown=80;forcedPanic=false;}
    public void forcePanic(boolean enabled) {
        forcedPanic = enabled;
        if (enabled && pace != Pace.PEAK) transition(Pace.PEAK);
    }

    public Decision tick(Sample sample, RandomGenerator random) {
        stateSeconds++;
        specialCooldown--;
        hordeCooldown--;
        bossCooldown--;
        double missingHealth = 1.0 - clamp(sample.averageHealth);
        double infectedPressure = Math.min(1.0, sample.livingInfected / (double)Math.max(8, sample.players * 14));
        double intensity = clamp(
            missingHealth * 0.36
                + clamp(sample.recentDamage) * 0.25
                + clamp(sample.separation) * 0.13
                + infectedPressure * 0.12
                + Math.min(1.0, sample.downed / (double)Math.max(1, sample.players)) * 0.28
                + (pace == Pace.PEAK ? 0.12 : 0.0)
        );

        if (forcedPanic) {
            if (pace != Pace.PEAK) transition(Pace.PEAK);
        } else {
            switch (pace) {
                case RELAX -> {
                    if (stateSeconds >= 10 && intensity < 0.75) transition(Pace.BUILD);
                }
                case BUILD -> {
                    int target = 16 + (int)Math.round((1.0 - sample.progress) * 10);
                    if (stateSeconds >= target || sample.progress > 0.72) transition(Pace.PEAK);
                }
                case PEAK -> {
                    if (stateSeconds >= 16 || intensity > 0.88) transition(Pace.FADE);
                }
                case FADE -> {
                    if (stateSeconds >= 9 && sample.livingInfected <= Math.max(3, sample.players * 3)) transition(Pace.RELAX);
                }
            }
        }

        int base = switch (pace) {
            case RELAX -> 2;
            case BUILD -> 7;
            case PEAK -> 15;
            case FADE -> 4;
        };
        int desiredCommon = Math.max(0, base * Math.max(1, sample.players) - sample.downed * 3);
        boolean horde = false;
        if (pace == Pace.PEAK && hordeCooldown <= 0) {
            horde = true;
            hordeCooldown = 8 + random.nextInt(5);
        }
        boolean special = false;
        if ((pace == Pace.BUILD || pace == Pace.PEAK) && specialCooldown <= 0 && intensity < 0.9) {
            special = true;
            specialCooldown = Math.max(4, 11 - sample.players * 2) + random.nextInt(5);
        }
        boolean boss = false;
        if (pace == Pace.PEAK && bossCooldown <= 0 && sample.progress > 0.35 && intensity < 0.75) {
            boss = true;
            bossCooldown = 75 + random.nextInt(45);
        }
        return new Decision(pace, intensity, desiredCommon, horde, special, boss);
    }

    private void transition(Pace next) {
        pace = next;
        stateSeconds = 0;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
