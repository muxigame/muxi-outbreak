package net.muxigame.outbreak;

import net.minecraft.world.level.GameType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.muxigame.outbreak.director.Director;
import net.muxigame.outbreak.infected.InfectedKind;
import net.muxigame.outbreak.map.OutbreakMap;

import java.util.*;

public final class OutbreakSession {
    public enum Phase { COUNTDOWN, RUNNING, SAFE_ROOM, FINISHED }

    public final UUID id = UUID.randomUUID();
    public final UUID host;
    public final OutbreakMap map;
    public final OutbreakMap.Mode mode;
    public final Director director = new Director();
    public final LinkedHashSet<UUID> players = new LinkedHashSet<>();
    public final LinkedHashSet<UUID> alive = new LinkedHashSet<>();
    public final Set<UUID> infected = new HashSet<>();
    public final Map<UUID, InfectedKind> infectedKinds = new HashMap<>();
    public final Set<UUID> downed = new HashSet<>();
    public final Map<UUID, Integer> downedSince = new HashMap<>();
    public final Map<UUID, Integer> incapCount = new HashMap<>();
    public final Map<UUID, Integer> reviveProgress = new HashMap<>();
    public final Map<UUID, GameType> originalModes = new HashMap<>();
    public final Map<UUID, ReturnPoint> returnPoints = new HashMap<>();
    public final Map<UUID, Double> recentDamage = new HashMap<>();
    public Phase phase = Phase.COUNTDOWN;
    public int section;
    public int timer;
    public int seconds;
    public int difficulty;
    public int panicWaves;
    public int panicDelay;
    public boolean directorEnabled = true;
    public boolean forcedWin;

    public record ReturnPoint(ResourceKey<Level> dimension, Vec3 position, float yaw, float pitch) {}

    public OutbreakSession(UUID host, OutbreakMap map, OutbreakMap.Mode mode, int difficulty, int now) {
        this.host = host;
        this.map = map;
        this.mode = mode;
        this.difficulty = Math.max(0, Math.min(3, difficulty));
        this.timer = now + 200;
        players.add(host);
        alive.add(host);
    }

    public String shortId() { return id.toString().substring(0, 8); }
    public boolean contains(UUID player) { return players.contains(player); }
}
