import net.muxigame.outbreak.equipment.SupplyRules;
import java.util.*;

public final class SupplyRulesTest {
    public static void main(String[] args){
        int checks=0;
        var shared=new SupplyRules.Stock(1,false);
        assert !shared.take(false)&&shared.remaining()==1;checks++;
        assert shared.take(true)&&shared.remaining()==0;checks++;
        assert !shared.take(true)&&shared.remaining()==0;checks++;
        var pile=new SupplyRules.Stock(1,true);
        for(int i=0;i<100;i++){assert pile.take(true)&&pile.remaining()==1;checks++;}
        assert SupplyRules.challengeReserve(30+17,false)==188;checks++;
        assert SupplyRules.challengeReserve(1,true)==4;checks++;
        assert SupplyRules.challengeReserve(200,false)==600;checks++;
        assert Math.abs(SupplyRules.firstAidPermanent(4,20)-16.8)<1e-8;checks++;
        assert SupplyRules.addTemporary(4,20,false)==10;checks++;
        assert SupplyRules.addTemporary(19,20,false)==1;checks++;
        assert SupplyRules.addTemporary(4,20,true)==5;checks++;
        var pool=List.of("medkit","pills","adrenaline","pipe_bomb");
        for(int i=0;i<1000;i++){
            String a=SupplyRules.choose(pool,true,true,.25,0,4,new Random(i));
            assert pool.contains(a);checks++;
            assert a.equals(SupplyRules.choose(pool,true,true,.25,0,4,new Random(i)));checks++;
            assert SupplyRules.choose(List.of("pipe_bomb"),false,false,0,0,4,new Random(i)).equals("pipe_bomb");checks++;
        }
        System.out.println("SUPPLY_RULES_PASS assertions="+checks+" shared stock, full-slot refusal, infinite ammo, caps, healing, deterministic allowed choices");
    }
}
