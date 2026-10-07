package net.muxigame.outbreak.aiqa;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
public final class AiFiles {
    public static final Path ROOT=Path.of(System.getProperty("qa.local.root"),"coordinator");
    static final String RUN=System.getProperty("qa.local.runId");
    public static JsonObject read(String name){try{var row=JsonParser.parseString(Files.readString(ROOT.resolve(name),StandardCharsets.UTF_8)).getAsJsonObject();return RUN.equals(row.get("runId").getAsString())?row:null;}catch(Exception busy){return null;}}
    public static void write(String name,JsonObject row)throws Exception{row.addProperty("runId",RUN);Path to=ROOT.resolve(name),tmp=to.resolveSibling(to.getFileName()+".tmp");Files.writeString(tmp,row.toString(),StandardCharsets.UTF_8);for(int i=0;;i++){try{Files.move(tmp,to,StandardCopyOption.REPLACE_EXISTING);return;}catch(java.io.IOException busy){if(i==9)throw busy;Thread.sleep(10);}}}
}
