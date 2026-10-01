package net.muxigame.outbreak.map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.muxigame.outbreak.MuxiOutbreak;

import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Installs native structure NBT in bounded server-tick batches, never on a live save from outside. */
public final class GeometryInstaller {
    private record Piece(ResourceLocation resource, BlockPos origin, int blocks, String sha256) {}
    private final MinecraftServer server;
    private final Map<String, Job> jobs = new HashMap<>();
    private final class Job {
        final OutbreakMap map;
        final List<Piece> pieces = new ArrayList<>();
        final Path marker;
        final String hash;
        int pieceIndex, blockIndex, placed, expected;
        ListTag blocks;
        List<BlockState> palette;
        boolean ready;
        String error;
        Job(OutbreakMap map, JsonObject manifest) {
            this.map = map;
            hash = manifest.get("sha256").getAsString();
            if (!hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid geometry hash");
            marker = server.getWorldPath(LevelResource.ROOT).resolve("data/muxi-outbreak/geometry-" + hash + ".done");
            for (var element : manifest.getAsJsonArray("structures")) {
                var row = element.getAsJsonObject();
                var origin = row.getAsJsonArray("origin");
                ResourceLocation resource = ResourceLocation.parse(row.get("resource").getAsString());
                if (!resource.getNamespace().equals(MuxiOutbreak.MOD_ID) || !resource.getPath().startsWith("structure/"))
                    throw new IllegalArgumentException("unexpected structure resource");
                int count = row.get("blocks").getAsInt();
                if (count < 1 || count > 32768) throw new IllegalArgumentException("invalid structure size");
                pieces.add(new Piece(resource, new BlockPos(origin.get(0).getAsInt(),origin.get(1).getAsInt(),origin.get(2).getAsInt()), count, row.get("sha256").getAsString()));
                expected += count;
            }
            if (pieces.isEmpty() || pieces.size() > 10000 || expected != manifest.get("blocks").getAsInt())
                throw new IllegalArgumentException("invalid geometry manifest");
            ready = Files.isRegularFile(marker) && spawnSentinelsPresent(map);
            if (ready) placed = expected;
        }
    }

    public GeometryInstaller(MinecraftServer server) { this.server = server; }

    private boolean spawnSentinelsPresent(OutbreakMap map) {
        ServerLevel level=server.getLevel(map.dimension());
        if (level==null) return false;
        List<BlockPos> points=new ArrayList<>();
        points.add(map.start());points.add(map.finish());
        for (var chapter:map.chapters()) points.add(chapter.start());
        for (BlockPos p:points) {
            if (level.getBlockState(p.below()).getCollisionShape(level,p.below()).isEmpty()) return false;
            if (!level.getBlockState(p).getCollisionShape(level,p).isEmpty()) return false;
            if (!level.getBlockState(p.above()).getCollisionShape(level,p.above()).isEmpty()) return false;
        }
        return true;
    }

    public void request(OutbreakMap map) {
        if (map.geometry().isBlank() || jobs.containsKey(map.id())) return;
        if (!map.dimension().location().getNamespace().equals(MuxiOutbreak.MOD_ID))
            throw new IllegalArgumentException("几何只能安装到 Outbreak 独立维度");
        try (var reader = server.getResourceManager().getResourceOrThrow(ResourceLocation.parse(map.geometry())).openAsReader()) {
            jobs.put(map.id(), new Job(map, JsonParser.parseReader(reader).getAsJsonObject()));
        } catch (Exception error) { throw new IllegalStateException("读取战役几何失败", error); }
    }

    public boolean ready(OutbreakMap map) { return map.geometry().isBlank() || (jobs.containsKey(map.id()) && jobs.get(map.id()).ready); }
    public String error(OutbreakMap map) { return jobs.containsKey(map.id()) ? jobs.get(map.id()).error : null; }
    public String progress(OutbreakMap map) {
        Job job = jobs.get(map.id());
        return job == null ? "未准备" : job.error != null ? "失败：" + job.error : job.ready ? "地图就绪" : job.placed + "/" + job.expected + " 方块";
    }

    public void tick() {
        // Includes chunk creation and light updates: at most 4096 blocks AND ~6ms per tick.
        long deadline = System.nanoTime() + 6_000_000L;
        int budget = 4096;
        for (Job job : jobs.values()) {
            if (job.ready || job.error != null) continue;
            try {
                ServerLevel level = server.getLevel(job.map.dimension());
                if (level == null) throw new IllegalStateException("战役维度未加载");
                while (budget > 0 && System.nanoTime() < deadline && job.pieceIndex < job.pieces.size()) {
                    Piece piece = job.pieces.get(job.pieceIndex);
                    if (job.blocks == null) {
                        byte[] bytes;
                        try (var in = server.getResourceManager().getResourceOrThrow(piece.resource()).open()) { bytes = in.readAllBytes(); }
                        if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(piece.sha256()))
                            throw new IllegalStateException("结构校验失败：" + piece.resource());
                        CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(32 * 1024 * 1024L));
                        job.blocks = root.getList("blocks", Tag.TAG_COMPOUND);
                        if (job.blocks.size() != piece.blocks()) throw new IllegalStateException("结构方块数不匹配");
                        job.palette = new ArrayList<>();
                        for (var state : root.getList("palette", Tag.TAG_COMPOUND))
                            job.palette.add(NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), (CompoundTag)state));
                        job.blockIndex = 0;
                    }
                    while (job.blockIndex < job.blocks.size() && budget > 0 && System.nanoTime() < deadline) {
                        CompoundTag block = job.blocks.getCompound(job.blockIndex++);
                        ListTag pos = block.getList("pos", Tag.TAG_INT);
                        if (pos.size() != 3) throw new IllegalStateException("结构坐标无效");
                        BlockPos target = piece.origin().offset(pos.getInt(0),pos.getInt(1),pos.getInt(2));
                        if (level.isOutsideBuildHeight(target)) throw new IllegalStateException("结构超出建筑高度");
                        level.setBlock(target,job.palette.get(block.getInt("state")),2 | 16);
                        job.placed++;
                        budget--;
                    }
                    if (job.blockIndex == job.blocks.size()) {
                        job.blocks = null;
                        job.palette = null;
                        job.pieceIndex++;
                    }
                }
                if (job.pieceIndex == job.pieces.size()) {
                    // Flush before the marker; a crash must not mark unsaved chunks as installed.
                    level.save(null, true, false);
                    Files.createDirectories(job.marker.getParent());
                    Path tmp = job.marker.resolveSibling(job.marker.getFileName() + ".tmp");
                    Files.writeString(tmp, job.hash + "\n" + job.placed + "\n");
                    Files.move(tmp,job.marker,StandardCopyOption.REPLACE_EXISTING);
                    job.ready = true;
                    MuxiOutbreak.LOG.info("OUTBREAK_GEOMETRY_READY map={} blocks={} structures={} sha256={}",job.map.id(),job.placed,job.pieces.size(),job.hash);
                }
            } catch (Exception error) {
                job.error = error.getMessage();
                MuxiOutbreak.LOG.error("Unable to install outbreak geometry {}",job.map.id(),error);
            }
            if (budget == 0 || System.nanoTime() >= deadline) return;
        }
    }
}
