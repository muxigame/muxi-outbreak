package net.muxigame.outbreak.infected;

import java.util.Locale;

public enum InfectedKind {
    COMMON,
    HUNTER,
    SMOKER,
    BOOMER,
    TANK,
    WITCH,
    SPITTER,
    CHARGER,
    JOCKEY;

    public static InfectedKind parse(String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        if (normalized.equals("HORDE")) return COMMON;
        if (normalized.equals("SPECIAL")) return HUNTER;
        if (normalized.equals("BOSS") || normalized.equals("TANKANGRY")) return TANK;
        return valueOf(normalized);
    }

    public boolean boss() { return this == TANK || this == WITCH; }
    public boolean special() { return this != COMMON; }
}
