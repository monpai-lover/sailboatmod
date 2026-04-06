package com.monpai.sailboatmod.market.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class MarketWebIconService {
    private static final int ICON_SIZE = 64;
    private static final List<String> FLAT_KEYS = List.of("layer0", "layer1", "particle");
    private static final List<String> TOP_KEYS = List.of("top", "up", "end", "all", "particle", "bottom", "side", "front", "north");
    private static final List<String> LEFT_KEYS = List.of("front", "north", "south", "side", "all", "particle", "end");
    private static final List<String> RIGHT_KEYS = List.of("side", "east", "west", "all", "particle", "front", "end");

    private final Map<String, byte[]> iconCache = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> textureCache = new ConcurrentHashMap<>();

    byte[] loadIcon(String commodityKey) {
        if (commodityKey == null || commodityKey.isBlank()) {
            return null;
        }
        return iconCache.computeIfAbsent(commodityKey.trim(), this::loadIconInternal);
    }

    void clearCache() {
        iconCache.clear();
        textureCache.clear();
    }

    private byte[] loadIconInternal(String commodityKey) {
        ResourceLocation itemId = ResourceLocation.tryParse(commodityKey);
        if (itemId == null) {
            return null;
        }

        try {
            BufferedImage icon = resolveIcon(itemId);
            if (icon == null) {
                icon = buildPlaceholderIcon();
            }
            return encodePng(icon);
        } catch (IOException ignored) {
            return null;
        }
    }

    private BufferedImage resolveIcon(ResourceLocation itemId) {
        List<ResolvedModelCandidate> candidates = new ArrayList<>();
        addCandidate(candidates, loadModel(new ModelRef(itemId.getNamespace(), "item", itemId.getPath()), new HashSet<>()), itemId.getNamespace());
        addCandidate(candidates, loadBlockStateModel(itemId), itemId.getNamespace());
        addCandidate(candidates, loadModel(new ModelRef(itemId.getNamespace(), "block", itemId.getPath()), new HashSet<>()), itemId.getNamespace());
        for (ResolvedModelCandidate candidate : candidates) {
            BufferedImage icon = renderResolvedModel(candidate.model(), candidate.fallbackNamespace());
            if (icon != null) {
                return icon;
            }
        }
        return null;
    }

    private void addCandidate(List<ResolvedModelCandidate> candidates, ResolvedModel model, String fallbackNamespace) {
        if (model == null) {
            return;
        }
        for (ResolvedModelCandidate existing : candidates) {
            if (existing.model().textures().equals(model.textures())
                    && existing.model().parentChain().equals(model.parentChain())) {
                return;
            }
        }
        candidates.add(new ResolvedModelCandidate(model, fallbackNamespace));
    }

    private BufferedImage renderResolvedModel(ResolvedModel model, String fallbackNamespace) {
        if (model == null) {
            return null;
        }
        if (isBlockLike(model)) {
            BufferedImage blockIcon = buildBlockLikeIcon(model, fallbackNamespace);
            if (blockIcon != null) {
                return blockIcon;
            }
        }
        return buildFlatIcon(model, fallbackNamespace);
    }

    private boolean isBlockLike(ResolvedModel model) {
        if (model.parentChain().stream().anyMatch(parent ->
                parent.startsWith("minecraft:block/")
                        || parent.startsWith("block/")
                        || parent.contains("/cube")
                        || parent.contains("/orientable")
                        || parent.contains("/column")
                        || parent.contains("glazed_terracotta")
                        || parent.contains("template"))) {
            return true;
        }
        return hasAnyTexture(model, TOP_KEYS) && (hasAnyTexture(model, LEFT_KEYS) || hasAnyTexture(model, RIGHT_KEYS));
    }

    private BufferedImage buildFlatIcon(ResolvedModel model, String fallbackNamespace) {
        String textureRef = firstTextureReference(model, FLAT_KEYS);
        if (textureRef.isBlank()) {
            textureRef = firstTextureReference(model, TOP_KEYS);
        }
        if (textureRef.isBlank()) {
            return null;
        }
        BufferedImage texture = loadTexture(parseTextureRef(textureRef, fallbackNamespace));
        if (texture == null) {
            return null;
        }
        BufferedImage out = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = out.createGraphics();
        configureGraphics(graphics);
        graphics.drawImage(texture, 8, 8, ICON_SIZE - 16, ICON_SIZE - 16, null);
        graphics.dispose();
        return out;
    }

    private BufferedImage buildBlockLikeIcon(ResolvedModel model, String fallbackNamespace) {
        BufferedImage top = loadTexture(parseTextureRef(firstTextureReference(model, TOP_KEYS), fallbackNamespace));
        BufferedImage left = loadTexture(parseTextureRef(firstTextureReference(model, LEFT_KEYS), fallbackNamespace));
        BufferedImage right = loadTexture(parseTextureRef(firstTextureReference(model, RIGHT_KEYS), fallbackNamespace));
        if (top == null && left == null && right == null) {
            return null;
        }
        if (top == null) {
            top = left != null ? left : right;
        }
        if (left == null) {
            left = right != null ? right : top;
        }
        if (right == null) {
            right = left != null ? left : top;
        }

        BufferedImage out = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = out.createGraphics();
        configureGraphics(graphics);

        graphics.setColor(new Color(0, 0, 0, 20));
        graphics.fillOval(16, 43, 32, 10);

        Point leftPoint = new Point(13, 17);
        Point topPoint = new Point(32, 7);
        Point bottomPoint = new Point(32, 28);
        Point rightPoint = new Point(51, 17);
        Point leftDown = new Point(13, 41);
        Point bottomDown = new Point(32, 52);
        Point rightDown = new Point(51, 41);

        drawFace(graphics, tinted(left, 0.82f), leftPoint, bottomPoint, leftDown);
        drawFace(graphics, tinted(right, 0.72f), bottomPoint, rightPoint, bottomDown);
        drawFace(graphics, tinted(top, 1.06f), leftPoint, topPoint, bottomPoint);

        graphics.setColor(new Color(255, 255, 255, 38));
        graphics.drawLine(topPoint.x(), topPoint.y(), rightPoint.x(), rightPoint.y());
        graphics.drawLine(topPoint.x(), topPoint.y(), leftPoint.x(), leftPoint.y());
        graphics.drawLine(topPoint.x(), topPoint.y() + 1, bottomPoint.x(), bottomPoint.y() + 1);
        graphics.setColor(new Color(0, 0, 0, 48));
        graphics.drawLine(leftDown.x(), leftDown.y(), bottomDown.x(), bottomDown.y());
        graphics.drawLine(bottomDown.x(), bottomDown.y(), rightDown.x(), rightDown.y());
        graphics.drawLine(leftPoint.x(), leftPoint.y(), leftDown.x(), leftDown.y());
        graphics.drawLine(rightPoint.x(), rightPoint.y(), rightDown.x(), rightDown.y());
        graphics.dispose();
        return out;
    }

    private void drawFace(Graphics2D graphics, BufferedImage texture, Point origin, Point xAxisPoint, Point yAxisPoint) {
        if (texture == null) {
            return;
        }
        Polygon polygon = new Polygon(
                new int[] {
                        origin.x(),
                        xAxisPoint.x(),
                        xAxisPoint.x() + yAxisPoint.x() - origin.x(),
                        yAxisPoint.x()
                },
                new int[] {
                        origin.y(),
                        xAxisPoint.y(),
                        xAxisPoint.y() + yAxisPoint.y() - origin.y(),
                        yAxisPoint.y()
                },
                4
        );
        Graphics2D faceGraphics = (Graphics2D) graphics.create();
        faceGraphics.setClip(polygon);
        double scaleXx = (xAxisPoint.x() - origin.x()) / (double) texture.getWidth();
        double scaleXy = (xAxisPoint.y() - origin.y()) / (double) texture.getWidth();
        double scaleYx = (yAxisPoint.x() - origin.x()) / (double) texture.getHeight();
        double scaleYy = (yAxisPoint.y() - origin.y()) / (double) texture.getHeight();
        faceGraphics.drawImage(
                texture,
                new java.awt.geom.AffineTransform(scaleXx, scaleXy, scaleYx, scaleYy, origin.x(), origin.y()),
                null
        );
        faceGraphics.dispose();
    }

    private ResolvedModel loadModel(ModelRef ref, Set<String> visited) {
        String visitKey = ref.namespace() + ":" + ref.type() + "/" + ref.path();
        if (!visited.add(visitKey)) {
            return null;
        }

        JsonObject json = readJson("assets/" + ref.namespace() + "/models/" + ref.type() + "/" + ref.path() + ".json");
        if (json == null) {
            return null;
        }

        Map<String, String> mergedTextures = new LinkedHashMap<>();
        List<String> parentChain = new ArrayList<>();
        String parentValue = string(json, "parent");
        if (!parentValue.isBlank()) {
            parentChain.add(parentValue);
            ModelRef parentRef = parseModelRef(parentValue, ref.namespace(), ref.type());
            if (parentRef != null) {
                ResolvedModel parent = loadModel(parentRef, visited);
                if (parent != null) {
                    mergedTextures.putAll(parent.textures());
                    parentChain.addAll(parent.parentChain());
                }
            }
        }

        JsonObject textures = object(json, "textures");
        if (textures != null) {
            for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
                if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                    mergedTextures.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        return new ResolvedModel(mergedTextures, parentChain);
    }

    private ResolvedModel loadBlockStateModel(ResourceLocation itemId) {
        JsonObject blockstate = readJson("assets/" + itemId.getNamespace() + "/blockstates/" + itemId.getPath() + ".json");
        if (blockstate == null) {
            return null;
        }
        ModelRef modelRef = selectBlockStateModel(blockstate, itemId.getNamespace());
        return modelRef == null ? null : loadModel(modelRef, new HashSet<>());
    }

    private ModelRef selectBlockStateModel(JsonObject blockstate, String fallbackNamespace) {
        JsonObject variants = object(blockstate, "variants");
        if (variants != null) {
            JsonElement preferred = variants.get("");
            ModelRef ref = firstModelRef(preferred, fallbackNamespace);
            if (ref != null) {
                return ref;
            }
            for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
                ref = firstModelRef(entry.getValue(), fallbackNamespace);
                if (ref != null) {
                    return ref;
                }
            }
        }

        JsonArray multipart = array(blockstate, "multipart");
        if (multipart != null) {
            for (JsonElement entry : multipart) {
                ModelRef ref = firstModelRef(entry, fallbackNamespace);
                if (ref != null) {
                    return ref;
                }
            }
        }
        return null;
    }

    private ModelRef firstModelRef(JsonElement element, String fallbackNamespace) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                ModelRef ref = firstModelRef(child, fallbackNamespace);
                if (ref != null) {
                    return ref;
                }
            }
            return null;
        }
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        String model = string(object, "model");
        if (!model.isBlank()) {
            return parseModelRef(model, fallbackNamespace, "block");
        }
        JsonElement apply = object.get("apply");
        if (apply != null) {
            return firstModelRef(apply, fallbackNamespace);
        }
        return null;
    }

    private JsonObject readJson(String resourcePath) {
        try (InputStream input = resource(resourcePath)) {
            if (input == null) {
                return null;
            }
            try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
            }
        } catch (IOException ignored) {
            return null;
        }
    }

    private boolean hasAnyTexture(ResolvedModel model, List<String> keys) {
        return !firstTextureReference(model, keys).isBlank();
    }

    private String firstTextureReference(ResolvedModel model, List<String> keys) {
        for (String key : keys) {
            String resolved = resolveTextureReference(model.textures(), key);
            if (!resolved.isBlank()) {
                return resolved;
            }
        }
        for (String key : model.textures().keySet()) {
            String resolved = resolveTextureReference(model.textures(), key);
            if (!resolved.isBlank()) {
                return resolved;
            }
        }
        return "";
    }

    private String resolveTextureReference(Map<String, String> textures, String key) {
        String current = textures.getOrDefault(key, "");
        Set<String> visited = new HashSet<>();
        while (current.startsWith("#")) {
            String nestedKey = current.substring(1);
            if (nestedKey.isBlank() || !visited.add(nestedKey)) {
                return "";
            }
            current = textures.getOrDefault(nestedKey, "");
        }
        return current == null ? "" : current.trim();
    }

    private ModelRef parseModelRef(String value, String fallbackNamespace, String fallbackType) {
        String raw = value == null ? "" : value.trim();
        if (raw.isBlank()) {
            return null;
        }
        String namespace = fallbackNamespace;
        String path = raw;
        int separator = raw.indexOf(':');
        if (separator >= 0) {
            namespace = raw.substring(0, separator).isBlank() ? fallbackNamespace : raw.substring(0, separator);
            path = raw.substring(separator + 1);
        }
        if (path.startsWith("item/")) {
            return new ModelRef(namespace, "item", path.substring(5));
        }
        if (path.startsWith("block/")) {
            return new ModelRef(namespace, "block", path.substring(6));
        }
        return new ModelRef(namespace, fallbackType, path);
    }

    private TextureRef parseTextureRef(String value, String fallbackNamespace) {
        String raw = value == null ? "" : value.trim();
        if (raw.isBlank() || raw.startsWith("#")) {
            return null;
        }
        String namespace = fallbackNamespace;
        String path = raw;
        int separator = raw.indexOf(':');
        if (separator >= 0) {
            namespace = raw.substring(0, separator).isBlank() ? fallbackNamespace : raw.substring(0, separator);
            path = raw.substring(separator + 1);
        }
        return path.isBlank() ? null : new TextureRef(namespace, path);
    }

    private BufferedImage loadTexture(TextureRef ref) {
        if (ref == null) {
            return null;
        }
        String cacheKey = ref.namespace() + ":" + ref.path();
        return textureCache.computeIfAbsent(cacheKey, ignored -> readTexture(ref));
    }

    private BufferedImage readTexture(TextureRef ref) {
        String resourcePath = "assets/" + ref.namespace() + "/textures/" + ref.path() + ".png";
        try (InputStream input = resource(resourcePath)) {
            return input == null ? null : ImageIO.read(input);
        } catch (IOException ignored) {
            return null;
        }
    }

    private BufferedImage buildPlaceholderIcon() {
        BufferedImage out = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = out.createGraphics();
        configureGraphics(graphics);

        graphics.setColor(new Color(10, 14, 20, 0));
        graphics.fillRect(0, 0, ICON_SIZE, ICON_SIZE);
        graphics.setColor(new Color(0, 0, 0, 20));
        graphics.fillOval(16, 44, 32, 9);

        graphics.setColor(new Color(56, 78, 102, 255));
        graphics.fillPolygon(new int[] {13, 32, 51, 32}, new int[] {17, 7, 17, 28}, 4);
        graphics.setColor(new Color(38, 55, 74, 255));
        graphics.fillPolygon(new int[] {13, 32, 32, 13}, new int[] {17, 28, 52, 41}, 4);
        graphics.setColor(new Color(29, 43, 59, 255));
        graphics.fillPolygon(new int[] {32, 51, 51, 32}, new int[] {28, 17, 41, 52}, 4);

        graphics.setColor(new Color(255, 255, 255, 38));
        graphics.drawLine(32, 7, 51, 17);
        graphics.drawLine(32, 7, 13, 17);
        graphics.drawLine(32, 28, 32, 51);
        graphics.setColor(new Color(120, 154, 191, 255));
        graphics.fillRect(29, 21, 6, 16);
        graphics.fillRect(24, 26, 16, 6);
        graphics.dispose();
        return out;
    }

    private BufferedImage tinted(BufferedImage source, float brightness) {
        if (source == null) {
            return null;
        }
        BufferedImage tinted = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int argb = source.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha == 0) {
                    continue;
                }
                int red = Math.min(255, Math.round(((argb >>> 16) & 0xFF) * brightness));
                int green = Math.min(255, Math.round(((argb >>> 8) & 0xFF) * brightness));
                int blue = Math.min(255, Math.round((argb & 0xFF) * brightness));
                tinted.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
            }
        }
        return tinted;
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }

    private static void configureGraphics(Graphics2D graphics) {
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        graphics.setBackground(new Color(0, 0, 0, 0));
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement element = parent == null ? null : parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject parent, String key) {
        JsonElement element = parent == null ? null : parent.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static String string(JsonObject parent, String key) {
        JsonElement element = parent == null ? null : parent.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static InputStream resource(String path) {
        return MarketWebIconService.class.getClassLoader().getResourceAsStream(path);
    }

    private record ModelRef(String namespace, String type, String path) {
    }

    private record TextureRef(String namespace, String path) {
    }

    private record Point(int x, int y) {
    }

    private record ResolvedModelCandidate(ResolvedModel model, String fallbackNamespace) {
    }

    private record ResolvedModel(Map<String, String> textures, List<String> parentChain) {
        private ResolvedModel(Map<String, String> textures, List<String> parentChain) {
            this.textures = new LinkedHashMap<>(textures);
            this.parentChain = List.copyOf(parentChain);
        }
    }
}
