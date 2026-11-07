package com.monpai.sailboatmod.transport.control;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VehicleRegistrationSetupTest {
    @Test
    void namedRequestKeepsTheWholeNameAndLegacyRequestsRemainValid() throws Exception {
        var named = parse("FIXED_WING:  西海:一號  ");
        assertEquals(VehicleRegistrationMode.FIXED_WING, value(named, "mode"));
        assertEquals("西海:一號", value(named, "name"));
        assertNull(value(parse("LAND"), "name"));
        assertEquals(VehicleRegistrationMode.LAND, value(parse("LAND"), "mode"));
        assertNull(value(parse(""), "mode"));
    }

    @Test
    void blankLongOrControlCharacterNamesAreRejectedBeforeRegistration() {
        for (String bad : List.of("WATER:  ", "WATER:" + "a".repeat(65), "WATER:bad\nname")) {
            assertInstanceOf(IllegalArgumentException.class, assertThrows(InvocationTargetException.class,
                    () -> parse(bad)).getCause());
        }
    }

    @Test
    void onlyOneInRangeNativeComponentCanSupplyAnAutomaticBinding() throws Exception {
        BlockPos core = new BlockPos(1000, 65, -2000);
        BlockPos lever = core.offset(12, -1, 16);
        var method = setupClass().getMethod("uniqueOffset", BlockPos.class, List.class);
        assertEquals(Optional.of(new BlockPos(12, -1, 16)), method.invoke(null, core, List.of(lever)));
        assertEquals(Optional.empty(), method.invoke(null, core, List.of(lever, core.east(65))));
        assertEquals(Optional.empty(), method.invoke(null, core, List.of(core.east(65))));
        assertEquals(Optional.empty(), method.invoke(null, core, List.of()));
    }

    @Test
    void missingPlotChunkPreventsAnyAutomaticChoice() throws Exception {
        var type = Class.forName("com.monpai.sailboatmod.compat.aeronautics.SablePlotMembers");
        var complete = type.getDeclaredMethod("coversBounds", int.class, int.class, int.class, int.class, Set.class);
        complete.setAccessible(true);
        Set<Long> partial = Set.of(ChunkPos.asLong(-1, 0), ChunkPos.asLong(0, 0));
        assertEquals(true, complete.invoke(null, -16, 15, 0, 15, partial));
        assertEquals(false, complete.invoke(null, -16, 15, 0, 16, partial));
        assertEquals(false, complete.invoke(null, 15, -16, 0, 15, partial));
        assertEquals(false, complete.invoke(null, 0, 4096, 0, 4096, partial));
    }

    private static Object parse(String value) throws ReflectiveOperationException {
        return setupClass().getMethod("parse", String.class).invoke(null, value);
    }

    private static Object value(Object request, String member) throws ReflectiveOperationException {
        return request.getClass().getMethod(member).invoke(request);
    }

    private static Class<?> setupClass() throws ClassNotFoundException {
        return Class.forName("com.monpai.sailboatmod.transport.control.VehicleRegistrationSetup");
    }
}
