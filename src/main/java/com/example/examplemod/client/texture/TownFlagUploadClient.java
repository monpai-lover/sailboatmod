package com.example.examplemod.client.texture;

import com.example.examplemod.nation.service.NationFlagStorage;
import com.example.examplemod.nation.service.TownFlagUploadService;
import com.example.examplemod.network.ModNetwork;
import com.example.examplemod.network.packet.UploadTownFlagChunkPacket;
import net.minecraft.network.chat.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class TownFlagUploadClient {
    public static Component uploadFromPath(String townId, String rawPath) {
        String normalizedTownId = townId == null ? "" : townId.trim();
        if (normalizedTownId.isBlank()) {
            return Component.translatable("screen.sailboatmod.town.overview.none");
        }
        String normalizedPath = rawPath == null ? "" : rawPath.trim();
        if (normalizedPath.isBlank()) {
            return Component.translatable("screen.sailboatmod.nation.upload.path_missing");
        }
        try {
            UploadSource source = isRemoteSource(normalizedPath) ? readRemoteSource(normalizedPath) : readLocalSource(normalizedPath);
            return queueUpload(normalizedTownId, source.displayName(), source.bytes());
        } catch (IllegalArgumentException e) {
            return Component.translatable("command.sailboatmod.nation.flag.path_invalid", normalizedPath);
        } catch (IOException e) {
            return Component.translatable("screen.sailboatmod.nation.upload.read_failed", e.getMessage());
        }
    }

    private static boolean isRemoteSource(String input) {
        return input.regionMatches(true, 0, "http://", 0, 7)
                || input.regionMatches(true, 0, "https://", 0, 8);
    }

    private static UploadSource readLocalSource(String rawPath) throws IOException {
        Path path = Path.of(rawPath);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Invalid file path");
        }
        byte[] bytes = Files.readAllBytes(path);
        String fileName = path.getFileName() == null ? rawPath : path.getFileName().toString();
        return new UploadSource(fileName, bytes);
    }

    private static UploadSource readRemoteSource(String rawUrl) throws IOException {
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid URL", e);
        }
        URLConnection connection = uri.toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "SailboatMod-TownFlagUploader/1.1.3");
        if (connection instanceof HttpURLConnection http) {
            http.setInstanceFollowRedirects(true);
            int responseCode = http.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("HTTP " + responseCode);
            }
        }
        long contentLength = connection.getContentLengthLong();
        if (contentLength > NationFlagStorage.maxUploadBytes()) {
            throw new IOException("Image exceeds upload limit");
        }
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > NationFlagStorage.maxUploadBytes()) {
                    throw new IOException("Image exceeds upload limit");
                }
                output.write(buffer, 0, read);
            }
            String displayName = uri.getPath() == null || uri.getPath().isBlank() ? rawUrl : uri.getPath();
            int slashIndex = Math.max(displayName.lastIndexOf('/'), displayName.lastIndexOf('\\'));
            if (slashIndex >= 0 && slashIndex + 1 < displayName.length()) {
                displayName = displayName.substring(slashIndex + 1);
            }
            return new UploadSource(displayName.isBlank() ? rawUrl : displayName, output.toByteArray());
        }
    }

    private static Component queueUpload(String townId, String displayName, byte[] bytes) {
        if (bytes.length <= 0 || bytes.length > NationFlagStorage.maxUploadBytes()) {
            return Component.translatable("screen.sailboatmod.nation.upload.too_large", NationFlagStorage.maxUploadBytes());
        }
        String uploadId = UUID.randomUUID().toString();
        int chunkCount = Math.max(1, (bytes.length + TownFlagUploadService.CHUNK_SIZE - 1) / TownFlagUploadService.CHUNK_SIZE);
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            int start = chunkIndex * TownFlagUploadService.CHUNK_SIZE;
            int length = Math.min(TownFlagUploadService.CHUNK_SIZE, bytes.length - start);
            byte[] slice = new byte[length];
            System.arraycopy(bytes, start, slice, 0, length);
            ModNetwork.CHANNEL.sendToServer(new UploadTownFlagChunkPacket(townId, uploadId, bytes.length, chunkIndex, chunkCount, slice));
        }
        return Component.translatable("screen.sailboatmod.nation.upload.queued", displayName, bytes.length, chunkCount);
    }

    private record UploadSource(String displayName, byte[] bytes) {
    }

    private TownFlagUploadClient() {
    }
}