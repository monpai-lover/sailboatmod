package com.monpai.sailboatmod.compat.aeronautics;

import com.monpai.sailboatmod.transport.physical.NativeControlResult;
import java.util.Map;

/** Bounded cached control ownership observation; no optional class is resolved by name. */
final class RailAircraftFlightControlGuard {
    static final String COMPUTER = "ace.flight.block.FlightControlComputerBlockEntity";
    static final String BROKER = "ace.flight.navigation.NavigationLeaseBroker";
    static final String FRAME = "ace.flight.navigation.NavigationEffectiveCommandFrame";
    static final Map<String, String> PINS = Map.of(
            COMPUTER, "44487adde64fd7fa88b091fad99be96cfc0e26ec6d179b24334e3d186d9f5cd1",
            BROKER, "332ea445cd4ce6c7d62b83e6aef0311d8ace7b8901a8484a18541332eda8aa8d",
            FRAME, "bf83d9be726ac645b2d30c5787ee6267664474990d86a27a65cb14d4eb9cf15a");
    record Readback(boolean working, boolean defaultEnabled, int activeBundles, int commands) { }
    @FunctionalInterface interface Membership { boolean sameBody(Object actor) throws ReflectiveOperationException; }
    @FunctionalInterface interface Observer { Readback read(Object actor) throws ReflectiveOperationException; }
    private RailAircraftFlightControlGuard() { }

    static NativeControlResult inspect(Iterable<?> actors, int limit, Membership membership, Observer observer) {
        if (actors == null || membership == null || observer == null || limit < 1)
            return NativeControlResult.failure("flight_control_state_unavailable");
        try {
            int count = 0;
            for (Object actor : actors) {
                if (++count > limit) return NativeControlResult.failure("flight_control_actor_limit");
                Readback state = observer.read(actor);
                if (state == null) continue;
                if (!membership.sameBody(actor) || state.activeBundles() < 0 || state.commands() < 0)
                    return NativeControlResult.failure("flight_control_state_unavailable");
                if (state.working() || state.defaultEnabled() || state.activeBundles() > 0 || state.commands() > 0)
                    return NativeControlResult.failure("flight_control_conflict");
            }
            return NativeControlResult.applied();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return NativeControlResult.failure("flight_control_state_unavailable");
        }
    }
}
