package net.muxigame.outbreak.map;

import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.muxigame.outbreak.MuxiOutbreak;

import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;

public final class OutbreakMapLoader {
    private OutbreakMapLoader() {}

    public static Map<String, OutbreakMap> load(MinecraftServer server) {
        Map<String, OutbreakMap> result = new LinkedHashMap<>();
        var resources = server.getResourceManager().listResources("outbreak_maps", id -> id.getPath().endsWith(".json"));
        for (var entry : resources.entrySet()) {
            if (!entry.getKey().getNamespace().equals(MuxiOutbreak.MOD_ID)) continue;
            try (Reader reader = entry.getValue().openAsReader()) {
                var json = JsonParser.parseReader(reader).getAsJsonObject();
                if (json.has("enabled") && !json.get("enabled").getAsBoolean()) continue;
                OutbreakMap map = OutbreakMap.parse(json);
                if (result.putIfAbsent(map.id(), map) != null) {
                    throw new IllegalArgumentException("duplicate map id " + map.id());
                }
            } catch (Exception error) {
                throw new IllegalStateException("Unable to load outbreak map " + entry.getKey(), error);
            }
        }
        return Map.copyOf(result);
    }
}
