package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.client.PostStationClientHooks;
import com.monpai.sailboatmod.dock.DockScreenData;
import com.monpai.sailboatmod.dock.PostStationScreenData;
import com.monpai.sailboatmod.menu.PostStationMenu;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.PostStationGuiActionPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class PostStationScreen extends AbstractContainerScreen<PostStationMenu> {
    private static final int TAB_DESTINATIONS = 0;
    private static final int TAB_VEHICLES = 1;
    private static final int TAB_DISPATCH = 2;
    private static final int TAB_ADVANCED = 3;
    private static final int RIGHT_PANEL_W = 194;
    private static final int RIGHT_PANEL_GAP = 6;
    private static final int ROW_H = 16;

    private PostStationScreenData data;
    private int activeTab = TAB_DESTINATIONS;
    private int rightPanelX;
    private int rightPanelY;
    private Button dispatchButton;
    private Button recallButton;
    private Button autoReturnButton;
    private Button importButton;
    private Button reverseButton;
    private Button deleteButton;
    private Button takeStorageButton;
    private Button takeWaybillButton;

    public PostStationScreen(PostStationMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 194;
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
        this.leftPos = Math.max(8, this.leftPos - (RIGHT_PANEL_W + RIGHT_PANEL_GAP) / 2);
        this.rightPanelX = this.leftPos + this.imageWidth + RIGHT_PANEL_GAP;
        this.rightPanelY = this.topPos;
        int x = rightPanelX;
        int y = rightPanelY;
        addRenderableWidget(Button.builder(text("tab.destinations"), button -> activeTab = TAB_DESTINATIONS)
                .bounds(x + 6, y + 4, 44, 16).build());
        addRenderableWidget(Button.builder(text("tab.vehicles"), button -> activeTab = TAB_VEHICLES)
                .bounds(x + 52, y + 4, 40, 16).build());
        addRenderableWidget(Button.builder(text("tab.dispatch"), button -> activeTab = TAB_DISPATCH)
                .bounds(x + 94, y + 4, 42, 16).build());
        addRenderableWidget(Button.builder(text("tab.advanced"), button -> activeTab = TAB_ADVANCED)
                .bounds(x + 138, y + 4, 50, 16).build());
        addRenderableWidget(Button.builder(text("refresh"), button -> send(PostStationGuiActionPacket.Action.REFRESH))
                .bounds(x + 146, y + 160, 40, 16).build());
        dispatchButton = addRenderableWidget(Button.builder(text("dispatch"), button -> send(PostStationGuiActionPacket.Action.DISPATCH_SELECTED))
                .bounds(x + 8, y + 142, 70, 16).build());
        recallButton = addRenderableWidget(Button.builder(text("recall"), button -> send(PostStationGuiActionPacket.Action.RECALL_SELECTED))
                .bounds(x + 82, y + 142, 60, 16).build());
        autoReturnButton = addRenderableWidget(Button.builder(Component.empty(), button -> send(PostStationGuiActionPacket.Action.TOGGLE_AUTO_RETURN))
                .bounds(x + 8, y + 122, 132, 16).build());
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
        send(PostStationGuiActionPacket.Action.REFRESH);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        updateButtons();
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xCC6E4B2A);
        guiGraphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xCC49311F);
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
        } else {
            drawAdvancedTab(guiGraphics, rightPanelX + 8, rightPanelY + 26, 178);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
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
                pos, "", "", "", false, List.of(), 0, List.of(), 0, true,
                PostStationScreenData.RouteSummary.empty(), List.of(), advanced
        );
    }

    @FunctionalInterface
    private interface IntClick {
        void accept(int index);
    }
}
