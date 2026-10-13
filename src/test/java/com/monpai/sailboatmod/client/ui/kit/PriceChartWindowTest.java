package com.monpai.sailboatmod.client.ui.kit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PriceChartWindowTest {
    private static List<Integer> series(int count) {
        return IntStream.range(0, count).boxed().toList();
    }

    @Test
    void showsTheNewestBucketsOldestFirst() {
        assertEquals(List.of(6, 7, 8, 9), ChartWindow.newest(series(10), 4));
    }

    @Test
    void shortOrEmptySeriesIsShownWhole() {
        assertEquals(3, ChartWindow.newest(series(3), 8).size());
        assertEquals(0, ChartWindow.newest(List.of(), 8).size());
        assertEquals(0, ChartWindow.newest(series(3), 0).size());
    }
}
