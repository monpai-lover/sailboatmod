package com.monpai.sailboatmod.transport.control;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegisteredVehicleMenuLookupTest {
    private static final String WORLD = "minecraft:overworld";
    private static final UUID BODY = new UUID(1, 2), REGISTRATION = new UUID(3, 4);

    @Test void lookupUsesThePhysicalClaimAndCannotReturnAnUnclaimedOrForeignWorldEntry() throws Exception {
        var data = new RegisteredTransportSavedData();
        var entry = entry(BODY);
        data.put(entry);
        assertNull(find(data, WORLD, BODY), "An entry alone cannot claim an arbitrary native body");
        assertTrue(data.claim(WORLD, BODY, REGISTRATION));
        assertEquals(entry, find(data, WORLD, BODY));
        assertNull(find(data, "minecraft:the_nether", BODY));
        assertNull(find(data, WORLD, new UUID(8, 9)));
        data.forget(REGISTRATION);
        assertNull(find(data, WORLD, BODY));
    }

    @Test void reassemblyCannotMakeTheOldPhysicalBodyOpenTheReboundController() throws Exception {
        var data = new RegisteredTransportSavedData();
        assertTrue(data.claim(WORLD, BODY, REGISTRATION)); data.put(entry(BODY));
        UUID replacement = new UUID(5, 6);
        assertTrue(data.claim(WORLD, replacement, REGISTRATION)); data.put(entry(replacement));
        assertNull(find(data, WORLD, BODY));
        assertEquals(entry(replacement), find(data, WORLD, replacement));
    }

    private static RegisteredTransportSavedData.Entry entry(UUID body) {
        return new RegisteredTransportSavedData.Entry(REGISTRATION, WORLD, new BlockPos(10, 64, 20), body, "owner", false);
    }
    private static RegisteredTransportSavedData.Entry find(RegisteredTransportSavedData data, String world, UUID body) throws Exception {
        var method = RegisteredTransportSavedData.class.getMethod("controllerForPhysical", String.class, UUID.class);
        return (RegisteredTransportSavedData.Entry) method.invoke(data, world, body);
    }
}
