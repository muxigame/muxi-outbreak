import net.muxigame.outbreak.ai.SurvivorAiRules;
import net.muxigame.outbreak.equipment.OutbreakEntityAdmission;
import java.util.*;
public final class SurvivorAiRulesTest {
    private static int checks;
    private static void check(boolean result,String message){checks++;if(!result)throw new AssertionError(message);}
    public static void main(String[] args){
        // A downed last human plus standing AI is recoverable; all-down and no-survivor are distinct.
        check(!SurvivorAiRules.allIncapacitated(2,1),"last human may be rescued by AI");
        check(SurvivorAiRules.allIncapacitated(4,4),"mixed party all down");
        check(!SurvivorAiRules.allIncapacitated(0,0),"empty party handled by elimination path");
        for(boolean running:new boolean[]{false,true})for(boolean helperDown:new boolean[]{false,true})
        for(boolean targetDown:new boolean[]{false,true})for(boolean visible:new boolean[]{false,true}){
            boolean near=SurvivorAiRules.canRescue(running,helperDown,targetDown,6.9,visible);
            check(near==(running&&!helperDown&&targetDown&&visible),"rescue state matrix");
            check(!SurvivorAiRules.canRescue(running,helperDown,targetDown,7.01,visible),"rescue cannot remotely complete");
        }
        check(SurvivorAiRules.targetScore(100,true,true)<SurvivorAiRules.targetScore(10,false,false),"protect threatened survivor before idle common");
        Map<String,String> tags=new HashMap<>();String current="test-current";
        tags.put("muxi_outbreak_ai_session",current);
        check(!OutbreakEntityAdmission.rejectForeignSession(k->tags.getOrDefault(k,""),current::equals),"owned live AI admitted");
        tags.put("muxi_outbreak_ai_session","expired");
        check(OutbreakEntityAdmission.rejectForeignSession(k->tags.getOrDefault(k,""),current::equals),"old AI rejected");
        tags.clear();tags.put("session","other-game");
        check(!OutbreakEntityAdmission.rejectForeignSession(k->tags.getOrDefault(k,""),current::equals),"unrelated mobs remain untouched");
        System.out.println("SurvivorAiRulesTest: "+checks+" assertions passed");
    }
}
