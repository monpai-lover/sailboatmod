package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.ConstructionGhostClientHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class SyncConstructionGhostPreviewPacket {
    public record GhostBlock(BlockPos pos, BlockState state) {
    }

    public record BuildingEntry(String jobId,
                                String structureId,
                                BlockPos origin,
                                List<GhostBlock> blocks,
                                BlockPos targetPos,
                                int progressPercent,
                                int activeWorkers) {
    }

    public record RoadEntry(String jobId,
                            String roadId,
                            String sourceTownName,
                            String targetTownName,
                            List<GhostBlock> blocks,
                            BlockPos targetPos,
                            int progressPercent,
                            int activeWorkers) {
    }

    private final List<BuildingEntry> buildings;
    private final List<RoadEntry> roads;

    public SyncConstructionGhostPreviewPacket(List<BuildingEntry> buildings, List<RoadEntry> roads) {
        this.buildings = buildings == null ? List.of() : List.copyOf(buildings);
        this.roads = roads == null ? List.of() : List.copyOf(roads);
    }

    public static void encode(SyncConstructionGhostPreviewPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.buildings.size());
        for (BuildingEntry entry : msg.buildings) {
            buf.writeUtf(entry.jobId(), ConstructionPacketStringLimits.MAX_JOB_ID_LENGTH);
            buf.writeUtf(entry.structureId(), 64);
            buf.writeBlockPos(entry.origin());
            writeGhostBlocks(buf, entry.blocks());
            writeOptionalPos(buf, entry.targetPos());
            buf.writeVarInt(entry.progressPercent());
            buf.writeVarInt(entry.activeWorkers());
        }

        buf.writeVarInt(msg.roads.size());
        for (RoadEntry entry : msg.roads) {
            buf.writeUtf(entry.jobId(), ConstructionPacketStringLimits.MAX_JOB_ID_LENGTH);
            buf.writeUtf(entry.roadId(), ConstructionPacketStringLimits.MAX_ROAD_ID_LENGTH);
            buf.writeUtf(entry.sourceTownName(), 64);
            buf.writeUtf(entry.targetTownName(), 64);
            writeGhostBlocks(buf, entry.blocks());
            writeOptionalPos(buf, entry.targetPos());
            buf.writeVarInt(entry.progressPercent());
            buf.writeVarInt(entry.activeWorkers());
        }
    }

    public static SyncConstructionGhostPreviewPacket decode(FriendlyByteBuf buf) {
        int buildingCount = buf.readVarInt();
        List<BuildingEntry> buildings = new ArrayList<>(buildingCount);
        for (int i = 0; i < buildingCount; i++) {
            buildings.add(new BuildingEntry(
                    buf.readUtf(ConstructionPacketStringLimits.MAX_JOB_ID_LENGTH),
                    buf.readUtf(64),
                    buf.readBlockPos(),
                    readGhostBlocks(buf),
                    readOptionalPos(buf),
                    buf.readVarInt(),
                    buf.readVarInt()
            ));
        }

        int roadCount = buf.readVarInt();
        List<RoadEntry> roads = new ArrayList<>(roadCount);
        for (int i = 0; i < roadCount; i++) {
            roads.add(new RoadEntry(
                    buf.readUtf(ConstructionPacketStringLimits.MAX_JOB_ID_LENGTH),
                    buf.readUtf(ConstructionPacketStringLimits.MAX_ROAD_ID_LENGTH),
                    buf.readUtf(64),
                    buf.readUtf(64),
                    readGhostBlocks(buf),
                    readOptionalPos(buf),
                    buf.readVarInt(),
                    buf.readVarInt()
            ));
        }

        return new SyncConstructionGhostPreviewPacket(buildings, roads);
    }

    public static void handle(SyncConstructionGhostPreviewPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient(msg)));
        ctx.get().setPacketHandled(true);
    }

    @net.minecraftforge.api.distmarker.OnlyIn(Dist.CLIENT)
    private static void handleClient(SyncConstructionGhostPreviewPacket msg) {
        List<ConstructionGhostClientHooks.BuildingPreview> buildings = msg.buildings.stream()
                .map(entry -> new ConstructionGhostClientHooks.BuildingPreview(
                        entry.jobId(),
                        entry.structureId(),
                        entry.origin(),
                        entry.blocks().stream()
                                .map(block -> new ConstructionGhostClientHooks.GhostBlock(block.pos(), block.state()))
                                .toList(),
                        entry.targetPos(),
                        entry.progressPercent(),
                        entry.activeWorkers()))
                .toList();

        List<ConstructionGhostClientHooks.RoadPreview> roads = msg.roads.stream()
                .map(entry -> new ConstructionGhostClientHooks.RoadPreview(
                        entry.jobId(),
                        entry.roadId(),
                        entry.sourceTownName(),
                        entry.targetTownName(),
                        entry.blocks().stream()
                                .map(block -> new ConstructionGhostClientHooks.GhostBlock(block.pos(), block.state()))
                                .toList(),
                        entry.targetPos(),
                        entry.progressPercent(),
                        entry.activeWorkers()))
                .toList();

        ConstructionGhostClientHooks.update(buildings, roads);
    }

    private static void writeGhostBlocks(FriendlyByteBuf buf, List<GhostBlock> blocks) {
        List<GhostBlock> safeBlocks = blocks == null ? List.of() : blocks;
        buf.writeVarInt(safeBlocks.size());
        for (GhostBlock block : safeBlocks) {
            buf.writeBlockPos(block.pos());
            buf.writeVarInt(Block.getId(block.state()));
        }
    }

    private static List<GhostBlock> readGhostBlocks(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<GhostBlock> blocks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            blocks.add(new GhostBlock(buf.readBlockPos(), Block.stateById(buf.readVarInt())));
        }
        return List.copyOf(blocks);
    }

    private static void writeOptionalPos(FriendlyByteBuf buf, BlockPos pos) {
        buf.writeBoolean(pos != null);
        if (pos != null) {
            buf.writeBlockPos(pos);
        }
    }

    private static BlockPos readOptionalPos(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readBlockPos() : null;
    }
}
