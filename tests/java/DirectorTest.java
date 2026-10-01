import net.muxigame.outbreak.director.Director;
import java.util.Random;

public final class DirectorTest {
    public static void main(String[] args) {
        Director director=new Director();
        Random random=new Random(20261001L);
        var healthy=new Director.Sample(4,1,0,0,.5,0,0);
        boolean build=false,peak=false,fade=false,horde=false,special=false,boss=false;
        for (int i=0;i<600;i++) {
            var d=director.tick(healthy,random);
            assert d.intensity()>=0 && d.intensity()<=1;
            assert d.desiredCommon()>=0 && d.desiredCommon()<=60;
            build|=d.pace()==Director.Pace.BUILD;peak|=d.pace()==Director.Pace.PEAK;fade|=d.pace()==Director.Pace.FADE;
            horde|=d.hordePulse();special|=d.special();boss|=d.boss();
        }
        assert build && peak && fade && horde && special && boss : "missing director cycle event";
        director.forcePanic(true);
        for(int i=0;i<50;i++) assert director.tick(healthy,random).pace()==Director.Pace.PEAK;
        director.forcePanic(false);
        var stressed=new Director.Sample(4,.1,1,1,.5,90,3);
        var sample=director.tick(stressed,random);
        assert !sample.special() && !sample.boss() : "must not intensify an overwhelmed squad";
        assert sample.pace()==Director.Pace.FADE;
        System.out.println("DIRECTOR_REGRESSION_PASS: cycle, horde, specials, boss, forced panic, pressure relief");
    }
}
