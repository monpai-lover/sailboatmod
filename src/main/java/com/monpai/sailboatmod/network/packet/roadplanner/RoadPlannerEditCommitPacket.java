package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.roadplanner.edit.RoadEditBlockPlacement;
import com.monpai.sailboatmod.roadplanner.edit.RoadEditCommitService;
import com.monpai.sailboatmod.roadplanner.edit.RoadEditPermissionService;
import com.monpai.sailboatmod.roadplanner.edit.RoadEditTaskService;
import com.monpai.sailboatmod.roadplanner.edit.RoadEditableMigrationService;
import com.monpai.sailboatmod.roadplanner.edit.RoadEditableNetworkSavedData;
import com.monpai.sailboatmod.roadplanner.edit.RoadEditableNode;
import com.monpai.sailboatmod.roadplanner.edit.RoadEditableRecord;
import com.monpai.sailboatmod.roadplanner.edit.RoadEditableSegment;
import com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildControlService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerEditCommitPacket(UUID sessionId,
                                          String roadId,
                                          List<BlockPos> nodes,
                                          List<RoadPlannerSegmentType> segmentTypes,
                                          RoadPlannerBuildSettings settings) {
    public RoadPlannerEditCommitPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        roadId = normalizeRoadId(roadId);
        nodes = nodes == null ? List.of() : nodes.stream()
                .filter(java.util.Objects::nonNull)
                .map(BlockPos::immutable)
                .toList();
        segmentTypes = normalizeSegments(segmentTypes, nodes.size());
        settings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
    }

    public static void encode(RoadPlannerEditCommitPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        RoadPlannerPacketCodec.writeString(buffer, packet.roadId(), 128);
        RoadPlannerPacketCodec.writeBlockPosList(buffer, packet.nodes());
        buffer.writeVarInt(packet.segmentTypes().size());
        for (RoadPlannerSegmentType type : packet.segmentTypes()) {
            buffer.writeEnum(type == null ? RoadPlannerSegmentType.ROAD : type);
        }
        buffer.writeVarInt(packet.settings().width());
        RoadPlannerPacketCodec.writeString(buffer, packet.settings().materialPreset(), 32);
        buffer.writeBoolean(packet.settings().streetlightsEnabled());
    }

    public static RoadPlannerEditCommitPacket decode(FriendlyByteBuf buffer) {
        UUID sessionId = RoadPlannerPacketCodec.readUuid(buffer);
        String roadId = buffer.readUtf(128);
        List<BlockPos> nodes = RoadPlannerPacketCodec.readBlockPosList(buffer);
        int segmentCount = Math.max(0, buffer.readVarInt());
        List<RoadPlannerSegmentType> segmentTypes = new ArrayList<>(segmentCount);
        for (int index = 0; index < segmentCount; index++) {
            segmentTypes.add(buffer.readEnum(RoadPlannerSegmentType.class));
        }
        RoadPlannerBuildSettings settings = new RoadPlannerBuildSettings(buffer.readVarInt(), buffer.readUtf(32), buffer.readBoolean());
        return new RoadPlannerEditCommitPacket(sessionId, roadId, nodes, segmentTypes, settings);
    }

    public static void handle(RoadPlannerEditCommitPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null || !(sender.level() instanceof ServerLevel level)) {
                return;
            }
            sender.sendSystemMessage(commit(level, sender, packet));
        });
        context.setPacketHandled(true);
    }

    private static Component commit(ServerLevel level, ServerPlayer player, RoadPlannerEditCommitPacket packet) {
        if (packet == null || packet.roadId().isBlank() || packet.nodes().size() < 2) {
            return Component.literal("Invalid road edit submission");
        }
        NationSavedData nationData = NationSavedData.get(level);
        RoadNetworkRecord road = nationData.getRoadNetwork(packet.roadId());
        if (road == null) {
            return Component.literal("Road not found");
        }
        if (!level.dimension().location().toString().equalsIgnoreCase(road.dimensionId())) {
            return Component.literal("Road is in another dimension");
        }
        if (!RoadEditPermissionService.canManageRoad(level, player, nationData, road)) {
            return Component.literal("No permission to edit this road");
        }
        RoadEditableNetworkSavedData editableData = RoadEditableNetworkSavedData.get(level);
        Optional<RoadEditableRecord> editable = editableData.getRoad(road.roadId());
        if (editable.isEmpty()) {
            editable = RoadEditableMigrationService.ensureLegacyLedger(level, road);
        }
        if (editable.isEmpty()) {
            return Component.literal("Road edit ledger could not be prepared");
        }
        ProposedEdit proposed = proposedEdit(editable.get(), packet, level);
        RoadEditCommitService.Result result = new RoadEditCommitService().queueEdit(
                editableData,
                editable.get(),
                proposed.nodes(),
                proposed.segments(),
                proposed.placements(),
                RoadEditTaskService.global(),
                System.currentTimeMillis());
        if (!result.success()) {
            return Component.literal("Road edit rejected: " + result.diff().conflicts().size() + " placement conflicts");
        }
        if (result.jobId().isPresent()) {
            return Component.literal("Road edit queued: " + result.jobId().get());
        }
        return Component.literal("Road edit applied");
    }

    static ProposedEdit proposedEditForTest(RoadEditableRecord current, RoadPlannerEditCommitPacket packet, ServerLevel level) {
        return proposedEdit(current, packet, level);
    }

    private static ProposedEdit proposedEdit(RoadEditableRecord current, RoadPlannerEditCommitPacket packet, ServerLevel level) {
        List<RoadEditableNode> proposedNodes = proposedNodes(current, packet.nodes());
        List<BuildStep> buildSteps = RoadPlannerBuildControlService.previewBuildSteps(
                packet.nodes(),
                packet.segmentTypes(),
                packet.settings(),
                level);
        Map<Integer, LinkedHashMap<Long, RoadEditBlockPlacement>> placementsBySegment = new LinkedHashMap<>();
        for (BuildStep step : buildSteps) {
            if (step == null || step.pos() == null || step.state() == null) {
                continue;
            }
            int segmentIndex = nearestSegmentIndex(step.pos(), packet.nodes());
            String segmentId = segmentId(current.roadId(), segmentIndex);
            placementsBySegment
                    .computeIfAbsent(segmentIndex, ignored -> new LinkedHashMap<>())
                    .putIfAbsent(step.pos().asLong(), new RoadEditBlockPlacement(segmentId, step.pos(), step.state()));
        }
        List<RoadEditableSegment> segments = new ArrayList<>();
        List<RoadEditBlockPlacement> placements = new ArrayList<>();
        int expectedSegments = Math.max(0, packet.nodes().size() - 1);
        for (int index = 0; index < expectedSegments; index++) {
            LinkedHashMap<Long, RoadEditBlockPlacement> segmentPlacements = placementsBySegment.getOrDefault(index, new LinkedHashMap<>());
            placements.addAll(segmentPlacements.values());
            segments.add(new RoadEditableSegment(
                    segmentId(current.roadId(), index),
                    nodeId(current.roadId(), index),
                    nodeId(current.roadId(), index + 1),
                    List.of(packet.nodes().get(index), packet.nodes().get(index + 1)),
                    List.of(packet.nodes().get(index), packet.nodes().get(index + 1)),
                    packet.settings().width(),
                    sectionType(packet.segmentTypes().get(index)),
                    packet.settings().materialPreset(),
                    List.copyOf(segmentPlacements.keySet())));
        }
        return new ProposedEdit(proposedNodes, segments, List.copyOf(placements));
    }

    private static List<RoadEditableNode> proposedNodes(RoadEditableRecord current, List<BlockPos> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        List<RoadEditableNode> proposed = new ArrayList<>(nodes.size());
        for (int index = 0; index < nodes.size(); index++) {
            RoadEditableNode.Kind kind = index == 0
                    ? RoadEditableNode.Kind.SOURCE_TOWN
                    : index == nodes.size() - 1 ? RoadEditableNode.Kind.TARGET_TOWN : RoadEditableNode.Kind.NORMAL;
            String label = index == 0
                    ? current.sourceTownName()
                    : index == nodes.size() - 1 ? current.targetTownName() : "";
            proposed.add(new RoadEditableNode(nodeId(current.roadId(), index), nodes.get(index), kind, label));
        }
        return List.copyOf(proposed);
    }

    private static int nearestSegmentIndex(BlockPos pos, List<BlockPos> nodes) {
        if (nodes == null || nodes.size() < 2 || pos == null) {
            return 0;
        }
        int bestIndex = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int index = 0; index < nodes.size() - 1; index++) {
            double distance = distanceToSegment2d(pos, nodes.get(index), nodes.get(index + 1));
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private static double distanceToSegment2d(BlockPos point, BlockPos start, BlockPos end) {
        double px = point.getX();
        double pz = point.getZ();
        double ax = start.getX();
        double az = start.getZ();
        double bx = end.getX();
        double bz = end.getZ();
        double dx = bx - ax;
        double dz = bz - az;
        double lengthSquared = dx * dx + dz * dz;
        double t = lengthSquared == 0.0D ? 0.0D : ((px - ax) * dx + (pz - az) * dz) / lengthSquared;
        t = Math.max(0.0D, Math.min(1.0D, t));
        double nearestX = ax + t * dx;
        double nearestZ = az + t * dz;
        double offsetX = px - nearestX;
        double offsetZ = pz - nearestZ;
        return offsetX * offsetX + offsetZ * offsetZ;
    }

    private static String sectionType(RoadPlannerSegmentType type) {
        if (type == RoadPlannerSegmentType.BRIDGE_MAJOR || type == RoadPlannerSegmentType.BRIDGE_SMALL) {
            return "BRIDGE";
        }
        if (type == RoadPlannerSegmentType.TUNNEL) {
            return "TUNNEL";
        }
        return "ROAD";
    }

    private static String segmentId(String roadId, int index) {
        return normalizeRoadId(roadId) + ":segment:" + Math.max(0, index);
    }

    private static String nodeId(String roadId, int index) {
        return normalizeRoadId(roadId) + ":node:" + Math.max(0, index);
    }

    private static String normalizeRoadId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static List<RoadPlannerSegmentType> normalizeSegments(List<RoadPlannerSegmentType> segmentTypes, int nodeCount) {
        int expected = Math.max(0, nodeCount - 1);
        List<RoadPlannerSegmentType> normalized = new ArrayList<>(expected);
        if (segmentTypes != null) {
            for (int index = 0; index < Math.min(expected, segmentTypes.size()); index++) {
                RoadPlannerSegmentType type = segmentTypes.get(index);
                normalized.add(type == null || type == RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE
                        ? RoadPlannerSegmentType.ROAD
                        : type);
            }
        }
        while (normalized.size() < expected) {
            normalized.add(RoadPlannerSegmentType.ROAD);
        }
        return List.copyOf(normalized);
    }

    public record ProposedEdit(List<RoadEditableNode> nodes,
                               List<RoadEditableSegment> segments,
                               List<RoadEditBlockPlacement> placements) {
        public ProposedEdit {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            segments = segments == null ? List.of() : List.copyOf(segments);
            placements = placements == null ? List.of() : List.copyOf(placements);
        }
    }
}
