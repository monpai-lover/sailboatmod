package com.monpai.sailboatmod.route;
import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class AutopilotMotionPolicyTest {
    @Test void intermediatePointDoesNotStopInOldDeadZone(){for(double d=1.26;d<2.16;d+=.01)assertFalse(AutopilotMotionPolicy.shouldStopForArrival(false,d,2.16));}
    @Test void finalBerthStillStops(){assertTrue(AutopilotMotionPolicy.shouldStopForArrival(true,1.5,2.16));}
    @Test void outsideFinalArrivalRangeDoesNotStop(){assertFalse(AutopilotMotionPolicy.shouldStopForArrival(true,4,2.16));}
    @Test void exactIntermediateVertexDoesNotRequestArrivalStop(){assertFalse(AutopilotMotionPolicy.shouldStopForArrival(false,0,2.16));}
    @Test void waterInterfaceIsIndependentOfBobbingHull(){assertEquals(64,AutopilotMotionPolicy.waterBlockY(65));}
    @Test void openWaterBoatCrossesIntermediateCaptureGap(){var route=List.of(new Vec3(0,65,0),new Vec3(10,65,0),new Vec3(40,65,0));double x=7.9;int index=1;Vec3 last=new Vec3(x,65,0);for(int tick=0;tick<200;tick++){Vec3 boat=new Vec3(x,65,0);index=WaterPolylineFollower.advance(route,index,last,boat,1.25,3,(a,b)->true);boolean stop=AutopilotMotionPolicy.shouldStopForArrival(index==2,Math.abs(route.get(index).x-x),2.16);last=boat;if(!stop)x+=.15;}assertEquals(2,index);assertTrue(x>35);}
    @Test void confirmedBlockedLookaheadStillStops(){var route=List.of(new Vec3(0,65,0),new Vec3(40,65,0));var aim=WaterPolylineFollower.aim(route,1,new Vec3(8,65,0),10,(a,b)->false);assertEquals(0,WaterPolylineFollower.speedCap(aim,0,.4),1e-8);}
}
