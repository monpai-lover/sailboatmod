package com.monpai.sailboatmod.market.web.map;

/** Cooperative suspension: incomplete prediction images must not be published or treated as failed terrain. */
public final class MarketWebMapWorkSuspendedException extends RuntimeException {
    public MarketWebMapWorkSuspendedException() {
        super("Map updates are paused", null, false, false);
    }
}
