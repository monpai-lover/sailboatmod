package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.client.screen.nation.NationHomeScreen;
import com.monpai.sailboatmod.nation.menu.ClaimPreviewMapState;
import com.monpai.sailboatmod.nation.menu.NationOverviewData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class NationClientHooks {
    private static final String CLAIM_RENDER_KIND = "nation-claims";
    private static NationOverviewData lastSyncedData = NationOverviewData.empty();
    private static boolean suppressReopen = false;
    private static long claimPreviewRevisionCounter = 0L;
    private static long latestRequestedClaimPreviewRevision = 0L;

    public static void openCachedOrEmpty() {
        suppressReopen = false;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof NationHomeScreen nationHomeScreen) {
            nationHomeScreen.updateData(lastSyncedData);
            return;
        }
        minecraft.setScreen(new NationHomeScreen(lastSyncedData));
    }

    public static void openOrUpdate(NationOverviewData data) {
        lastSyncedData = data == null ? NationOverviewData.empty() : data;
        queueClaimPreviewRaster(lastSyncedData);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof NationHomeScreen nationHomeScreen) {
            nationHomeScreen.updateData(lastSyncedData);
            return;
        }
        if (suppressReopen) {
            return;
        }
        minecraft.setScreen(new NationHomeScreen(lastSyncedData));
    }

    public static void updateIfOpen(NationOverviewData data) {
        lastSyncedData = data == null ? NationOverviewData.empty() : data;
        queueClaimPreviewRaster(lastSyncedData);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof NationHomeScreen nationHomeScreen) {
            nationHomeScreen.updateData(lastSyncedData);
        }
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
        if (minecraft.screen instanceof NationHomeScreen nationHomeScreen) {
            nationHomeScreen.updateData(lastSyncedData);
        }
    }

    public static void onScreenClosed() {
        suppressReopen = true;
    }

    public static void showToast(String title, String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        Component titleComponent = title == null || title.isBlank()
                ? Component.translatable("screen.sailboatmod.nation.home.title")
                : Component.literal(title);
        Component messageComponent = message == null || message.isBlank()
                ? CommonComponents.EMPTY
                : Component.literal(message);
        SystemToast.add(minecraft.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION, titleComponent, messageComponent);
    }

    public static NationOverviewData lastSyncedData() {
        return lastSyncedData;
    }

    public static void clearCache() {
        lastSyncedData = NationOverviewData.empty();
        claimPreviewRevisionCounter = 0L;
        latestRequestedClaimPreviewRevision = 0L;
        ClaimMapRenderTaskService.shutdownShared();
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
        if (minecraft.screen instanceof NationHomeScreen nationHomeScreen) {
            nationHomeScreen.updateData(lastSyncedData);
        }
        return revision;
    }

    private static long nextClaimPreviewRevision() {
        claimPreviewRevisionCounter = Math.max(1L, claimPreviewRevisionCounter + 1L);
        return claimPreviewRevisionCounter;
    }

    private static void queueClaimPreviewRaster(NationOverviewData snapshot) {
        if (snapshot == null || !snapshot.nearbyTerrainColors().isEmpty()) {
            return;
        }
        ClaimPreviewMapState state = snapshot.claimMapState();
        if (!state.ready() || state.colors().isEmpty()) {
            return;
        }
        String ownerKey = snapshot.nationId().isBlank() ? "nation-screen" : snapshot.nationId();
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
        if (minecraft.screen instanceof NationHomeScreen nationHomeScreen) {
            nationHomeScreen.updateData(lastSyncedData);
        }
    }

    private static List<Integer> toColorList(int[] pixels) {
        return java.util.Arrays.stream(pixels).boxed().toList();
    }

    private NationClientHooks() {
    }
}
