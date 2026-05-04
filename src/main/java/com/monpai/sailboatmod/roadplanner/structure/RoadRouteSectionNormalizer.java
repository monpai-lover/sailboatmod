package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class RoadRouteSectionNormalizer {
    private RoadRouteSectionNormalizer() {
    }

    public static List<RoadRouteSection> sections(List<BlockPos> nodes, List<RoadPlannerSegmentType> segmentTypes) {
        if (nodes == null || nodes.size() < 2) {
            return List.of();
        }
        List<RoadRouteSection> sections = new ArrayList<>();
        List<BlockPos> sectionNodes = new ArrayList<>();
        List<RoadPlannerSegmentType> sectionTypes = new ArrayList<>();
        for (int index = 0; index < nodes.size() - 1; index++) {
            BlockPos from = nodes.get(index);
            BlockPos to = nodes.get(index + 1);
            if (from == null || to == null) {
                addSection(sections, sectionNodes, sectionTypes);
                sectionNodes = new ArrayList<>();
                sectionTypes = new ArrayList<>();
                continue;
            }
            if (from.equals(to)) {
                continue;
            }
            BlockPos immutableFrom = from.immutable();
            BlockPos immutableTo = to.immutable();
            if (sectionNodes.isEmpty()) {
                sectionNodes.add(immutableFrom);
            } else if (!sectionNodes.get(sectionNodes.size() - 1).equals(immutableFrom)) {
                addSection(sections, sectionNodes, sectionTypes);
                sectionNodes = new ArrayList<>();
                sectionTypes = new ArrayList<>();
                sectionNodes.add(immutableFrom);
            }
            sectionNodes.add(immutableTo);
            sectionTypes.add(segmentTypeAt(segmentTypes, index));
        }
        addSection(sections, sectionNodes, sectionTypes);
        return List.copyOf(sections);
    }

    public static List<BlockPos> flattenNodes(List<RoadRouteSection> sections) {
        if (sections == null || sections.isEmpty()) {
            return List.of();
        }
        List<BlockPos> result = new ArrayList<>();
        for (RoadRouteSection section : sections) {
            for (BlockPos node : section.nodes()) {
                if (result.isEmpty() || !result.get(result.size() - 1).equals(node)) {
                    result.add(node.immutable());
                }
            }
        }
        return List.copyOf(result);
    }

    public static List<RoadPlannerSegmentType> flattenSegmentTypes(List<RoadRouteSection> sections) {
        if (sections == null || sections.isEmpty()) {
            return List.of();
        }
        List<RoadPlannerSegmentType> result = new ArrayList<>();
        for (RoadRouteSection section : sections) {
            result.addAll(section.segmentTypes());
        }
        return List.copyOf(result);
    }

    private static void addSection(List<RoadRouteSection> sections,
                                   List<BlockPos> nodes,
                                   List<RoadPlannerSegmentType> segmentTypes) {
        if (nodes.size() >= 2 && segmentTypes.size() == nodes.size() - 1) {
            sections.add(new RoadRouteSection(List.copyOf(nodes), List.copyOf(segmentTypes)));
        }
    }

    private static RoadPlannerSegmentType segmentTypeAt(List<RoadPlannerSegmentType> segmentTypes, int index) {
        RoadPlannerSegmentType type = RoadPlannerSegmentType.ROAD;
        if (segmentTypes != null && index >= 0 && index < segmentTypes.size() && segmentTypes.get(index) != null) {
            type = segmentTypes.get(index);
        }
        return type == RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE ? RoadPlannerSegmentType.BRIDGE_MAJOR : type;
    }
}
