package com.monpai.sailboatmod.transport.air.fuel;

import com.monpai.sailboatmod.transport.air.rail.RailAircraftPath;

/** Pure nominal movement duration. Arrival dwell, resource waits and reserve belong to the caller's budget. */
public final class RailFlightDuration {
    private static final long MAX_STEPS=1_000_000;
    private RailFlightDuration() { }

    /** Mirrors a fresh, unblocked mission's discrete proposal without constructing or mutating a mission. */
    public static long estimateTicks(RailAircraftPath path,double fromDistance) {
        if(path==null||!Double.isFinite(fromDistance)||fromDistance<0||!Double.isFinite(path.length())||fromDistance>path.length())
            throw new IllegalArgumentException("fuel_duration_invalid");
        double distance=fromDistance;long ticks=0;
        while(distance<path.length()) {
            if(ticks>=MAX_STEPS)throw new IllegalArgumentException("fuel_duration_step_limit");
            double speed=path.speedForAdvance(distance),boundary=path.nextBoundary(distance);
            if(!Double.isFinite(speed)||speed<=0||!Double.isFinite(boundary)||boundary<=distance||boundary>path.length())
                throw new IllegalArgumentException("fuel_duration_no_progress");
            double next=Math.min(boundary,Math.min(path.length(),distance+speed/20));
            if(!Double.isFinite(next)||next<=distance)throw new IllegalArgumentException("fuel_duration_no_progress");
            distance=next;ticks++;
        }
        return ticks;
    }
}
