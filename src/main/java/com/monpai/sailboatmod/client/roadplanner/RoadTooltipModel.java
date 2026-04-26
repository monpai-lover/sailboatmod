package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import com.monpai.sailboatmod.roadplanner.graph.RoadRouteMetadata;

import java.util.List;

public record RoadTooltipModel(List<String> lines) {
    public RoadTooltipModel {
        lines = List.copyOf(lines);
    }

    public static RoadTooltipModel from(RoadRouteMetadata metadata,
                                        int lengthBlocks,
                                        int width,
                                        CompiledRoadSectionType type,
                                        String statusText) {
        return new RoadTooltipModel(List.of(
                metadata.roadName(),
                "连接: " + metadata.fromTownName() + " → " + metadata.toTownName(),
                "长度: " + lengthBlocks + " blocks",
                "宽度: " + width + " / 类型: " + type,
                "创建者: " + metadata.creatorId(),
                "状态: " + statusText
        ));
    }
}
