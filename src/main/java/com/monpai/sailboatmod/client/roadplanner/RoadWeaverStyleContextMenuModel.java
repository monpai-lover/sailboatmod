package com.monpai.sailboatmod.client.roadplanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record RoadWeaverStyleContextMenuModel(UUID roadEdgeId,
                                              int anchorX,
                                              int anchorY,
                                              List<RoadWeaverStyleContextMenuItem> items,
                                              int hoverIndex) {
    public static final int PADDING = 6;
    public static final int ITEM_HEIGHT = 16;
    public static final int SEPARATOR_HEIGHT = 8;
    public static final int MIN_WIDTH = 132;
    public static final int MENU_BG = 0xEE101827;
    public static final int MENU_BORDER = 0xFF2F3B52;
    public static final int MENU_HOVER = 0x663B82F6;
    public static final int MENU_TEXT = 0xFFE5E7EB;
    public static final int MENU_DISABLED_TEXT = 0xFF808080;
    public static final int MENU_SHADOW = 0x60000000;

    public RoadWeaverStyleContextMenuModel {
        roadEdgeId = roadEdgeId == null ? new UUID(0L, 0L) : roadEdgeId;
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static RoadWeaverStyleContextMenuModel forRoadEdge(UUID roadEdgeId, int anchorX, int anchorY) {
        return new RoadWeaverStyleContextMenuModel(roadEdgeId, anchorX, anchorY, List.of(
                RoadWeaverStyleContextMenuItem.action("重命名道路", RoadPlannerContextMenuAction.RENAME_ROAD),
                RoadWeaverStyleContextMenuItem.action("编辑节点", RoadPlannerContextMenuAction.EDIT_NODES),
                RoadWeaverStyleContextMenuItem.divider(),
                RoadWeaverStyleContextMenuItem.action("拆除本段", RoadPlannerContextMenuAction.DEMOLISH_EDGE),
                RoadWeaverStyleContextMenuItem.action("拆除分支", RoadPlannerContextMenuAction.DEMOLISH_BRANCH),
                RoadWeaverStyleContextMenuItem.divider(),
                RoadWeaverStyleContextMenuItem.action("连接城镇", RoadPlannerContextMenuAction.CONNECT_TOWN),
                RoadWeaverStyleContextMenuItem.action("查看回滚账本", RoadPlannerContextMenuAction.VIEW_LEDGER)
        ), -1);
    }

    public int padding() {
        return PADDING;
    }

    public int itemHeight() {
        return ITEM_HEIGHT;
    }

    public int separatorHeight() {
        return SEPARATOR_HEIGHT;
    }

    public RoadWeaverStyleRect bounds() {
        return new RoadWeaverStyleRect(anchorX, anchorY, MIN_WIDTH, height());
    }

    public int height() {
        int height = PADDING * 2;
        for (RoadWeaverStyleContextMenuItem item : items) {
            height += item.separator() ? SEPARATOR_HEIGHT : ITEM_HEIGHT;
        }
        return height;
    }

    public Optional<RoadPlannerContextMenuAction> hoveredAction() {
        if (hoverIndex < 0 || hoverIndex >= items.size()) {
            return Optional.empty();
        }
        RoadWeaverStyleContextMenuItem item = items.get(hoverIndex);
        if (item.separator() || !item.enabled()) {
            return Optional.empty();
        }
        return Optional.ofNullable(item.action());
    }

    public RoadWeaverStyleContextMenuModel withMousePosition(int mouseX, int mouseY) {
        return new RoadWeaverStyleContextMenuModel(roadEdgeId, anchorX, anchorY, items, findHoverIndex(mouseX, mouseY));
    }

    public RoadWeaverStyleContextMenuModel withActionEnabled(RoadPlannerContextMenuAction action, boolean enabled) {
        List<RoadWeaverStyleContextMenuItem> updated = new ArrayList<>();
        for (RoadWeaverStyleContextMenuItem item : items) {
            updated.add(item.action() == action ? item.withEnabled(enabled) : item);
        }
        return new RoadWeaverStyleContextMenuModel(roadEdgeId, anchorX, anchorY, updated, hoverIndex);
    }

    public RoadWeaverStyleContextMenuClick click(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return new RoadWeaverStyleContextMenuClick(true, true, null);
        }
        if (!bounds().contains(mouseX, mouseY)) {
            return new RoadWeaverStyleContextMenuClick(true, true, null);
        }
        Optional<RoadPlannerContextMenuAction> action = withMousePosition((int) mouseX, (int) mouseY).hoveredAction();
        return new RoadWeaverStyleContextMenuClick(true, true, action.orElse(null));
    }

    private int findHoverIndex(int mouseX, int mouseY) {
        if (!bounds().contains(mouseX, mouseY)) {
            return -1;
        }
        int y = anchorY + PADDING;
        for (int index = 0; index < items.size(); index++) {
            RoadWeaverStyleContextMenuItem item = items.get(index);
            int itemHeight = item.separator() ? SEPARATOR_HEIGHT : ITEM_HEIGHT;
            if (mouseY >= y && mouseY < y + itemHeight) {
                return item.separator() ? -1 : index;
            }
            y += itemHeight;
        }
        return -1;
    }
}
