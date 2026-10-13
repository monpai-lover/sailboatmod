package com.monpai.sailboatmod.client.roadplanner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

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

    @Test
    void expandedMenusStayVisibleAndHitTestableAtSmallGuiSizes() {
        for (int[] size : new int[][] {{320, 240}, {320, 160}, {426, 320}, {640, 480}}) {
            for (var group : RoadPlannerTopToolbar.Group.values()) {
                var original = RoadPlannerTopToolbar.toolbar(size[0], group);
                var fitted = RoadPlannerScreenLayoutModel.fitToolbar(original, size[0], size[1]);
                assertEquals(original.items().size(), fitted.items().size());
                for (var item : fitted.items()) {
                    var rect = item.bounds();
                    assertTrue(rect.x() >= 0 && rect.y() >= 0
                            && rect.right() <= size[0] && rect.bottom() <= size[1], item.toString());
                    assertSame(item, fitted.itemAt(rect.x() + rect.width() / 2f, rect.y() + rect.height() / 2f));
                }
            }
        }
    }

    @Test
    void roomyViewportKeepsOriginalToolbarGeometry() {
        var toolbar = RoadPlannerTopToolbar.toolbar(640, RoadPlannerTopToolbar.Group.TOOLS);
        assertSame(toolbar, RoadPlannerScreenLayoutModel.fitToolbar(toolbar, 640, 480));
    }
}
