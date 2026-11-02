package com.monpai.sailboatmod.route;

/** Shared movement policy. An intermediate waypoint is a THROUGH point, never a berth. */
public final class AutopilotMotionPolicy {
    private AutopilotMotionPolicy() { }
    public static boolean shouldStopForArrival(boolean finalTarget,double distance,double stopRadius) {
        return finalTarget && distance < stopRadius;
    }
    /** Route Y denotes the water/air interface. Floating entity Y oscillates below it. */
    public static int waterBlockY(double routeInterfaceY) {
        return (int)Math.floor(routeInterfaceY + 1e-6) - 1;
    }
}
