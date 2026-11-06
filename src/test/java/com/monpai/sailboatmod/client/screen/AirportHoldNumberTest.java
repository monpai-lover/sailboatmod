package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.client.airport.AirportPlannerModel;
import com.monpai.sailboatmod.transport.air.airport.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AirportHoldNumberTest {
    @Test void theVisibleMapNumberTwoRoundTripsToNativeIndexOne() throws Exception {
        var screen = outbound(); screen.planner().setHoldPoint(1); synchronize(screen);
        assertEquals("2", raw(screen).get("hold"));
        raw(screen).put("hold", "2"); assertTrue(apply(screen));
        assertEquals(1, screen.planner().draft().orElseThrow().holdPoint());
        assertEquals(1, screen.planner().savePacket().holdPoint());
    }
    @Test void settingTheSelectedFirstPointDisplaysMapNumberOne() throws Exception {
        var screen = outbound(); synchronize(screen);
        var set = AirportScreen.class.getDeclaredMethod("setSelectedHoldPoint"); set.setAccessible(true); set.invoke(screen);
        assertEquals(0, screen.planner().draft().orElseThrow().holdPoint());
        assertEquals("1", raw(screen).get("hold"));
    }
    @Test void zeroIsRetainedAsInvalidInputAndCannotSilentlySelectTheFirstPoint() throws Exception {
        var screen = outbound(); screen.planner().setHoldPoint(1); synchronize(screen);
        raw(screen).put("hold", "0"); assertFalse(apply(screen));
        assertEquals("0", raw(screen).get("hold"));
        assertEquals(1, screen.planner().draft().orElseThrow().holdPoint());
        assertFalse(screen.planner().canSave());
        assertThrows(IllegalArgumentException.class, () -> screen.planner().savePacket());
    }
    @Test void minusOneStillMeansNoWaitingPoint() throws Exception {
        var screen = outbound(); synchronize(screen);
        raw(screen).put("hold", "-1"); assertTrue(apply(screen));
        assertEquals(-1, screen.planner().draft().orElseThrow().holdPoint());
        synchronize(screen); assertEquals("-1", raw(screen).get("hold"));
    }
    private static AirportScreen outbound() {
        var runway = new AirportFacility("r", AirportFacility.Type.RUNWAY, new BlockPos(-32,81,5), new BlockPos(96,81,5), 12, 16);
        var stand = new AirportParkingStand("s", new Vec3(-60.5,81,5.5),90,24,20,12,true);
        var data = new AirportScreenData(Level.OVERWORLD.location(), new BlockPos(20,81,30), "Airport", "Town", "Owner", true,
                0, List.of(runway), new AirportGroundLayout(0,List.of(stand),List.of()),List.of(),"");
        var screen = new AirportScreen(data, packet -> {});
        assertTrue(screen.planner().startNew(AirportPlannerModel.Tool.OUTBOUND));
        screen.planner().click(stand.position()); screen.planner().click(new Vec3(-40.5,81,5.5)); screen.planner().click(new Vec3(-31.5,81,5.5));
        return screen;
    }
    private static void synchronize(AirportScreen screen) throws Exception {
        var method = AirportScreen.class.getDeclaredMethod("synchronizePrecision"); method.setAccessible(true); method.invoke(screen);
    }
    private static boolean apply(AirportScreen screen) throws Exception {
        var method = AirportScreen.class.getDeclaredMethod("applyPrecision", boolean.class); method.setAccessible(true); return (boolean) method.invoke(screen, false);
    }
    @SuppressWarnings("unchecked") private static Map<String,String> raw(AirportScreen screen) throws Exception {
        var field = AirportScreen.class.getDeclaredField("precision"); field.setAccessible(true); return (Map<String,String>) field.get(screen);
    }
}
