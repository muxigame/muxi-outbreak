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
    List<SafeRoom> startRooms,
    List<Spawn> commonSpawns,
    List<Spawn> hordeSpawns,
    List<Spawn> bossSpawns,
    List<ItemSpawn> itemSpawns,
    String geometry,
    List<Chapter> chapters,
    List<PanicEvent> panicEvents,
    Finale finale,
    List<Supply> supplies
) {
    public enum Mode { CAMPAIGN, SURVIVAL }
    public record Spawn(int section, BlockPos pos) {}
    public record ItemSpawn(int section, BlockPos pos, String preset) {}
    public record Chapter(String id, String title, BlockPos start, BlockPos end, List<BlockPos> route) {}
    public record PanicEvent(int section, BlockPos pos, int waves) {}
    public record Finale(BlockPos pos, int holdSeconds, double radius, int waves) {}
    public record Supply(String id,int section,BlockPos pos,List<String> choices,int count,boolean infinite,boolean mustExist,boolean directorChoice) {}
    public record SafeRoom(String id, BlockPos min, BlockPos max, int nextSection, List<BlockPos> doors) {
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
                row.has("nextSection") ? row.get("nextSection").getAsInt() : safeRooms.size() + 1,
                positions(row,"doors")
            ));
        }
        return new OutbreakMap(
            id, title, mode, dimension, start, finish, List.copyOf(safeRooms), startRooms(json),
            spawns(json, "commonSpawns"), spawns(json, "hordeSpawns"), spawns(json, "bossSpawns"),
            items(json, "itemSpawns"),
            json.has("geometry") ? ResourceLocation.parse(json.get("geometry").getAsString()).toString() : "",
            chapters(json), panics(json), finale(json), supplies(json)
        );
    }

    private static List<BlockPos> positions(JsonObject row,String key) {
        List<BlockPos> result=new ArrayList<>();
        if(row.has(key))for(var value:row.getAsJsonArray(key))result.add(pos(value.getAsJsonArray()));
        return List.copyOf(result);
    }
    private static List<SafeRoom> startRooms(JsonObject json) {
        List<SafeRoom> result=new ArrayList<>();
        if(json.has("startRooms"))for(var element:json.getAsJsonArray("startRooms")) {
            var row=element.getAsJsonObject();
            result.add(new SafeRoom(requiredString(row,"id"),pos(row.getAsJsonArray("min")),pos(row.getAsJsonArray("max")),row.get("section").getAsInt(),positions(row,"doors")));
        }
        return List.copyOf(result);
    }
    public SafeRoom startRoom(int section) {return startRooms.stream().filter(r->r.nextSection()==section).findFirst().orElse(null);}

    public BlockPos sectionStart(int section) {
        return section >= 0 && section < chapters.size() ? chapters.get(section).start() : start;
    }

    public String sectionTitle(int section) {
        return section >= 0 && section < chapters.size() ? chapters.get(section).title() : title;
    }

    private static List<Chapter> chapters(JsonObject json) {
        List<Chapter> result = new ArrayList<>();
        if (json.has("chapters")) for (var element : json.getAsJsonArray("chapters")) {
            JsonObject row = element.getAsJsonObject();
            List<BlockPos> route = new ArrayList<>();
            if (row.has("route")) for (var point : row.getAsJsonArray("route")) route.add(pos(point.getAsJsonArray()));
            result.add(new Chapter(requiredString(row,"id"), requiredString(row,"title"),
                pos(row.getAsJsonArray("start")), pos(row.getAsJsonArray("end")), List.copyOf(route)));
        }
        return List.copyOf(result);
    }

    private static List<PanicEvent> panics(JsonObject json) {
        List<PanicEvent> result = new ArrayList<>();
        if (json.has("panicEvents")) for (var element : json.getAsJsonArray("panicEvents")) {
            JsonObject row = element.getAsJsonObject();
            result.add(new PanicEvent(row.get("section").getAsInt(), pos(row.getAsJsonArray("pos")),
                Math.max(1, Math.min(10, row.get("waves").getAsInt()))));
        }
        return List.copyOf(result);
    }

    private static Finale finale(JsonObject json) {
        if (!json.has("finale")) return null;
        JsonObject row = json.getAsJsonObject("finale");
        return new Finale(pos(row.getAsJsonArray("pos")),
            Math.max(20, Math.min(600, row.get("holdSeconds").getAsInt())),
            Math.max(3, Math.min(32, row.get("radius").getAsDouble())),
            Math.max(1, Math.min(10, row.get("waves").getAsInt())));
    }

    private static List<Supply> supplies(JsonObject json){
        List<Supply> result=new ArrayList<>();java.util.Set<String> ids=new java.util.HashSet<>();
        if(json.has("supplies"))for(var value:json.getAsJsonArray("supplies")){
            var row=value.getAsJsonObject();String id=requiredString(row,"id");
            if(!ids.add(id))throw new IllegalArgumentException("duplicate supply id "+id);
            List<String> choices=new ArrayList<>();for(var choice:row.getAsJsonArray("choices"))choices.add(choice.getAsString());
            if(choices.isEmpty())throw new IllegalArgumentException("empty supply choices "+id);
            int count=row.get("count").getAsInt();if(count<1||count>10000)throw new IllegalArgumentException("invalid stock "+id);
            result.add(new Supply(id,row.get("section").getAsInt(),pos(row.getAsJsonArray("pos")),List.copyOf(choices),count,
                row.get("infinite").getAsBoolean(),row.get("mustExist").getAsBoolean(),row.get("directorChoice").getAsBoolean()));
        }
        return List.copyOf(result);
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
