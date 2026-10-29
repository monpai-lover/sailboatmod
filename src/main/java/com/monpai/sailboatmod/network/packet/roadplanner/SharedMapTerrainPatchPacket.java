package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.map.SharedMapClientState;
import com.monpai.sailboatmod.roadplanner.map.SharedMapTileGrid;
import com.monpai.sailboatmod.network.NetworkEvent;
import com.monpai.sailboatmod.util.ClientSideExecutor;
import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;

import java.util.Arrays;
import java.util.function.Supplier;

/** Prediction is a separate full native tile; an actual update is one compact chunk, with explicit provenance. */
public record SharedMapTerrainPatchPacket(String worldId, String dimensionId, int tileX, int tileZ,
                                          int offsetX, int offsetZ, int width, int height, int[] pixels, boolean prediction, int blocksPerPixel) {
    public SharedMapTerrainPatchPacket(String worldId, String dimensionId, int tileX, int tileZ, int offsetX, int offsetZ,
                                        int width, int height, int[] pixels, boolean prediction) {
        this(worldId, dimensionId, tileX, tileZ, offsetX, offsetZ, width, height, pixels, prediction, 1);
    }
    public SharedMapTerrainPatchPacket {
        if (worldId == null || dimensionId == null || offsetX < 0 || offsetZ < 0
                || width < 1 || height < 1 || offsetX + (long) width > 256 || offsetZ + (long) height > 256
                || pixels == null || (long) width * height != pixels.length
                || prediction && (offsetX != 0 || offsetZ != 0 || width != 256 || height != 256)
                || !SharedMapTileGrid.validBps(blocksPerPixel)
                || !prediction && blocksPerPixel == 1 && (width != 16 || height != 16 || offsetX % 16 != 0 || offsetZ % 16 != 0)
                || !prediction && blocksPerPixel > 1 && (width > 16 || height > 16)) {
            throw new IllegalArgumentException("Invalid shared-map terrain patch");
        }
        pixels = Arrays.copyOf(pixels, pixels.length);
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
        if (count < 0 || count > 256 * 256 || count > buffer.readableBytes() / Integer.BYTES) {
            throw new IllegalArgumentException("Invalid shared-map pixel count");
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
