package net.muxigame.outbreak.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public record OutbreakMap(
    String id,
    String title,
    Mode mode,
    ResourceKey<Level> dimension,
    BlockPos start,
    BlockPos finish,
    List<SafeRoom> safeRooms,
    List<Spawn> commonSpawns,
    List<Spawn> hordeSpawns,
    List<Spawn> bossSpawns,
    List<ItemSpawn> itemSpawns
) {
    public enum Mode { CAMPAIGN, SURVIVAL }
    public record Spawn(int section, BlockPos pos) {}
    public record ItemSpawn(int section, BlockPos pos, String preset) {}
    public record SafeRoom(String id, BlockPos min, BlockPos max, int nextSection) {
        public boolean contains(Vec3 point) {
            return point.x >= Math.min(min.getX(), max.getX()) && point.x <= Math.max(min.getX(), max.getX()) + 1
                && point.y >= Math.min(min.getY(), max.getY()) && point.y <= Math.max(min.getY(), max.getY()) + 1
                && point.z >= Math.min(min.getZ(), max.getZ()) && point.z <= Math.max(min.getZ(), max.getZ()) + 1;
        }
    }

    public static OutbreakMap parse(JsonObject json) {
        String id = requiredString(json, "id");
        String title = json.has("title") ? json.get("title").getAsString() : id;
        Mode mode = "survival".equalsIgnoreCase(json.has("mode") ? json.get("mode").getAsString() : "campaign")
            ? Mode.SURVIVAL : Mode.CAMPAIGN;
        ResourceLocation dimId = ResourceLocation.parse(
            json.has("dimension") ? json.get("dimension").getAsString() : "minecraft:overworld"
        );
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimId);
        BlockPos start = pos(json.getAsJsonArray("start"));
        BlockPos finish = json.has("finish") ? pos(json.getAsJsonArray("finish")) : start;
        List<SafeRoom> safeRooms = new ArrayList<>();
        if (json.has("safeRooms")) for (var element : json.getAsJsonArray("safeRooms")) {
            JsonObject row = element.getAsJsonObject();
            safeRooms.add(new SafeRoom(
                requiredString(row, "id"),
                pos(row.getAsJsonArray("min")),
                pos(row.getAsJsonArray("max")),
                row.has("nextSection") ? row.get("nextSection").getAsInt() : safeRooms.size() + 1
            ));
        }
        return new OutbreakMap(
            id, title, mode, dimension, start, finish, List.copyOf(safeRooms),
            spawns(json, "commonSpawns"), spawns(json, "hordeSpawns"), spawns(json, "bossSpawns"),
            items(json, "itemSpawns")
        );
    }

    public List<Spawn> commonFor(int section) { return commonSpawns.stream().filter(s -> s.section == section).toList(); }
    public List<Spawn> hordeFor(int section) { return hordeSpawns.stream().filter(s -> s.section == section).toList(); }
    public List<Spawn> bossFor(int section) { return bossSpawns.stream().filter(s -> s.section == section).toList(); }

    private static List<Spawn> spawns(JsonObject json, String name) {
        if (!json.has(name)) return List.of();
        List<Spawn> result = new ArrayList<>();
        for (var element : json.getAsJsonArray(name)) {
            JsonObject row = element.getAsJsonObject();
            result.add(new Spawn(row.has("section") ? row.get("section").getAsInt() : 0, pos(row.getAsJsonArray("pos"))));
        }
        return List.copyOf(result);
    }

    private static List<ItemSpawn> items(JsonObject json, String name) {
        if (!json.has(name)) return List.of();
        List<ItemSpawn> result = new ArrayList<>();
        for (var element : json.getAsJsonArray(name)) {
            JsonObject row = element.getAsJsonObject();
            result.add(new ItemSpawn(
                row.has("section") ? row.get("section").getAsInt() : 0,
                pos(row.getAsJsonArray("pos")),
                row.has("preset") ? row.get("preset").getAsString() : "default"
            ));
        }
        return List.copyOf(result);
    }

    private static BlockPos pos(JsonArray array) {
        if (array == null || array.size() != 3) throw new IllegalArgumentException("position must have three numbers");
        return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
    }

    private static String requiredString(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).getAsString().isBlank()) throw new IllegalArgumentException("missing " + key);
        return json.get(key).getAsString();
    }
}
