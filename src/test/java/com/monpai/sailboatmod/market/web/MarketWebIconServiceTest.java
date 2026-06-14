package com.monpai.sailboatmod.market.web;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebIconServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsVanillaIconFromLocalMinecraftAssetIndex() throws Exception {
        Path assetsRoot = tempDir.resolve("assets");
        byte[] model = """
                {"parent":"minecraft:item/generated","textures":{"layer0":"minecraft:item/sailboatmod_test_icon"}}
                """.getBytes(StandardCharsets.UTF_8);
        byte[] texture = pngBytes();
        String modelPath = "assets/minecraft/models/item/sailboatmod_test_icon.json";
        String texturePath = "assets/minecraft/textures/item/sailboatmod_test_icon.png";
        String modelHash = writeIndexedAsset(assetsRoot, model);
        String textureHash = writeIndexedAsset(assetsRoot, texture);

        JsonObject index = new JsonObject();
        JsonObject objects = new JsonObject();
        objects.add(modelPath, indexEntry(modelHash, model.length));
        objects.add(texturePath, indexEntry(textureHash, texture.length));
        index.add("objects", objects);
        Files.createDirectories(assetsRoot.resolve("indexes"));
        Files.writeString(assetsRoot.resolve("indexes").resolve("1.20.1.json"), index.toString(), StandardCharsets.UTF_8);

        String property = "sailboatmod.marketweb.assetsRoot";
        String previous = System.getProperty(property);
        System.setProperty(property, assetsRoot.toString());
        try {
            MarketWebIconService service = new MarketWebIconService();
            byte[] icon = service.loadIcon("minecraft:sailboatmod_test_icon");

            assertNotNull(icon);
            assertTrue(icon.length > 8);
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void rendersBlockItemIconFromBlockModelAndLocalAssetIndex() throws Exception {
        Path assetsRoot = tempDir.resolve("assets");
        byte[] blockModel = """
                {"parent":"minecraft:block/cube_all","textures":{"all":"minecraft:block/sailboatmod_test_block"}}
                """.getBytes(StandardCharsets.UTF_8);
        byte[] cubeAllParent = """
                {"textures":{"particle":"#all"}}
                """.getBytes(StandardCharsets.UTF_8);
        byte[] texture = pngBytes();
        String blockModelPath = "assets/minecraft/models/block/sailboatmod_test_block.json";
        String cubeAllPath = "assets/minecraft/models/block/cube_all.json";
        String texturePath = "assets/minecraft/textures/block/sailboatmod_test_block.png";
        String blockModelHash = writeIndexedAsset(assetsRoot, blockModel);
        String cubeAllHash = writeIndexedAsset(assetsRoot, cubeAllParent);
        String textureHash = writeIndexedAsset(assetsRoot, texture);

        JsonObject index = new JsonObject();
        JsonObject objects = new JsonObject();
        objects.add(blockModelPath, indexEntry(blockModelHash, blockModel.length));
        objects.add(cubeAllPath, indexEntry(cubeAllHash, cubeAllParent.length));
        objects.add(texturePath, indexEntry(textureHash, texture.length));
        index.add("objects", objects);
        Files.createDirectories(assetsRoot.resolve("indexes"));
        Files.writeString(assetsRoot.resolve("indexes").resolve("1.20.1.json"), index.toString(), StandardCharsets.UTF_8);

        String property = "sailboatmod.marketweb.assetsRoot";
        String previous = System.getProperty(property);
        System.setProperty(property, assetsRoot.toString());
        try {
            MarketWebIconService service = new MarketWebIconService();
            byte[] icon = service.loadIcon("minecraft:sailboatmod_test_block");

            assertNotNull(icon);
            assertTrue(icon.length > 8);
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void downloadsVanillaAssetsIntoPersistentCacheAndReusesThem() throws Exception {
        Path cacheRoot = tempDir.resolve("web_assets").resolve("1.20.1");
        String itemId = "sailboatmod_cached_vanilla_block";
        String itemModelPath = "assets/minecraft/models/item/" + itemId + ".json";
        String blockModelPath = "assets/minecraft/models/block/" + itemId + ".json";
        String cubeAllPath = "assets/minecraft/models/block/cube_all.json";
        String texturePath = "assets/minecraft/textures/block/" + itemId + ".png";
        Map<String, byte[]> resources = new LinkedHashMap<>();
        resources.put(itemModelPath, """
                {"parent":"minecraft:block/sailboatmod_cached_vanilla_block"}
                """.getBytes(StandardCharsets.UTF_8));
        resources.put(blockModelPath, """
                {"parent":"minecraft:block/cube_all","textures":{"all":"minecraft:block/sailboatmod_cached_vanilla_block"}}
                """.getBytes(StandardCharsets.UTF_8));
        resources.put(cubeAllPath, """
                {"textures":{"particle":"#all"}}
                """.getBytes(StandardCharsets.UTF_8));
        resources.put(texturePath, pngBytes());

        CountingAssetServer assetServer = CountingAssetServer.start(resources);
        byte[] downloadedIcon;
        try {
            MarketWebIconService service = new MarketWebIconService(cacheRoot, assetServer.baseUrl());
            downloadedIcon = service.loadIcon("minecraft:" + itemId);
        } finally {
            assetServer.stop();
        }

        assertNotNull(downloadedIcon);
        assertTrue(downloadedIcon.length > 8);
        assertTrue(assetServer.requestCount() > 0,
                "initial load should request missing vanilla resources from the external fallback");
        assertTrue(Files.isRegularFile(cacheRoot.resolve(itemModelPath)));
        assertTrue(Files.isRegularFile(cacheRoot.resolve(blockModelPath)));
        assertTrue(Files.isRegularFile(cacheRoot.resolve(texturePath)));

        MarketWebIconService cachedService = new MarketWebIconService(cacheRoot, "http://127.0.0.1:1/");
        byte[] cachedIcon = cachedService.loadIcon("minecraft:" + itemId);

        assertNotNull(cachedIcon);
        assertTrue(cachedIcon.length > 8);
    }

    @Test
    void bundledClasspathVanillaAssetAvoidsExternalDownload() throws Exception {
        CountingAssetServer assetServer = CountingAssetServer.start(Map.of());
        try {
            MarketWebIconService service = new MarketWebIconService(tempDir.resolve("web_assets"), assetServer.baseUrl());
            byte[] icon = service.loadIcon("minecraft:sailboatmod_bundled_entity_icon");

            assertNotNull(icon);
            assertTrue(icon.length > 8);
            assertEquals(0, assetServer.requestCount(),
                    "classpath or jar-bundled vanilla assets should be used before external fallback downloads");
        } finally {
            assetServer.stop();
        }
    }

    private static JsonObject indexEntry(String hash, int size) {
        JsonObject entry = new JsonObject();
        entry.addProperty("hash", hash);
        entry.addProperty("size", size);
        return entry;
    }

    private static byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, new Color(40 + x * 5, 90 + y * 4, 180, 255).getRGB());
            }
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }

    private static String writeIndexedAsset(Path assetsRoot, byte[] bytes) throws Exception {
        String hash = sha1(bytes);
        Path object = assetsRoot.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
        Files.createDirectories(object.getParent());
        Files.write(object, bytes);
        return hash;
    }

    private static String sha1(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(bytes);
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            out.append(String.format("%02x", value & 0xFF));
        }
        return out.toString();
    }

    private static final class CountingAssetServer {
        private final HttpServer server;
        private final AtomicInteger requestCount;

        private CountingAssetServer(HttpServer server, AtomicInteger requestCount) {
            this.server = server;
            this.requestCount = requestCount;
        }

        static CountingAssetServer start(Map<String, byte[]> resources) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicInteger requestCount = new AtomicInteger();
            server.createContext("/", exchange -> handle(exchange, resources, requestCount));
            server.start();
            return new CountingAssetServer(server, requestCount);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        }

        int requestCount() {
            return requestCount.get();
        }

        void stop() {
            server.stop(0);
        }

        private static void handle(HttpExchange exchange, Map<String, byte[]> resources, AtomicInteger requestCount) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String key = path == null || path.isBlank() ? "" : path.substring(1);
            requestCount.incrementAndGet();
            byte[] bytes = resources.get(key);
            if (bytes == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            String contentType = key.endsWith(".png") ? "image/png" : "application/json; charset=utf-8";
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
