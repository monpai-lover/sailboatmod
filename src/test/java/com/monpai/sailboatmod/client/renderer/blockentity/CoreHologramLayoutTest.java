package com.monpai.sailboatmod.client.renderer.blockentity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoreHologramLayoutTest {
    @Test
    void nationLayoutOmitsBlankPlaceholderAndAddsWarLineOnlyWhenActive() {
        List<CoreHologramLayout.HologramLine> idle = CoreHologramLayout.nationLines("Nation Core", "-", 0xAA3333, false, "Idle");
        List<CoreHologramLayout.HologramLine> active = CoreHologramLayout.nationLines("Nation Core", "Red Harbor", 0xAA3333, true, "War: Contested");

        assertEquals(List.of("Nation Core"), idle.stream().map(CoreHologramLayout.HologramLine::text).toList());
        assertEquals(List.of("Nation Core", "Red Harbor", "War: Contested"),
                active.stream().map(CoreHologramLayout.HologramLine::text).toList());
    }
}
