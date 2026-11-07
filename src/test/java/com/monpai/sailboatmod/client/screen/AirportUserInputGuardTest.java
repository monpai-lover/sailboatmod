package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.network.packet.airport.AirportActionPacket;
import com.monpai.sailboatmod.client.airport.AirportPlannerModel;
import com.monpai.sailboatmod.transport.air.airport.AirportGroundLayout;
import com.monpai.sailboatmod.transport.air.airport.AirportScreenData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AirportUserInputGuardTest {
    @Test void overlongPastedRenameValidateAndDeleteAreRejectedWithoutEscapingButtonCallback() throws Exception {
        var screen = screen();
        Method send = AirportScreen.class.getDeclaredMethod("send", AirportActionPacket.Action.class, String.class);
        send.setAccessible(true);
        for (var action : List.of(AirportActionPacket.Action.RENAME, AirportActionPacket.Action.VALIDATE_RUNWAY,
                AirportActionPacket.Action.REMOVE_RUNWAY, AirportActionPacket.Action.REMOVE_STAND,
                AirportActionPacket.Action.REMOVE_TAXI_ROUTE)) {
            assertDoesNotThrow(() -> send.invoke(screen, action, "a".repeat(65)), action.name());
        }
        assertEquals("Airport", screen.data().name());
    }

    @Test void worldPickAtPointLimitPreservesTheDraftWithoutEscapingInputEvent() throws Exception {
        var screen = screen();
        assertTrue(screen.planner().startNew(AirportPlannerModel.Tool.OUTBOUND));
        screen.planner().updateProperties(screen.planner().draft().orElseThrow()
                .withPoints(Collections.nCopies(AirportActionPacket.MAX_POINTS, Vec3.ZERO)));
        List<Vec3> points = screen.planner().draft().orElseThrow().points();
        Method insert = AirportScreen.class.getDeclaredMethod("insertPoint", Vec3.class);
        insert.setAccessible(true);
        Consumer<Vec3> selection = point -> {
            try { insert.invoke(screen, point); }
            catch (ReflectiveOperationException exception) {
                if (exception.getCause() instanceof IllegalArgumentException invalid) throw invalid;
                throw new AssertionError(exception);
            }
        };
        Method guardedPick = AirportScreen.class.getDeclaredMethod("guardedPick", Consumer.class);
        guardedPick.setAccessible(true);
        @SuppressWarnings("unchecked") Consumer<Vec3> callback = (Consumer<Vec3>) guardedPick.invoke(screen, selection);

        assertDoesNotThrow(() -> callback.accept(new Vec3(1, 64, 1)));
        assertEquals(AirportActionPacket.MAX_POINTS, screen.planner().draft().orElseThrow().points().size());
        assertEquals(points, screen.planner().draft().orElseThrow().points());
        assertTrue(points.stream().allMatch(Vec3.ZERO::equals));
    }

    private static AirportScreen screen() {
        return new AirportScreen(new AirportScreenData(net.minecraft.world.level.Level.OVERWORLD.location(),BlockPos.ZERO,"Airport","Town","Owner",true,0,
                List.of(), AirportGroundLayout.empty(), List.of(), ""));
    }
}
