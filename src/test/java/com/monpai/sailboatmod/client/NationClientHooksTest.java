package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.nation.menu.ClaimPreviewMapState;
import com.monpai.sailboatmod.nation.menu.NationOverviewData;
import net.minecraft.SharedConstants;
import net.minecraft.core.NonNullList;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NationClientHooksTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void metadataOnlyOverviewPreservesLocalPendingClaimPreviewContext() {
        NationOverviewData local = nationData(
                "nation-a",
                "local-nation",
                false,
                "idle",
                10,
                20,
                List.of(0xFF010203, 0xFF0A0B0C),
                ClaimPreviewMapState.loading(7L, 8, 40, -12)
        );
        NationOverviewData incomingMetadataOnly = nationData(
                "nation-a",
                "incoming-nation",
                true,
                "active",
                -1,
                -2,
                List.of(),
                ClaimPreviewMapState.loading(0L, 8, 0, 0)
        );

        NationOverviewData merged = NationClientHooks.mergeOverviewPreservingPendingClaimPreview(local, incomingMetadataOnly, 7L);

        assertEquals("incoming-nation", merged.nationName());
        assertTrue(merged.hasActiveWar());
        assertEquals("active", merged.warStatus());

        assertEquals(10, merged.previewCenterChunkX());
        assertEquals(20, merged.previewCenterChunkZ());
        assertEquals(List.of(0xFF010203, 0xFF0A0B0C), merged.nearbyTerrainColors());
        assertTrue(merged.claimMapState().loading());
        assertEquals(7L, merged.claimMapState().revision());
        assertEquals(40, merged.claimMapState().centerChunkX());
        assertEquals(-12, merged.claimMapState().centerChunkZ());
    }

    @Test
    void metadataOnlyOverviewFromDifferentOwnerDoesNotPreservePreviewContext() {
        NationOverviewData local = nationData(
                "nation-a",
                "local-nation",
                false,
                "idle",
                10,
                20,
                List.of(0xFF010203, 0xFF0A0B0C),
                ClaimPreviewMapState.loading(7L, 8, 40, -12)
        );
        NationOverviewData incomingMetadataOnly = nationData(
                "nation-b",
                "incoming-nation",
                true,
                "active",
                -1,
                -2,
                List.of(),
                ClaimPreviewMapState.loading(0L, 8, 0, 0)
        );

        NationOverviewData merged = NationClientHooks.mergeOverviewPreservingPendingClaimPreview(local, incomingMetadataOnly, 7L);

        assertEquals("nation-b", merged.nationId());
        assertEquals(-1, merged.previewCenterChunkX());
        assertEquals(-2, merged.previewCenterChunkZ());
        assertTrue(merged.nearbyTerrainColors().isEmpty());
        assertTrue(merged.claimMapState().loading());
        assertEquals(0L, merged.claimMapState().revision());
        assertEquals(0, merged.claimMapState().centerChunkX());
        assertEquals(0, merged.claimMapState().centerChunkZ());
    }

    private static NationOverviewData nationData(String nationId,
                                                 String nationName,
                                                 boolean hasActiveWar,
                                                 String warStatus,
                                                 int previewCenterChunkX,
                                                 int previewCenterChunkZ,
                                                 List<Integer> nearbyTerrainColors,
                                                 ClaimPreviewMapState claimMapState) {
        return new NationOverviewData(
                true,
                nationId,
                nationName,
                "na",
                0x123456,
                0x654321,
                "leader",
                "office",
                "town-a",
                "Town A",
                3,
                false,
                "minecraft:overworld",
                0L,
                5,
                0,
                0,
                previewCenterChunkX,
                previewCenterChunkZ,
                false,
                false,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                hasActiveWar,
                "opponent",
                1,
                2,
                3,
                10,
                warStatus,
                120,
                30,
                false,
                "",
                0,
                0L,
                false,
                0,
                false,
                "",
                0L,
                0L,
                false,
                "",
                0,
                0,
                0L,
                "",
                false,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                100L,
                100,
                100,
                0,
                NonNullList.withSize(com.monpai.sailboatmod.nation.model.NationTreasuryRecord.TREASURY_SLOTS, ItemStack.EMPTY),
                "officer",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                nearbyTerrainColors,
                List.of(),
                List.of(),
                claimMapState
        );
    }
}
