package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.menu.ClaimPreviewMapState;
import com.monpai.sailboatmod.nation.menu.NationOverviewData;
import com.monpai.sailboatmod.nation.menu.TownOverviewData;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.NonNullList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimOverviewPacketRoundTripTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void openTownPacketCarriesEmptyMapStateWithoutTerrainColorPayload() {
        TownOverviewData data = new TownOverviewData(
                true, "town-a", "Town A", "", "", "", "", false, 0x123456, 0x654321,
                false, "", 0L, 0, 0, 0, 0, 10, 20, false, false, "", "", "", "", "", "", "", "",
                "", 0, 0, 0L, "", false, false, false, false, false, false,
                List.of(), List.of(), List.of(), "european", Map.of(), 0.0f, Map.of(), 0.0f,
                0, 0, 0, 0, 0, 0L, 0L, 0L, List.of(), List.of(), List.of(), List.of(), List.of(),
                ClaimPreviewMapState.loading(11L, 8, 10, 20)
        );

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        OpenTownScreenPacket.encode(new OpenTownScreenPacket(data), buffer);
        OpenTownScreenPacket decoded = OpenTownScreenPacket.decode(buffer);

        TownOverviewData decodedData = extractTownData(decoded);
        assertEquals(11L, decodedData.claimMapState().revision());
        assertEquals(8, decodedData.claimMapState().radius());
        assertEquals(10, decodedData.claimMapState().centerChunkX());
        assertEquals(20, decodedData.claimMapState().centerChunkZ());
        assertTrue(decodedData.claimMapState().loading());
        assertFalse(decodedData.claimMapState().ready());
        assertTrue(decodedData.nearbyTerrainColors().isEmpty());
    }

    @Test
    void openNationPacketCarriesEmptyMapStateWithoutTerrainColorPayload() {
        NationOverviewData data = new NationOverviewData(
                true, "nation-a", "Nation A", "NA", 0x123456, 0x654321, "", "", "", "",
                0, false, "", 0L, 0, 0, 0, 10, 20, false, false, "", "", "", "", "", "", "", "",
                false, "", 0, 0, 0, 0, "", 0, 0, false, "", 0, 0L, false, 0,
                false, "", 0L, 0L, false, "", 0, 0, 0L, "", false, false, false, false, false, false, false,
                false, 0L, 0, 0, 0, NonNullList.withSize(com.monpai.sailboatmod.nation.model.NationTreasuryRecord.TREASURY_SLOTS, ItemStack.EMPTY),
                "", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                ClaimPreviewMapState.loading(17L, 6, 10, 20)
        );

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        OpenNationScreenPacket.encode(new OpenNationScreenPacket(data), buffer);
        OpenNationScreenPacket decoded = OpenNationScreenPacket.decode(buffer);

        NationOverviewData decodedData = extractNationData(decoded);
        assertEquals(17L, decodedData.claimMapState().revision());
        assertEquals(6, decodedData.claimMapState().radius());
        assertEquals(10, decodedData.claimMapState().centerChunkX());
        assertEquals(20, decodedData.claimMapState().centerChunkZ());
        assertTrue(decodedData.claimMapState().loading());
        assertFalse(decodedData.claimMapState().ready());
        assertTrue(decodedData.nearbyTerrainColors().isEmpty());
    }

    @Test
    void openTownMenuPacketCarriesOnlyTownMetadata() {
        OpenTownMenuPacket packet = new OpenTownMenuPacket("town-a");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        OpenTownMenuPacket.encode(packet, buffer);

        FriendlyByteBuf expected = new FriendlyByteBuf(Unpooled.buffer());
        expected.writeUtf("town-a", 40);
        assertEquals(expected.writerIndex(), buffer.writerIndex());

        OpenTownMenuPacket decoded = OpenTownMenuPacket.decode(buffer);
        assertEquals("town-a", extractTownId(decoded));
    }

    @Test
    void openNationMenuPacketCarriesNoViewportPayload() {
        OpenNationMenuPacket packet = new OpenNationMenuPacket();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        OpenNationMenuPacket.encode(packet, buffer);

        assertEquals(0, buffer.writerIndex());
        OpenNationMenuPacket.decode(buffer);
    }

    @Test
    void townOverviewWithClaimPreviewUsesMapStateCenter() {
        TownOverviewData updated = TownOverviewData.empty().withClaimPreview(
                ClaimPreviewMapState.loading(9L, 8, 33, -14),
                List.of()
        );

        assertEquals(33, updated.previewCenterChunkX());
        assertEquals(-14, updated.previewCenterChunkZ());
    }

    @Test
    void nationOverviewWithClaimPreviewUsesMapStateCenter() {
        NationOverviewData updated = NationOverviewData.empty().withClaimPreview(
                ClaimPreviewMapState.loading(12L, 6, -9, 27),
                List.of()
        );

        assertEquals(-9, updated.previewCenterChunkX());
        assertEquals(27, updated.previewCenterChunkZ());
    }

    @Test
    void loadingClaimPreviewStatePreservesCurrentPreviewCenterAndTerrain() {
        TownOverviewData current = new TownOverviewData(
                true, "town-a", "Town A", "", "", "", "", false, 0x123456, 0x654321,
                false, "", 0L, 0, 0, 0, 0, 10, 20, false, false, "", "", "", "", "", "", "", "",
                "", 0, 0, 0L, "", false, false, false, false, false, false,
                List.of(), List.of(0xFF010203, 0xFF0A0B0C), List.of(), "european", Map.of(), 0.0f, Map.of(), 0.0f,
                0, 0, 0, 0, 0, 0L, 0L, 0L, List.of(), List.of(), List.of(), List.of(), List.of(),
                ClaimPreviewMapState.ready(1L, 8, 10, 20, List.of(0xFF010203, 0xFF0A0B0C))
        );

        TownOverviewData updated = current.withClaimPreviewState(ClaimPreviewMapState.loading(22L, 8, 40, -12));

        assertEquals(10, updated.previewCenterChunkX());
        assertEquals(20, updated.previewCenterChunkZ());
        assertEquals(List.of(0xFF010203, 0xFF0A0B0C), updated.nearbyTerrainColors());
        assertEquals(40, updated.claimMapState().centerChunkX());
        assertEquals(-12, updated.claimMapState().centerChunkZ());
        assertEquals(22L, updated.claimMapState().revision());
        assertTrue(updated.claimMapState().loading());
    }

    private static TownOverviewData extractTownData(OpenTownScreenPacket packet) {
        try {
            var field = OpenTownScreenPacket.class.getDeclaredField("data");
            field.setAccessible(true);
            return (TownOverviewData) field.get(packet);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static NationOverviewData extractNationData(OpenNationScreenPacket packet) {
        try {
            var field = OpenNationScreenPacket.class.getDeclaredField("data");
            field.setAccessible(true);
            return (NationOverviewData) field.get(packet);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String extractTownId(OpenTownMenuPacket packet) {
        try {
            var field = OpenTownMenuPacket.class.getDeclaredField("townId");
            field.setAccessible(true);
            return (String) field.get(packet);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
