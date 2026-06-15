package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeProposalRecordTest {
    @Test
    void statusSurvivesNbtRoundTrip() {
        TradeProposalRecord p = new TradeProposalRecord(
                "p1", "natA", "natB", 100L, List.of(), 0L, List.of(),
                1000L, TradeProposalRecord.STATUS_PENDING);
        TradeProposalRecord loaded = TradeProposalRecord.load(p.save());
        assertEquals(TradeProposalRecord.STATUS_PENDING, loaded.status());
        assertTrue(loaded.isPending());
    }

    @Test
    void legacyNbtWithoutStatusDefaultsPending() {
        TradeProposalRecord p = new TradeProposalRecord(
                "p1", "natA", "natB", 100L, List.of(), 0L, List.of(),
                1000L, TradeProposalRecord.STATUS_ACCEPTED);
        CompoundTag tag = p.save();
        tag.remove("Status");
        TradeProposalRecord loaded = TradeProposalRecord.load(tag);
        assertEquals(TradeProposalRecord.STATUS_PENDING, loaded.status());
    }

    @Test
    void blankStatusSanitizesToPending() {
        TradeProposalRecord p = new TradeProposalRecord(
                "p1", "natA", "natB", 100L, List.of(), 0L, List.of(), 1000L, "");
        assertEquals(TradeProposalRecord.STATUS_PENDING, p.status());
    }
}
