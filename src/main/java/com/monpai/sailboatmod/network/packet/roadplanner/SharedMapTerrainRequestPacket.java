package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.network.NetworkEvent;
import com.monpai.sailboatmod.roadplanner.service.SharedMapTerrainService;
import com.monpai.sailboatmod.roadplanner.map.SharedMapTileGrid;
import net.minecraft.network.FriendlyByteBuf;

import java.util.function.Supplier;

/** A map view requests existing terrain and an optional Tellus underlay; this never requests world generation. */
public record SharedMapTerrainRequestPacket(String worldId, String dimensionId, int tileX, int tileZ, int blocksPerPixel) {
    public SharedMapTerrainRequestPacket {
        if (!SharedMapTileGrid.validBps(blocksPerPixel)) throw new IllegalArgumentException("Invalid shared-map grid");
    }
    public SharedMapTerrainRequestPacket(String worldId, String dimensionId, int tileX, int tileZ) {
        this(worldId, dimensionId, tileX, tileZ, 1);
    }
    public static void encode(SharedMapTerrainRequestPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeString(buffer, packet.worldId, 128);
        RoadPlannerPacketCodec.writeString(buffer, packet.dimensionId, 128);
        buffer.writeInt(packet.tileX);
        buffer.writeInt(packet.tileZ);
        buffer.writeVarInt(packet.blocksPerPixel);
    }

    public static SharedMapTerrainRequestPacket decode(FriendlyByteBuf buffer) {
        return new SharedMapTerrainRequestPacket(buffer.readUtf(128), buffer.readUtf(128), buffer.readInt(), buffer.readInt(), buffer.readVarInt());
    }

    public static void handle(SharedMapTerrainRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (context.getSender() != null) SharedMapTerrainService.global().request(context.getSender(), packet);
        });
        context.setPacketHandled(true);
    }
}
