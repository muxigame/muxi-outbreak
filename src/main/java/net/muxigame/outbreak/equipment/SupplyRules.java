package net.muxigame.outbreak.equipment;

import java.util.*;

/** Pure policy shared by runtime and regression tests. No Minecraft state or IO. */
public final class SupplyRules {
    public enum Slot { PRIMARY, SECONDARY, THROWABLE, LARGE_MEDICAL, SMALL_MEDICAL, AMMO, NONE }
    public static final int AMMO_FIRST_SLOT=9;
    private SupplyRules() {}

    public static int challengeReserve(int combinedMagazineCapacity,boolean launcher) {
        return launcher?Math.max(4,Math.min(12,combinedMagazineCapacity*4)):
            Math.max(120,Math.min(600,combinedMagazineCapacity*4));
    }
    public static double firstAidPermanent(double permanent,double maximum) {
        return Math.min(maximum,permanent+(maximum-permanent)*.8);
    }
    public static double addTemporary(double currentTotal,double maximum,boolean adrenaline) {
        return Math.min(maximum-currentTotal,maximum*(adrenaline?.25:.5));
    }

    public static final class Stock {
        private int remaining;
        private final boolean infinite;
        public Stock(int count,boolean infinite) {
            if (count<1 || count>10000) throw new IllegalArgumentException("invalid stock");
            remaining=count;this.infinite=infinite;
        }
        public int remaining(){return remaining;}
        public boolean infinite(){return infinite;}
        public boolean available(){return infinite||remaining>0;}
        // Full slot or failed transaction must not consume the source's shared stock.
        public boolean take(boolean accepted) {
            if(!accepted||!available())return false;
            if(!infinite)remaining--;
            return true;
        }
    }

    /** Native adaptation of the documented Director item-choice contract, not Valve's private RNG. */
    public static String choose(List<String> allowed,boolean mustExist,boolean randomNode,
                                double healthRatio,int largeMedicalCarried,int players,Random random) {
        if(allowed.isEmpty())throw new IllegalArgumentException("empty supply pool");
        // Guaranteed map items cannot be culled. Fixed map entities are never rewritten.
        if(!randomNode)return allowed.get(0);
        if(!mustExist&&random.nextDouble()<.18)return "";
        double need=Math.max(0,Math.min(1,1-healthRatio));
        double[] weights=new double[allowed.size()];double total=0;
        for(int i=0;i<allowed.size();i++){
            String key=allowed.get(i);double weight=1;
            if(key.equals("medkit")||key.equals("pills")||key.equals("adrenaline"))
                weight=1+need*3+(largeMedicalCarried<players?.8:0);
            if(key.startsWith("gun:"))weight=.8+healthRatio*.4;
            weights[i]=weight;total+=weight;
        }
        double roll=random.nextDouble()*total;
        for(int i=0;i<weights.length;i++)if((roll-=weights[i])<=0)return allowed.get(i);
        return allowed.getLast();
    }
}
