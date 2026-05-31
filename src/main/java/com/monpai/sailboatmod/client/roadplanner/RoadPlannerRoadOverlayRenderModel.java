package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerRoadOverlaySyncPacket;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeRelationship;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class RoadPlannerRoadOverlayRenderModel {
    public enum Kind {
        BASE,
        HOVERED,
        SELECTED_REUSE
    }

    public record RoadLayer(String roadId,
                            Kind kind,
                            RoadPlannerMergeRelationship relationship,
                            List<BlockPos> path,
                            int thickness,
                            boolean dashed) {
        public RoadLayer {
            roadId = roadId == null ? "" : roadId;
            kind = kind == null ? Kind.BASE : kind;
            relationship = relationship == null ? RoadPlannerMergeRelationship.OWN : relationship;
            path = path == null ? List.of() : path.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(BlockPos::immutable)
                    .toList();
            thickness = Math.max(1, thickness);
        }
    }

    public static List<RoadLayer> layers(List<RoadPlannerRoadOverlaySyncPacket.Entry> overlays,
                                         RoadPlannerMergeSelection selectedMerge,
                                         Set<String> selectedReuseRoadIds,
                                         String hoveredRoadId,
                                         int lodStepBlocks) {
        if (overlays == null || overlays.isEmpty()) {
            return List.of();
        }
        ArrayList<RoadLayer> layers = new ArrayList<>();
        for (RoadPlannerRoadOverlaySyncPacket.Entry overlay : overlays) {
            if (overlay == null || overlay.displayPath().size() < 2) {
                continue;
            }
            String roadId = overlay.roadId();
            Kind kind = Kind.BASE;
            int thickness = 3;
            boolean dashed = false;
            if (selectedReuseRoadIds != null && selectedReuseRoadIds.contains(roadId)) {
                kind = Kind.SELECTED_REUSE;
                thickness = 6;
            } else if (hoveredRoadId != null && hoveredRoadId.equals(roadId)) {
                kind = Kind.HOVERED;
                thickness = 5;
            }
            layers.add(new RoadLayer(roadId, kind, overlay.relationship(),
                    RoadPlannerOverlayLod.simplify(overlay.displayPath(), lodStepBlocks), thickness, dashed));
        }
        return List.copyOf(layers);
    }

    public static List<BlockPos> keyNodes(RoadPlannerRoadOverlaySyncPacket.Entry overlay,
                                          RoadPlannerMergeSelection selectedMerge,
                                          Set<String> selectedReuseRoadIds,
                                          BlockPos hoveredNode,
                                          boolean showAllNodes) {
        if (overlay == null) {
            return List.of();
        }
        ArrayList<BlockPos> nodes = new ArrayList<>();
        if (showAllNodes) {
            nodes.addAll(overlay.displayPath());
        }
        if (selectedMerge != null && selectedMerge.present() && overlay.roadId().equals(selectedMerge.roadId())) {
            nodes.add(selectedMerge.anchorPos());
        }
        if (hoveredNode != null) {
            nodes.add(hoveredNode);
        }
        return nodes.stream().distinct().map(BlockPos::immutable).toList();
    }

    private RoadPlannerRoadOverlayRenderModel() {
    }
}
