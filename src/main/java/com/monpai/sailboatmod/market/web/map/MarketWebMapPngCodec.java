package com.monpai.sailboatmod.market.web.map;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;

/** PNG streams stay in memory without changing ImageIO settings shared with other mods. */
final class MarketWebMapPngCodec {
    private MarketWebMapPngCodec() {}

    static byte[] encode(int width, int height, int[] argb) {
        if (width <= 0 || height <= 0 || argb == null || (long) width * height != argb.length) return new byte[0];
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        return encodeImage(image);
    }

    static byte[] encodeImage(BufferedImage image) {
        if (image == null) return new byte[0];
        var writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) return new byte[0];
        var writer = writers.next();
        var bytes = new ByteArrayOutputStream(32 * 1024);
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            // Keep the existing lossless compression defaults and byte-compatible PNG output.
            writer.write(null, new IIOImage(image, null, null), writer.getDefaultWriteParam());
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to encode web map PNG", exception);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    static Optional<int[]> decode(byte[] png, int expectedWidth, int expectedHeight) {
        if (png == null || png.length < 8 || expectedWidth <= 0 || expectedHeight <= 0
                || png[0] != (byte) 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') return Optional.empty();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return Optional.empty();
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                // Reject unexpected dimensions before allocating the decoded raster.
                if (reader.getWidth(0) != expectedWidth || reader.getHeight(0) != expectedHeight) return Optional.empty();
                BufferedImage image = reader.read(0);
                if (image == null) return Optional.empty();
                return Optional.of(image.getRGB(0, 0, expectedWidth, expectedHeight, null, 0, expectedWidth));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }
}
