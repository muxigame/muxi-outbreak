package net.muxigame.outbreak.equipment;

import java.util.function.Function;
import java.util.function.Predicate;

/** Admission claims only Outbreak-owned tags; the common throw tag is not a room ID. */
public final class OutbreakEntityAdmission {
    public static boolean rejectForeignSession(Function<String,String> tags, Predicate<String> currentSession) {
        String supplySession=tags.apply("muxi_outbreak_supply_session");
        String equipmentSession=tags.apply("muxi_outbreak_equipment_session");
        return (!supplySession.isBlank()&&!currentSession.test(supplySession)) ||
            (!equipmentSession.isBlank()&&!currentSession.test(equipmentSession));
    }
    private OutbreakEntityAdmission(){}
}
