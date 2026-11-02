package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.dock.AvailableDockEntry;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.route.water.PortBerthValidator.Verdict.*;

class PortTerrainListingTest {
    @Test void pendingSourceKeepsVerifiedTargetPending() { assertEquals(PENDING, PortCandidateListing.combine(PENDING, VERIFIED)); }
    @Test void knownInvalidTargetCanBeRejected() { assertEquals(INVALID, PortCandidateListing.combine(PENDING, INVALID)); }
    @Test void eitherPredictionIsLabelled() { assertEquals(PREDICTED, PortCandidateListing.combine(PREDICTED, VERIFIED)); }
    @Test void bothRealEndpointsAreVerified() { assertEquals(VERIFIED, PortCandidateListing.combine(VERIFIED, VERIFIED)); }
    @Test void wireCompatibleLabelNeverRenamesPersistentEntry() {
        var entry = new AvailableDockEntry(BlockPos.ZERO, "君士坦丁堡", "owner", "nation", 100);
        var labelled = PortCandidateListing.labelled(entry, PENDING);
        assertEquals("君士坦丁堡", entry.dockName());
        assertTrue(labelled.dockName().startsWith("[地形待验证]"));
        assertEquals(entry.pos(), labelled.pos());
    }
    @Test void labelFitsExistingPacketLimitAndDoesNotSplitEmoji() {
        var entry = new AvailableDockEntry(BlockPos.ZERO, "港".repeat(52) + "🚢".repeat(6), "o", "n", 1);
        String value = PortCandidateListing.labelled(entry, PENDING).dockName();
        assertTrue(value.length() <= 64);
        assertFalse(Character.isHighSurrogate(value.charAt(value.length() - 1)));
    }
}
