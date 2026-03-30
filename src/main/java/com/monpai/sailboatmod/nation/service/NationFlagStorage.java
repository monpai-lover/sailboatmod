package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationFlagRecord;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;

public final class NationFlagStorage {
    private static final int MAX_WIDTH = 512;
    private static final int MAX_HEIGHT = 512;
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_UPLOAD_BYTES = 8 * 1024 * 1024;
    private static volatile boolean pluginsScanned;

    public static Path flagsDirectory(ServerLevel level) throws IOException {
        Path directory = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve("sailboatmod_flags");
        Files.createDirectories(directory);
        return directory;
    }

    public static NationFlagRecord saveFlag(ServerLevel level, String nationId, String uploadedBy, byte[] imageBytes) throws IOException {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IOException("Empty image data");
        }
        if (imageBytes.length > MAX_UPLOAD_BYTES) {
            throw new IOException("Image exceeds upload limit");
        }

        BufferedImage image = decodeImage(imageBytes);
        BufferedImage processed = scaleToFit(normalizeImage(image), MAX_WIDTH, MAX_HEIGHT);
        byte[] encoded = encodePng(processed);
        if (encoded.length > MAX_BYTES) {
            throw new IOException("Image exceeds maximum size");
        }

        String sha256 = sha256(encoded);
        String flagId = nationId + "_" + sha256.substring(0, 12);
        Path sharedTarget = sharedFlagPath(level, sha256);
        if (!Files.exists(sharedTarget)) {
            Files.write(sharedTarget, encoded);
        }
        migrateLegacyFlag(level, flagId, sha256);
        return new NationFlagRecord(flagId, nationId, sha256, processed.getWidth(), processed.getHeight(), System.currentTimeMillis(), uploadedBy, encoded.length, false);
    }

    public static Path resolveFlagPath(ServerLevel level, String flagId) throws IOException {
        NationFlagRecord record = NationSavedData.get(level).getFlag(flagId);
        if (record != null) {
            return resolveFlagPath(level, record);
        }
        return legacyFlagPath(level, flagId);
    }

    public static Path resolveFlagPath(ServerLevel level, NationFlagRecord record) throws IOException {
        if (record == null) {
            return flagsDirectory(level);
        }
        Path sharedPath = sharedFlagPath(level, record.sha256());
        if (Files.exists(sharedPath) && Files.isRegularFile(sharedPath)) {
            return sharedPath;
        }
        Path legacyPath = legacyFlagPath(level, record.flagId());
        if (Files.exists(legacyPath) && Files.isRegularFile(legacyPath)) {
            return legacyPath;
        }
        return sharedPath;
    }

    public static void deleteFlag(ServerLevel level, NationSavedData data, String flagId) throws IOException {
        if (level == null || flagId == null || flagId.isBlank()) {
            return;
        }
        NationFlagRecord record = data == null ? null : data.getFlag(flagId);
        Path legacyPath = legacyFlagPath(level, flagId);
        Files.deleteIfExists(legacyPath);
        if (record == null || record.sha256().isBlank()) {
            return;
        }
        boolean sharedByAnotherFlag = false;
        if (data != null) {
            for (NationFlagRecord other : data.getFlags()) {
                if (flagId.equals(other.flagId())) {
                    continue;
                }
                if (record.sha256().equals(other.sha256())) {
                    sharedByAnotherFlag = true;
                    break;
                }
            }
        }
        if (!sharedByAnotherFlag) {
            Files.deleteIfExists(sharedFlagPath(level, record.sha256()));
        }
    }

    public static int maxWidth() {
        return MAX_WIDTH;
    }

    public static int maxHeight() {
        return MAX_HEIGHT;
    }

    public static int maxBytes() {
        return MAX_BYTES;
    }

    public static int maxUploadBytes() {
        return MAX_UPLOAD_BYTES;
    }

    private static Path sharedFlagPath(ServerLevel level, String sha256) throws IOException {
        String safeSha = sha256 == null ? "" : sha256.trim().toLowerCase();
        if (safeSha.isBlank()) {
            throw new IOException("Missing flag hash");
        }
        return flagsDirectory(level).resolve(safeSha + ".png");
    }

    private static Path legacyFlagPath(ServerLevel level, String flagId) throws IOException {
        return flagsDirectory(level).resolve(flagId + ".png");
    }

    private static void migrateLegacyFlag(ServerLevel level, String flagId, String sha256) throws IOException {
        Path legacyPath = legacyFlagPath(level, flagId);
        Path sharedPath = sharedFlagPath(level, sha256);
        if (Files.exists(legacyPath) && !Files.exists(sharedPath)) {
            Files.move(legacyPath, sharedPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static BufferedImage decodeImage(byte[] imageBytes) throws IOException {
        ensurePluginsScanned();
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
            if (input == null) {
                throw unsupportedFormat();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw unsupportedFormat();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw unsupportedFormat();
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    private static void ensurePluginsScanned() {
        if (!pluginsScanned) {
            synchronized (NationFlagStorage.class) {
                if (!pluginsScanned) {
                    ImageIO.scanForPlugins();
                    pluginsScanned = true;
                }
            }
        }
    }

    private static IOException unsupportedFormat() {
        return new IOException("Unsupported image format. Supported uploads include PNG, WebP, JPG, and JPEG.");
    }

    private static BufferedImage normalizeImage(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }
        BufferedImage normalized = new BufferedImage(
                Math.max(1, source.getWidth()),
                Math.max(1, source.getHeight()),
                BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D graphics = normalized.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return normalized;
    }

    private static BufferedImage scaleToFit(BufferedImage source, int maxWidth, int maxHeight) {
        int sourceWidth = Math.max(1, source.getWidth());
        int sourceHeight = Math.max(1, source.getHeight());
        double scale = Math.min(1.0D, Math.min(maxWidth / (double) sourceWidth, maxHeight / (double) sourceHeight));
        int targetWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
        int targetHeight = Math.max(1, (int) Math.round(sourceHeight * scale));
        if (targetWidth == sourceWidth && targetHeight == sourceHeight) {
            return source;
        }

        BufferedImage current = source;
        int currentWidth = sourceWidth;
        int currentHeight = sourceHeight;
        while (currentWidth / 2 >= targetWidth && currentHeight / 2 >= targetHeight) {
            currentWidth = Math.max(targetWidth, currentWidth / 2);
            currentHeight = Math.max(targetHeight, currentHeight / 2);
            current = resize(current, currentWidth, currentHeight);
        }
        if (currentWidth != targetWidth || currentHeight != targetHeight) {
            current = resize(current, targetWidth, targetHeight);
        }
        return current;
    }

    private static BufferedImage resize(BufferedImage source, int targetWidth, int targetHeight) {
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("PNG encoder unavailable");
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private NationFlagStorage() {
    }
}
