package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapLod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class RoadPlannerPacketCodec {
    private RoadPlannerPacketCodec() {
    }

    static void writeUuid(FriendlyByteBuf buffer, UUID value) {
        buffer.writeUUID(value == null ? new UUID(0L, 0L) : value);
    }

    static UUID readUuid(FriendlyByteBuf buffer) {
        return buffer.readUUID();
    }

    static void writeString(FriendlyByteBuf buffer, String value, int maxLength) {
        String safe = value == null ? "" : value.trim();
        buffer.writeUtf(safe.length() <= maxLength ? safe : safe.substring(0, maxLength), maxLength);
    }

    static void writeBlockPosList(FriendlyByteBuf buffer, List<BlockPos> positions) {
        List<BlockPos> safePositions = positions == null ? List.of() : positions;
        buffer.writeVarInt(safePositions.size());
        for (BlockPos pos : safePositions) {
            buffer.writeBlockPos(pos);
        }
    }

    static List<BlockPos> readBlockPosList(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        List<BlockPos> positions = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            positions.add(buffer.readBlockPos());
        }
        return List.copyOf(positions);
    }

    static void writeLod(FriendlyByteBuf buffer, MapLod lod) {
        buffer.writeEnum(lod == null ? MapLod.LOD_4 : lod);
    }

    static MapLod readLod(FriendlyByteBuf buffer) {
        return buffer.readEnum(MapLod.class);
    }
}
