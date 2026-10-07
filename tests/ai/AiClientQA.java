package net.muxigame.outbreak.aiqa;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
@Mod(value="outbreak_ai_qa",dist=Dist.CLIENT)
public final class AiClientQA {
    int last=-1;String role=System.getProperty("qa.local.role");
    public AiClientQA(){NeoForge.EVENT_BUS.addListener(this::tick);}
    void tick(ClientTickEvent.Post event){var mc=Minecraft.getInstance();if(mc.player==null)return;var command=AiFiles.read("ai-command-"+role+".json");if(command==null||command.get("id").getAsInt()<=last)return;last=command.get("id").getAsInt();var result=new JsonObject();result.addProperty("id",last);
        try{switch(command.get("type").getAsString()){
            case "terminal"->net.neoforged.neoforge.network.PacketDistributor.sendToServer(new net.muxigame.minigames.GameNetwork.TerminalAction(command.get("request").getAsString(),"outbreak",command.get("action").getAsString(),command.get("value").getAsString()));
            case "crouch"->mc.options.keyShift.setDown(command.get("down").getAsBoolean());
            case "capture"->{try(var frame=Screenshot.takeScreenshot(mc.getMainRenderTarget())){frame.writeToFile(AiFiles.ROOT.resolve("ai-model-"+role+".png"));}}
            default->throw new IllegalArgumentException("Unknown client QA action");
        }result.addProperty("ok",true);}catch(Throwable error){result.addProperty("ok",false);result.addProperty("error",error.toString());}
        try{AiFiles.write("ai-result-"+role+"-"+last+".json",result);}catch(Exception failed){throw new RuntimeException(failed);}
    }
}
