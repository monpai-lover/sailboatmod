package com.monpai.sailboatmod.client.texture;

import com.monpai.sailboatmod.SailboatMod;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.storage.LevelResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

public final class NationFlagTextureCache {
    private static final ResourceLocation BANNER_BASE_TEXTURE = new ResourceLocation("minecraft", "textures/entity/banner/base.png");
    private static final int BANNER_TEXTURE_SIZE = 64;
    private static final int FLAG_FACE_WIDTH = 20;
    private static final int FLAG_FACE_HEIGHT = 40;
    private static final int FRONT_FACE_X = 1;
    private static final int BACK_FACE_X = 22;
    private static final int FACE_Y = 1;
    private static final int LEFT_EDGE_X = 0;
    private static final int CENTER_EDGE_X = 21;

    private static final Map<String, ResourceLocation> CACHE = new HashMap<>();
    private static final Map<String, byte[]> SYNCED_BYTES = new HashMap<>();
    private static final Map<String, Integer> FLAG_VERSIONS = new HashMap<>();
    private static final Map<String, UploadAssembly> PENDING = new HashMap<>();
    private static byte[] bannerBaseBytes;

    public static ResourceLocation resolve(String flagId, int primaryColor, int secondaryColor, boolean mirrored) {
        return resolveTexture(flagId, primaryColor, secondaryColor, mirrored, false);
    }

    public static ResourceLocation resolveBannerCloth(String flagId, int primaryColor, int secondaryColor, boolean mirrored) {
        return resolveTexture(flagId, primaryColor, secondaryColor, mirrored, true);
    }

    private static ResourceLocation resolveTexture(String flagId, int primaryColor, int secondaryColor, boolean mirrored, boolean bannerCloth) {
        String normalizedFlagId = flagId == null ? "none" : flagId;
        int version = FLAG_VERSIONS.getOrDefault(normalizedFlagId, 0);
        String mode = bannerCloth ? "cloth" : "flat";
        String key = normalizedFlagId + "|" + mode + "|" + primaryColor + "|" + secondaryColor + "|" + mirrored + "|" + version;
        ResourceLocation cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        NativeImage source = tryLoadSyncedFlagImage(normalizedFlagId);
        if (source == null) {
            source = tryLoadFlagImage(normalizedFlagId);
        }
        if (source == null) {
            source = buildFallback(64, 32, primaryColor, secondaryColor);
        }

        NativeImage finalImage;
        if (bannerCloth) {
            finalImage = buildBannerClothTexture(source, primaryColor, secondaryColor, mirrored);
        } else {
            finalImage = buildFlatTexture(source, primaryColor, secondaryColor, mirrored);
        }
        source.close();

        ResourceLocation textureId = Minecraft.getInstance().getTextureManager().register(
                SailboatMod.MODID + "/nation_flag/" + Integer.toHexString(key.hashCode()),
                new DynamicTexture(finalImage)
        );
        CACHE.put(key, textureId);
        return textureId;
    }

    public static void acceptChunk(String flagId, int chunkIndex, int chunkCount, byte[] chunkBytes) {
        if (flagId == null || flagId.isBlank() || chunkCount <= 0 || chunkIndex < 0 || chunkIndex >= chunkCount || chunkBytes == null) {
            return;
        }
        UploadAssembly assembly = PENDING.computeIfAbsent(flagId, ignored -> new UploadAssembly(chunkCount));
        if (assembly.chunkCount != chunkCount) {
            assembly = new UploadAssembly(chunkCount);
            PENDING.put(flagId, assembly);
        }
        if (assembly.parts[chunkIndex] == null) {
            assembly.received++;
        }
        assembly.parts[chunkIndex] = chunkBytes;
        if (assembly.isComplete()) {
            int totalLength = 0;
            for (byte[] part : assembly.parts) {
                totalLength += part.length;
            }
            byte[] merged = new byte[totalLength];
            int offset = 0;
            for (byte[] part : assembly.parts) {
                System.arraycopy(part, 0, merged, offset, part.length);
                offset += part.length;
            }
            SYNCED_BYTES.put(flagId, merged);
            FLAG_VERSIONS.put(flagId, FLAG_VERSIONS.getOrDefault(flagId, 0) + 1);
            PENDING.remove(flagId);
            invalidateFlag(flagId);
        }
    }

    private static NativeImage buildFlatTexture(NativeImage source, int primaryColor, int secondaryColor, boolean mirrored) {
        NativeImage result = new NativeImage(source.getWidth(), source.getHeight(), true);
        fillFaceBackground(result, 0, 0, source.getWidth(), source.getHeight(), primaryColor, secondaryColor);
        blitWithAlpha(source, result, 0, 0, mirrored);
        return result;
    }

    private static NativeImage buildBannerClothTexture(NativeImage source, int primaryColor, int secondaryColor, boolean mirrored) {
        NativeImage banner = tryLoadBannerBaseTemplate();
        if (banner == null) {
            banner = new NativeImage(BANNER_TEXTURE_SIZE, BANNER_TEXTURE_SIZE, true);
        }
        fillFaceBackground(banner, FRONT_FACE_X, FACE_Y, FLAG_FACE_WIDTH, FLAG_FACE_HEIGHT, primaryColor, secondaryColor);
        fillFaceBackground(banner, BACK_FACE_X, FACE_Y, FLAG_FACE_WIDTH, FLAG_FACE_HEIGHT, primaryColor, secondaryColor);
        blitAspectFit(source, banner, FRONT_FACE_X, FACE_Y, FLAG_FACE_WIDTH, FLAG_FACE_HEIGHT, mirrored);
        blitAspectFit(source, banner, BACK_FACE_X, FACE_Y, FLAG_FACE_WIDTH, FLAG_FACE_HEIGHT, mirrored);
        fillVerticalEdge(banner, LEFT_EDGE_X, FACE_Y, FLAG_FACE_HEIGHT, primaryColor);
        fillVerticalEdge(banner, CENTER_EDGE_X, FACE_Y, FLAG_FACE_HEIGHT, primaryColor);
        return banner;
    }

    private static void fillFaceBackground(NativeImage target, int targetX, int targetY, int targetWidth, int targetHeight, int primaryColor, int secondaryColor) {
        int primary = 0xFF000000 | (primaryColor & 0x00FFFFFF);
        int secondary = 0xFF000000 | (secondaryColor & 0x00FFFFFF);
        for (int y = 0; y < targetHeight; y++) {
            for (int x = 0; x < targetWidth; x++) {
                double t = targetWidth <= 1 ? 0.0D : x / (double) (targetWidth - 1);
                int color = mix(primary, secondary, t);
                if (((x + y) & 3) == 0) {
                    color = brighten(color, 10);
                }
                target.setPixelRGBA(targetX + x, targetY + y, color);
            }
        }
    }

    private static void fillVerticalEdge(NativeImage target, int targetX, int targetY, int targetHeight, int color) {
        int rgba = 0xFF000000 | (color & 0x00FFFFFF);
        for (int y = 0; y < targetHeight; y++) {
            target.setPixelRGBA(targetX, targetY + y, brighten(rgba, (y & 1) == 0 ? 6 : 0));
        }
    }

    private static void blitAspectFit(NativeImage source, NativeImage target, int targetX, int targetY, int targetWidth, int targetHeight, boolean mirrored) {
        if (targetWidth <= 0 || targetHeight <= 0) {
            return;
        }
        int sourceWidth = Math.max(1, source.getWidth());
        int sourceHeight = Math.max(1, source.getHeight());
        double scale = Math.min(targetWidth / (double) sourceWidth, targetHeight / (double) sourceHeight);
        int drawWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
        int drawHeight = Math.max(1, (int) Math.round(sourceHeight * scale));
        int offsetX = targetX + Math.max(0, (targetWidth - drawWidth) / 2);
        int offsetY = targetY + Math.max(0, (targetHeight - drawHeight) / 2);
        for (int y = 0; y < drawHeight; y++) {
            int sampleY = Math.min(sourceHeight - 1, y * sourceHeight / drawHeight);
            for (int x = 0; x < drawWidth; x++) {
                int sourceX = Math.min(sourceWidth - 1, x * sourceWidth / drawWidth);
                int sampleX = mirrored ? sourceWidth - 1 - sourceX : sourceX;
                target.setPixelRGBA(offsetX + x, offsetY + y, source.getPixelRGBA(sampleX, sampleY));
            }
        }
    }

    private static void blitWithAlpha(NativeImage source, NativeImage target, int targetX, int targetY, boolean mirrored) {
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int sampleX = mirrored ? source.getWidth() - 1 - x : x;
                int src = source.getPixelRGBA(sampleX, y);
                int dst = target.getPixelRGBA(targetX + x, targetY + y);
                target.setPixelRGBA(targetX + x, targetY + y, alphaBlend(src, dst));
            }
        }
    }

    private static int alphaBlend(int foreground, int background) {
        int fa = (foreground >>> 24) & 0xFF;
        if (fa <= 0) {
            return background;
        }
        if (fa >= 255) {
            return foreground;
        }
        int ba = (background >>> 24) & 0xFF;
        float srcAlpha = fa / 255.0F;
        float dstAlpha = ba / 255.0F;
        float outAlpha = srcAlpha + dstAlpha * (1.0F - srcAlpha);
        if (outAlpha <= 0.0F) {
            return 0;
        }
        int fr = (foreground >>> 16) & 0xFF;
        int fg = (foreground >>> 8) & 0xFF;
        int fb = foreground & 0xFF;
        int br = (background >>> 16) & 0xFF;
        int bg = (background >>> 8) & 0xFF;
        int bb = background & 0xFF;
        int r = Math.min(255, Math.round((fr * srcAlpha + br * dstAlpha * (1.0F - srcAlpha)) / outAlpha));
        int g = Math.min(255, Math.round((fg * srcAlpha + bg * dstAlpha * (1.0F - srcAlpha)) / outAlpha));
        int b = Math.min(255, Math.round((fb * srcAlpha + bb * dstAlpha * (1.0F - srcAlpha)) / outAlpha));
        int a = Math.min(255, Math.round(outAlpha * 255.0F));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int mix(int left, int right, double factor) {
        double t = Math.max(0.0D, Math.min(1.0D, factor));
        int la = (left >>> 24) & 0xFF;
        int lr = (left >>> 16) & 0xFF;
        int lg = (left >>> 8) & 0xFF;
        int lb = left & 0xFF;
        int ra = (right >>> 24) & 0xFF;
        int rr = (right >>> 16) & 0xFF;
        int rg = (right >>> 8) & 0xFF;
        int rb = right & 0xFF;
        int a = (int) Math.round(la * (1.0D - t) + ra * t);
        int r = (int) Math.round(lr * (1.0D - t) + rr * t);
        int g = (int) Math.round(lg * (1.0D - t) + rg * t);
        int b = (int) Math.round(lb * (1.0D - t) + rb * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int brighten(int rgba, int amount) {
        int a = (rgba >>> 24) & 0xFF;
        int r = Math.min(255, ((rgba >>> 16) & 0xFF) + amount);
        int g = Math.min(255, ((rgba >>> 8) & 0xFF) + amount);
        int b = Math.min(255, (rgba & 0xFF) + amount);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static NativeImage tryLoadBannerBaseTemplate() {
        byte[] bytes = loadBannerBaseBytes();
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            return NativeImage.read(input);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static byte[] loadBannerBaseBytes() {
        if (bannerBaseBytes != null) {
            return bannerBaseBytes;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return null;
        }
        Optional<Resource> resource = minecraft.getResourceManager().getResource(BANNER_BASE_TEXTURE);
        if (resource.isEmpty()) {
            return null;
        }
        try (InputStream input = resource.get().open()) {
            bannerBaseBytes = input.readAllBytes();
            return bannerBaseBytes;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static void invalidateFlag(String flagId) {
        Iterator<String> iterator = CACHE.keySet().iterator();
        while (iterator.hasNext()) {
            String key = iterator.next();
            if (key.startsWith(flagId + "|")) {
                iterator.remove();
            }
        }
    }

    private static NativeImage tryLoadSyncedFlagImage(String flagId) {
        byte[] bytes = SYNCED_BYTES.get(flagId);
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            return NativeImage.read(input);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static NativeImage tryLoadFlagImage(String flagId) {
        if (flagId == null || flagId.isBlank() || "none".equalsIgnoreCase(flagId)) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            return null;
        }
        Path path = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("sailboatmod_flags").resolve(flagId + ".png");
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return null;
        }
        try (InputStream input = Files.newInputStream(path)) {
            return NativeImage.read(input);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static NativeImage buildFallback(int width, int height, int primaryColor, int secondaryColor) {
        NativeImage image = new NativeImage(width, height, true);
        int primary = 0xFF000000 | (primaryColor & 0x00FFFFFF);
        int secondary = 0xFF000000 | (secondaryColor & 0x00FFFFFF);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean stripe = x < Math.max(1, width / 3) || (x >= Math.max(1, (width * 2) / 3) && y < Math.max(1, height / 2));
                image.setPixelRGBA(x, y, stripe ? primary : secondary);
            }
        }
        return image;
    }

    private static final class UploadAssembly {
        private final int chunkCount;
        private final byte[][] parts;
        private int received;

        private UploadAssembly(int chunkCount) {
            this.chunkCount = chunkCount;
            this.parts = new byte[chunkCount][];
            this.received = 0;
        }

        private boolean isComplete() {
            if (received < chunkCount) {
                return false;
            }
            for (byte[] part : parts) {
                if (part == null) {
                    return false;
                }
            }
            return true;
        }
    }

    public static void clearCache() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            for (ResourceLocation textureId : CACHE.values()) {
                minecraft.getTextureManager().release(textureId);
            }
        }
        CACHE.clear();
        SYNCED_BYTES.clear();
        FLAG_VERSIONS.clear();
        PENDING.clear();
        bannerBaseBytes = null;
    }

    private NationFlagTextureCache() {
    }
}
