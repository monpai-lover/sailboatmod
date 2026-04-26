package com.monpai.sailboatmod.client.roadplanner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerScreenLayoutModelTest {
    @Test
    void mvpLayoutContainsVisibleToolsMapControlsAndActionButtons() {
        RoadPlannerScreenLayoutModel layout = RoadPlannerScreenLayoutModel.mvp();

        assertEquals(List.of("道路", "桥梁", "隧道", "擦除", "选择"), layout.tools());
        assertTrue(layout.mapLabels().contains("起点"));
        assertTrue(layout.mapLabels().contains("目的地方向"));
        assertTrue(layout.mapLabels().contains("当前节点线"));
        assertTrue(layout.buttons().containsAll(List.of("上一阶段", "下一阶段", "撤销节点", "清除区域", "自动补全", "确认建造", "取消")));
        assertTrue(layout.statusLines().stream().anyMatch(line -> line.contains("宽度")));
    }
}
