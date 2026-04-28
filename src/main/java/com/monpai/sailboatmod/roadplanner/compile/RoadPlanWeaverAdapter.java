package com.monpai.sailboatmod.roadplanner.compile;

import com.monpai.sailboatmod.roadplanner.build.RoadBuildStep;
import com.monpai.sailboatmod.roadplanner.model.RoadNode;
import com.monpai.sailboatmod.roadplanner.model.RoadPlan;
import com.monpai.sailboatmod.roadplanner.model.RoadSegment;
import com.monpai.sailboatmod.roadplanner.model.RoadStroke;
import com.monpai.sailboatmod.roadplanner.model.RoadToolType;
import com.monpai.sailboatmod.roadplanner.weaver.bridge.WeaverBridgeBackend;
import com.monpai.sailboatmod.roadplanner.weaver.bridge.WeaverBridgeBuilder;
import com.monpai.sailboatmod.roadplanner.weaver.model.WeaverRoadSpan;
import com.monpai.sailboatmod.roadplanner.weaver.model.WeaverSpanType;
import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverBuildCandidate;
import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverSegmentPaver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

public class RoadPlanWeaverAdapter {
    private final WeaverBridgeBackend bridgeBackend;

    public RoadPlanWeaverAdapter() {
        this(WeaverBridgeBuilder::buildDeck);
    }

    public RoadPlanWeaverAdapter(WeaverBridgeBackend bridgeBackend) {
        this.bridgeBackend = bridgeBackend == null ? WeaverBridgeBuilder::buildDeck : bridgeBackend;
    }

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
        List<RoadIssue> issues = new ArrayList<>();

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
                    preview.addAll(bridgePreviewWithFallback(strokeCenterline, width, plan, stroke.strokeId(), issues));
                } else if (type == CompiledRoadSectionType.ROAD) {
                    preview.addAll(withPhase(WeaverSegmentPaver.paveCenterline(strokeCenterline, width, plan.settings().mainMaterial().defaultBlockState()), RoadBuildStep.Phase.ROAD_SURFACE));
                    preview.addAll(lampCandidates(strokeCenterline, width, plan.settings()));
                }
            }
        }

        return new CompiledRoadPath(centerline, sections, spans, issues, preview);
    }

    private List<WeaverBuildCandidate> bridgePreviewWithFallback(List<BlockPos> strokeCenterline,
                                                                 int width,
                                                                 RoadPlan plan,
                                                                 java.util.UUID strokeId,
                                                                 List<RoadIssue> issues) {
        try {
            return withPhase(bridgeBackend.buildBridge(strokeCenterline, width, plan.settings().mainMaterial().defaultBlockState()), RoadBuildStep.Phase.BRIDGE_DECK);
        } catch (RuntimeException exception) {
            BlockPos issuePos = strokeCenterline.isEmpty() ? null : strokeCenterline.get(0);
            issues.add(new RoadIssue("bridge_backend_fallback", "Bridge backend failed; using current deck compiler", issuePos, false));
            return withPhase(WeaverBridgeBuilder.buildDeck(strokeCenterline, width, plan.settings().mainMaterial().defaultBlockState()), RoadBuildStep.Phase.BRIDGE_DECK);
        }
    }

    private List<WeaverBuildCandidate> lampCandidates(List<BlockPos> centers, int width, com.monpai.sailboatmod.roadplanner.model.RoadSettings settings) {
        if (centers == null || centers.isEmpty()) {
            return List.of();
        }
        List<WeaverBuildCandidate> lamps = new ArrayList<>();
        int radius = width / 2;
        int distance = 0;
        for (int index = 0; index < centers.size(); index++) {
            boolean place = index == 0 || index == centers.size() - 1 || distance >= settings.lampIntervalBlocks();
            if (place) {
                BlockPos base = centers.get(index).offset(radius + 1, 1, 0);
                lamps.add(new WeaverBuildCandidate(base, Blocks.OAK_FENCE.defaultBlockState(), true, RoadBuildStep.Phase.LAMP));
                lamps.add(new WeaverBuildCandidate(base.above(), Blocks.LANTERN.defaultBlockState(), true, RoadBuildStep.Phase.LAMP));
                distance = 0;
            } else {
                distance++;
            }
        }
        return List.copyOf(lamps);
    }

    private List<WeaverBuildCandidate> withPhase(List<WeaverBuildCandidate> candidates, RoadBuildStep.Phase phase) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<WeaverBuildCandidate> phased = new ArrayList<>(candidates.size());
        for (WeaverBuildCandidate candidate : candidates) {
            phased.add(new WeaverBuildCandidate(candidate.pos(), candidate.state(), candidate.visible(), phase));
        }
        return List.copyOf(phased);
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
