package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.market.TransportTerminalKind;
import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.transport.air.airport.AirportFacility;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarketAirDispatchPolicyTest {
    @Test
    void terminalSelectionKeepsAirportsOutOfSeaAndLandCandidates() throws Exception {
        var airport = airport(AirportFacility.Type.RUNWAY);
        var port = allocate(DockBlockEntity.class);
        var station = allocate(PostStationBlockEntity.class);
        assertTrue(MarketBlockEntity.matchesTerminalKind(airport, TransportTerminalKind.AIRPORT));
        assertFalse(MarketBlockEntity.matchesTerminalKind(airport, TransportTerminalKind.PORT));
        assertFalse(MarketBlockEntity.matchesTerminalKind(airport, TransportTerminalKind.POST_STATION));
        assertFalse(MarketBlockEntity.matchesTerminalKind(port, TransportTerminalKind.AIRPORT));
        assertFalse(MarketBlockEntity.matchesTerminalKind(station, TransportTerminalKind.AIRPORT));
        assertTrue(MarketBlockEntity.matchesTerminalKind(port, TransportTerminalKind.PORT));
        assertTrue(MarketBlockEntity.matchesTerminalKind(station, TransportTerminalKind.POST_STATION));
    }

    @Test
    void fixedWingNeedsRunwayWhileRotorAndAirshipUseCompatibleFacilities() throws Exception {
        var fixed = carrier(LogisticsCarrier.Kind.AIR, "AIRPLANE");
        var rotor = carrier(LogisticsCarrier.Kind.AIR, "HELICOPTER");
        var airship = carrier(LogisticsCarrier.Kind.AIR, "AIRSHIP");
        var runway = airport(AirportFacility.Type.RUNWAY);
        var pad = airport(AirportFacility.Type.HELIPAD);
        var mast = airport(AirportFacility.Type.MOORING);
        assertTrue(MarketBlockEntity.carrierCanUseTarget(fixed, runway));
        assertFalse(MarketBlockEntity.carrierCanUseTarget(fixed, pad));
        assertTrue(MarketBlockEntity.carrierCanUseTarget(rotor, pad));
        assertTrue(MarketBlockEntity.carrierCanUseTarget(rotor, runway));
        assertTrue(MarketBlockEntity.carrierCanUseTarget(airship, mast));
        assertFalse(MarketBlockEntity.carrierCanUseTarget(airship, runway));
        assertFalse(MarketBlockEntity.carrierCanUseTarget(fixed, allocate(DockBlockEntity.class)));
        assertFalse(MarketBlockEntity.carrierCanUseTarget(carrier(LogisticsCarrier.Kind.WATER, "PORT"), runway));
    }

    @Test
    void automaticPickupChoosesTerminalByCapabilityInsteadOfEntityClass() {
        assertEquals(TransportTerminalKind.AIRPORT, MarketBlockEntity.terminalKindForCarrier(carrier(LogisticsCarrier.Kind.AIR, "HELICOPTER")));
        assertEquals(TransportTerminalKind.POST_STATION, MarketBlockEntity.terminalKindForCarrier(carrier(LogisticsCarrier.Kind.LAND, "LAND")));
        assertEquals(TransportTerminalKind.PORT, MarketBlockEntity.terminalKindForCarrier(carrier(LogisticsCarrier.Kind.WATER, "PORT")));
    }

    private static LogisticsCarrier carrier(LogisticsCarrier.Kind kind, String mode) {
        return (LogisticsCarrier) Proxy.newProxyInstance(LogisticsCarrier.class.getClassLoader(), new Class<?>[]{LogisticsCarrier.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "transportKind" -> kind;
                    case "transportMode" -> mode;
                    default -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : null;
                });
    }

    private static AirportBlockEntity airport(AirportFacility.Type type) throws Exception {
        var airport = allocate(AirportBlockEntity.class);
        var start = new BlockPos(10, 72, 10);
        var facility = new AirportFacility("facility", type, start, type == AirportFacility.Type.RUNWAY ? start.offset(120, 0, 0) : start, 12, 24);
        Field field = AirportBlockEntity.class.getDeclaredField("facilities");
        field.setAccessible(true);
        var facilities = new LinkedHashMap<String, AirportFacility>();
        facilities.put(facility.id(), facility);
        field.set(airport, facilities);
        return airport;
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (T) ((Unsafe) field.get(null)).allocateInstance(type);
    }
}
