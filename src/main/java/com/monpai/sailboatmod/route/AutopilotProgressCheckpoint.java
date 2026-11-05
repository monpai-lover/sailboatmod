package com.monpai.sailboatmod.route;
/** Keep a distance checkpoint until meaningful progress is accumulated.
 * Avoid treating slow intentional turns as repeated stalls.
 */
public final class AutopilotProgressCheckpoint {
    private AutopilotProgressCheckpoint() {}
    public static double next(double previous,double current,int stalledTicks) {
        return stalledTicks==0 || !Double.isFinite(previous) ? current : previous;
    }
}
