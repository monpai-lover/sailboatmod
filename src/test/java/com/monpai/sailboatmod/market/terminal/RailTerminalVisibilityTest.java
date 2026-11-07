package com.monpai.sailboatmod.market.terminal;

import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RailTerminalVisibilityTest {
    @Test void persistedRailConnectionRemainsVisibleWithoutLiveWorldOrAvailableTrain() {
        var network = new TerminalNetworkSavedData();
        network.putEntry(entry(1, TerminalNetworkSavedData.Kind.RAIL, "Rail A", "a", List.of(), Set.of("b")));
        network.putEntry(entry(2, TerminalNetworkSavedData.Kind.RAIL, "Rail B", "b", List.of(), Set.of()));
        network.putEntry(entry(3, TerminalNetworkSavedData.Kind.POST_STATION, "Road B", "b", List.of(), Set.of("c")));
        var restored = TerminalNetworkSavedData.load(network.save(new CompoundTag()));
        assertEquals(TerminalNetworkSavedData.Kind.RAIL, restored.getEntry("minecraft:overworld", 1).kind());
        assertTrue(TerminalVisibility.isVisible(restored, "a", "c"));
        assertTrue(TerminalVisibility.isVisible(restored, "c", "a"));
        assertFalse(TerminalVisibility.isVisible(restored, "a", "isolated"));
    }
    @Test void railNamesCannotAccidentallyBecomeWaterRouteDestinations() {
        var network = new TerminalNetworkSavedData();
        network.putEntry(entry(1, TerminalNetworkSavedData.Kind.PORT, "Port A", "a", List.of("Shared"), Set.of()));
        network.putEntry(entry(2, TerminalNetworkSavedData.Kind.RAIL, "Shared", "rail-only", List.of(), Set.of()));
        assertFalse(TerminalVisibility.isVisible(network, "a", "rail-only"));
        network.putEntry(entry(3, TerminalNetworkSavedData.Kind.PORT, "Shared", "port-town", List.of(), Set.of()));
        assertTrue(TerminalVisibility.isVisible(network, "a", "port-town"));
        assertFalse(TerminalVisibility.isVisible(network, "rail-only", "port-town"));
    }
    private static TerminalNetworkSavedData.TerminalEntry entry(long pos, TerminalNetworkSavedData.Kind kind,
            String name, String town, List<String> ends, Set<String> reachable) {
        return new TerminalNetworkSavedData.TerminalEntry("minecraft:overworld", pos, kind, name, town, "nation", ends, reachable);
    }
}
