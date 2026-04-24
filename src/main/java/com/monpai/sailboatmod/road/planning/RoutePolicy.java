package com.monpai.sailboatmod.road.planning;

import com.monpai.sailboatmod.road.model.BridgeSpan;
import com.monpai.sailboatmod.road.model.BridgeSpanKind;

public enum RoutePolicy {
    DETOUR,
    BRIDGE;

    public boolean allowsSpan(BridgeSpan span) {
        if (span == null) {
            return true;
        }
        return switch (this) {
            case DETOUR -> span.kind() == BridgeSpanKind.SHORT_SPAN_FLAT && span.length() <= 12;
            case BRIDGE -> true;
        };
    }
}