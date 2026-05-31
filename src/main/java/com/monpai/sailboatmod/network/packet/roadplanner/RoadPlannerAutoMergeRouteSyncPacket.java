package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.nation.service.RoadPlannerAutoMergeRouteService;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerSharedRoadSpan;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerAutoMergeRouteSyncPacket(UUID sessionId,
                                                  UUID requestId,
                                                  Status status,
                                                  RoadPlannerMergeSelection mergeSelection,
                                                  List<BlockPos> displayPath,
                                                  List<RoadPlannerSharedRoadSpan> sharedSpans,
                                                  List<String> roadIds,
                                                  String message) {
    private static final int MAX_DISPLAY_PATH = 512;
    private static final int MAX_SHARED_SPANS = 64;
    private static final int MAX_ROAD_IDS = 64;

    public RoadPlannerAutoMergeRouteSyncPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        requestId = requestId == null ? new UUID(0L, 0L) : requestId;
        status = status == null ? Status.NOT_FOUND : status;
        mergeSelection = mergeSelection == null ? RoadPlannerMergeSelection.none() : mergeSelection;
        displayPath = displayPath == null ? List.of() : displayPath.stream()
                .filter(Objects::nonNull)
                .limit(MAX_DISPLAY_PATH)
                .map(BlockPos::immutable)
                .toList();
        sharedSpans = sharedSpans == null ? List.of() : sharedSpans.stream()
                .filter(Objects::nonNull)
                .filter(RoadPlannerSharedRoadSpan::present)
                .limit(MAX_SHARED_SPANS)
                .toList();
        roadIds = roadIds == null ? List.of() : roadIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(id -> id.trim().toLowerCase(Locale.ROOT))
                .limit(MAX_ROAD_IDS)
                .toList();
        message = message == null ? "" : message.trim();
    }

    public static RoadPlannerAutoMergeRouteSyncPacket fromResult(UUID sessionId,
                                                                 UUID requestId,
                                                                 RoadPlannerAutoMergeRouteService.Result result) {
        RoadPlannerAutoMergeRouteService.Result safe = result == null
                ? RoadPlannerAutoMergeRouteService.Result.notFound("No route")
                : result;
        return new RoadPlannerAutoMergeRouteSyncPacket(
                sessionId,
                requestId,
                Status.valueOf(safe.status().name()),
                safe.mergeSelection(),
                safe.displayPath(),
                safe.sharedSpans(),
                safe.roadIds(),
                safe.message());
    }

    public static void encode(RoadPlannerAutoMergeRouteSyncPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        RoadPlannerPacketCodec.writeUuid(buffer, packet.requestId());
        buffer.writeEnum(packet.status());
        writeMergeSelection(buffer, packet.mergeSelection());
        RoadPlannerPacketCodec.writeBlockPosList(buffer, packet.displayPath());
        writeSharedSpans(buffer, packet.sharedSpans());
        writeStringList(buffer, packet.roadIds());
        RoadPlannerPacketCodec.writeString(buffer, packet.message(), 256);
    }

    public static RoadPlannerAutoMergeRouteSyncPacket decode(FriendlyByteBuf buffer) {
        return new RoadPlannerAutoMergeRouteSyncPacket(
                RoadPlannerPacketCodec.readUuid(buffer),
                RoadPlannerPacketCodec.readUuid(buffer),
                buffer.readEnum(Status.class),
                readMergeSelection(buffer),
                readCappedBlockPosList(buffer),
                readSharedSpans(buffer),
                readStringList(buffer),
                buffer.readUtf(256));
    }

    public static void handle(RoadPlannerAutoMergeRouteSyncPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                RoadPlannerClientHooks.applyAutoMergeRoute(packet)));
        context.setPacketHandled(true);
    }

    private static void writeMergeSelection(FriendlyByteBuf buffer, RoadPlannerMergeSelection selection) {
        RoadPlannerMergeSelection safe = selection == null ? RoadPlannerMergeSelection.none() : selection;
        RoadPlannerPacketCodec.writeString(buffer, safe.roadId(), 128);
        buffer.writeVarInt(safe.pathIndex());
        buffer.writeBlockPos(safe.anchorPos());
        buffer.writeEnum(safe.scope());
    }

    private static RoadPlannerMergeSelection readMergeSelection(FriendlyByteBuf buffer) {
        return new RoadPlannerMergeSelection(
                buffer.readUtf(128),
                buffer.readVarInt(),
                buffer.readBlockPos(),
                buffer.readEnum(RoadPlannerMergeScope.class));
    }

    private static List<BlockPos> readCappedBlockPosList(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_DISPLAY_PATH) {
            throw new IllegalArgumentException("Auto merge display path count out of bounds: " + count);
        }
        java.util.ArrayList<BlockPos> positions = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            positions.add(buffer.readBlockPos());
        }
        return List.copyOf(positions);
    }

    private static void writeSharedSpans(FriendlyByteBuf buffer, List<RoadPlannerSharedRoadSpan> sharedSpans) {
        List<RoadPlannerSharedRoadSpan> safeSpans = sharedSpans == null ? List.of() : sharedSpans.stream()
                .filter(Objects::nonNull)
                .filter(RoadPlannerSharedRoadSpan::present)
                .limit(MAX_SHARED_SPANS)
                .toList();
        buffer.writeVarInt(safeSpans.size());
        for (RoadPlannerSharedRoadSpan span : safeSpans) {
            RoadPlannerPacketCodec.writeString(buffer, span.roadId(), 128);
            buffer.writeVarInt(span.fromPathIndex());
            buffer.writeVarInt(span.toPathIndex());
            buffer.writeBlockPos(span.fromPos());
            buffer.writeBlockPos(span.toPos());
            buffer.writeEnum(span.scope());
            buffer.writeEnum(span.role());
        }
    }

    private static List<RoadPlannerSharedRoadSpan> readSharedSpans(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_SHARED_SPANS) {
            throw new IllegalArgumentException("Auto merge shared span count out of bounds: " + count);
        }
        java.util.ArrayList<RoadPlannerSharedRoadSpan> spans = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            spans.add(new RoadPlannerSharedRoadSpan(
                    buffer.readUtf(128),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readBlockPos(),
                    buffer.readBlockPos(),
                    buffer.readEnum(RoadPlannerMergeScope.class),
                    buffer.readEnum(RoadPlannerSharedRoadSpan.Role.class)));
        }
        return List.copyOf(spans);
    }

    private static void writeStringList(FriendlyByteBuf buffer, List<String> values) {
        List<String> safeValues = values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .limit(MAX_ROAD_IDS)
                .toList();
        buffer.writeVarInt(safeValues.size());
        for (String value : safeValues) {
            RoadPlannerPacketCodec.writeString(buffer, value, 128);
        }
    }

    private static List<String> readStringList(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_ROAD_IDS) {
            throw new IllegalArgumentException("Auto merge road id count out of bounds: " + count);
        }
        java.util.ArrayList<String> values = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(buffer.readUtf(128));
        }
        return List.copyOf(values);
    }

    public enum Status {
        FOUND,
        NOT_FOUND,
        INVALID_REQUEST,
        SCOPE_BLOCKED
    }
}
