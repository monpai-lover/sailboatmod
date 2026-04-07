package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.client.DockClientHooks;
import com.monpai.sailboatmod.dock.DockScreenData;
import com.monpai.sailboatmod.market.TransportTerminalKind;
import com.monpai.sailboatmod.menu.DockMenu;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.DockGuiActionPacket;
import com.monpai.sailboatmod.network.packet.RenameDockPacket;
import com.monpai.sailboatmod.network.packet.SetDockZonePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class DockScreen<T extends DockMenu> extends AbstractContainerScreen<T> {
    private static final int TAB_ROUTE = 0;
    private static final int TAB_DISPATCH = 1;
    private static final int TAB_STORAGE = 2;
    private static final int TAB_WAYBILL = 3;
    private static final int COMPACT_ROW_H = 12;
    private static final int COMPACT_VISIBLE_ROWS = 9;
    private static final int META_ROW_H = 20;
    private static final int META_VISIBLE_ROWS = 5;
    private static final int WAYBILL_ROW_H = 12;
    private static final int WAYBILL_VISIBLE_ROWS = 3;
    private static final int DISPATCH_VISIBLE_ROWS = 5;
    private static final int STORAGE_ROW_H = 12;
    private static final int STORAGE_VISIBLE_ROWS = 4;
    private static final int RIGHT_PANEL_W = 194;
    private static final int RIGHT_PANEL_GAP = 6;
    private static final int RIGHT_PANEL_OVERLAP = 0;
    private static final int MINIMAP_X = 140;
    private static final int MINIMAP_Y = 86;
    private static final int MINIMAP_W = 46;
    private static final int MINIMAP_H = 34;
    private static final int DISPATCH_BOATS_X = 8;
    private static final int DISPATCH_BOATS_W = 66;
    private static final int DISPATCH_ROUTES_X = 76;
    private static final int DISPATCH_ROUTES_W = 62;

    private DockScreenData data;
    private int activeTab = TAB_ROUTE;
    private EditBox dockNameInput;
    private int rightPanelX;
    private int rightPanelY;
    private boolean selectingZone = false;
    private int selectStartPx;
    private int selectStartPz;
    private int selectNowPx;
    private int selectNowPz;
    @Nullable
    private String hoveredMinimapBoatName;
    @Nullable
    private Button importButton;
    @Nullable
    private Button reverseButton;
    @Nullable
    private Button deleteButton;
    @Nullable
    private Button assignButton;
    @Nullable
    private Button dispatchCargoButton;
    @Nullable
    private Button takeWaybillButton;
    @Nullable
    private Button takeStorageButton;
    @Nullable
    private Button nonOrderAutoReturnButton;
    @Nullable
    private Button nonOrderAutoUnloadButton;
    @Nullable
    private Button autoRouteButton;
    private boolean closingContainer;

    public DockScreen(T menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 194;
        this.imageHeight = 176;
        DockScreenData initial = DockClientHooks.consumeFor(menu.getDockPos());
        this.data = initial != null ? initial : empty(menu.getDockPos());
    }

    public static DockScreen<DockMenu> create(DockMenu menu, Inventory inventory, Component title) {
        return new DockScreen<>(menu, inventory, title);
    }

    public boolean isForDock(net.minecraft.core.BlockPos pos) {
        return data.dockPos().equals(pos);
    }

    public void updateData(DockScreenData updated) {
        this.data = updated;
    }

    protected String screenKeyPrefix() {
        return "screen.sailboatmod.dock";
    }

    protected String defaultFacilityNameKey() {
        return "block.sailboatmod.dock";
    }

    protected TransportTerminalKind terminalKind() {
        return TransportTerminalKind.PORT;
    }

    protected boolean useLandMinimapPalette() {
        return false;
    }

    protected Component screenText(String suffix, Object... args) {
        return Component.translatable(screenKeyPrefix() + "." + suffix, args);
    }

    @Override
    protected void init() {
        super.init();
        int extraWidth = RIGHT_PANEL_W + RIGHT_PANEL_GAP - RIGHT_PANEL_OVERLAP;
        int centeredLeft = this.leftPos - extraWidth / 2;
        this.leftPos = Math.max(8, centeredLeft);
        updateRightPanelAnchor();
        int top = this.rightPanelY;
        int panelX = this.rightPanelX;
        this.addRenderableWidget(Button.builder(screenText("tab.routes"), b -> activeTab = TAB_ROUTE)
                .bounds(panelX + 6, top + 4, 42, 16).build());
        this.addRenderableWidget(Button.builder(screenText("tab.dispatch"), b -> activeTab = TAB_DISPATCH)
                .bounds(panelX + 52, top + 4, 42, 16).build());
        this.addRenderableWidget(Button.builder(screenText("tab.warehouse"), b -> activeTab = TAB_STORAGE)
                .bounds(panelX + 98, top + 4, 42, 16).build());
        this.addRenderableWidget(Button.builder(screenText("tab.waybill"), b -> activeTab = TAB_WAYBILL)
                .bounds(panelX + 144, top + 4, 44, 16).build());
        this.addRenderableWidget(Button.builder(screenText("refresh"), b -> send(DockGuiActionPacket.Action.REFRESH))
                .bounds(panelX + 146, top + 160, 40, 16).build());
        this.importButton = this.addRenderableWidget(Button.builder(screenText("import"), b -> send(DockGuiActionPacket.Action.IMPORT_BOOK))
                .bounds(panelX + 146, top + 44, 40, 16).build());
        this.reverseButton = this.addRenderableWidget(Button.builder(screenText("reverse"), b -> send(DockGuiActionPacket.Action.REVERSE_ROUTE))
                .bounds(panelX + 146, top + 66, 40, 16).build());
        this.autoRouteButton = this.addRenderableWidget(Button.builder(screenText("auto_route"), b -> {
            ModNetwork.CHANNEL.sendToServer(new com.monpai.sailboatmod.network.packet.RequestAutoRouteDocksPacket(data.dockPos()));
        }).bounds(panelX + 146, top + 124, 40, 16).build());
        this.deleteButton = this.addRenderableWidget(Button.builder(screenText("delete"), b -> send(DockGuiActionPacket.Action.DELETE_ROUTE))
                .bounds(panelX + 146, top + 142, 40, 16).build());
        this.assignButton = this.addRenderableWidget(Button.builder(screenText("assign"), b -> send(DockGuiActionPacket.Action.ASSIGN_SELECTED))
                .bounds(panelX + 8, top + 44, 60, 16).build());
        this.dispatchCargoButton = this.addRenderableWidget(Button.builder(screenText("ship_cargo"), b -> send(DockGuiActionPacket.Action.DISPATCH_SELECTED_CARGO))
                .bounds(panelX + 72, top + 44, 74, 16).build());
        this.nonOrderAutoReturnButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> send(DockGuiActionPacket.Action.TOGGLE_NON_ORDER_AUTO_RETURN))
                .bounds(panelX + 8, top + 126, 132, 16).build());
        this.nonOrderAutoUnloadButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> send(DockGuiActionPacket.Action.TOGGLE_NON_ORDER_AUTO_UNLOAD))
                .bounds(panelX + 8, top + 144, 132, 16).build());
        this.takeStorageButton = this.addRenderableWidget(Button.builder(screenText("withdraw"), b -> send(DockGuiActionPacket.Action.TAKE_SELECTED_STORAGE))
                .bounds(panelX + 146, top + 66, 40, 16).build());
        this.takeWaybillButton = this.addRenderableWidget(Button.builder(screenText("take"), b -> send(DockGuiActionPacket.Action.TAKE_SELECTED_WAYBILL))
                .bounds(panelX + 146, top + 142, 40, 16).build());

        this.dockNameInput = new EditBox(this.font, panelX + 36, top + 22, 108, 16, screenText("name_input"));
        this.dockNameInput.setMaxLength(64);
        this.dockNameInput.setValue(data.dockName());
        this.addRenderableWidget(this.dockNameInput);
        this.addRenderableWidget(Button.builder(screenText("name_save"), b -> {
            ModNetwork.CHANNEL.sendToServer(new RenameDockPacket(data.dockPos(), dockNameInput.getValue()));
        }).bounds(panelX + 146, top + 22, 40, 16).build());

        send(DockGuiActionPacket.Action.REFRESH);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        hoveredMinimapBoatName = null;
        updateActionButtonVisibility();
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xCC6E4B2A);
        guiGraphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xCC49311F);
        drawMenuSlotFrames(guiGraphics);
        guiGraphics.fill(rightPanelX, rightPanelY, rightPanelX + RIGHT_PANEL_W, rightPanelY + imageHeight, 0xCC6E4B2A);
        guiGraphics.fill(rightPanelX + 1, rightPanelY + 1, rightPanelX + RIGHT_PANEL_W - 1, rightPanelY + imageHeight - 1, 0xCC49311F);
        guiGraphics.drawString(font, screenText("name"), rightPanelX + 8, rightPanelY + 26, 0xFFF4CF8A);
        guiGraphics.drawString(font, screenText("book"), rightPanelX + 8, rightPanelY + 44, 0xFFF4CF8A);
        if (activeTab == TAB_ROUTE) {
            guiGraphics.drawString(font, screenText("slot_hint"), rightPanelX + 30, rightPanelY + 44, 0xFFAEDAD1);
            String ownerLine = Component.translatable("screen.sailboatmod.owner_name", data.dockOwnerName()).getString();
            guiGraphics.drawString(font, Component.literal(trimToWidth(ownerLine, 136)), rightPanelX + 8, rightPanelY + 52, 0xFFAEDAD1);
        }
        int slotLabelColor = data.canManageDock() ? 0xFFF4CF8A : 0xFFAAA39A;
        guiGraphics.drawString(font, screenText("book_slot"), leftPos + 8, topPos + 6, slotLabelColor);
        guiGraphics.drawString(font, screenText("storage_slot"), leftPos + 8, topPos + 32, slotLabelColor);
        guiGraphics.fill(leftPos + 6, topPos + 38, leftPos + imageWidth - 6, topPos + 39, 0xFF9F7A4A);

        if (activeTab == TAB_ROUTE) {
            drawRouteTab(guiGraphics, mouseX, mouseY);
        } else if (activeTab == TAB_DISPATCH) {
            drawDispatchTab(guiGraphics);
        } else if (activeTab == TAB_STORAGE) {
            drawStorageTab(guiGraphics);
        } else {
            drawWaybillTab(guiGraphics);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderTooltip(guiGraphics, mouseX, mouseY);
        if (hoveredMinimapBoatName != null && !hoveredMinimapBoatName.isBlank()) {
            guiGraphics.renderTooltip(this.font, Component.literal(hoveredMinimapBoatName), mouseX, mouseY);
        }
        if (dockNameInput != null && !dockNameInput.isFocused()) {
            dockNameInput.setValue(data.dockName());
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (this.minecraft != null
                && this.minecraft.options.keyInventory.matches(keyCode, scanCode)
                && (dockNameInput == null || !dockNameInput.isFocused())) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        try {
            if (!closingContainer && minecraft != null && minecraft.player != null && minecraft.player.containerMenu == menu) {
                closingContainer = true;
                minecraft.player.closeContainer();
            }
        } finally {
            closingContainer = false;
        }
        super.onClose();
    }

    private void drawRouteTab(GuiGraphics g, int mouseX, int mouseY) {
        int listX = rightPanelX + 8;
        int listY = rightPanelY + 62;
        int listW = 128;
        drawListFrame(g, listX, listY, listW, META_VISIBLE_ROWS * META_ROW_H);
        drawRouteList(g, listX, listY, listW, true);

        int mx = rightPanelX + MINIMAP_X;
        int my = rightPanelY + MINIMAP_Y;
        int mw = MINIMAP_W;
        int mh = MINIMAP_H;
        drawMiniMap(g, mx, my, mw, mh, mouseX, mouseY);
    }

    private void drawDispatchTab(GuiGraphics g) {
        int boatsX = rightPanelX + DISPATCH_BOATS_X;
        int listsY = rightPanelY + 62;
        int boatsW = DISPATCH_BOATS_W;
        int routesX = rightPanelX + DISPATCH_ROUTES_X;
        int routesW = DISPATCH_ROUTES_W;
        drawListFrame(g, boatsX, listsY, boatsW, DISPATCH_VISIBLE_ROWS * COMPACT_ROW_H);
        drawBoatList(g, boatsX, listsY, boatsW);
        drawListFrame(g, routesX, listsY, routesW, DISPATCH_VISIBLE_ROWS * COMPACT_ROW_H);
        drawRouteList(g, routesX, listsY, routesW, false);
    }

    private void drawStorageTab(GuiGraphics g) {
        int storageX = rightPanelX + 8;
        int storageY = rightPanelY + 62;
        int storageW = 178;
        int storageH = STORAGE_VISIBLE_ROWS * STORAGE_ROW_H + 12;
        drawListFrame(g, storageX, storageY, storageW, storageH);
        g.drawString(font, screenText("storage_title"), storageX + 4, storageY + 3, 0xFFF4CF8A);
        drawStorageList(g, storageX, storageY + 12, storageW, storageH - 12);
    }

    private void drawWaybillTab(GuiGraphics g) {
        int listX = rightPanelX + 8;
        int listY = rightPanelY + 62;
        int listW = 178;
        int listH = WAYBILL_VISIBLE_ROWS * WAYBILL_ROW_H;
        drawListFrame(g, listX, listY, listW, listH);
        drawWaybillList(g, listX, listY, listW);

        int infoX = listX;
        int infoY = listY + listH + 4;
        int infoW = listW;
        int infoH = 30;
        drawListFrame(g, infoX, infoY, infoW, infoH);
        int lineY = infoY + 3;
        int maxWidth = infoW - 6;
        int maxBottom = infoY + infoH - 10;
        for (String line : data.selectedWaybillCargoLines()) {
            if (lineY > maxBottom) {
                break;
            }
            g.drawString(font, Component.literal(trimToWidth(line, maxWidth)), infoX + 3, lineY, 0xFFAEDAD1);
            lineY += 9;
        }
        for (String line : data.selectedWaybillInfoLines()) {
            if (lineY > maxBottom) {
                break;
            }
            g.drawString(font, Component.literal(trimToWidth(line, maxWidth)), infoX + 3, lineY, 0xFFE7E3D8);
            lineY += 9;
        }
    }

    private void drawMiniMap(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF8EAF9E);
        g.fill(x, y, x + w, y + h, 0xAA0B110F);
        if (minecraft == null || minecraft.level == null) {
            return;
        }
        int radius = DockBlockEntity.MINIMAP_RADIUS;
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int ox = (int) Math.round((px / (double) (w - 1) * 2.0D - 1.0D) * radius);
                int oz = (int) Math.round((py / (double) (h - 1) * 2.0D - 1.0D) * radius);
                int wx = data.dockPos().getX() + ox;
                int wz = data.dockPos().getZ() + oz;
                int wy = minecraft.level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
                var pos = new net.minecraft.core.BlockPos(wx, wy, wz);
                int color;
                if (wy >= minecraft.level.getMinBuildHeight() && minecraft.level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)) {
                    color = useLandMinimapPalette() ? 0xFF27415A : 0xFF2F8FBF;
                } else {
                    MapColor mc = minecraft.level.getBlockState(pos).getMapColor(minecraft.level, pos);
                    int base = mc == null ? 0x55606A : mc.col;
                    if (useLandMinimapPalette()) {
                        int r = (((base >> 16) & 0xFF) + 0x76) / 2;
                        int gCol = (((base >> 8) & 0xFF) + 0x95) / 2;
                        int b = ((base & 0xFF) + 0x62) / 2;
                        base = (r << 16) | (gCol << 8) | b;
                    }
                    color = 0xFF000000 | (base & 0xFFFFFF);
                }
                g.fill(x + px, y + py, x + px + 1, y + py + 1, color);
            }
        }

        int rx1 = x + toMiniX(data.zoneMinX(), w, radius);
        int rx2 = x + toMiniX(data.zoneMaxX(), w, radius);
        int rz1 = y + toMiniZ(data.zoneMinZ(), h, radius);
        int rz2 = y + toMiniZ(data.zoneMaxZ(), h, radius);
        g.fill(Math.min(rx1, rx2), Math.min(rz1, rz2), Math.max(rx1, rx2) + 1, Math.max(rz1, rz2) + 1, 0x3345D7A8);
        drawRect(g, rx1, rz1, rx2, rz2, 0xFF56DDB4);
        drawRoutePreview(g, x, y, w, h, radius);
        drawNearbyBoatsOnMiniMap(g, x, y, w, h, radius, mouseX, mouseY);

        if (selectingZone) {
            int sx = x + selectStartPx;
            int sz = y + selectStartPz;
            int ex = x + selectNowPx;
            int ez = y + selectNowPz;
            drawRect(g, sx, sz, ex, ez, 0xFFFFE28A);
        }
    }

    private void drawRect(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2);
        int maxY = Math.max(y1, y2);
        g.fill(minX, minY, maxX + 1, minY + 1, color);
        g.fill(minX, maxY, maxX + 1, maxY + 1, color);
        g.fill(minX, minY, minX + 1, maxY + 1, color);
        g.fill(maxX, minY, maxX + 1, maxY + 1, color);
    }

    private void drawRoutePreview(GuiGraphics g, int mapX, int mapY, int mapW, int mapH, int radius) {
        List<Vec3> points = data.selectedRouteWaypoints();
        if (points == null || points.isEmpty()) {
            return;
        }

        int prevX = toRouteMiniX(points.get(0), mapX, mapW, radius);
        int prevZ = toRouteMiniZ(points.get(0), mapY, mapH, radius);
        for (int i = 1; i < points.size(); i++) {
            int currX = toRouteMiniX(points.get(i), mapX, mapW, radius);
            int currZ = toRouteMiniZ(points.get(i), mapY, mapH, radius);
            drawMiniLine(g, prevX, prevZ, currX, currZ, 0xFFEFD36A);
            prevX = currX;
            prevZ = currZ;
        }

        int startX = toRouteMiniX(points.get(0), mapX, mapW, radius);
        int startZ = toRouteMiniZ(points.get(0), mapY, mapH, radius);
        int endX = toRouteMiniX(points.get(points.size() - 1), mapX, mapW, radius);
        int endZ = toRouteMiniZ(points.get(points.size() - 1), mapY, mapH, radius);
        drawMiniDot(g, startX, startZ, 0xFF6BFF95);
        drawMiniDot(g, endX, endZ, 0xFFFF6F6F);
    }

    private int toRouteMiniX(Vec3 waypoint, int mapX, int mapW, int radius) {
        int offsetX = (int) Math.round(waypoint.x - data.dockPos().getX());
        return mapX + toMiniX(offsetX, mapW, radius);
    }

    private int toRouteMiniZ(Vec3 waypoint, int mapY, int mapH, int radius) {
        int offsetZ = (int) Math.round(waypoint.z - data.dockPos().getZ());
        return mapY + toMiniZ(offsetZ, mapH, radius);
    }

    private void drawMiniLine(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        if (steps <= 0) {
            g.fill(x1, y1, x1 + 1, y1 + 1, color);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            int px = x1 + (x2 - x1) * i / steps;
            int py = y1 + (y2 - y1) * i / steps;
            g.fill(px, py, px + 1, py + 1, color);
        }
    }

    private void drawMiniDot(GuiGraphics g, int x, int y, int color) {
        g.fill(x - 1, y - 1, x + 2, y + 2, color);
    }

    private void drawNearbyBoatsOnMiniMap(GuiGraphics g, int mapX, int mapY, int mapW, int mapH, int radius, int mouseX, int mouseY) {
        if (data.nearbyBoatPositions().isEmpty()) {
            return;
        }
        String hoveredName = null;
        int hoveredDistSq = Integer.MAX_VALUE;
        int count = Math.min(data.nearbyBoatNames().size(), data.nearbyBoatPositions().size());
        for (int i = 0; i < count; i++) {
            Vec3 boat = data.nearbyBoatPositions().get(i);
            int bx = mapX + toMiniX((int) Math.round(boat.x - data.dockPos().getX()), mapW, radius);
            int bz = mapY + toMiniZ((int) Math.round(boat.z - data.dockPos().getZ()), mapH, radius);
            int dx = mouseX - bx;
            int dz = mouseY - bz;
            int distSq = dx * dx + dz * dz;
            boolean hovered = distSq <= 9;
            drawMiniDot(g, bx, bz, hovered ? 0xFFFFD166 : 0xFFFFFFFF);
            if (hovered && distSq < hoveredDistSq) {
                hoveredDistSq = distSq;
                hoveredName = data.nearbyBoatNames().get(i);
            }
        }
        hoveredMinimapBoatName = hoveredName;
    }

    private int toMiniX(int ox, int w, int radius) {
        return MthClamp((int) Math.round((ox + radius) / (double) (radius * 2) * (w - 1)), 0, w - 1);
    }

    private int toMiniZ(int oz, int h, int radius) {
        return MthClamp((int) Math.round((oz + radius) / (double) (radius * 2) * (h - 1)), 0, h - 1);
    }

    private int fromMiniToOffsetX(int px, int w, int radius) {
        double t = px / (double) (w - 1);
        return (int) Math.round(t * radius * 2 - radius);
    }

    private int fromMiniToOffsetZ(int pz, int h, int radius) {
        double t = pz / (double) (h - 1);
        return (int) Math.round(t * radius * 2 - radius);
    }

    private int MthClamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private void drawListFrame(GuiGraphics guiGraphics, int x, int y, int w, int h) {
        guiGraphics.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF8EAF9E);
        guiGraphics.fill(x, y, x + w, y + h, 0xAA1A1916);
    }

    private void drawMenuSlotFrames(GuiGraphics guiGraphics) {
        for (Slot slot : this.menu.slots) {
            int x = this.leftPos + slot.x;
            int y = this.topPos + slot.y;
            drawVanillaSlotFrame(guiGraphics, x, y);
        }
    }

    private void drawVanillaSlotFrame(GuiGraphics guiGraphics, int x, int y) {
        guiGraphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFFAA8A5A);
        guiGraphics.fill(x, y, x + 16, y + 16, 0xFF21160E);
        guiGraphics.fill(x + 1, y + 1, x + 15, y + 15, 0xFF3C2A1A);
    }

    private void drawRouteList(GuiGraphics g, int x, int y, int w, boolean showMeta) {
        if (data.routeNames().isEmpty()) {
            g.drawString(font, screenText("no_route"), x + 4, y + 4, 0xFFAAA39A);
            return;
        }
        int rowHeight = showMeta ? META_ROW_H : COMPACT_ROW_H;
        int visibleRows = showMeta ? META_VISIBLE_ROWS : DISPATCH_VISIBLE_ROWS;
        int selected = Math.max(0, Math.min(data.selectedRouteIndex(), data.routeNames().size() - 1));
        int start = Math.max(0, Math.min(selected - visibleRows / 2, Math.max(0, data.routeNames().size() - visibleRows)));
        int end = Math.min(data.routeNames().size(), start + visibleRows);
        for (int i = start; i < end; i++) {
            int row = i - start;
            int ry = y + row * rowHeight;
            int bg = i == selected ? 0x8860B6A7 : 0x220E0905;
            g.fill(x + 1, ry + 1, x + w - 1, ry + rowHeight - 1, bg);
            String routeLine = (i + 1) + "." + (data.routeNames().get(i) == null ? "" : data.routeNames().get(i));
            g.drawString(font, Component.literal(trimToWidth(routeLine, w - 6)), x + 3, ry + 2, 0xFFE7E3D8);
            if (showMeta && i < data.routeMetas().size()) {
                g.drawString(font, Component.literal(trimToWidth(data.routeMetas().get(i), w - 6)), x + 3, ry + 11, 0xFFAEDAD1);
            }
        }
    }

    private void drawBoatList(GuiGraphics g, int x, int y, int w) {
        if (data.nearbyBoatNames().isEmpty()) {
            g.drawString(font, Component.literal("-"), x + 4, y + 4, 0xFFAAA39A);
            return;
        }
        int selected = Math.max(0, Math.min(data.selectedBoatIndex(), data.nearbyBoatNames().size() - 1));
        int start = Math.max(0, Math.min(selected - DISPATCH_VISIBLE_ROWS / 2, Math.max(0, data.nearbyBoatNames().size() - DISPATCH_VISIBLE_ROWS)));
        int end = Math.min(data.nearbyBoatNames().size(), start + DISPATCH_VISIBLE_ROWS);
        for (int i = start; i < end; i++) {
            int row = i - start;
            int ry = y + row * COMPACT_ROW_H;
            int bg = i == selected ? 0x8860B6A7 : 0x220E0905;
            g.fill(x + 1, ry + 1, x + w - 1, ry + COMPACT_ROW_H - 1, bg);
            String boatLine = (i + 1) + "." + (data.nearbyBoatNames().get(i) == null ? "" : data.nearbyBoatNames().get(i));
            g.drawString(font, Component.literal(trimToWidth(boatLine, w - 6)), x + 3, ry + 2, 0xFFE7E3D8);
        }
    }

    private void drawWaybillList(GuiGraphics g, int x, int y, int w) {
        if (data.waybillNames().isEmpty()) {
            g.drawString(font, screenText("no_waybill"), x + 4, y + 4, 0xFFAAA39A);
            return;
        }
        int selected = Math.max(0, Math.min(data.selectedWaybillIndex(), data.waybillNames().size() - 1));
        int start = Math.max(0, Math.min(selected - WAYBILL_VISIBLE_ROWS / 2, Math.max(0, data.waybillNames().size() - WAYBILL_VISIBLE_ROWS)));
        int end = Math.min(data.waybillNames().size(), start + WAYBILL_VISIBLE_ROWS);
        for (int i = start; i < end; i++) {
            int row = i - start;
            int ry = y + row * WAYBILL_ROW_H;
            int bg = i == selected ? 0x8860B6A7 : 0x220E0905;
            g.fill(x + 1, ry + 1, x + w - 1, ry + WAYBILL_ROW_H - 1, bg);
            String line = (i + 1) + "." + (data.waybillNames().get(i) == null ? "" : data.waybillNames().get(i));
            g.drawString(font, Component.literal(trimToWidth(line, w - 6)), x + 3, ry + 2, 0xFFE7E3D8);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (activeTab == TAB_ROUTE && data.canManageDock() && button == 0 && tryStartZoneSelect(mouseX, mouseY)) {
            return true;
        }
        if (button == 0 && (activeTab == TAB_ROUTE || activeTab == TAB_DISPATCH) && tryClickRouteList(mouseX, mouseY)) {
            return true;
        }
        if (button == 0 && activeTab == TAB_DISPATCH && tryClickBoatList(mouseX, mouseY)) {
            return true;
        }
        if (button == 0 && activeTab == TAB_STORAGE && tryClickStorageList(mouseX, mouseY)) {
            return true;
        }
        if (button == 0 && activeTab == TAB_WAYBILL && tryClickWaybillList(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (selectingZone && button == 0) {
            int[] mini = minimapBounds();
            int px = MthClamp((int) mouseX - mini[0], 0, mini[2] - 1);
            int pz = MthClamp((int) mouseY - mini[1], 0, mini[3] - 1);
            selectNowPx = px;
            selectNowPz = pz;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (selectingZone && button == 0) {
            selectingZone = false;
            int[] mini = minimapBounds();
            int minPx = Math.min(selectStartPx, selectNowPx);
            int maxPx = Math.max(selectStartPx, selectNowPx);
            int minPz = Math.min(selectStartPz, selectNowPz);
            int maxPz = Math.max(selectStartPz, selectNowPz);
            int r = DockBlockEntity.MINIMAP_RADIUS;
            int minX = fromMiniToOffsetX(minPx, mini[2], r);
            int maxX = fromMiniToOffsetX(maxPx, mini[2], r);
            int minZ = fromMiniToOffsetZ(minPz, mini[3], r);
            int maxZ = fromMiniToOffsetZ(maxPz, mini[3], r);
            ModNetwork.CHANNEL.sendToServer(new SetDockZonePacket(data.dockPos(), minX, maxX, minZ, maxZ));
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private boolean tryStartZoneSelect(double mouseX, double mouseY) {
        int[] mini = minimapBounds();
        if (mouseX < mini[0] || mouseX >= mini[0] + mini[2] || mouseY < mini[1] || mouseY >= mini[1] + mini[3]) {
            return false;
        }
        selectingZone = true;
        selectStartPx = MthClamp((int) mouseX - mini[0], 0, mini[2] - 1);
        selectStartPz = MthClamp((int) mouseY - mini[1], 0, mini[3] - 1);
        selectNowPx = selectStartPx;
        selectNowPz = selectStartPz;
        return true;
    }

    private int[] minimapBounds() {
        return new int[] { rightPanelX + MINIMAP_X, rightPanelY + MINIMAP_Y, MINIMAP_W, MINIMAP_H };
    }

    private boolean tryClickRouteList(double mouseX, double mouseY) {
        int x = activeTab == TAB_ROUTE ? rightPanelX + 8 : rightPanelX + DISPATCH_ROUTES_X;
        int y = rightPanelY + 62;
        int w = activeTab == TAB_ROUTE ? 128 : DISPATCH_ROUTES_W;
        int rowHeight = activeTab == TAB_ROUTE ? META_ROW_H : COMPACT_ROW_H;
        int visibleRows = activeTab == TAB_ROUTE ? META_VISIBLE_ROWS : DISPATCH_VISIBLE_ROWS;
        if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + visibleRows * rowHeight || data.routeNames().isEmpty()) {
            return false;
        }
        int selected = Math.max(0, Math.min(data.selectedRouteIndex(), data.routeNames().size() - 1));
        int start = Math.max(0, Math.min(selected - visibleRows / 2, Math.max(0, data.routeNames().size() - visibleRows)));
        int row = (int) ((mouseY - y) / rowHeight);
        int idx = start + row;
        if (idx < 0 || idx >= data.routeNames().size()) {
            return false;
        }
        send(DockGuiActionPacket.Action.SELECT_ROUTE_INDEX, idx);
        return true;
    }

    private boolean tryClickBoatList(double mouseX, double mouseY) {
        int x = rightPanelX + DISPATCH_BOATS_X;
        int y = rightPanelY + 62;
        int w = DISPATCH_BOATS_W;
        if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + DISPATCH_VISIBLE_ROWS * COMPACT_ROW_H || data.nearbyBoatNames().isEmpty()) {
            return false;
        }
        int selected = Math.max(0, Math.min(data.selectedBoatIndex(), data.nearbyBoatNames().size() - 1));
        int start = Math.max(0, Math.min(selected - DISPATCH_VISIBLE_ROWS / 2, Math.max(0, data.nearbyBoatNames().size() - DISPATCH_VISIBLE_ROWS)));
        int row = (int) ((mouseY - y) / COMPACT_ROW_H);
        int idx = start + row;
        if (idx < 0 || idx >= data.nearbyBoatNames().size()) {
            return false;
        }
        send(DockGuiActionPacket.Action.SELECT_BOAT_INDEX, idx);
        return true;
    }

    private boolean tryClickStorageList(double mouseX, double mouseY) {
        int x = rightPanelX + 8;
        int y = rightPanelY + 74;
        int w = 178;
        int h = STORAGE_VISIBLE_ROWS * STORAGE_ROW_H;
        if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + h || data.storageLines().isEmpty()) {
            return false;
        }
        int selected = Math.max(0, Math.min(data.selectedStorageIndex(), data.storageLines().size() - 1));
        int start = Math.max(0, Math.min(selected - STORAGE_VISIBLE_ROWS / 2, Math.max(0, data.storageLines().size() - STORAGE_VISIBLE_ROWS)));
        int row = (int) ((mouseY - y) / STORAGE_ROW_H);
        int idx = start + row;
        if (idx < 0 || idx >= data.storageLines().size()) {
            return false;
        }
        send(DockGuiActionPacket.Action.SELECT_STORAGE_INDEX, idx);
        return true;
    }

    private boolean tryClickWaybillList(double mouseX, double mouseY) {
        int x = rightPanelX + 8;
        int y = rightPanelY + 62;
        int w = 178;
        if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + WAYBILL_VISIBLE_ROWS * WAYBILL_ROW_H || data.waybillNames().isEmpty()) {
            return false;
        }
        int selected = Math.max(0, Math.min(data.selectedWaybillIndex(), data.waybillNames().size() - 1));
        int start = Math.max(0, Math.min(selected - WAYBILL_VISIBLE_ROWS / 2, Math.max(0, data.waybillNames().size() - WAYBILL_VISIBLE_ROWS)));
        int row = (int) ((mouseY - y) / WAYBILL_ROW_H);
        int idx = start + row;
        if (idx < 0 || idx >= data.waybillNames().size()) {
            return false;
        }
        send(DockGuiActionPacket.Action.SELECT_WAYBILL_INDEX, idx);
        return true;
    }

    private void send(DockGuiActionPacket.Action action) {
        ModNetwork.CHANNEL.sendToServer(new DockGuiActionPacket(data.dockPos(), action));
    }

    private void send(DockGuiActionPacket.Action action, int value) {
        ModNetwork.CHANNEL.sendToServer(new DockGuiActionPacket(data.dockPos(), action, value));
    }

    private String trim(String src, int max) {
        if (src == null) {
            return "";
        }
        return src.length() <= max ? src : src.substring(0, max) + "...";
    }

    private String trimToWidth(String src, int maxPixels) {
        if (src == null || src.isEmpty()) {
            return "";
        }
        if (maxPixels <= 0 || font.width(src) <= maxPixels) {
            return src;
        }
        String ellipsis = "...";
        int ellipsisWidth = font.width(ellipsis);
        if (ellipsisWidth >= maxPixels) {
            return "";
        }
        int end = src.length();
        while (end > 0 && font.width(src.substring(0, end)) + ellipsisWidth > maxPixels) {
            end--;
        }
        return src.substring(0, Math.max(0, end)) + ellipsis;
    }

    private void updateActionButtonVisibility() {
        boolean onRoute = activeTab == TAB_ROUTE;
        boolean onDispatch = activeTab == TAB_DISPATCH;
        boolean onStorage = activeTab == TAB_STORAGE;
        boolean onWaybill = activeTab == TAB_WAYBILL;
        if (importButton != null) {
            importButton.visible = onRoute;
            importButton.active = onRoute && data.canManageDock();
        }
        if (reverseButton != null) {
            reverseButton.visible = onRoute;
            reverseButton.active = onRoute && data.canManageDock();
        }
        if (autoRouteButton != null) {
            autoRouteButton.visible = onRoute;
            autoRouteButton.active = onRoute && data.canManageDock();
        }
        if (deleteButton != null) {
            deleteButton.visible = onRoute;
            deleteButton.active = onRoute && data.canManageDock();
        }
        if (assignButton != null) {
            assignButton.visible = onDispatch;
            assignButton.active = onDispatch;
        }
        if (dispatchCargoButton != null) {
            dispatchCargoButton.visible = onStorage;
            dispatchCargoButton.active = onStorage && data.canManageDock() && !data.storageLines().isEmpty() && !data.nearbyBoatNames().isEmpty() && !data.routeNames().isEmpty();
        }
        if (takeStorageButton != null) {
            takeStorageButton.visible = onStorage;
            takeStorageButton.active = onStorage && data.canManageDock() && !data.storageLines().isEmpty();
        }
        if (nonOrderAutoReturnButton != null) {
            nonOrderAutoReturnButton.visible = onDispatch;
            nonOrderAutoReturnButton.active = onDispatch && data.canManageDock();
            nonOrderAutoReturnButton.setMessage(screenText(
                    data.nonOrderAutoReturnEnabled()
                            ? "non_order_auto.on"
                            : "non_order_auto.off"));
        }
        if (nonOrderAutoUnloadButton != null) {
            nonOrderAutoUnloadButton.visible = onDispatch;
            nonOrderAutoUnloadButton.active = onDispatch && data.canManageDock();
            nonOrderAutoUnloadButton.setMessage(screenText(
                    data.nonOrderAutoUnloadEnabled()
                            ? "non_order_unload.on"
                            : "non_order_unload.off"));
        }
        if (takeWaybillButton != null) {
            takeWaybillButton.visible = onWaybill;
            takeWaybillButton.active = onWaybill && !data.waybillNames().isEmpty();
        }
        if (dockNameInput != null) {
            dockNameInput.setEditable(data.canManageDock());
        }
    }

    private void updateRightPanelAnchor() {
        this.rightPanelY = this.topPos;
        int desired = this.leftPos + this.imageWidth + RIGHT_PANEL_GAP - RIGHT_PANEL_OVERLAP;
        int maxAllowed = this.width - RIGHT_PANEL_W - 8;
        this.rightPanelX = Math.max(8, Math.min(desired, maxAllowed));
    }

    private DockScreenData empty(net.minecraft.core.BlockPos pos) {
        return new DockScreenData(
                pos,
                Component.translatable(defaultFacilityNameKey()).getString(),
                "-",
                "",
                false,
                false,
                false,
                ItemStack.EMPTY,
                List.of(),
                List.of(),
                0,
                List.of(),
                -12,
                12,
                -8,
                8,
                List.of(),
                List.of(),
                List.of(),
                0,
                List.of(),
                0,
                List.of(),
                0,
                List.of(),
                List.of()
        );
    }

    private void drawStorageList(GuiGraphics g, int x, int y, int w, int h) {
        if (!data.canManageDock()) {
            g.drawString(font, screenText("storage_owner_only"), x + 4, y + 4, 0xFFAAA39A);
            return;
        }
        if (data.storageLines().isEmpty()) {
            g.drawString(font, screenText("storage_empty"), x + 4, y + 4, 0xFFAAA39A);
            return;
        }
        int selected = Math.max(0, Math.min(data.selectedStorageIndex(), data.storageLines().size() - 1));
        int start = Math.max(0, Math.min(selected - STORAGE_VISIBLE_ROWS / 2, Math.max(0, data.storageLines().size() - STORAGE_VISIBLE_ROWS)));
        int end = Math.min(data.storageLines().size(), start + STORAGE_VISIBLE_ROWS);
        for (int i = start; i < end; i++) {
            int row = i - start;
            int ry = y + row * STORAGE_ROW_H;
            int bg = i == selected ? 0x8860B6A7 : 0x220E0905;
            g.fill(x + 1, ry + 1, x + w - 1, ry + STORAGE_ROW_H - 1, bg);
            String line = (i + 1) + "." + (data.storageLines().get(i) == null ? "" : data.storageLines().get(i));
            g.drawString(font, Component.literal(trimToWidth(line, w - 6)), x + 3, ry + 2, 0xFFE7E3D8);
        }
    }
}
