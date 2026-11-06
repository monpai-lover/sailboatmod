package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.map.SharedMapClientState;
import com.monpai.sailboatmod.roadplanner.map.SharedMapTileGrid;
import com.monpai.sailboatmod.network.NetworkEvent;
import com.monpai.sailboatmod.util.ClientSideExecutor;
import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;

import java.util.Arrays;
import java.util.function.Supplier;

/** Bounded source-tagged patches. Prediction tiles travel as sixteen independent row strips. */
public record SharedMapTerrainPatchPacket(String worldId, String dimensionId, int tileX, int tileZ,
                                          int offsetX, int offsetZ, int width, int height, int[] pixels, boolean prediction, int blocksPerPixel) {
    public SharedMapTerrainPatchPacket(String worldId, String dimensionId, int tileX, int tileZ, int offsetX, int offsetZ,
                                        int width, int height, int[] pixels, boolean prediction) {
        this(worldId, dimensionId, tileX, tileZ, offsetX, offsetZ, width, height, pixels, prediction, 1);
    }
    public static final int MAX_PIXELS = 4096;
    public static final int PREDICTION_ROWS = MAX_PIXELS / 256;

    public SharedMapTerrainPatchPacket {
        validate(worldId, dimensionId, tileX, tileZ, offsetX, offsetZ, width, height,
                pixels == null ? -1 : pixels.length, prediction, blocksPerPixel);
        // Preserve defensive ownership, but never copy more than 16 KiB per packet.
        pixels = Arrays.copyOf(pixels, pixels.length);
    }

    private static void validate(String world, String dimension, int tileX, int tileZ, int x, int z,
                                 int width, int height, int count, boolean prediction, int bps) {
        if (world == null || world.isBlank() || world.length() > 128
                || dimension == null || dimension.isBlank() || dimension.length() > 128
                || x < 0 || z < 0 || width < 1 || height < 1
                || x + (long) width > 256 || z + (long) height > 256
                || count < 1 || count > MAX_PIXELS || (long) width * height != count
                || !SharedMapTileGrid.validTile(tileX, tileZ, bps)
                || prediction && (x != 0 || width != 256 || height != PREDICTION_ROWS || z % PREDICTION_ROWS != 0)
                || !prediction && bps == 1 && (width != 16 || height != 16 || x % 16 != 0 || z % 16 != 0)
                || !prediction && bps > 1 && (width > 16 || height > 16)) {
            throw new IllegalArgumentException("Invalid or oversized shared-map terrain patch");
        }
    }

    @Override public int[] pixels() { return Arrays.copyOf(pixels, pixels.length); }

    public static void encode(SharedMapTerrainPatchPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeString(buffer, packet.worldId, 128);
        RoadPlannerPacketCodec.writeString(buffer, packet.dimensionId, 128);
        buffer.writeInt(packet.tileX);
        buffer.writeInt(packet.tileZ);
        buffer.writeVarInt(packet.offsetX);
        buffer.writeVarInt(packet.offsetZ);
        buffer.writeVarInt(packet.width);
        buffer.writeVarInt(packet.height);
        buffer.writeBoolean(packet.prediction);
        buffer.writeVarInt(packet.blocksPerPixel);
        buffer.writeVarInt(packet.pixels.length);
        for (int color : packet.pixels) buffer.writeInt(color);
    }

    public static SharedMapTerrainPatchPacket decode(FriendlyByteBuf buffer) {
        String world = buffer.readUtf(128);
        String dimension = buffer.readUtf(128);
        int tileX = buffer.readInt();
        int tileZ = buffer.readInt();
        int x = buffer.readVarInt();
        int z = buffer.readVarInt();
        int width = buffer.readVarInt();
        int height = buffer.readVarInt();
        boolean prediction = buffer.readBoolean();
        int blocksPerPixel = buffer.readVarInt();
        int count = buffer.readVarInt();
        // Validate geometry and count before allocating or reading the pixel array.
        validate(world, dimension, tileX, tileZ, x, z, width, height, count, prediction, blocksPerPixel);
        if (count > buffer.readableBytes() / Integer.BYTES) {
            throw new IllegalArgumentException("Truncated shared-map pixels");
        }
        int[] pixels = new int[count];
        for (int i = 0; i < count; i++) pixels[i] = buffer.readInt();
        return new SharedMapTerrainPatchPacket(world, dimension, tileX, tileZ, x, z, width, height, pixels, prediction, blocksPerPixel);
    }

    public static void handle(SharedMapTerrainPatchPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ClientSideExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                SharedMapClientState.defaultState().applyTerrainPatch(packet.worldId, packet.dimensionId, packet.tileX,
                        packet.tileZ, packet.offsetX, packet.offsetZ, packet.width, packet.height, packet.pixels, packet.prediction, packet.blocksPerPixel)));
        context.setPacketHandled(true);
    }
}
