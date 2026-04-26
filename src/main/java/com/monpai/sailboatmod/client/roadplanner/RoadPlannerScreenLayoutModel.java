package com.monpai.sailboatmod.client.roadplanner;

import java.util.List;

public record RoadPlannerScreenLayoutModel(List<String> tools,
                                           List<String> mapLabels,
                                           List<String> buttons,
                                           List<String> statusLines) {
    public RoadPlannerScreenLayoutModel {
        tools = tools == null ? List.of() : List.copyOf(tools);
        mapLabels = mapLabels == null ? List.of() : List.copyOf(mapLabels);
        buttons = buttons == null ? List.of() : List.copyOf(buttons);
        statusLines = statusLines == null ? List.of() : List.copyOf(statusLines);
    }

    public static RoadPlannerScreenLayoutModel mvp() {
        return new RoadPlannerScreenLayoutModel(
                List.of("道路", "桥梁", "隧道", "擦除", "选择"),
                List.of("起点", "目的地方向", "当前节点线", "128x128 区域", "真实地形快照"),
                List.of("上一阶段", "下一阶段", "撤销节点", "清除区域", "自动补全", "确认建造", "取消"),
                List.of("阶段: 1", "距离目的地: 未设置", "宽度: 5", "节点: 0", "队列: 待确认")
        );
    }
}
