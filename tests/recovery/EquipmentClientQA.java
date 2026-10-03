package net.muxigame.outbreak.qa;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.*;
import net.muxigame.minigames.GameNetwork;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.nio.file.*;
/** QA-only real loopback clients; no fake ServerPlayers and no focus calls. */
@Mod(value="outbreak_equipment_client_qa",dist=Dist.CLIENT)
public final class EquipmentClientQA {
    final String role=System.getProperty("qa.outbreak.role");final Path coordinator=Path.of(System.getProperty("qa.outbreak.coordinator"));
    boolean connecting,stopping;int ticks,last=-1,useUntil;JsonObject pending;int completeAt;
    public EquipmentClientQA(){NeoForge.EVENT_BUS.addListener(this::tick);}
    void write(String name,JsonObject value)throws Exception{Path p=coordinator.resolve(name),tmp=p.resolveSibling(p.getFileName()+".tmp");Files.writeString(tmp,value.toString());Files.move(tmp,p,StandardCopyOption.REPLACE_EXISTING);}
    void tick(ClientTickEvent.Post ignored){if(stopping)return;Minecraft mc=Minecraft.getInstance();ticks++;
        try{
            if(useUntil>0&&ticks>=useUntil){mc.options.keyUse.setDown(false);mc.gameMode.releaseUsingItem(mc.player);useUntil=0;}
            if(!connecting&&mc.screen instanceof TitleScreen){connecting=true;ConnectScreen.startConnecting(mc.screen,mc,ServerAddress.parseString("127.0.0.1:"+Integer.getInteger("qa.outbreak.port")),new ServerData("Outbreak owned QA","127.0.0.1",ServerData.Type.OTHER),false,null);}
            Path command=coordinator.resolve("command-"+role+".json");
            if(Files.exists(command)&&pending==null){JsonObject row;try{row=JsonParser.parseString(Files.readString(command)).getAsJsonObject();}catch(java.io.IOException busy){return;}if(row.get("id").getAsInt()>last){last=row.get("id").getAsInt();pending=row;completeAt=ticks+5;}}
            if(pending==null)return;
            if(pending.has("executed")){if(ticks<completeAt)return;JsonObject result=new JsonObject();result.addProperty("ok",true);result.addProperty("realMinecraftClient",true);result.addProperty("windowActive",mc.isWindowActive());result.addProperty("glfwVisible",org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(mc.getWindow().getWindow(),org.lwjgl.glfw.GLFW.GLFW_VISIBLE)==1);result.addProperty("glfwFocused",org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(mc.getWindow().getWindow(),org.lwjgl.glfw.GLFW.GLFW_FOCUSED)==1);if(mc.player!=null){result.addProperty("uuid",mc.player.getUUID().toString());result.addProperty("playersSeen",mc.getConnection().getOnlinePlayers().size());result.addProperty("health",mc.player.getHealth());}write("result-"+role+"-"+last+".json",result);pending=null;return;}
            String type=pending.get("type").getAsString();if(type.equals("stop")){stopping=true;mc.stop();return;}
            if(mc.player==null||mc.gameMode==null)return;mc.setScreen(null);
            switch(type){
                case "action"->PacketDistributor.sendToServer(new GameNetwork.Action("outbreak",pending.get("action").getAsString(),pending.has("value")?pending.get("value").getAsString():""));
                case "select"->mc.player.getInventory().selected=pending.get("slot").getAsInt();
                case "drop"->mc.player.drop(pending.get("all").getAsBoolean());
                case "aim"->{Vec3 delta=new Vec3(pending.get("x").getAsDouble(),pending.get("y").getAsDouble(),pending.get("z").getAsDouble()).subtract(mc.player.getEyePosition());mc.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x,delta.z)));mc.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y,Math.sqrt(delta.x*delta.x+delta.z*delta.z))));}
                case "use"->{mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);mc.options.keyUse.setDown(true);useUntil=ticks+pending.get("duration").getAsInt();completeAt=useUntil+5;}
                case "door"->{var p=new net.minecraft.core.BlockPos(pending.get("x").getAsInt(),pending.get("y").getAsInt(),pending.get("z").getAsInt());mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(p.getCenter(),net.minecraft.core.Direction.UP,p,false));}
                case "observe"->{}
                default->throw new IllegalArgumentException(type);
            }
            pending.addProperty("executed",true);
        }catch(Throwable failure){failure.printStackTrace();try{JsonObject result=new JsonObject();result.addProperty("ok",false);result.addProperty("error",failure.toString());write("fatal-"+role+".json",result);}catch(Exception ignored2){}stopping=true;mc.stop();}
    }
}
