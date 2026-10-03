package net.muxigame.outbreak.infected;
/** Source has 100 survivor HP; the campaign uses Minecraft's 20. Special vanilla
 * attacks retain their existing relative profiles, converted once into MC units.
 * They are an adaptation, not Source pounce/choke/acid AI. */
public final class OutbreakCombatRules {
    private static final double[] COMMON={.2,.4,1,4};
    private static final double[] FRIENDLY={0,.1,.3,.5};
    public static int difficulty(int d){return Math.max(0,Math.min(3,d));}
    public static double melee(InfectedKind kind,int difficulty){
        int d=difficulty(difficulty);if(kind==InfectedKind.COMMON)return COMMON[d];
        double sourceUnits=switch(kind){case HUNTER->7;case SMOKER,JOCKEY->5;case BOOMER->3;case TANK->15;case WITCH->11;case SPITTER->4;case CHARGER->12;default->throw new IllegalArgumentException();};
        return sourceUnits/5*(.85+d*.17);
    }
    public static double friendly(int difficulty){return FRIENDLY[difficulty(difficulty)];}
    private OutbreakCombatRules(){}
}
