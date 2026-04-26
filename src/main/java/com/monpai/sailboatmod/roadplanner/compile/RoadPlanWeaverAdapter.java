package com.monpai.sailboatmod.roadplanner.compile;

import com.monpai.sailboatmod.roadplanner.model.RoadNode;
import com.monpai.sailboatmod.roadplanner.model.RoadPlan;
import com.monpai.sailboatmod.roadplanner.model.RoadSegment;
import com.monpai.sailboatmod.roadplanner.model.RoadStroke;
import com.monpai.sailboatmod.roadplanner.model.RoadToolType;
import com.monpai.sailboatmod.roadplanner.weaver.bridge.WeaverBridgeBuilder;
import com.monpai.sailboatmod.roadplanner.weaver.model.WeaverRoadSpan;
import com.monpai.sailboatmod.roadplanner.weaver.model.WeaverSpanType;
import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverBuildCandidate;
import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverSegmentPaver;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class RoadPlanWeaverAdapter {
    public CompiledRoadPath compile(RoadPlan plan) {
        if (plan == null || plan.segments().isEmpty() || plan.nodesInOrder().isEmpty()) {
            return new CompiledRoadPath(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(new RoadIssue("empty_plan", "Road plan has no drawable nodes", null, true)),
                    List.of());
        }

        List<BlockPos> centerline = new ArrayList<>();
        List<CompiledRoadSection> sections = new ArrayList<>();
        List<WeaverRoadSpan> spans = new ArrayList<>();
        List<WeaverBuildCandidate> preview = new ArrayList<>();

        for (RoadSegment segment : plan.segments()) {
            for (RoadStroke stroke : segment.strokes()) {
                List<BlockPos> strokeCenterline = stroke.nodes().stream().map(RoadNode::pos).toList();
                if (strokeCenterline.isEmpty()) {
                    continue;
                }
                int width = stroke.settings().widthOverride() == null ? plan.settings().width() : stroke.settings().widthOverride();
                CompiledRoadSectionType type = sectionType(stroke.tool());
                centerline.addAll(strokeCenterline);
                sections.add(new CompiledRoadSection(stroke.strokeId(), type, stroke.tool(), strokeCenterline, width));
                if (type == CompiledRoadSectionType.BRIDGE && strokeCenterline.size() >= 2) {
                    spans.add(new WeaverRoadSpan(strokeCenterline.get(0), strokeCenterline.get(strokeCenterline.size() - 1), WeaverSpanType.BRIDGE));
                    preview.addAll(WeaverBridgeBuilder.buildDeck(strokeCenterline, width, plan.settings().mainMaterial().defaultBlockState()));
                } else if (type == CompiledRoadSectionType.ROAD) {
                    preview.addAll(WeaverSegmentPaver.paveCenterline(strokeCenterline, width, plan.settings().mainMaterial().defaultBlockState()));
                }
            }
        }

        return new CompiledRoadPath(centerline, sections, spans, List.of(), preview);
    }

    private CompiledRoadSectionType sectionType(RoadToolType tool) {
        if (tool == RoadToolType.BRIDGE) {
            return CompiledRoadSectionType.BRIDGE;
        }
        if (tool == RoadToolType.TUNNEL) {
            return CompiledRoadSectionType.TUNNEL;
        }
        return CompiledRoadSectionType.ROAD;
    }
}
