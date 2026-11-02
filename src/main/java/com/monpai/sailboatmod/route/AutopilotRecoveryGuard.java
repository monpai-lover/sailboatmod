package com.monpai.sailboatmod.route;

/** Rescue completion is NOT movement. Only real horizontal movement replenishes retries.
 * Game ticks, not wall time, mean a paused single-player world cannot consume this budget.
 */
public final class AutopilotRecoveryGuard {
    public enum Decision { ALLOW, COOLDOWN, EXHAUSTED }
    private double x,z; private boolean anchored; private int attempts; private long nextTick;
    public void reset(){anchored=false;attempts=0;nextTick=0;}
    public void moved(double nx,double nz) {
        if(anchored&&Math.hypot(nx-x,nz-z)>=4)reset();
    }
    public Decision request(long tick,double nx,double nz) {
        moved(nx,nz);
        if(attempts>=3)return Decision.EXHAUSTED;
        if(tick<nextTick)return Decision.COOLDOWN;
        if(!anchored){x=nx;z=nz;anchored=true;}
        attempts++;nextTick=tick+100L*attempts;return Decision.ALLOW;
    }
    public int attempts(){return attempts;}
}
