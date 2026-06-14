package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.market.MarketOverviewData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketScreenDispatchCandidateTest {
    @Test
    void dispatchCandidateSubtitleShowsCarrierNameInCandidateList() {
        assertEquals(
                "Oak Carriage | Alpha -> Cedar",
                MarketScreen.dispatchCandidateSubtitleForTest("Oak Carriage", "Alpha -> Cedar", "Select carrier")
        );
    }

    @Test
    void dispatchCandidateSubtitleFallsBackWhenCarrierAndRouteAreBlank() {
        assertEquals(
                "Select carrier",
                MarketScreen.dispatchCandidateSubtitleForTest("", "", "Select carrier")
        );
    }

    @Test
    void dispatchPanelShowsAvailableCarriersWhenNoOrderIsSelected() {
        MarketOverviewData.DispatchOption carrier = dispatchOption("PORT", "Crimea Port", "Sailboat A");

        assertEquals(
                List.of(carrier),
                MarketScreen.dispatchOptionsForPanelForTest(null, List.of(carrier))
        );
    }

    @Test
    void dispatchPanelPrefersSelectedOrderCandidatesWhenOrderIsSelected() {
        MarketOverviewData.DispatchOption orderCarrier = dispatchOption("POST_STATION", "Crimea Station", "Oak Carriage");
        MarketOverviewData.DispatchOption availableCarrier = dispatchOption("PORT", "Crimea Port", "Sailboat A");
        MarketOverviewData.OrderEntry order = new MarketOverviewData.OrderEntry(
                "order-1",
                "Wheat x4",
                "Crimea",
                "Kaffa",
                4,
                "WAITING",
                List.of(orderCarrier)
        );

        assertEquals(
                List.of(orderCarrier),
                MarketScreen.dispatchOptionsForPanelForTest(order, List.of(availableCarrier))
        );
    }

    private static MarketOverviewData.DispatchOption dispatchOption(String kind, String terminal, String carrier) {
        return new MarketOverviewData.DispatchOption(
                kind,
                terminal,
                carrier,
                "",
                terminal,
                "",
                0,
                0,
                true,
                "Ready",
                terminal
        );
    }
}
