package net.muxigame.outbreak.map;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Fixed cosmetic geometry: no collision, stock, drops, projectile target or AI. */
final class SourceDecorations {
    static final String OWNER_TAG="muxi_outbreak_source_decor";
    private static final TicketType<ChunkPos> INSTALL_TICKET=TicketType.create("muxi_outbreak_source_decor",Comparator.comparingLong(ChunkPos::toLong),40);
    record Part(UUID id,double[] pos,String block,float[] translation,float[] rotation,float[] scale) {}
    static double[] doubles(JsonObject row,String key,int size){
        var values=row.getAsJsonArray(key);if(values==null||values.size()!=size)throw new IllegalArgumentException("invalid decoration vector");
        var result=new double[size];for(int i=0;i<size;i++){result[i]=values.get(i).getAsDouble();if(!Double.isFinite(result[i]))throw new IllegalArgumentException("nonfinite decoration vector");}return result;
    }
    static float[] floats(JsonObject row,String key,int size){
        var data=doubles(row,key,size);var result=new float[size];for(int i=0;i<size;i++){if(Math.abs(data[i])>64)throw new IllegalArgumentException("decoration transform out of bounds");result[i]=(float)data[i];}return result;
    }
    static List<Part> read(JsonObject manifest){
        var result=new ArrayList<Part>();var seen=new HashSet<UUID>();
        if(!manifest.has("decorations"))return result;
        if(manifest.getAsJsonArray("decorations").size()>10000)throw new IllegalArgumentException("too many decoration parts");
        for(var element:manifest.getAsJsonArray("decorations")){
            var row=element.getAsJsonObject();var id=UUID.fromString(row.get("uuid").getAsString());if(!seen.add(id))throw new IllegalArgumentException("duplicate decoration UUID");
            var pos=doubles(row,"position",3);for(double v:pos)if(Math.abs(v)>29999984)throw new IllegalArgumentException("decoration outside world");
            String block=row.get("block").getAsString();var key=ResourceLocation.parse(block);
            if(!key.getNamespace().equals("minecraft")||BuiltInRegistries.BLOCK.get(key)==Blocks.AIR)throw new IllegalArgumentException("unknown decoration block");
            var scale=floats(row,"scale",3);for(float v:scale)if(v<=0)throw new IllegalArgumentException("nonpositive decoration scale");
            var rotation=floats(row,"rotation",4);double norm=0;for(float v:rotation)norm+=v*v;if(Math.abs(norm-1)>.001)throw new IllegalArgumentException("invalid decoration rotation");
            result.add(new Part(id,pos,block,floats(row,"translation",3),rotation,scale));
        }
        return result;
    }
    private static ListTag floats(float... data){var list=new ListTag();for(float v:data)list.add(FloatTag.valueOf(v));return list;}
    private static ListTag doubles(double... data){var list=new ListTag();for(double v:data)list.add(DoubleTag.valueOf(v));return list;}
    static boolean install(ServerLevel level,Part part){
        BlockPos pos=BlockPos.containing(part.pos[0],part.pos[1],part.pos[2]);if(level.isOutsideBuildHeight(pos))throw new IllegalArgumentException("decoration outside build height");
        // Saved entity UUIDs may exist in hidden sections before the public lookup can see them.
        // Hold an expiring, task-owned ticking ticket and yield to the next server tick.
        var chunk=new ChunkPos(pos);level.getChunkSource().addRegionTicket(INSTALL_TICKET,chunk,2,chunk);
        level.getChunkAt(pos);
        if(!level.areEntitiesLoaded(chunk.toLong())||!level.isPositionEntityTicking(pos))return false;
        var existing=level.getEntity(part.id);Display.BlockDisplay display;
        if(existing!=null){
            if(!(existing instanceof Display.BlockDisplay old)||!existing.getTags().contains(OWNER_TAG))throw new IllegalStateException("unowned decoration UUID");
            display=old;
        }else{display=EntityType.BLOCK_DISPLAY.create(level);if(display==null)throw new IllegalStateException("cannot create decoration display");}
        var tag=new CompoundTag();tag.putString("id","minecraft:block_display");tag.putUUID("UUID",part.id);tag.put("Pos",doubles(part.pos));
        tag.putBoolean("NoGravity",true);tag.putBoolean("Invulnerable",true);tag.putBoolean("Silent",true);
        var tags=new ListTag();tags.add(StringTag.valueOf(OWNER_TAG));tag.put("Tags",tags);
        var state=new CompoundTag();state.putString("Name",part.block);tag.put("block_state",state);
        var transform=new CompoundTag();transform.put("translation",floats(part.translation));transform.put("scale",floats(part.scale));
        transform.put("left_rotation",floats(part.rotation));transform.put("right_rotation",floats(0,0,0,1));tag.put("transformation",transform);
        tag.putString("billboard","fixed");tag.putFloat("view_range",.8F);tag.putFloat("shadow_radius",0);tag.putFloat("shadow_strength",0);
        tag.putInt("interpolation_duration",0);tag.putInt("teleport_duration",0);display.load(tag);
        if(display.isPickable()||display.getBoundingBox().getSize()!=0)throw new IllegalStateException("decoration acquired a gameplay hitbox");
        if(existing==null&&!level.addFreshEntity(display))throw new IllegalStateException("cannot add source decoration");
        return true;
    }
}
