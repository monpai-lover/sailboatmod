package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToIntFunction;

public class RoadPlannerVanillaContextMenu {
    public static final int PADDING = 6;
    public static final int ITEM_HEIGHT = 16;
    public static final int SEPARATOR_HEIGHT = 8;
    private static final int MENU_BG = 0xEE101827;
    private static final int MENU_BORDER = 0xFF2F3B52;
    private static final int MENU_HOVER = 0x663B82F6;
    private static final int MENU_TEXT = 0xFFE5E7EB;
    private static final int MENU_DISABLED = 0xFF808080;

    private final UUID roadEdgeId;
    private final List<Item> items = new ArrayList<>();
    private boolean open;
    private int anchorX;
    private int anchorY;
    private int hoverIndex = -1;
    private RoadPlannerVanillaLayout.Rect bounds = new RoadPlannerVanillaLayout.Rect(0, 0, 0, 0);

    private RoadPlannerVanillaContextMenu(UUID roadEdgeId) {
        this.roadEdgeId = roadEdgeId == null ? new UUID(0L, 0L) : roadEdgeId;
    }

    public static RoadPlannerVanillaContextMenu forRoadEdge(UUID roadEdgeId) {
        RoadPlannerVanillaContextMenu menu = new RoadPlannerVanillaContextMenu(roadEdgeId);
        menu.items.add(Item.action("重命名道路", RoadPlannerContextMenuAction.RENAME_ROAD));
        menu.items.add(Item.action("编辑节点", RoadPlannerContextMenuAction.EDIT_NODES));
        menu.items.add(Item.separator());
        menu.items.add(Item.action("拆除本段", RoadPlannerContextMenuAction.DEMOLISH_EDGE));
        menu.items.add(Item.action("拆除分支", RoadPlannerContextMenuAction.DEMOLISH_BRANCH));
        menu.items.add(Item.separator());
        menu.items.add(Item.action("连接城镇", RoadPlannerContextMenuAction.CONNECT_TOWN));
        menu.items.add(Item.action("查看回滚账本", RoadPlannerContextMenuAction.VIEW_LEDGER));
        return menu;
    }

    public void open(int x, int y) {
        this.anchorX = x;
        this.anchorY = y;
        this.open = true;
        this.hoverIndex = -1;
    }

    public void close() {
        this.open = false;
        this.hoverIndex = -1;
    }

    public boolean isOpen() {
        return open;
    }

    public UUID roadEdgeId() {
        return roadEdgeId;
    }

    public RoadPlannerVanillaLayout.Rect bounds() {
        return bounds;
    }

    public void setEnabled(RoadPlannerContextMenuAction action, boolean enabled) {
        for (int index = 0; index < items.size(); index++) {
            Item item = items.get(index);
            if (item.action() == action) {
                items.set(index, item.withEnabled(enabled));
            }
        }
    }

    public void layout(int screenWidth, int screenHeight, ToIntFunction<String> labelWidth) {
        int width = 96;
        int height = PADDING * 2;
        for (Item item : items) {
            if (item.divider()) {
                height += SEPARATOR_HEIGHT;
            } else {
                width = Math.max(width, labelWidth.applyAsInt(item.label()) + PADDING * 2 + 12);
                height += ITEM_HEIGHT;
            }
        }
        int x = clamp(anchorX + 4, 4, Math.max(4, screenWidth - width - 4));
        int y = clamp(anchorY, 4, Math.max(4, screenHeight - height - 4));
        bounds = new RoadPlannerVanillaLayout.Rect(x, y, width, height);
    }

    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY, int screenWidth, int screenHeight) {
        if (!open || items.isEmpty()) {
            return;
        }
        layout(screenWidth, screenHeight, font::width);
        updateHover(mouseX, mouseY);
        graphics.fill(bounds.x() + 2, bounds.y() + 2, bounds.right() + 2, bounds.bottom() + 2, 0x60000000);
        graphics.fill(bounds.x() - 1, bounds.y() - 1, bounds.right() + 1, bounds.bottom() + 1, MENU_BORDER);
        graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), MENU_BG);
        int y = bounds.y() + PADDING;
        for (int index = 0; index < items.size(); index++) {
            Item item = items.get(index);
            if (item.divider()) {
                int lineY = y + SEPARATOR_HEIGHT / 2;
                graphics.fill(bounds.x() + 4, lineY, bounds.right() - 4, lineY + 1, 0x40FFFFFF);
                y += SEPARATOR_HEIGHT;
            } else {
                if (index == hoverIndex && item.enabled()) {
                    graphics.fill(bounds.x() + 2, y, bounds.right() - 2, y + ITEM_HEIGHT, MENU_HOVER);
                }
                graphics.drawString(font, item.label(), bounds.x() + PADDING, y + 4, item.enabled() ? MENU_TEXT : MENU_DISABLED, false);
                y += ITEM_HEIGHT;
            }
        }
    }

    public void updateHover(int mouseX, int mouseY) {
        hoverIndex = -1;
        if (!bounds.contains(mouseX, mouseY)) {
            return;
        }
        int y = bounds.y() + PADDING;
        for (int index = 0; index < items.size(); index++) {
            Item item = items.get(index);
            int itemHeight = item.divider() ? SEPARATOR_HEIGHT : ITEM_HEIGHT;
            if (mouseY >= y && mouseY < y + itemHeight) {
                hoverIndex = item.divider() ? -1 : index;
                return;
            }
            y += itemHeight;
        }
    }

    public Optional<RoadPlannerContextMenuAction> hoveredAction() {
        if (hoverIndex < 0 || hoverIndex >= items.size()) {
            return Optional.empty();
        }
        Item item = items.get(hoverIndex);
        if (item.divider() || !item.enabled()) {
            return Optional.empty();
        }
        return Optional.ofNullable(item.action());
    }

    public ClickResult click(double mouseX, double mouseY, int button) {
        if (!open) {
            return new ClickResult(false, false, null);
        }
        if (button != 0 || !bounds.contains(mouseX, mouseY)) {
            close();
            return new ClickResult(true, true, null);
        }
        updateHover((int) mouseX, (int) mouseY);
        Optional<RoadPlannerContextMenuAction> action = hoveredAction();
        close();
        return new ClickResult(true, true, action.orElse(null));
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Item(String label, RoadPlannerContextMenuAction action, boolean enabled, boolean divider) {
        static Item action(String label, RoadPlannerContextMenuAction action) {
            return new Item(label, action, true, false);
        }

        static Item separator() {
            return new Item("", null, false, true);
        }

        Item withEnabled(boolean enabled) {
            return new Item(label, action, enabled, divider);
        }
    }

    public record ClickResult(boolean consumed, boolean closeMenu, RoadPlannerContextMenuAction selectedAction) {
        public Optional<RoadPlannerContextMenuAction> action() {
            return Optional.ofNullable(selectedAction);
        }
    }
}
