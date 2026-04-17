package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.nation.service.ClaimMapViewportSnapshot;
import com.monpai.sailboatmod.client.screen.town.TownHomeScreen;
import com.monpai.sailboatmod.nation.menu.ClaimPreviewMapState;
import com.monpai.sailboatmod.nation.menu.TownOverviewData;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.CloseClaimMapViewportPacket;
import net.minecraft.client.Minecraft;

import java.util.List;

public final class TownClientHooks {
    private static TownOverviewData lastSyncedData = TownOverviewData.empty();
    private static boolean suppressReopen = false;
    private static long claimPreviewRevisionCounter = 0L;
    private static long latestRequestedClaimPreviewRevision = 0L;

    public static void requestOpen() {
        suppressReopen = false;
    }

    public static void openCachedOrEmpty() {
        suppressReopen = false;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof TownHomeScreen townHomeScreen) {
            townHomeScreen.updateData(lastSyncedData);
            return;
        }
        minecraft.setScreen(new TownHomeScreen(lastSyncedData));
    }

    public static void openOrUpdate(TownOverviewData data) {
        lastSyncedData = mergeOverviewPreservingPendingClaimPreview(
                lastSyncedData,
                data,
                latestRequestedClaimPreviewRevision
        );
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof TownHomeScreen townHomeScreen) {
            townHomeScreen.updateData(lastSyncedData);
            return;
        }
        if (suppressReopen) {
            return;
        }
        minecraft.setScreen(new TownHomeScreen(lastSyncedData));
    }

    static TownOverviewData mergeOverviewPreservingPendingClaimPreview(TownOverviewData current,
                                                                       TownOverviewData incoming,
                                                                       long latestRequestedPreviewRevision) {
        TownOverviewData safeCurrent = current == null ? TownOverviewData.empty() : current;
        TownOverviewData safeIncoming = incoming == null ? TownOverviewData.empty() : incoming;
        if (!sameOwner(safeCurrent, safeIncoming)
                || !isMetadataOnlyClaimPreview(safeIncoming)
                || !shouldPreserveLocalClaimPreview(safeCurrent, latestRequestedPreviewRevision)) {
            return safeIncoming;
        }
        return safeIncoming.withClaimPreviewContext(
                safeCurrent.claimMapState(),
                safeCurrent.nearbyTerrainColors(),
                safeCurrent.previewCenterChunkX(),
                safeCurrent.previewCenterChunkZ()
        );
    }

    private static boolean sameOwner(TownOverviewData current, TownOverviewData incoming) {
        if (current == null || incoming == null) {
            return false;
        }
        String currentOwnerId = current.townId();
        String incomingOwnerId = incoming.townId();
        return currentOwnerId != null
                && incomingOwnerId != null
                && !currentOwnerId.isBlank()
                && currentOwnerId.equals(incomingOwnerId);
    }

    private static boolean isMetadataOnlyClaimPreview(TownOverviewData data) {
        ClaimPreviewMapState state = data == null ? ClaimPreviewMapState.empty() : data.claimMapState();
        return state.loading() && state.revision() == 0L && data != null && data.nearbyTerrainColors().isEmpty();
    }

    private static boolean shouldPreserveLocalClaimPreview(TownOverviewData data, long latestRequestedPreviewRevision) {
        if (data == null) {
            return false;
        }
        ClaimPreviewMapState localState = data.claimMapState();
        return localState.revision() > 0L
                || !data.nearbyTerrainColors().isEmpty()
                || latestRequestedPreviewRevision > 0L;
    }

    public static void applyClaimPreview(String ownerId, ClaimMapViewportSnapshot snapshot) {
        if (!shouldApplyClaimPreviewOwner(lastSyncedData.townId(), ownerId)) {
            return;
        }
        ClaimMapViewportSnapshot safeSnapshot = snapshot == null
                ? new ClaimMapViewportSnapshot("", 0L, 0, 0, 0, List.of())
                : snapshot;
        claimPreviewRevisionCounter = Math.max(claimPreviewRevisionCounter, safeSnapshot.revision());
        if (safeSnapshot.revision() < latestRequestedClaimPreviewRevision) {
            return;
        }
        ClaimPreviewMapState state = ClaimPreviewMapState.ready(
                safeSnapshot.revision(),
                safeSnapshot.radius(),
                safeSnapshot.centerChunkX(),
                safeSnapshot.centerChunkZ(),
                List.of()
        );
        lastSyncedData = lastSyncedData.withClaimPreview(state, safeSnapshot.pixels());
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof TownHomeScreen townHomeScreen) {
            townHomeScreen.updateData(lastSyncedData);
        }
    }

    public static void onScreenClosed(String ownerId) {
        suppressReopen = true;
        if (!shouldApplyClaimPreviewOwner(ownerId, ownerId)) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new CloseClaimMapViewportPacket(
                CloseClaimMapViewportPacket.ScreenKind.TOWN,
                ownerId
        ));
    }

    static boolean shouldApplyClaimPreviewOwner(String cachedOwnerId, String incomingOwnerId) {
        String expected = cachedOwnerId == null ? "" : cachedOwnerId.trim();
        String incoming = incomingOwnerId == null ? "" : incomingOwnerId.trim();
        return !expected.isBlank() && expected.equals(incoming);
    }

    public static void clearCache() {
        lastSyncedData = TownOverviewData.empty();
        claimPreviewRevisionCounter = 0L;
        latestRequestedClaimPreviewRevision = 0L;
    }

    public static TownOverviewData lastSyncedData() {
        return lastSyncedData;
    }

    public static long beginClaimPreviewRequest(int centerChunkX, int centerChunkZ, int radius) {
        long revision = nextClaimPreviewRevision();
        latestRequestedClaimPreviewRevision = Math.max(latestRequestedClaimPreviewRevision, revision);
        ClaimPreviewMapState loading = ClaimPreviewMapState.loading(
                revision,
                Math.max(0, radius),
                centerChunkX,
                centerChunkZ
        );
        lastSyncedData = lastSyncedData.withClaimPreviewState(loading);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof TownHomeScreen townHomeScreen) {
            townHomeScreen.updateData(lastSyncedData);
        }
        return revision;
    }

    private static long nextClaimPreviewRevision() {
        claimPreviewRevisionCounter = Math.max(1L, claimPreviewRevisionCounter + 1L);
        return claimPreviewRevisionCounter;
    }

    private TownClientHooks() {
    }
}
