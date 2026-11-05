package com.monpai.sailboatmod.route;

import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Objects;
import java.util.Locale;
import java.util.function.BiPredicate;

/** Per-voyage, server-tick steering state. No persistent data, world references,
 * static entity maps, async reads, new tickets, or changes to physical collisions.
 */
public final class AutonavSteeringState {
    public static final float ENTER_DEGREES=95f, EXIT_DEGREES=35f;
    public static final int MAX_PIVOT_COMMANDS=160;
    private boolean pivot, timedOut;
    private int index=-1,size=-1,pivotCommands;
    private Vec3 before,vertex,after,heldAim,holdOrigin;
    private double releaseDistance, aimDistance;
    private boolean tightCorner;
    private float exitDegrees=EXIT_DEGREES;
    private float desiredYaw,error;
    private Vec3 lastAim;
    private String reason="new";

    public static WaterPolylineFollower.Aim selectAim(WaterPolylineFollower.Aim fresh,
            List<Vec3> route,int index,Vec3 boat,float yaw,BiPredicate<Vec3,Vec3> clear,
            AutonavSteeringState state) {
        return state.choose(fresh,route,index,boat,yaw,clear);
    }
    private WaterPolylineFollower.Aim choose(WaterPolylineFollower.Aim fresh,List<Vec3> route,
                                             int targetIndex,Vec3 boat,float yaw,
                                             BiPredicate<Vec3,Vec3> clear) {
        if(route==null||route.isEmpty()||!AutonavPolylineFix.finite(boat)||!Float.isFinite(yaw)) {
            drop();reason="invalid_geometry";return new WaterPolylineFollower.Aim(boat,true,0,0);
        }
        int i=Math.clamp(targetIndex,0,route.size()-1);
        Vec3 a=i>0?route.get(i-1):null, b=route.get(i), c=i+1<route.size()?route.get(i+1):null;
        if(i!=index||route.size()!=size||!Objects.equals(a,before)||!Objects.equals(b,vertex)||!Objects.equals(c,after)) {
            drop();index=i;size=route.size();before=a;vertex=b;after=c;
        }
        if(timedOut) {reason="turn_timeout";return new WaterPolylineFollower.Aim(boat,true,fresh.cornerDistance(),fresh.cornerDegrees());}
        if(fresh.blocked()||!AutonavPolylineFix.finite(fresh.point())) {
            drop();reason="no_clear_aim";lastAim=fresh.point();error=0;
            return fresh;
        }
        WaterPolylineFollower.Aim selected=fresh;
        if(heldAim!=null) {
            boolean reached=AutonavPolylineFix.horizontal(boat,heldAim)<=0.75;
            boolean departed=!pivot&&AutonavPolylineFix.horizontal(boat,holdOrigin)>=releaseDistance;
            if(reached||departed||AutonavPolylineFix.horizontal(boat,heldAim)>32) {
                heldAim=null;holdOrigin=null;
            } else if(clear.test(boat,heldAim)) {
                selected=new WaterPolylineFollower.Aim(heldAim,false,fresh.cornerDistance(),fresh.cornerDegrees());
            } else {
                heldAim=null;holdOrigin=null;pivot=false;pivotCommands=0;
            }
        }
        lastAim=selected.point();
        double distance=AutonavPolylineFix.horizontal(boat,lastAim);
        aimDistance=distance;
        tightCorner=selected.cornerDegrees()>25 && selected.cornerDistance()<4;
        desiredYaw=distance<1e-6?yaw:(float)Math.toDegrees(Math.atan2(-(lastAim.x-boat.x),lastAim.z-boat.z));
        error=wrap(desiredYaw-yaw);
        if(pivot) {
            if(Math.abs(error)<=exitDegrees) {
                pivot=false;pivotCommands=0;reason="aligned_release";
                holdOrigin=boat;releaseDistance=Math.min(2,Math.max(0.5,distance*0.5));
            } else if(pivotCommands>=MAX_PIVOT_COMMANDS) {
                timedOut=true;reason="turn_timeout";
                return new WaterPolylineFollower.Aim(boat,true,selected.cornerDistance(),selected.cornerDegrees());
            } else reason="aligning";
        } else if(Math.abs(error)>(tightCorner?40f:ENTER_DEGREES) && distance>=0.04) {
            pivot=true;pivotCommands=0;heldAim=lastAim;holdOrigin=boat;exitDegrees=tightCorner?8f:EXIT_DEGREES;
            releaseDistance=Math.min(2,Math.max(0.5,distance*0.5));reason="aligning";
        } else reason=heldAim==null?"tracking":"release_leg";
        return selected;
    }
    private void drop(){pivot=false;timedOut=false;pivotCommands=0;heldAim=null;holdOrigin=null;}
    public boolean pivoting(){return pivot;}
    public boolean timedOut(){return timedOut;}
    public static void commandIssued(AutonavSteeringState state){if(state!=null&&state.pivot)state.pivotCommands++;}
    public static float threshold(float originalAbsError,AutonavSteeringState state) {
        if(state==null)return originalAbsError;
        return state.pivot?96f:Math.min(originalAbsError,95f);
    }
    public static double speedCap(double originalCap,AutonavSteeringState state) {
        if(state==null)return originalCap;
        if(state.pivot||state.timedOut)return 0;
        double cap=Math.min(originalCap,Math.max(.005,state.aimDistance*.30));
        if(state.tightCorner && Math.abs(state.error)>12)cap=Math.min(cap,.035);
        return cap;
    }
    public String describe(float currentYaw,int stalled,double cap) {
        return String.format(Locale.ROOT,"reason=%s yaw=%.2f desired=%.2f error=%.2f pivot=%s aim=%s noProgress=%d cap=%.4f pivotCommands=%d tightCorner=%s aimDistance=%.3f",
                reason,currentYaw,desiredYaw,error,pivot,lastAim,stalled,cap,pivotCommands,tightCorner,aimDistance);
    }
    static float wrap(float a){a%=360;if(a>=180)a-=360;if(a< -180)a+=360;return a;}
}
