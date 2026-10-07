package net.muxigame.outbreak.ai;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.server.MinecraftServer;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Resolves the installed preset from YSM's actual command registry, never just a directory name. */
public final class WineFoxSkin {
    public record Skin(String model,String texture) {}
    private static final Map<MinecraftServer,WineFoxSkin> SERVERS=new WeakHashMap<>();
    private CompletableFuture<Suggestions> models,textures;
    private String model;
    private Skin skin;
    private int requestedAt=-1000;
    private WineFoxSkin() {}
    private static CompletableFuture<Suggestions> suggest(MinecraftServer server,String command) {
        var dispatcher=server.getCommands().getDispatcher();
        var source=server.createCommandSourceStack();
        if(!server.getPlayerList().getPlayers().isEmpty())source=source.withEntity(server.getPlayerList().getPlayers().getFirst());
        return dispatcher.getCompletionSuggestions(dispatcher.parse(command,source));
    }
    private static String text(String raw) {
        try{return new StringReader(raw).readString();}catch(Exception invalid){return "";}
    }
    public static Skin resolve(MinecraftServer server) {
        if(server==null||!server.isSameThread())return null;
        return SERVERS.computeIfAbsent(server,s->new WineFoxSkin()).read(server);
    }
    private Skin read(MinecraftServer server) {
        try{
            int now=server.getTickCount();
            if(models==null||now-requestedAt>=200){
                models=suggest(server,"ysm model set @s ");textures=null;model=null;skin=null;requestedAt=now;
            }
            Suggestions registered=models.getNow(null);if(registered==null)return null;
            if(model==null){
                model=registered.getList().stream().map(s->text(s.getText())).filter(id->id.equals("wine_fox/01_taisho_maid")).findFirst().orElse(null);
                if(model==null)return null;
                textures=suggest(server,"ysm model set @s \""+model+"\" ");
            }
            Suggestions variants=textures.getNow(null);if(variants==null)return null;
            if(skin==null){
                String texture=variants.getList().stream().map(s->text(s.getText())).filter(id->id.equals("skin")).findFirst().orElse(null);
                if(texture!=null)skin=new Skin(model,texture);
            }
            return skin;
        }catch(RuntimeException unavailable){models=null;return null;}
    }
    public static void forget(MinecraftServer server){SERVERS.remove(server);}
}
