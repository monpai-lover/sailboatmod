package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketDispatchPlannerTest {
    @Test
    void autoDispatchChoosesAvailablePostStationWhenPortHasNoCarrier() {
        MarketDispatchPlanner.DispatchChoice port = new MarketDispatchPlanner.DispatchChoice(
                "port",
                TransportTerminalKind.PORT,
                false,
                120,
                800,
                25.0D,
                0
        );
        MarketDispatchPlanner.DispatchChoice postStation = new MarketDispatchPlanner.DispatchChoice(
                "post",
                TransportTerminalKind.POST_STATION,
                true,
                240,
                1000,
                30.0D,
                1
        );

        assertEquals(postStation, MarketDispatchPlanner.bestChoice(List.of(port, postStation)).orElseThrow());
    }

    @Test
    void autoDispatchChoosesFastestAvailableTerminalInsteadOfAlwaysPreferringPort() {
        MarketDispatchPlanner.DispatchChoice slowPort = new MarketDispatchPlanner.DispatchChoice(
                "port",
                TransportTerminalKind.PORT,
                true,
                300,
                1600,
                20.0D,
                0
        );
        MarketDispatchPlanner.DispatchChoice fastPostStation = new MarketDispatchPlanner.DispatchChoice(
                "post",
                TransportTerminalKind.POST_STATION,
                true,
                180,
                900,
                40.0D,
                1
        );

        assertEquals(fastPostStation, MarketDispatchPlanner.bestChoice(List.of(slowPort, fastPostStation)).orElseThrow());
    }

    @Test
    void autoDispatchUsesTerminalDistanceAsTieBreakerAfterEtaAndRouteDistance() {
        MarketDispatchPlanner.DispatchChoice farTerminal = new MarketDispatchPlanner.DispatchChoice(
                "far",
                TransportTerminalKind.POST_STATION,
                true,
                180,
                900,
                100.0D,
                0
        );
        MarketDispatchPlanner.DispatchChoice nearTerminal = new MarketDispatchPlanner.DispatchChoice(
                "near",
                TransportTerminalKind.PORT,
                true,
                180,
                900,
                12.0D,
                1
        );

        assertEquals(nearTerminal, MarketDispatchPlanner.bestChoice(List.of(farTerminal, nearTerminal)).orElseThrow());
    }

    @Test
    void rankedChoicesKeepFallbackCandidatesAfterBestChoice() {
        MarketDispatchPlanner.DispatchChoice first = new MarketDispatchPlanner.DispatchChoice(
                "first",
                TransportTerminalKind.POST_STATION,
                true,
                100,
                500,
                20.0D,
                0
        );
        MarketDispatchPlanner.DispatchChoice second = new MarketDispatchPlanner.DispatchChoice(
                "second",
                TransportTerminalKind.PORT,
                true,
                120,
                450,
                10.0D,
                1
        );
        MarketDispatchPlanner.DispatchChoice unavailable = new MarketDispatchPlanner.DispatchChoice(
                "unavailable",
                TransportTerminalKind.PORT,
                false,
                60,
                100,
                5.0D,
                2
        );

        assertEquals(List.of(first, second, unavailable),
                MarketDispatchPlanner.rankedChoices(List.of(unavailable, second, first)));
    }
}
