import net.muxigame.outbreak.equipment.OutbreakEntityAdmission;
import java.util.*;

public final class EntityAdmissionTest {
    private static int checks;
    private static final String SHARED="muxi_minigames_throw_session";
    private static final String SUPPLY="muxi_outbreak_supply_session";
    private static final String EQUIPMENT="muxi_outbreak_equipment_session";
    private static boolean reject(Map<String,String> data,Set<String> rooms) {
        return OutbreakEntityAdmission.rejectForeignSession(key->data.getOrDefault(key,""),rooms::contains);
    }
    private static void check(boolean condition,String label) {
        checks++;if(!condition)throw new AssertionError(label);
    }
    public static void main(String[] args) {
        String player=UUID.fromString("b80c8065-d016-3fb3-9089-79a3cc281c20").toString();
        String roomA="room-a",roomB="room-b",stale="finished-room";
        check(!reject(Map.of(SHARED,player),Set.of()),"zombie shared player UUID, no Outbreak room");
        check(!reject(Map.of(SHARED,player),Set.of(roomA)),"other game's projectile while Outbreak active");
        check(!reject(Map.of(SHARED,roomA),Set.of(roomA)),"legacy campaign shared room tag is not claimed");
        check(!reject(Map.of(),Set.of()),"ordinary untagged entity");
        check(!reject(Map.of(SUPPLY,"",EQUIPMENT,""),Set.of()),"blank private tags");
        check(!reject(Map.of(SUPPLY,roomA),Set.of(roomA)),"valid private supply");
        check(!reject(Map.of(EQUIPMENT,roomA),Set.of(roomA)),"valid private equipment");
        check(!reject(Map.of(SHARED,player,EQUIPMENT,roomA),Set.of(roomA)),"shared tag plus valid private ownership");
        check(reject(Map.of(SUPPLY,stale),Set.of(roomA)),"orphan supply discarded");
        check(reject(Map.of(EQUIPMENT,stale),Set.of(roomA)),"orphan equipment discarded");
        check(reject(Map.of(SHARED,player,SUPPLY,stale),Set.of(roomA)),"shared tag cannot bypass invalid supply");
        check(reject(Map.of(SHARED,roomA,EQUIPMENT,stale),Set.of(roomA)),"shared matching room cannot bypass invalid equipment");
        check(reject(Map.of(SUPPLY,roomA,EQUIPMENT,stale),Set.of(roomA)),"valid supply does not permit foreign equipment");
        check(reject(Map.of(SUPPLY,stale,EQUIPMENT,roomA),Set.of(roomA)),"valid equipment does not permit foreign supply");
        check(reject(Map.of(EQUIPMENT,roomB),Set.of(roomA)),"private tag for absent second room");
        check(!reject(Map.of(SUPPLY,roomA,EQUIPMENT,roomB),Set.of(roomA,roomB)),"admission preserves both live rooms; impact handles targeting");
        check(reject(Map.of(EQUIPMENT,roomA),Set.of()),"closed room equipment still rejected");
        List<String> queried=new ArrayList<>();
        check(!OutbreakEntityAdmission.rejectForeignSession(key->{queried.add(key);return "";},id->{throw new AssertionError("blank tag queried session");}),"blank tags need no room query");
        check(queried.equals(List.of(SUPPLY,EQUIPMENT)),"only private Outbreak tags read, never shared throw tag");
        System.out.println("ENTITY_ADMISSION_PASS checks="+checks);
    }
}
