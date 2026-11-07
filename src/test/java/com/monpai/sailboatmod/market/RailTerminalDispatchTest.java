package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RailTerminalDispatchTest {
    @Test void railSurvivesWireAndSavedNameParsingWithoutFallingBackToAuto() {
        assertEquals("RAIL", TransportTerminalKind.fromName(" rail ").name());
        assertEquals(TransportTerminalKind.AUTO, TransportTerminalKind.fromName("unknown_provider"));
    }

    @Test void automaticSelectionCanChooseReadyRailAndKeepsUnavailableRailOutOfFirstPlace() {
        var railKind = TransportTerminalKind.fromName("RAIL");
        assertNotEquals(TransportTerminalKind.AUTO, railKind);
        var rail = new MarketDispatchPlanner.DispatchChoice("rail", railKind, true, 30, 200, 5, 0);
        var road = new MarketDispatchPlanner.DispatchChoice("road", TransportTerminalKind.POST_STATION, true, 80, 200, 5, 1);
        var unavailable = new MarketDispatchPlanner.DispatchChoice("busy-rail", railKind, false, 10, 100, 1, 2);
        assertEquals(rail, MarketDispatchPlanner.bestChoice(List.of(road, unavailable, rail)).orElseThrow());
    }
}
