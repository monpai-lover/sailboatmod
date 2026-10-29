package com.monpai.sailboatmod.network.packet.roadplanner;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SharedMapTerrainPacketTest {
    @Test
    void actualChunkPatchAndPredictionTileRoundTripWithDistinctSource() throws Exception {
        Class<?> type = type();
        for (boolean prediction : new boolean[]{false, true}) {
            int size = prediction ? 256 : 16;
            int offset = prediction ? 0 : 240;
            int[] pixels = new int[size * size];
            Arrays.fill(pixels, 0xFF336699);
            Object packet = constructor(type).newInstance("earth", "minecraft:overworld", -1, -2,
                    offset, offset, size, size, pixels, prediction);
            FriendlyByteBuf bytes = new FriendlyByteBuf(Unpooled.buffer());
            try {
                type.getMethod("encode", type, FriendlyByteBuf.class).invoke(null, packet, bytes);
                Object decoded = type.getMethod("decode", FriendlyByteBuf.class).invoke(null, bytes);
                assertEquals(prediction, type.getMethod("prediction").invoke(decoded));
                assertEquals(-1, type.getMethod("tileX").invoke(decoded));
                assertEquals(offset, type.getMethod("offsetX").invoke(decoded));
                assertArrayEquals(pixels, (int[]) type.getMethod("pixels").invoke(decoded));
            } finally {
                bytes.release();
            }
        }
    }

    @Test
    void invalidPatchCannotClaimActualCoverageOutsideItsChunk() throws Exception {
        Constructor<?> constructor = constructor(type());
        InvocationTargetException invalid = assertThrows(InvocationTargetException.class,
                () -> constructor.newInstance("earth", "minecraft:overworld", 0, 0,
                        255, 0, 16, 16, new int[256], false));
        assertInstanceOf(IllegalArgumentException.class, invalid.getCause());
        InvocationTargetException mismatch = assertThrows(InvocationTargetException.class,
                () -> constructor.newInstance("earth", "minecraft:overworld", 0, 0,
                        0, 0, 256, 256, new int[1], true));
        assertInstanceOf(IllegalArgumentException.class, mismatch.getCause());
    }

    private static Constructor<?> constructor(Class<?> type) throws Exception {
        return type.getConstructor(String.class, String.class, int.class, int.class,
                int.class, int.class, int.class, int.class, int[].class, boolean.class);
    }

    private static Class<?> type() {
        try {
            return Class.forName("com.monpai.sailboatmod.network.packet.roadplanner.SharedMapTerrainPatchPacket");
        } catch (ClassNotFoundException missing) {
            return fail("shared maps need bounded source-tagged terrain patches");
        }
    }
}
