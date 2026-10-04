package net.muxigame.outbreak.interactionqa;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.muxigame.core.feature.input.GameInputContextState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Calls the real KeyboardHandler path; never injects an OS physical key or sets F claims. */
@Mod(value="outbreak_interaction_qa",dist=Dist.CLIENT)
public final class InteractionClientQA {
    final String role=System.getProperty("qa.local.role");int last=-1;
    public InteractionClientQA(){NeoForge.EVENT_BUS.addListener(this::tick);}
    void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();if(mc.player==null||mc.getConnection()==null)return;
        var command=InteractionFiles.read("interaction-command-"+role+".json");if(command==null||command.get("id").getAsInt()<=last)return;
        last=command.get("id").getAsInt();var result=new JsonObject();result.addProperty("id",last);
        try{
            String type=command.get("type").getAsString();
            result.addProperty("serverIssuedFContext",GameInputContextState.claimsOutbreak(mc.level.dimension().location().toString()));
            if(type.equals("f")){
                mc.setScreen(null);
                var method=mc.keyboardHandler.getClass().getDeclaredMethod("keyPress",long.class,int.class,int.class,int.class,int.class);method.setAccessible(true);
                long window=mc.getWindow().getWindow();int mods=command.has("modifiers")?command.get("modifiers").getAsInt():0;
                method.invoke(mc.keyboardHandler,window,70,0,1,mods);
                for(int i=0;i<(command.has("repeats")?command.get("repeats").getAsInt():0);i++)method.invoke(mc.keyboardHandler,window,70,0,2,mods);
                int gunClicks=0;boolean gunDown=false;
                for(var mapping:mc.options.keyMappings)if(mapping.getName().equals("key.tacz.interact.desc")){gunDown|=mapping.isDown();while(mapping.consumeClick())gunClicks++;}
                method.invoke(mc.keyboardHandler,window,70,0,0,mods);
                result.addProperty("taczClicks",gunClicks);result.addProperty("taczDown",gunDown);
                result.addProperty("nativeKeyboardHandler",true);result.addProperty("physicalOSInput",false);
            }else if(type.equals("close")){mc.player.closeContainer();mc.setScreen(null);}
            else if(!type.equals("observe"))throw new IllegalArgumentException(type);
            result.addProperty("ok",true);
        }catch(Throwable failure){result.addProperty("ok",false);result.addProperty("error",failure.toString());failure.printStackTrace();}
        try{InteractionFiles.write("interaction-result-"+role+"-"+last+".json",result);}catch(Exception busy){throw new IllegalStateException(busy);}
    }
}
