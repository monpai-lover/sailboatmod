package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.client.screen.town.TownHomeScreen;
import com.monpai.sailboatmod.nation.menu.ClaimPreviewMapState;
import com.monpai.sailboatmod.nation.menu.TownOverviewData;
import net.minecraft.client.Minecraft;

import java.util.List;

public final class TownClientHooks {
    private static final String CLAIM_RENDER_KIND = "town-claims";
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
        lastSyncedData = data == null ? TownOverviewData.empty() : data;
        queueClaimPreviewRaster(lastSyncedData);
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

    public static void applyClaimPreview(ClaimPreviewMapState state) {
        ClaimPreviewMapState safeState = state == null ? ClaimPreviewMapState.empty() : state;
        claimPreviewRevisionCounter = Math.max(claimPreviewRevisionCounter, safeState.revision());
        if (safeState.revision() < latestRequestedClaimPreviewRevision) {
            return;
        }
        lastSyncedData = lastSyncedData.withClaimPreview(safeState, List.of());
        queueClaimPreviewRaster(lastSyncedData);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof TownHomeScreen townHomeScreen) {
            townHomeScreen.updateData(lastSyncedData);
        }
    }

    public static void onScreenClosed() {
        suppressReopen = true;
    }

    public static void clearCache() {
        lastSyncedData = TownOverviewData.empty();
        claimPreviewRevisionCounter = 0L;
        latestRequestedClaimPreviewRevision = 0L;
        ClaimMapRenderTaskService.shutdownShared();
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
        lastSyncedData = lastSyncedData.withClaimPreview(loading, lastSyncedData.nearbyTerrainColors());
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

    private static void queueClaimPreviewRaster(TownOverviewData snapshot) {
        if (snapshot == null || !snapshot.nearbyTerrainColors().isEmpty()) {
            return;
        }
        ClaimPreviewMapState state = snapshot.claimMapState();
        if (!state.ready() || state.colors().isEmpty()) {
            return;
        }
        String ownerKey = snapshot.townId().isBlank() ? "town-screen" : snapshot.townId();
        ClaimMapRenderTaskService.getOrCreate().submitLatest(
                new ClaimMapRenderTaskService.TaskKey(CLAIM_RENDER_KIND, ownerKey),
                () -> ClaimMapRasterizer.rasterize(
                        state.radius(),
                        state.colors(),
                        snapshot.nearbyClaims(),
                        state.centerChunkX(),
                        state.centerChunkZ()
                ),
                pixels -> applyRasterizedClaimPreview(state, pixels)
        );
    }

    private static void applyRasterizedClaimPreview(ClaimPreviewMapState state, int[] pixels) {
        if (state == null || pixels == null || state.revision() < latestRequestedClaimPreviewRevision) {
            return;
        }
        lastSyncedData = lastSyncedData.withClaimPreview(state, toColorList(pixels));
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof TownHomeScreen townHomeScreen) {
            townHomeScreen.updateData(lastSyncedData);
        }
    }

    private static List<Integer> toColorList(int[] pixels) {
        return java.util.Arrays.stream(pixels).boxed().toList();
    }

    private TownClientHooks() {
    }
}
