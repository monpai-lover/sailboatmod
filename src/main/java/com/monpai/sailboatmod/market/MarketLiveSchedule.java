package com.monpai.sailboatmod.market;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Decides which open market screen gets a fresh overview next. Pure bookkeeping, no Minecraft types, so the rules
 * are unit-tested: building an overview runs dozens of SQLite queries, so at most one viewer is refreshed per call
 * (one per server tick), the same viewer at most every {@code minIntervalTicks}, and only when the market revision
 * moved past what that viewer last received. Screens that just opened are assumed current, because opening one
 * already sends an overview.
 *
 * @param <K> viewer identity (a player UUID on the server)
 */
public final class MarketLiveSchedule<K> {
    private record State(Object market, long sentRevision, long sentTick) {
    }

    private final Map<K, State> states = new HashMap<>();
    private final int minIntervalTicks;

    public MarketLiveSchedule(int minIntervalTicks) {
        this.minIntervalTicks = Math.max(1, minIntervalTicks);
    }

    /** {@code viewer} received an overview of {@code market} that includes every change up to {@code revision}. */
    public void sent(K viewer, Object market, long revision, long tick) {
        states.put(viewer, new State(market, revision, tick));
    }

    /**
     * @param openMarkets every viewer with a market screen open, mapped to the market it shows; anyone missing is
     *                    forgotten
     * @return the viewer to refresh now, or {@code null}; the caller sends the overview and reports it via
     * {@link #sent}
     */
    public K next(Map<K, ?> openMarkets, long revision, long tick) {
        states.keySet().retainAll(openMarkets.keySet());
        K due = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<K, ?> entry : openMarkets.entrySet()) {
            K viewer = entry.getKey();
            State state = states.get(viewer);
            if (state == null || !Objects.equals(state.market(), entry.getValue())) {
                states.put(viewer, new State(entry.getValue(), revision, tick));
                continue;
            }
            if (state.sentRevision() >= revision || tick - state.sentTick() < minIntervalTicks) {
                continue;
            }
            if (state.sentTick() < oldest) {
                oldest = state.sentTick();
                due = viewer;
            }
        }
        return due;
    }

    public void clear() {
        states.clear();
    }

    int trackedViewers() {
        return states.size();
    }
}
