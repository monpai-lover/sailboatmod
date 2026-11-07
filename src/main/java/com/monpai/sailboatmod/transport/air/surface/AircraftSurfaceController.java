package com.monpai.sailboatmod.transport.air.surface;

import java.util.*;
import com.monpai.sailboatmod.transport.air.rail.RailAircraftPath.Phase;

/** Pure visual servo demand. These targets do not predict lift, certify flight or advance any aircraft state. */
public final class AircraftSurfaceController {
    private AircraftSurfaceController() { }

    /** Heading increases from world +X toward +Z; bank follows Path's right-hand rotation about the nose. */
    public record Attitude(double pitchRadians,double bankRadians,double headingRadians) {
        public Attitude {
            if(!finite(pitchRadians)||Math.abs(pitchRadians)>Math.PI/2||!finite(bankRadians)||!finite(headingRadians))throw invalid();
            bankRadians=wrapRadians(bankRadians);headingRadians=wrapRadians(headingRadians);
        }
    }
    public record Input(Attitude actual,Attitude target,Attitude lookAhead,double lookAheadSeconds,double speed,Phase phase,double dtSeconds) {
        public Input {
            if(actual==null||target==null||lookAhead==null||phase==null||!finite(lookAheadSeconds)||lookAheadSeconds<=0||lookAheadSeconds>5
                    ||!finite(speed)||speed<0||speed>100||!finite(dtSeconds)||dtSeconds<=0||dtSeconds>.25)throw invalid();
        }
    }
    /** Transient actual-attitude history. Start fresh after a pause or missing observation; it is never actuator angle history. */
    public record State(AircraftSurfaceProfile profile,Attitude previousActual) {
        public State {if((profile==null)!=(previousActual==null))throw invalid();}
        public static State initial(){return new State(null,null);}
    }
    public record Channels(double elevator,double aileron,double rudder) { }
    /** Logical role demand, wrapped desired angle and native servo rate limit; actual angular slew belongs to the servo. */
    public record Command(AircraftSurfaceBinding binding,double normalizedDemand,double targetAngleDeg,double rateDegPerSecond) { }
    public record Result(State state,Channels channels,List<Command> commands) {
        public Result {commands=List.copyOf(commands);}
    }

    public static Result step(AircraftSurfaceProfile profile,State state,Input input) {
        if(profile==null||state==null||input==null)throw invalid();
        Attitude actual=input.actual(),target=input.target(),ahead=input.lookAhead();
        Attitude previous=profile.equals(state.profile())?state.previousActual():null;
        double pitchRate=previous==null?0:boundedRate(actual.pitchRadians()-previous.pitchRadians(),input.dtSeconds());
        double bankRate=previous==null?0:boundedRate(wrapRadians(actual.bankRadians()-previous.bankRadians()),input.dtSeconds());
        double yawRate=previous==null?0:boundedRate(wrapRadians(actual.headingRadians()-previous.headingRadians()),input.dtSeconds());
        double desiredPitchRate=boundedRate(ahead.pitchRadians()-target.pitchRadians(),input.lookAheadSeconds());
        double desiredBankRate=boundedRate(wrapRadians(ahead.bankRadians()-target.bankRadians()),input.lookAheadSeconds());
        double desiredYawRate=boundedRate(wrapRadians(ahead.headingRadians()-target.headingRadians()),input.lookAheadSeconds());
        double elevator=0,aileron=0,rudder=0;
        boolean airborne=input.phase()==Phase.CLIMB||input.phase()==Phase.CRUISE||input.phase()==Phase.APPROACH;
        if(airborne||input.phase()==Phase.TAKEOFF) {
            double moving=Math.min(1,input.speed()/5);
            double turnTrim=airborne?Math.min(.06,.25*(1/Math.cos(Math.min(Math.abs(actual.bankRadians()),Math.PI/3))-1))*moving:0;
            double flareTrim=input.phase()==Phase.APPROACH?.04*clamp((ahead.pitchRadians()-target.pitchRadians())/.1,0,1)*moving:0;
            elevator=clamp(1.8*(target.pitchRadians()-actual.pitchRadians())+.55*desiredPitchRate-.15*pitchRate+turnTrim+flareTrim,-1,1);
            if(airborne) {
                // Bank itself is deliberately absent: an established turn returns differential surfaces to neutral.
                aileron=clamp(1.5*wrapRadians(target.bankRadians()-actual.bankRadians())+.65*desiredBankRate-.12*bankRate,-1,1);
                rudder=clamp(.12*desiredYawRate+.10*wrapRadians(target.headingRadians()-actual.headingRadians())-.04*yawRate,-.25,.25);
            }
        }
        var channels=new Channels(elevator,aileron,rudder);var commands=new ArrayList<Command>(profile.bindings().size());
        for(var binding:profile.bindings()) {
            double demand=binding.mode()==AircraftSurfaceBinding.Mode.HOLD?0:switch(binding.role()) {
                case ELEVATOR -> elevator;
                case AILERON_LEFT -> -aileron;
                case AILERON_RIGHT -> aileron;
                case RUDDER -> rudder;
                case ELEVON_LEFT -> clamp(elevator-aileron,-1,1);
                case ELEVON_RIGHT -> clamp(elevator+aileron,-1,1);
            };
            double angle=wrapDegrees(binding.neutralAngleDeg()+binding.polarity()*binding.maxDeflectionDeg()*demand);
            commands.add(new Command(binding,demand,angle,binding.rateDegPerSecond()));
        }
        return new Result(new State(profile,actual),channels,commands);
    }
    private static double boundedRate(double delta,double seconds){return clamp(delta/seconds,-2,2);}
    private static double wrapRadians(double value){double v=value%(2*Math.PI);return v>=Math.PI?v-2*Math.PI:v< -Math.PI?v+2*Math.PI:v;}
    private static double wrapDegrees(double value){double v=value%360;return v>=180?v-360:v< -180?v+360:v;}
    private static double clamp(double value,double min,double max){return Math.max(min,Math.min(max,value));}
    private static boolean finite(double value){return Double.isFinite(value);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("surface_invalid_sample");}
}
