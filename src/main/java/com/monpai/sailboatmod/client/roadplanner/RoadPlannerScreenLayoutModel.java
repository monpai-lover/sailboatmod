package com.monpai.sailboatmod.client.roadplanner;

import java.util.List;
import java.util.ArrayList;

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

    /** Keeps every expanded action reachable when a tall dropdown does not fit the screen. */
    public static RoadPlannerTopToolbar fitToolbar(RoadPlannerTopToolbar toolbar, int screenWidth, int screenHeight) {
        if (toolbar.items().stream().allMatch(item -> item.bounds().right() <= screenWidth
                && item.bounds().bottom() <= screenHeight)) {
            return toolbar;
        }
        var dropdown = toolbar.items().stream()
                .filter(item -> item.kind() != RoadPlannerTopToolbar.Kind.GROUP).toList();
        if (dropdown.isEmpty()) return toolbar;

        int top = dropdown.stream().mapToInt(item -> item.bounds().y()).min().orElse(36);
        int step = 24;
        int gap = 4;
        int maxRows = Math.max(1, (screenHeight - top - 8) / step);
        int columns = (dropdown.size() + maxRows - 1) / maxRows;
        int maxColumns = Math.max(1, (screenWidth - 16 + gap) / (90 + gap));
        columns = Math.min(columns, maxColumns);
        int rows = (dropdown.size() + columns - 1) / columns;
        int cellWidth = Math.min(90, Math.max(1, (screenWidth - 16 - gap * (columns - 1)) / columns));
        int dropdownWidth = columns * cellWidth + gap * (columns - 1);
        int left = Math.max(8, Math.min(dropdown.getFirst().bounds().x(), screenWidth - dropdownWidth - 8));
        var items = new ArrayList<RoadPlannerTopToolbar.Item>();
        int index = 0;
        int bottom = 34;
        for (var item : toolbar.items()) {
            if (item.kind() == RoadPlannerTopToolbar.Kind.GROUP) {
                items.add(item);
                continue;
            }
            var bounds = new RoadPlannerMapLayout.Rect(left + (index / rows) * (cellWidth + gap),
                    top + (index % rows) * step, cellWidth, item.bounds().height());
            items.add(new RoadPlannerTopToolbar.Item(item.label(), item.kind(), item.toolType(), item.group(), bounds));
            bottom = Math.max(bottom, bounds.bottom() + 2);
            index++;
        }
        return new RoadPlannerTopToolbar(List.copyOf(items), new RoadPlannerMapLayout.Rect(0, 0, screenWidth, bottom));
    }
}
