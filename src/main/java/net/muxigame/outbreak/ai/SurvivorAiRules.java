package net.muxigame.outbreak.ai;

/** Pure decisions shared by the coordinator and executable tests. */
public final class SurvivorAiRules {
    private SurvivorAiRules() {}
    public static boolean canRescue(boolean running,boolean helperDown,boolean targetDown,double distanceSquared,boolean visible) {
        return running&&!helperDown&&targetDown&&distanceSquared<=7&&visible;
    }
    public static boolean allIncapacitated(int living,int downed) { return living>0&&downed>=living; }
    public static boolean shouldRefill(int reserve,int capacity) { return reserve<Math.max(0,capacity); }
    public static double targetScore(double distanceSquared,boolean special,boolean threatensSurvivor) {
        return distanceSquared-(special?256:0)-(threatensSurvivor?128:0);
    }
}
