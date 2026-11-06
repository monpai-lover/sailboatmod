package com.monpai.sailboatmod.transport.air.surface;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.transport.air.surface.AircraftSurfaceBinding.*;
import static com.monpai.sailboatmod.transport.air.surface.AircraftSurfaceController.*;
import static com.monpai.sailboatmod.transport.air.surface.AircraftSurfaceProfileTest.*;
import com.monpai.sailboatmod.transport.air.rail.RailAircraftPath.Phase;

class AircraftSurfaceControllerTest {
    static Attitude a(double pitch,double bank,double heading){return new Attitude(Math.toRadians(pitch),Math.toRadians(bank),Math.toRadians(heading));}
    static Input input(Attitude actual,Attitude target,Attitude ahead,Phase phase){return new Input(actual,target,ahead,1,25,phase,.05);}
    static Result run(AircraftSurfaceProfile p,Attitude actual,Attitude target,Attitude ahead,Phase phase){return step(p,State.initial(),input(actual,target,ahead,phase));}
    static AircraftSurfaceProfile classic(){return profile(binding(Role.ELEVATOR,0),binding(Role.AILERON_LEFT,1),binding(Role.AILERON_RIGHT,2),binding(Role.RUDDER,3));}
    static Command command(Result result,Role role){return result.commands().stream().filter(c->c.binding().role()==role).findFirst().orElseThrow();}
    static double delta(double a,double b){double d=(a-b)%360;return d>=180?d-360:d< -180?d+360:d;}

    @Test void anticipatingTurnMovesAileronsDifferentiallyAndExitTurnReversesThem() {
        var entering=run(classic(),a(0,0,0),a(0,0,0),a(0,20,5),Phase.CRUISE);
        assertTrue(command(entering,Role.AILERON_RIGHT).targetAngleDeg()>0);assertTrue(command(entering,Role.AILERON_LEFT).targetAngleDeg()<0);
        assertEquals(-command(entering,Role.AILERON_LEFT).targetAngleDeg(),command(entering,Role.AILERON_RIGHT).targetAngleDeg(),1e-10);
        var exiting=run(classic(),a(0,20,0),a(0,20,0),a(0,0,0),Phase.CRUISE);
        assertTrue(command(exiting,Role.AILERON_RIGHT).targetAngleDeg()<0);assertTrue(command(exiting,Role.AILERON_LEFT).targetAngleDeg()>0);
    }
    @Test void establishedBankReturnsAileronsToNeutralRatherThanHoldingFullDeflection() {
        var steady=run(classic(),a(0,25,30),a(0,25,30),a(0,25,30),Phase.CRUISE);
        assertEquals(0,steady.channels().aileron(),1e-12);assertEquals(0,command(steady,Role.AILERON_RIGHT).targetAngleDeg(),1e-12);
        assertTrue(steady.channels().elevator()>0&&steady.channels().elevator()<.08,"Only a small visual turn-load trim remains");
        assertEquals(0,steady.channels().rudder(),1e-12);
    }
    @Test void actualRollRateDampsCommandWhileRollErrorAndPreviewRemainTheSame() {
        var p=classic();var prior=run(p,a(0,0,0),a(0,10,0),a(0,20,0),Phase.CRUISE);
        var moving=step(p,prior.state(),input(a(0,1,0),a(0,10,0),a(0,20,0),Phase.CRUISE));
        var stationary=run(p,a(0,1,0),a(0,10,0),a(0,20,0),Phase.CRUISE);
        assertTrue(moving.channels().aileron()<stationary.channels().aileron());assertTrue(moving.channels().aileron()>0);
    }
    @Test void pitchRateErrorAndFlareProduceCorrectElevatorDirection() {
        var climb=run(classic(),a(0,0,0),a(0,0,0),a(10,0,0),Phase.CLIMB);
        var error=run(classic(),a(0,0,0),a(10,0,0),a(10,0,0),Phase.CLIMB);
        var descend=run(classic(),a(10,0,0),a(0,0,0),a(0,0,0),Phase.CRUISE);
        assertTrue(climb.channels().elevator()>0);assertTrue(error.channels().elevator()>0);assertTrue(descend.channels().elevator()<0);
        var flare=run(classic(),a(-8,0,0),a(-8,0,0),a(0,0,0),Phase.APPROACH);
        var samePreview=run(classic(),a(-8,0,0),a(-8,0,0),a(0,0,0),Phase.CRUISE);
        assertTrue(flare.channels().elevator()>samePreview.channels().elevator());
    }
    @Test void actualPitchRateDampsTheElevatorWithoutChangingTargetAttitude() {
        var p=classic();var prior=run(p,a(0,0,0),a(10,0,0),a(10,0,0),Phase.CLIMB);
        var moving=step(p,prior.state(),input(a(1,0,0),a(10,0,0),a(10,0,0),Phase.CLIMB));
        assertTrue(moving.channels().elevator()<run(p,a(1,0,0),a(10,0,0),a(10,0,0),Phase.CLIMB).channels().elevator());
    }
    @Test void communityRudderTracksWrappedYawRateWithSmallCoordinationCommand() {
        var right=run(classic(),a(0,0,179),a(0,0,179),a(0,0,-179),Phase.CRUISE);
        var left=run(classic(),a(0,0,-179),a(0,0,-179),a(0,0,179),Phase.CRUISE);
        assertTrue(command(right,Role.RUDDER).targetAngleDeg()>0);assertTrue(command(left,Role.RUDDER).targetAngleDeg()<0);
        assertTrue(Math.abs(right.channels().rudder())<.03,"179 to -179 is two degrees, not a 358 degree yaw jump");
        var extreme=run(classic(),a(0,0,0),a(0,0,160),a(0,0,170),Phase.CRUISE);
        assertTrue(Math.abs(extreme.channels().rudder())<=.25);
    }
    @Test void actualHeadingDerivativeAlsoUsesShortestWrappedDifference() {
        var p=classic();var first=run(p,a(0,0,179),a(0,0,179),a(0,0,179),Phase.CRUISE);
        var next=step(p,first.state(),input(a(0,0,-179),a(0,0,-179),a(0,0,-179),Phase.CRUISE));
        assertTrue(next.channels().rudder()<0);assertTrue(Math.abs(next.channels().rudder())<.05);
    }
    @Test void elevonsMixPitchAndDifferentialRollAndStayWithinConfiguredLimit() {
        var p=profile(binding(Role.ELEVON_LEFT,0),binding(Role.ELEVON_RIGHT,1));
        var pitch=run(p,a(0,0,0),a(10,0,0),a(10,0,0),Phase.CLIMB);
        assertEquals(command(pitch,Role.ELEVON_LEFT).targetAngleDeg(),command(pitch,Role.ELEVON_RIGHT).targetAngleDeg(),1e-10);
        assertTrue(command(pitch,Role.ELEVON_LEFT).targetAngleDeg()>0);
        var roll=run(p,a(0,0,0),a(0,20,0),a(0,20,0),Phase.CRUISE);
        assertTrue(command(roll,Role.ELEVON_LEFT).targetAngleDeg()<0);assertTrue(command(roll,Role.ELEVON_RIGHT).targetAngleDeg()>0);
        var both=run(p,a(-80,-80,0),a(80,80,0),a(80,80,100),Phase.CLIMB);
        assertTrue(both.commands().stream().allMatch(c->Math.abs(c.targetAngleDeg())<=20));
    }
    @Test void polarityNeutralAndTargetWrappingAlwaysRespectRelativeDeflection() {
        var p=profile(new AircraftSurfaceBinding(Role.ELEVATOR,BlockPos.ZERO,179,-1,30,90));
        var result=run(p,a(-80,0,0),a(80,0,0),a(80,0,0),Phase.CLIMB);var c=result.commands().getFirst();
        assertEquals(149,c.targetAngleDeg(),1e-10);assertEquals(90,c.rateDegPerSecond());
        var opposite=run(p,a(80,0,0),a(-80,0,0),a(-80,0,0),Phase.CLIMB).commands().getFirst();
        assertEquals(-151,opposite.targetAngleDeg(),1e-10);assertEquals(30,Math.abs(delta(opposite.targetAngleDeg(),179)),1e-10);
        assertTrue(c.targetAngleDeg()>=-180&&c.targetAngleDeg()<180);
    }
    @Test void holdAndGroundPhasesRequestNeutralAndNeverInventUnboundSurfaces() {
        var hold=profile(new AircraftSurfaceBinding(Role.RUDDER,BlockPos.ZERO,15,-1,20,60,Mode.HOLD));
        var result=run(hold,a(0,0,0),a(0,0,50),a(0,0,60),Phase.CRUISE);
        assertEquals(1,result.commands().size());assertEquals(15,result.commands().getFirst().targetAngleDeg());assertEquals(0,result.commands().getFirst().normalizedDemand());
        for(Phase phase:List.of(Phase.TAXI_OUT,Phase.HOLD_SHORT,Phase.LINE_UP,Phase.LANDING_ROLL,Phase.TAXI_IN,Phase.STAND_STABILIZING,Phase.COMPLETE)) {
            var ground=run(classic(),a(10,20,0),a(0,0,30),a(0,0,60),phase);
            assertTrue(ground.commands().stream().allMatch(c->c.normalizedDemand()==0));
        }
        assertTrue(run(profile(),a(0,0,0),a(0,0,0),a(0,0,0),Phase.CRUISE).commands().isEmpty());
    }
    @Test void takeoffElevatorAnticipatesRotationButStoppedAircraftHasNoTurnTrim() {
        var rotation=run(classic(),a(0,0,0),a(0,0,0),a(3,0,0),Phase.TAKEOFF);
        assertTrue(rotation.channels().elevator()>0);assertEquals(0,rotation.channels().aileron());assertEquals(0,rotation.channels().rudder());
        var stopped=step(classic(),State.initial(),new Input(a(0,25,0),a(0,25,0),a(0,25,0),1,0,Phase.CRUISE,.05));
        assertEquals(0,stopped.channels().elevator());
    }
    @Test void invalidSamplesFailBeforeProducingCommandsAndCannotAdvanceState() {
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY})assertThrows(IllegalArgumentException.class,()->new Attitude(bad,0,0));
        assertThrows(IllegalArgumentException.class,()->a(91,0,0));
        for(double dt:new double[]{0,-.05,.251,Double.NaN})assertThrows(IllegalArgumentException.class,()->new Input(a(0,0,0),a(0,0,0),a(0,0,0),1,25,Phase.CRUISE,dt));
        for(double speed:new double[]{-1,100.1,Double.NaN})assertThrows(IllegalArgumentException.class,()->new Input(a(0,0,0),a(0,0,0),a(0,0,0),1,speed,Phase.CRUISE,.05));
        for(double preview:new double[]{0,5.1,Double.NaN})assertThrows(IllegalArgumentException.class,()->new Input(a(0,0,0),a(0,0,0),a(0,0,0),preview,25,Phase.CRUISE,.05));
        assertEquals(State.initial(),State.initial());
    }
    @Test void aDifferentExplicitProfileCannotReuseOldBodyAngularHistory() {
        var p=classic();var first=run(p,a(0,0,0),a(0,0,0),a(0,0,0),Phase.CRUISE);
        var other=new AircraftSurfaceProfile(UUID.randomUUID(),Direction.WEST,p.bindings());
        var fresh=step(other,first.state(),input(a(1,1,1),a(1,1,1),a(1,1,1),Phase.CRUISE));
        assertEquals(0,fresh.channels().aileron());assertEquals(0,fresh.channels().rudder());
        assertEquals(a(0,0,0),first.state().previousActual());
        assertThrows(UnsupportedOperationException.class,()->fresh.commands().clear());
    }
}
