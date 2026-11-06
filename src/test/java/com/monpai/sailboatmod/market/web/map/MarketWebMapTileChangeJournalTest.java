package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketWebMapTileChangeJournalTest {
    @Test void onlyChangedCoordinatesAreReturnedAndRepeatedWritesAreCoalesced() {
        var journal = new MarketWebMapTileChangeJournal(4);
        String session = journal.session();
        journal.record("square", "minecraft:overworld", 0, 1, 2);
        long first = journal.revision();
        journal.record("square", "minecraft:overworld", 0, 2, 2);
        journal.record("square", "minecraft:overworld", 0, 1, 2);
        var changes = journal.changes("minecraft:overworld", first, session);
        assertFalse(changes.reset());
        assertEquals(2, changes.tiles().size());
        assertEquals(3, changes.revision());
    }

    @Test void boundedHistoryReportsOverflowAndRestartInsteadOfSilentlyLosingUpdates() {
        var journal = new MarketWebMapTileChangeJournal(2);
        for (int i = 0; i < 100; i++) journal.record("square", "minecraft:overworld", 0, i, 0);
        assertEquals(2, journal.retainedChanges());
        assertTrue(journal.changes("minecraft:overworld", 1, journal.session()).reset());
        assertTrue(journal.changes("minecraft:overworld", 100, "previous-server").reset());
        assertTrue(journal.changes("minecraft:overworld", 101, journal.session()).reset());
        assertFalse(journal.changes("minecraft:overworld", 98, journal.session()).reset());
    }

    @Test void dimensionsDoNotLeakAndNoChangePollIsEmpty() {
        var journal = new MarketWebMapTileChangeJournal(4);
        journal.record("square", "minecraft:the_nether", 0, 1, 2);
        assertTrue(journal.changes("minecraft:overworld", 0, journal.session()).tiles().isEmpty());
        assertTrue(journal.changes("minecraft:the_nether", 1, journal.session()).tiles().isEmpty());
    }
}
