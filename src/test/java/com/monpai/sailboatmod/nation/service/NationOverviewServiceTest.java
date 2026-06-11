package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.menu.NationOverviewData;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NationOverviewServiceTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void noNationOverviewUsesPlayerChunkForCurrentAndPreviewPositions() {
        NationOverviewData data = NationOverviewService.noNationDataForTest(new ChunkPos(656, -877), null);

        assertFalse(data.hasNation());
        assertEquals(656, data.currentChunkX());
        assertEquals(-877, data.currentChunkZ());
        assertEquals(656, data.previewCenterChunkX());
        assertEquals(-877, data.previewCenterChunkZ());
        assertEquals(656, data.claimMapState().centerChunkX());
        assertEquals(-877, data.claimMapState().centerChunkZ());
        assertTrue(data.claimMapState().loading());
    }

    @Test
    void noNationOverviewKeepsExplicitPreviewCenter() {
        NationOverviewData data = NationOverviewService.noNationDataForTest(new ChunkPos(656, -877), new ChunkPos(100, 200));

        assertFalse(data.hasNation());
        assertEquals(656, data.currentChunkX());
        assertEquals(-877, data.currentChunkZ());
        assertEquals(100, data.previewCenterChunkX());
        assertEquals(200, data.previewCenterChunkZ());
        assertEquals(100, data.claimMapState().centerChunkX());
        assertEquals(200, data.claimMapState().centerChunkZ());
    }
}
