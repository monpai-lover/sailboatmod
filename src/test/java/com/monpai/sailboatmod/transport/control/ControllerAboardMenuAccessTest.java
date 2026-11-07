package com.monpai.sailboatmod.transport.control;

import com.monpai.sailboatmod.transport.LogisticsCarrier;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ControllerAboardMenuAccessTest {
    private static final UUID OWNER = new UUID(1, 2), BODY = new UUID(3, 4);

    @Test void ownerInTheSameRegisteredBodyCanUseALargeCabinBeyondEightBlocks() throws Exception {
        var registration = registered();
        assertTrue(canManage(registration, OWNER, false, 900, BODY));
        assertTrue(canManage(registration, new UUID(8, 9), true, 900, BODY));
    }

    @Test void nearbyOtherBodiesAndPassengersWithoutManagementPermissionCannotOpenTheMenu() throws Exception {
        var registration = registered();
        assertFalse(canManage(registration, OWNER, false, 900, new UUID(5, 6)));
        assertFalse(canManage(registration, OWNER, false, 900, null));
        assertFalse(canManage(registration, new UUID(8, 9), false, 1, BODY));
        assertFalse(canManage(registration, new UUID(8, 9), false, 900, BODY));
        assertFalse(canManage(registration, OWNER, false, Double.NaN, BODY));
        assertFalse(canManage(registration, OWNER, false, -1, BODY));
    }

    @Test void unregisteringOrLeavingTheBodyRevokesTheLongDistancePermission() throws Exception {
        var registration = registered();
        assertTrue(canManage(registration, OWNER, false, 900, BODY));
        assertFalse(canManage(registration, OWNER, false, 900, null));
        registration.unregister();
        assertFalse(canManage(registration, OWNER, false, 900, BODY));
        assertTrue(canManage(registration, OWNER, false, 64, null), "Normal nearby block management remains available");
    }

    private static ControllerRegistration registered() {
        var registration = new ControllerRegistration(new UUID(7, 8), OWNER.toString(), LogisticsCarrier.Kind.AIR);
        assertTrue(registration.register(BODY, 10, true));
        return registration;
    }

    private static boolean canManage(ControllerRegistration state, UUID actor, boolean admin,
            double distanceSquared, UUID actualPassengerBody) throws Exception {
        // Reflection keeps this regression executable before the new overload exists.
        var method = ControllerRegistration.class.getMethod("canManage", UUID.class, boolean.class, double.class, UUID.class);
        return (boolean) method.invoke(state, actor, admin, distanceSquared, actualPassengerBody);
    }
}
