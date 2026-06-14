package com.monpai.sailboatmod.client.marketweb;

import com.monpai.sailboatmod.market.web.map.MarketWebMapConstants;
import com.monpai.sailboatmod.market.web.map.MarketWebMapTileCache;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.marketweb.MarketWebMapTileUploadPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileManager;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import net.minecraft.client.Minecraft;

import java.util.Arrays;

public final class MarketWebMapTileUploadClient {
    private static final boolean CLIENT_UPLOADS_ENABLED = false;
    private static final int MAX_RECENT_KEYS = 4_096;
    private static final MarketWebMapTileUploadDedupe DEDUPE = new MarketWebMapTileUploadDedupe(MAX_RECENT_KEYS);
    private static Object lastConnection;

    public static void offer(RoadPlannerMapTileSyncPacket packet) {
        if (!CLIENT_UPLOADS_ENABLED) {
            return;
        }
        if (packet == null
                || !MarketWebMapConstants.OVERWORLD.equals(packet.dimensionId())
                || packet.lod() != MapLod.LOD_1
                || packet.pixelWidth() != MarketWebMapConstants.TILE_SIZE
                || packet.pixelHeight() != MarketWebMapConstants.TILE_SIZE
                || packet.argbPixels().length != MarketWebMapConstants.TILE_SIZE * MarketWebMapConstants.TILE_SIZE) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Object connection = minecraft == null ? null : minecraft.getConnection();
        if (connection == null) {
            DEDUPE.clear();
            lastConnection = null;
            return;
        }
        if (connection != lastConnection) {
            DEDUPE.clear();
            lastConnection = connection;
        }
        UploadPixels upload = selectUploadPixels(packet, mergedTilePixels(packet));
        int[] uploadPixels = upload.pixels();
        if (uploadPixels.length != MarketWebMapConstants.TILE_SIZE * MarketWebMapConstants.TILE_SIZE) {
            return;
        }
        if (!DEDUPE.remember(key(packet, uploadPixels))) {
            return;
        }
        byte[] pngBytes = encodeUploadPng(packet.pixelWidth(), packet.pixelHeight(), uploadPixels, upload.nativeImageFormat());
        if (pngBytes.length <= 0 || pngBytes.length > MarketWebMapConstants.MAX_TILE_BYTES) {
            return;
        }
        int uploadId = uploadId(packet, pngBytes);
        int chunkCount = (pngBytes.length + MarketWebMapConstants.MAX_UPLOAD_CHUNK_BYTES - 1)
                / MarketWebMapConstants.MAX_UPLOAD_CHUNK_BYTES;
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            int from = chunkIndex * MarketWebMapConstants.MAX_UPLOAD_CHUNK_BYTES;
            int to = Math.min(pngBytes.length, from + MarketWebMapConstants.MAX_UPLOAD_CHUNK_BYTES);
            ModNetwork.CHANNEL.sendToServer(new MarketWebMapTileUploadPacket(
                    packet.dimensionId(),
                    packet.lod(),
                    packet.tileX(),
                    packet.tileZ(),
                    uploadId,
                    pngBytes.length,
                    chunkIndex,
                    chunkCount,
                    Arrays.copyOfRange(pngBytes, from, to)
            ));
        }
    }

    static int[] selectUploadPixelsForTest(RoadPlannerMapTileSyncPacket packet, int[] mergedPixels) {
        return selectUploadPixels(packet, mergedPixels).pixels();
    }

    static byte[] encodeUploadPngForTest(int pixelWidth, int pixelHeight, int[] uploadPixels) {
        return encodeUploadPng(pixelWidth, pixelHeight, uploadPixels, true);
    }

    static byte[] encodeSelectedUploadPngForTest(RoadPlannerMapTileSyncPacket packet, int[] mergedPixels) {
        UploadPixels upload = selectUploadPixels(packet, mergedPixels);
        return encodeUploadPng(packet.pixelWidth(), packet.pixelHeight(), upload.pixels(), upload.nativeImageFormat());
    }

    private static byte[] encodeUploadPng(int pixelWidth, int pixelHeight, int[] uploadPixels, boolean nativeImageFormat) {
        return nativeImageFormat
                ? MarketWebMapTileCache.encodeNativeImagePng(pixelWidth, pixelHeight, uploadPixels)
                : MarketWebMapTileCache.encodePng(pixelWidth, pixelHeight, uploadPixels);
    }

    private static UploadPixels selectUploadPixels(RoadPlannerMapTileSyncPacket packet, int[] mergedPixels) {
        if (mergedPixels != null && mergedPixels.length == MarketWebMapConstants.TILE_SIZE * MarketWebMapConstants.TILE_SIZE) {
            return new UploadPixels(Arrays.copyOf(mergedPixels, mergedPixels.length), true);
        }
        if (packet == null || hasPartialCoverage(packet.coverageMask())) {
            return UploadPixels.empty();
        }
        return new UploadPixels(packet.argbPixels(), false);
    }

    private static int[] mergedTilePixels(RoadPlannerMapTileSyncPacket packet) {
        try {
            return RoadPlannerTileManager.sharedDefault().copyTilePixels(packet);
        } catch (RuntimeException ignored) {
            return new int[0];
        }
    }

    private static boolean hasPartialCoverage(boolean[] coverageMask) {
        if (coverageMask == null || coverageMask.length == 0) {
            return false;
        }
        for (boolean covered : coverageMask) {
            if (!covered) {
                return true;
            }
        }
        return false;
    }

    private static MarketWebMapTileUploadDedupe.Key key(RoadPlannerMapTileSyncPacket packet, int[] uploadPixels) {
        return new MarketWebMapTileUploadDedupe.Key(
                packet.dimensionId(),
                packet.lod(),
                packet.tileX(),
                packet.tileZ(),
                0,
                Arrays.hashCode(uploadPixels)
        );
    }

    private static int uploadId(RoadPlannerMapTileSyncPacket packet, byte[] pngBytes) {
        int hash = Arrays.hashCode(pngBytes);
        hash = 31 * hash + packet.tileX();
        hash = 31 * hash + packet.tileZ();
        hash = 31 * hash + packet.lod().ordinal();
        return hash;
    }

    private record UploadPixels(int[] pixels, boolean nativeImageFormat) {
        private static UploadPixels empty() {
            return new UploadPixels(new int[0], false);
        }
    }

    private MarketWebMapTileUploadClient() {
    }
}
