package com.monpai.sailboatmod.network.packet.roadplanner;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Constructor;
import static org.junit.jupiter.api.Assertions.*;

class SharedMapTerrainLodPacketTest {
    @Test void requestCarriesItsIndependentSamplingGrid() throws Exception {
        Class<?> type = SharedMapTerrainRequestPacket.class;
        Object request = type.getConstructor(String.class, String.class, int.class, int.class, int.class)
                .newInstance("earth", "minecraft:overworld", -2, 1, 65536);
        FriendlyByteBuf bytes = new FriendlyByteBuf(Unpooled.buffer());
        try {
            type.getMethod("encode", type, FriendlyByteBuf.class).invoke(null, request, bytes);
            Object decoded = type.getMethod("decode", FriendlyByteBuf.class).invoke(null, bytes);
            assertEquals(65536, type.getMethod("blocksPerPixel").invoke(decoded));
        } finally { bytes.release(); }
    }
    @Test void coarseActualPixelAndPredictionHaveTheSameGridWithoutClaimingAFullChunk() throws Exception {
        Class<?> type = SharedMapTerrainPatchPacket.class;
        Constructor<?> constructor = type.getConstructor(String.class, String.class, int.class, int.class,
                int.class, int.class, int.class, int.class, int[].class, boolean.class, int.class);
        Object actual = constructor.newInstance("earth", "minecraft:overworld", -1, 2, 40, 80, 1, 1, new int[]{0xFF123456}, false, 65536);
        FriendlyByteBuf bytes = new FriendlyByteBuf(Unpooled.buffer());
        try {
            type.getMethod("encode", type, FriendlyByteBuf.class).invoke(null, actual, bytes);
            Object decoded = type.getMethod("decode", FriendlyByteBuf.class).invoke(null, bytes);
            assertEquals(65536, type.getMethod("blocksPerPixel").invoke(decoded));
            assertEquals(1, type.getMethod("width").invoke(decoded));
            assertEquals(false, type.getMethod("prediction").invoke(decoded));
        } finally { bytes.release(); }
    }
}
