package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.client.PostStationClientHooks;
import com.monpai.sailboatmod.dock.DockScreenData;
import com.monpai.sailboatmod.dock.PostStationScreenData;
import com.monpai.sailboatmod.menu.PostStationMenu;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.PostStationGuiActionPacket;
import com.monpai.sailboatmod.network.packet.RenamePostStationPacket;
import com.monpai.sailboatmod.network.packet.SetDockZonePacket;
import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class PostStationScreen extends AbstractContainerScreen<PostStationMenu> {
    private static final int TAB_DESTINATIONS = 0;
    private static final int TAB_VEHICLES = 1;
    private static final int TAB_DISPATCH = 2;
    private static final int TAB_ADVANCED = 3;
    private static final int TAB_RENAME_ZONE = 4;
    private static final int MAIN_PANEL_W = 194;
    private static final int RIGHT_PANEL_W = 194;
    private static final int RIGHT_PANEL_GAP = 6;
    private static final int ROW_H = 16;
    // 改名与范围分页的 zone 编辑小地图（相对右面板）
    private static final int MINIMAP_X = 8;
    private static final int MINIMAP_Y = 56;
    private static final int MINIMAP_W = 150;
    private static final int MINIMAP_H = 110;

    private PostStationScreenData data;
    private int activeTab = TAB_DESTINATIONS;
    private int rightPanelX;
    private int rightPanelY;
    private Button dispatchButton;
    private Button recallButton;
    private Button autoReturnButton;
    private Button autoUnloadButton;
    private Button importButton;
    private Button reverseButton;
    private Button deleteButton;
    private Button takeStorageButton;
    private Button takeWaybillButton;
    private EditBox nameInput;
    private Button renameButton;
    // zone 拖拽编辑状态（移植自 DockScreen）
    private boolean selectingZone = false;
    private int selectStartPx;
    private int selectStartPz;
    private int selectNowPx;
    private int selectNowPz;
    private String hoveredMinimapBoatName;

    public PostStationScreen(PostStationMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = combinedImageWidth();
        this.imageHeight = 176;
        PostStationScreenData initial = PostStationClientHooks.consumeFor(menu.getDockPos());
        this.data = initial != null ? initial : empty(menu.getDockPos());
    }

    public static PostStationScreen create(PostStationMenu menu, Inventory inventory, Component title) {
        return new PostStationScreen(menu, inventory, title);
    }

    public boolean isForStation(BlockPos pos) {
        return data.stationPos().equals(pos);
    }

    public void updateData(PostStationScreenData updated) {
        this.data = updated;
    }

    @Override
    protected void init() {
        super.init();
        this.rightPanelX = this.leftPos + rightPanelOffset();
        this.rightPanelY = this.topPos;
        int x = rightPanelX;
        int y = rightPanelY;
        addRenderableWidget(Button.builder(text("tab.destinations"), button -> activeTab = TAB_DESTINATIONS)
                .bounds(x + 6, y + 4, 34, 16).build());
        addRenderableWidget(Button.builder(text("tab.vehicles"), button -> activeTab = TAB_VEHICLES)
                .bounds(x + 42, y + 4, 32, 16).build());
        addRenderableWidget(Button.builder(text("tab.dispatch"), button -> activeTab = TAB_DISPATCH)
                .bounds(x + 76, y + 4, 32, 16).build());
        addRenderableWidget(Button.builder(text("tab.advanced"), button -> activeTab = TAB_ADVANCED)
                .bounds(x + 110, y + 4, 34, 16).build());
        addRenderableWidget(Button.builder(text("tab.rename_zone"), button -> activeTab = TAB_RENAME_ZONE)
                .bounds(x + 146, y + 4, 42, 16).build());
        addRenderableWidget(Button.builder(text("refresh"), button -> send(PostStationGuiActionPacket.Action.REFRESH))
                .bounds(x + 146, y + 160, 40, 16).build());
        dispatchButton = addRenderableWidget(Button.builder(text("dispatch"), button -> sendDispatchSelected())
                .bounds(x + 8, y + 142, 70, 16).build());
        recallButton = addRenderableWidget(Button.builder(text("recall"), button -> send(PostStationGuiActionPacket.Action.RECALL_SELECTED))
                .bounds(x + 82, y + 142, 60, 16).build());
        autoReturnButton = addRenderableWidget(Button.builder(Component.empty(), button -> send(PostStationGuiActionPacket.Action.TOGGLE_AUTO_RETURN))
                .bounds(x + 8, y + 122, 64, 16).build());
        autoUnloadButton = addRenderableWidget(Button.builder(Component.empty(), button -> send(PostStationGuiActionPacket.Action.TOGGLE_AUTO_UNLOAD))
                .bounds(x + 76, y + 122, 64, 16).build());
        importButton = addRenderableWidget(Button.builder(text("import"), button -> send(PostStationGuiActionPacket.Action.ADV_IMPORT_BOOK))
                .bounds(x + 146, y + 44, 40, 16).build());
        reverseButton = addRenderableWidget(Button.builder(text("reverse"), button -> send(PostStationGuiActionPacket.Action.ADV_REVERSE_ROUTE))
                .bounds(x + 146, y + 66, 40, 16).build());
        deleteButton = addRenderableWidget(Button.builder(text("delete"), button -> send(PostStationGuiActionPacket.Action.ADV_DELETE_ROUTE))
                .bounds(x + 146, y + 142, 40, 16).build());
        takeStorageButton = addRenderableWidget(Button.builder(text("withdraw"), button -> send(PostStationGuiActionPacket.Action.ADV_TAKE_SELECTED_STORAGE))
                .bounds(x + 146, y + 92, 40, 16).build());
        takeWaybillButton = addRenderableWidget(Button.builder(text("take"), button -> send(PostStationGuiActionPacket.Action.ADV_TAKE_SELECTED_WAYBILL))
                .bounds(x + 146, y + 116, 40, 16).build());
        // 改名控件移到「改名与范围」分页：小地图上方
        nameInput = new EditBox(this.font, x + 8, y + 30, 130, 16, text("rename.hint"));
        nameInput.setMaxLength(64);
        nameInput.setValue(data.stationName());
        addRenderableWidget(nameInput);
        renameButton = addRenderableWidget(Button.builder(text("rename.save"), button ->
                        ModNetwork.CHANNEL.sendToServer(new RenamePostStationPacket(data.stationPos(), nameInput.getValue())))
                .bounds(x + 142, y + 30, 44, 16).build());
        send(PostStationGuiActionPacket.Action.REFRESH);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        updateButtons();
        guiGraphics.fill(leftPos, topPos, leftPos + MAIN_PANEL_W, topPos + imageHeight, 0xCC6E4B2A);
        guiGraphics.fill(leftPos + 1, topPos + 1, leftPos + MAIN_PANEL_W - 1, topPos + imageHeight - 1, 0xCC49311F);
        guiGraphics.fill(rightPanelX, rightPanelY, rightPanelX + RIGHT_PANEL_W, rightPanelY + imageHeight, 0xCC6E4B2A);
        guiGraphics.fill(rightPanelX + 1, rightPanelY + 1, rightPanelX + RIGHT_PANEL_W - 1, rightPanelY + imageHeight - 1, 0xCC49311F);
        drawSlotFrames(guiGraphics);
        guiGraphics.drawString(font, text("book_slot"), leftPos + 8, topPos + 6, 0xFFF4CF8A);
        guiGraphics.drawString(font, text("storage_slot"), leftPos + 8, topPos + 32, 0xFFF4CF8A);
        guiGraphics.drawString(font, title, leftPos + 8, topPos + 70, 0xFFF4CF8A);
        guiGraphics.drawString(font, playerInventoryTitle, leftPos + 8, topPos + 76, 0xFFE7E3D8);

        if (activeTab == TAB_DESTINATIONS) {
            drawDestinationList(guiGraphics, rightPanelX + 8, rightPanelY + 26, 178);
        } else if (activeTab == TAB_VEHICLES) {
            drawVehicleList(guiGraphics, rightPanelX + 8, rightPanelY + 26, 178);
        } else if (activeTab == TAB_DISPATCH) {
            drawDispatchTab(guiGraphics, rightPanelX + 8, rightPanelY + 26, 178);
        } else if (activeTab == TAB_RENAME_ZONE) {
            drawRenameZoneTab(guiGraphics, mouseX, mouseY);
        } else {
            drawAdvancedTab(guiGraphics, rightPanelX + 8, rightPanelY + 26, 178);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        if (activeTab == TAB_RENAME_ZONE && hoveredMinimapBoatName != null && !hoveredMinimapBoatName.isBlank()) {
            guiGraphics.renderTooltip(this.font, Component.literal(hoveredMinimapBoatName), mouseX, mouseY);
        }
        renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (activeTab == TAB_RENAME_ZONE && data.canManage() && button == 0 && tryStartZoneSelect(mouseX, mouseY)) {
            return true;
        }
        if (button == 0 && activeTab == TAB_DESTINATIONS && tryClickDestination(mouseX, mouseY)) {
            return true;
        }
        if (button == 0 && activeTab == TAB_VEHICLES && tryClickVehicle(mouseX, mouseY)) {
            return true;
        }
        if (button == 0 && activeTab == TAB_ADVANCED && tryClickAdvanced(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (selectingZone && button == 0) {
            int[] mini = minimapBounds();
            selectNowPx = MthClamp((int) mouseX - mini[0], 0, mini[2] - 1);
            selectNowPz = MthClamp((int) mouseY - mini[1], 0, mini[3] - 1);
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
            ModNetwork.CHANNEL.sendToServer(new SetDockZonePacket(data.stationPos(), minX, maxX, minZ, maxZ));
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void drawDestinationList(GuiGraphics g, int x, int y, int w) {
        List<PostStationScreenData.ReachableTownEntry> towns = data.reachableTowns();
        if (towns.isEmpty()) {
            g.drawString(font, text("empty.no_reachable_town"), x + 4, y + 4, 0xFFE7E3D8);
            return;
        }
        drawSectionTitle(g, text("destinations"), x, y - 10);
        int start = listStart(data.selectedTownIndex(), towns.size(), 7);
        for (int i = 0; i < Math.min(7, towns.size()); i++) {
            int idx = start + i;
            PostStationScreenData.ReachableTownEntry town = towns.get(idx);
            int rowY = y + i * ROW_H;
            int color = idx == data.selectedTownIndex() ? 0xFF2F6F4E : 0xFF49311F;
            g.fill(x, rowY, x + w, rowY + 15, color);
            g.drawString(font, trimToWidth(town.townName(), w - 58), x + 4, rowY + 4, 0xFFF4CF8A);
            g.drawString(font, town.distanceMeters() + "m", x + w - 48, rowY + 4, 0xFFAEDAD1);
        }
    }

    private void drawVehicleList(GuiGraphics g, int x, int y, int w) {
        List<PostStationScreenData.VehicleEntry> vehicles = data.vehicles();
        if (vehicles.isEmpty()) {
            g.drawString(font, text("empty.no_vehicle"), x + 4, y + 4, 0xFFE7E3D8);
            return;
        }
        drawSectionTitle(g, text("vehicles"), x, y - 10);
        int start = listStart(data.selectedVehicleIndex(), vehicles.size(), 7);
        for (int i = 0; i < Math.min(7, vehicles.size()); i++) {
            int idx = start + i;
            PostStationScreenData.VehicleEntry vehicle = vehicles.get(idx);
            int rowY = y + i * ROW_H;
            int color = idx == data.selectedVehicleIndex() ? 0xFF2F6F4E : 0xFF49311F;
            g.fill(x, rowY, x + w, rowY + 15, color);
            g.drawString(font, trimToWidth(vehicle.name(), w - 58), x + 4, rowY + 4, 0xFFF4CF8A);
            g.drawString(font, trimToWidth(vehicle.state(), 48), x + w - 50, rowY + 4, 0xFFAEDAD1);
        }
    }

    private void drawDispatchTab(GuiGraphics g, int x, int y, int w) {
        PostStationScreenData.ReachableTownEntry town = selectedTown();
        PostStationScreenData.VehicleEntry vehicle = selectedVehicle();
        g.drawString(font, text("selected_town"), x + 4, y + 2, 0xFFAEDAD1);
        g.drawString(font, trimToWidth(town == null ? "-" : town.townName(), w - 8), x + 4, y + 14, 0xFFF4CF8A);
        g.drawString(font, text("selected_vehicle"), x + 4, y + 30, 0xFFAEDAD1);
        g.drawString(font, trimToWidth(vehicle == null ? "-" : vehicle.name(), w - 8), x + 4, y + 42, 0xFFF4CF8A);
        drawRouteSummary(g, x, y + 62, w);
    }

    private void drawRouteSummary(GuiGraphics g, int x, int y, int w) {
        PostStationScreenData.RouteSummary summary = data.selectedRouteSummary();
        if (summary == null || summary.routeName().isBlank()) {
            g.drawString(font, text("route.none"), x + 4, y + 4, 0xFFE7E3D8);
            return;
        }
        g.fill(x, y, x + w, y + 52, 0x8849311F);
        g.drawString(font, trimToWidth(summary.routeName(), w - 8), x + 4, y + 4, 0xFFF4CF8A);
        g.drawString(font, summary.distanceMeters() + "m / " + summary.etaSeconds() + "s", x + 4, y + 17, 0xFFAEDAD1);
        if (!summary.passThroughTownNames().isEmpty()) {
            g.drawString(font, trimToWidth(String.join(" > ", summary.passThroughTownNames()), w - 8), x + 4, y + 30, 0xFFE7E3D8);
        }
    }

    private void drawAdvancedTab(GuiGraphics g, int x, int y, int w) {
        DockScreenData advanced = data.advancedData();
        drawSectionTitle(g, text("advanced.routes"), x, y);
        List<String> routes = advanced.routeNames();
        if (routes.isEmpty()) {
            g.drawString(font, text("no_route"), x + 4, y + 14, 0xFFE7E3D8);
        } else {
            int start = listStart(advanced.selectedRouteIndex(), routes.size(), 4);
            for (int i = 0; i < Math.min(4, routes.size()); i++) {
                int idx = start + i;
                int rowY = y + 12 + i * 14;
                g.fill(x, rowY, x + 132, rowY + 13, idx == advanced.selectedRouteIndex() ? 0xFF2F6F4E : 0xFF49311F);
                g.drawString(font, trimToWidth(routes.get(idx), 126), x + 4, rowY + 3, 0xFFF4CF8A);
            }
        }
        drawSectionTitle(g, text("advanced.storage"), x, y + 76);
        drawLineList(g, advanced.storageLines(), advanced.selectedStorageIndex(), x, y + 88, 132, 2, text("storage_empty"));
        drawSectionTitle(g, text("advanced.waybill"), x, y + 122);
        drawLineList(g, advanced.waybillNames(), advanced.selectedWaybillIndex(), x, y + 134, 132, 1, text("no_waybill"));
    }

    // ===== 改名与范围分页：zone 编辑可视化小地图（移植自 DockScreen，数据源 advancedData，恒用陆地调色板） =====

    private void drawRenameZoneTab(GuiGraphics g, int mouseX, int mouseY) {
        hoveredMinimapBoatName = null;
        int x = rightPanelX + 8;
        int y = rightPanelY + 26;
        drawSectionTitle(g, text("rename_zone.title"), x, y);
        if (data.canManage()) {
            g.drawString(font, text("zone_hint"), x, rightPanelY + MINIMAP_Y - 12, 0xFFAEDAD1);
        }
        drawMiniMap(g, rightPanelX + MINIMAP_X, rightPanelY + MINIMAP_Y, MINIMAP_W, MINIMAP_H, mouseX, mouseY);
    }

    private void drawMiniMap(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        DockScreenData adv = data.advancedData();
        BlockPos center = adv.dockPos();
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
                int wx = center.getX() + ox;
                int wz = center.getZ() + oz;
                int wy = minecraft.level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
                BlockPos pos = new BlockPos(wx, wy, wz);
                int color;
                if (wy >= minecraft.level.getMinBuildHeight() && minecraft.level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)) {
                    color = 0xFF27415A; // 驿站陆地调色板：水偏暗
                } else {
                    MapColor mc = minecraft.level.getBlockState(pos).getMapColor(minecraft.level, pos);
                    int base = mc == null ? 0x55606A : mc.col;
                    int r = (((base >> 16) & 0xFF) + 0x76) / 2;
                    int gCol = (((base >> 8) & 0xFF) + 0x95) / 2;
                    int b = ((base & 0xFF) + 0x62) / 2;
                    base = (r << 16) | (gCol << 8) | b;
                    color = 0xFF000000 | (base & 0xFFFFFF);
                }
                g.fill(x + px, y + py, x + px + 1, y + py + 1, color);
            }
        }

        int rx1 = x + toMiniX(adv.zoneMinX(), w, radius);
        int rx2 = x + toMiniX(adv.zoneMaxX(), w, radius);
        int rz1 = y + toMiniZ(adv.zoneMinZ(), h, radius);
        int rz2 = y + toMiniZ(adv.zoneMaxZ(), h, radius);
        g.fill(Math.min(rx1, rx2), Math.min(rz1, rz2), Math.max(rx1, rx2) + 1, Math.max(rz1, rz2) + 1, 0x3345D7A8);
        drawRect(g, rx1, rz1, rx2, rz2, 0xFF56DDB4);
        drawRoutePreview(g, x, y, w, h, radius);
        drawNearbyBoatsOnMiniMap(g, x, y, w, h, radius, mouseX, mouseY);

        if (selectingZone) {
            drawRect(g, x + selectStartPx, y + selectStartPz, x + selectNowPx, y + selectNowPz, 0xFFFFE28A);
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
        List<Vec3> points = data.advancedData().selectedRouteWaypoints();
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
        drawMiniDot(g, toRouteMiniX(points.get(0), mapX, mapW, radius), toRouteMiniZ(points.get(0), mapY, mapH, radius), 0xFF6BFF95);
        drawMiniDot(g, toRouteMiniX(points.get(points.size() - 1), mapX, mapW, radius), toRouteMiniZ(points.get(points.size() - 1), mapY, mapH, radius), 0xFFFF6F6F);
    }

    private int toRouteMiniX(Vec3 waypoint, int mapX, int mapW, int radius) {
        int offsetX = (int) Math.round(waypoint.x - data.advancedData().dockPos().getX());
        return mapX + toMiniX(offsetX, mapW, radius);
    }

    private int toRouteMiniZ(Vec3 waypoint, int mapY, int mapH, int radius) {
        int offsetZ = (int) Math.round(waypoint.z - data.advancedData().dockPos().getZ());
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
        DockScreenData adv = data.advancedData();
        if (adv.nearbyBoatPositions().isEmpty()) {
            return;
        }
        BlockPos center = adv.dockPos();
        String hoveredName = null;
        int hoveredDistSq = Integer.MAX_VALUE;
        int count = Math.min(adv.nearbyBoatNames().size(), adv.nearbyBoatPositions().size());
        for (int i = 0; i < count; i++) {
            Vec3 boat = adv.nearbyBoatPositions().get(i);
            int bx = mapX + toMiniX((int) Math.round(boat.x - center.getX()), mapW, radius);
            int bz = mapY + toMiniZ((int) Math.round(boat.z - center.getZ()), mapH, radius);
            int dx = mouseX - bx;
            int dz = mouseY - bz;
            int distSq = dx * dx + dz * dz;
            boolean hovered = distSq <= 9;
            drawMiniDot(g, bx, bz, hovered ? 0xFFFFD166 : 0xFFFFFFFF);
            if (hovered && distSq < hoveredDistSq) {
                hoveredDistSq = distSq;
                hoveredName = adv.nearbyBoatNames().get(i);
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

    private void drawLineList(GuiGraphics g, List<String> lines, int selected, int x, int y, int w, int visible, Component emptyText) {
        if (lines.isEmpty()) {
            g.drawString(font, emptyText, x + 4, y + 2, 0xFFE7E3D8);
            return;
        }
        int start = listStart(selected, lines.size(), visible);
        for (int i = 0; i < Math.min(visible, lines.size()); i++) {
            int idx = start + i;
            int rowY = y + i * 14;
            g.fill(x, rowY, x + w, rowY + 13, idx == selected ? 0xFF2F6F4E : 0xFF49311F);
            g.drawString(font, trimToWidth(lines.get(idx), w - 8), x + 4, rowY + 3, 0xFFF4CF8A);
        }
    }

    private boolean tryClickDestination(double mouseX, double mouseY) {
        List<PostStationScreenData.ReachableTownEntry> towns = data.reachableTowns();
        return clickList(mouseX, mouseY, rightPanelX + 8, rightPanelY + 26, 178, ROW_H, 7,
                data.selectedTownIndex(), towns.size(), idx -> send(PostStationGuiActionPacket.Action.SELECT_DESTINATION_INDEX, idx));
    }

    private boolean tryClickVehicle(double mouseX, double mouseY) {
        List<PostStationScreenData.VehicleEntry> vehicles = data.vehicles();
        return clickList(mouseX, mouseY, rightPanelX + 8, rightPanelY + 26, 178, ROW_H, 7,
                data.selectedVehicleIndex(), vehicles.size(), idx -> send(PostStationGuiActionPacket.Action.SELECT_VEHICLE_INDEX, idx));
    }

    private boolean tryClickAdvanced(double mouseX, double mouseY) {
        DockScreenData advanced = data.advancedData();
        if (clickList(mouseX, mouseY, rightPanelX + 8, rightPanelY + 38, 132, 14, 4,
                advanced.selectedRouteIndex(), advanced.routeNames().size(), idx -> send(PostStationGuiActionPacket.Action.ADV_SELECT_ROUTE_INDEX, idx))) {
            return true;
        }
        if (clickList(mouseX, mouseY, rightPanelX + 8, rightPanelY + 114, 132, 14, 2,
                advanced.selectedStorageIndex(), advanced.storageLines().size(), idx -> send(PostStationGuiActionPacket.Action.ADV_SELECT_STORAGE_INDEX, idx))) {
            return true;
        }
        return clickList(mouseX, mouseY, rightPanelX + 8, rightPanelY + 160, 132, 14, 1,
                advanced.selectedWaybillIndex(), advanced.waybillNames().size(), idx -> send(PostStationGuiActionPacket.Action.ADV_SELECT_WAYBILL_INDEX, idx));
    }

    private boolean clickList(double mouseX, double mouseY, int x, int y, int w, int rowH, int visible, int selected, int size, IntClick action) {
        if (size <= 0 || mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + visible * rowH) {
            return false;
        }
        int row = (int) ((mouseY - y) / rowH);
        int idx = listStart(selected, size, visible) + row;
        if (idx < 0 || idx >= size) {
            return false;
        }
        action.accept(idx);
        return true;
    }

    private void updateButtons() {
        boolean advanced = activeTab == TAB_ADVANCED;
        boolean dispatch = activeTab == TAB_DISPATCH;
        PostStationScreenData.VehicleEntry selectedVehicle = selectedVehicle();
        if (dispatchButton != null) {
            dispatchButton.visible = dispatch;
            dispatchButton.active = data.canManage()
                    && !data.reachableTowns().isEmpty()
                    && selectedVehicle != null
                    && selectedVehicle.ownedOrRentable();
        }
        if (recallButton != null) {
            recallButton.visible = dispatch;
            recallButton.active = data.canManage() && selectedVehicle != null && selectedVehicle.recallable();
        }
        if (autoReturnButton != null) {
            autoReturnButton.visible = dispatch;
            autoReturnButton.active = data.canManage();
            autoReturnButton.setMessage(text(data.autoReturnOnDispatch() ? "auto_return.on" : "auto_return.off"));
        }
        if (autoUnloadButton != null) {
            autoUnloadButton.visible = dispatch;
            autoUnloadButton.active = data.canManage();
            autoUnloadButton.setMessage(text(data.autoUnloadOnDispatch() ? "auto_unload.on" : "auto_unload.off"));
        }
        if (importButton != null) {
            importButton.visible = advanced && data.canManage();
        }
        if (reverseButton != null) {
            reverseButton.visible = advanced && data.canManage();
        }
        if (deleteButton != null) {
            deleteButton.visible = advanced && data.canManage();
        }
        if (takeStorageButton != null) {
            takeStorageButton.visible = advanced && data.canManage();
        }
        if (takeWaybillButton != null) {
            takeWaybillButton.visible = advanced;
        }
        if (nameInput != null) {
            nameInput.visible = activeTab == TAB_RENAME_ZONE && data.canManage();
        }
        if (renameButton != null) {
            renameButton.visible = activeTab == TAB_RENAME_ZONE && data.canManage();
            renameButton.active = activeTab == TAB_RENAME_ZONE && data.canManage();
        }
    }

    private PostStationScreenData.ReachableTownEntry selectedTown() {
        if (data.reachableTowns().isEmpty()) {
            return null;
        }
        return data.reachableTowns().get(Math.max(0, Math.min(data.selectedTownIndex(), data.reachableTowns().size() - 1)));
    }

    private PostStationScreenData.VehicleEntry selectedVehicle() {
        if (data.vehicles().isEmpty()) {
            return null;
        }
        return data.vehicles().get(Math.max(0, Math.min(data.selectedVehicleIndex(), data.vehicles().size() - 1)));
    }

    private void drawSlotFrames(GuiGraphics g) {
        drawSlotFrame(g, leftPos + 9, topPos + 19);
        drawSlotFrame(g, leftPos + 9, topPos + 45);
    }

    private void drawSlotFrame(GuiGraphics g, int x, int y) {
        g.fill(x - 1, y - 1, x + 18, y + 18, 0xFF9F7A4A);
        g.fill(x, y, x + 17, y + 17, 0xFF2F2118);
    }

    private void drawSectionTitle(GuiGraphics g, Component label, int x, int y) {
        g.drawString(font, label, x, y, 0xFFAEDAD1);
    }

    private int listStart(int selected, int size, int visible) {
        if (size <= visible) {
            return 0;
        }
        return Math.max(0, Math.min(selected - visible / 2, size - visible));
    }

    private Component text(String suffix, Object... args) {
        return Component.translatable("screen.sailboatmod.post_station." + suffix, args);
    }

    private void send(PostStationGuiActionPacket.Action action) {
        ModNetwork.CHANNEL.sendToServer(new PostStationGuiActionPacket(data.stationPos(), action));
    }

    private void send(PostStationGuiActionPacket.Action action, int value) {
        ModNetwork.CHANNEL.sendToServer(new PostStationGuiActionPacket(data.stationPos(), action, value));
    }

    private void sendDispatchSelected() {
        PostStationScreenData.VehicleEntry selectedVehicle = selectedVehicle();
        int selectedVehicleId = selectedVehicle == null ? -1 : selectedVehicle.entityId();
        send(PostStationGuiActionPacket.Action.DISPATCH_SELECTED, selectedVehicleId);
    }

    private static int combinedImageWidth() {
        return MAIN_PANEL_W + RIGHT_PANEL_GAP + RIGHT_PANEL_W;
    }

    private static int rightPanelOffset() {
        return MAIN_PANEL_W + RIGHT_PANEL_GAP;
    }

    static int mainPanelWidthForTest() {
        return MAIN_PANEL_W;
    }

    static int rightPanelWidthForTest() {
        return RIGHT_PANEL_W;
    }

    static int rightPanelGapForTest() {
        return RIGHT_PANEL_GAP;
    }

    static int combinedImageWidthForTest() {
        return combinedImageWidth();
    }

    static int rightPanelOffsetForTest() {
        return rightPanelOffset();
    }

    private String trimToWidth(String src, int maxPixels) {
        if (src == null || src.isEmpty()) {
            return "";
        }
        if (maxPixels <= 0 || font.width(src) <= maxPixels) {
            return src;
        }
        String ellipsis = "...";
        int end = src.length();
        while (end > 0 && font.width(src.substring(0, end) + ellipsis) > maxPixels) {
            end--;
        }
        return end <= 0 ? "" : src.substring(0, end) + ellipsis;
    }

    private static PostStationScreenData empty(BlockPos pos) {
        DockScreenData advanced = new DockScreenData(
                pos, "", "", "", false, false, false, ItemStack.EMPTY,
                List.of(), List.of(), 0, List.of(), -12, 12, -8, 8,
                List.of(), List.of(), List.of(), 0, List.of(), 0, List.of(), 0, List.of(), List.of()
        );
        return new PostStationScreenData(
                pos, "", "", "", false, List.of(), 0, List.of(), 0, true, true,
                PostStationScreenData.RouteSummary.empty(), List.of(), advanced
        );
    }

    @FunctionalInterface
    private interface IntClick {
        void accept(int index);
    }
}
