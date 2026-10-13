package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.monpai.sailboatmod.client.PostStationClientHooks;
import com.monpai.sailboatmod.client.ui.dock.TransportTerminalScreen;
import com.monpai.sailboatmod.client.ui.kit.ActionButton;
import com.monpai.sailboatmod.client.ui.kit.TabBar;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.dock.DockScreenData;
import com.monpai.sailboatmod.dock.PostStationScreenData;
import com.monpai.sailboatmod.menu.PostStationMenu;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.PostStationGuiActionPacket;
import com.monpai.sailboatmod.network.packet.RenamePostStationPacket;
import com.monpai.sailboatmod.network.packet.SetDockZonePacket;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexWrap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Post-station terminal: reachable towns, carriages, dispatch, the dock-style advanced lists, and name & zone.
 *
 * <p>Shares the cargo bay and terrain map with the dock screen. Selection is kept by the server (towns, vehicles and
 * advanced rows are selected by index); every button sends the same {@link PostStationGuiActionPacket},
 * {@link RenamePostStationPacket} or {@link SetDockZonePacket} as before.</p>
 */
public class PostStationScreen extends TransportTerminalScreen<PostStationMenu> {
    private enum Page { DESTINATIONS, VEHICLES, DISPATCH, ADVANCED, RENAME_ZONE }

    private static final String KEY_ACTION = "station";
    private static final String CONFIRM_DELETE = "delete-route";

    private PostStationScreenData data;
    private Page page = Page.DESTINATIONS;
    private final Map<Page, UIElement> pages = new EnumMap<>(Page.class);
    private final TabBar<Page> tabs;
    private final Label townLabel = Ui.text("", "sm-muted");

    private final ScrollerView townList = Ui.scroller();
    private final ScrollerView vehicleList = Ui.scroller();

    private final Label dispatchTown = Ui.text("", "sm-title");
    private final Label dispatchVehicle = Ui.text("", "sm-title");
    private final UIElement routeSummary = Ui.column("sm-pane");
    private ActionButton dispatchButton;
    private ActionButton recallButton;
    private ActionButton autoReturnButton;
    private ActionButton autoUnloadButton;

    private final ScrollerView advancedRoutes = Ui.scroller();
    private final ScrollerView advancedStorage = Ui.scroller();
    private final ScrollerView advancedWaybills = Ui.scroller();
    private ActionButton importButton;
    private ActionButton reverseButton;
    private ActionButton deleteButton;
    private ActionButton takeStorageButton;
    private ActionButton takeWaybillButton;

    private UIElement renameRow;
    private TextField nameInput;
    private ActionButton renameButton;
    private Label zoneHint;

    public PostStationScreen(PostStationMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        PostStationScreenData initial = PostStationClientHooks.consumeFor(menu.getDockPos());
        this.data = initial != null ? initial : empty(menu.getDockPos());

        tabs = TabBar.horizontal(next -> {
            page = next;
            refresh();
        });
        tabs.add(Page.DESTINATIONS, new ItemStack(Items.FILLED_MAP), text("tab.destinations"))
                .add(Page.VEHICLES, new ItemStack(Items.MINECART), text("tab.vehicles"))
                .add(Page.DISPATCH, new ItemStack(Items.EMERALD), text("tab.dispatch"))
                .add(Page.ADVANCED, new ItemStack(Items.WRITABLE_BOOK), text("tab.advanced"))
                .add(Page.RENAME_ZONE, new ItemStack(Items.NAME_TAG), text("tab.rename_zone"));

        UIElement terminal = terminalPanel();
        terminal.addChild(tabs.element());
        pages.put(Page.DESTINATIONS, listPage(text("destinations"), townList));
        pages.put(Page.VEHICLES, listPage(text("vehicles"), vehicleList));
        pages.put(Page.DISPATCH, buildDispatchPage());
        pages.put(Page.ADVANCED, scrollPage(buildAdvancedPage()));
        pages.put(Page.RENAME_ZONE, scrollPage(buildRenameZonePage()));
        pages.values().forEach(content -> {
            content.layout(l -> l.flexGrow(1).flexShrink(1).minWidth(0).minHeight(0));
            terminal.addChild(content);
        });

        UIElement body = twoHalves(cargoBay(text("book_slot"), text("storage_slot"), text("slot_hint")), terminal);
        UIElement townChip = Ui.row("sm-chip");
        townChip.layout(l -> l.height(Ui.CONTROL_HEIGHT).paddingHorizontal(Ui.GAP_S).maxWidth(110));
        townChip.addChild(townLabel);
        UIElement window = window(Component.literal(data.stationName()), body, townChip,
                Ui.iconButton(new ItemStack(Items.COMPASS), text("refresh"), () -> send(PostStationGuiActionPacket.Action.REFRESH)));
        install(window);
        bindAllSlots();

        miniMap.setLandPalette(true).onZoneSelected((minX, maxX, minZ, maxZ) -> {
            if (pending.begin("zone", ticks())) {
                ModNetwork.CHANNEL.sendToServer(new SetDockZonePacket(data.stationPos(), minX, maxX, minZ, maxZ));
            }
        });
        refresh();
    }

    public static PostStationScreen create(PostStationMenu menu, Inventory inventory, Component title) {
        return new PostStationScreen(menu, inventory, title);
    }

    public boolean isForStation(BlockPos pos) {
        return data.stationPos().equals(pos);
    }

    public void updateData(PostStationScreenData updated) {
        this.data = updated;
        onServerData();
        refresh();
    }

    // ================================================================= building

    private static UIElement scrollPage(UIElement content) {
        content.layout(l -> l.heightAuto().flexGrow(0).widthPercent(100).minWidth(0));
        ScrollerView viewport = Ui.scroller();
        viewport.layout(l -> l.height(170));
        viewport.addScrollViewChild(content);
        return viewport;
    }

    private static UIElement listPage(Component heading, ScrollerView list) {
        UIElement page = Ui.column();
        page.layout(l -> l.gapAll(Ui.GAP_XS).height(170));
        page.addChildren(panelHeading(heading), list);
        return page;
    }

    private UIElement buildDispatchPage() {
        UIElement page = Ui.column();
        page.layout(l -> l.gapAll(Ui.GAP_S).height(170));
        UIElement picks = Ui.column("sm-pane");
        picks.layout(l -> l.paddingAll(Ui.GAP_S).gapAll(Ui.GAP_XS));
        picks.addChildren(pickLine(text("selected_town"), dispatchTown), pickLine(text("selected_vehicle"), dispatchVehicle));
        routeSummary.layout(l -> l.widthPercent(100).minWidth(0).paddingAll(Ui.GAP_S).gapAll(Ui.GAP_XS));

        autoReturnButton = new ActionButton(Component.empty(), Ui.Tone.SECONDARY, () -> send(PostStationGuiActionPacket.Action.TOGGLE_AUTO_RETURN));
        autoUnloadButton = new ActionButton(Component.empty(), Ui.Tone.SECONDARY, () -> send(PostStationGuiActionPacket.Action.TOGGLE_AUTO_UNLOAD));
        recallButton = new ActionButton(text("recall"), Ui.Tone.SECONDARY, () -> send(PostStationGuiActionPacket.Action.RECALL_SELECTED));
        dispatchButton = new ActionButton(text("dispatch"), Ui.Tone.PRIMARY, this::sendDispatchSelected);
        UIElement toggles = Ui.row();
        toggles.layout(l -> l.widthPercent(100).minWidth(0).flexWrap(FlexWrap.WRAP).gapAll(Ui.GAP_S).flexShrink(0));
        toggles.addChildren(autoReturnButton.inRow().element(), autoUnloadButton.inRow().element());
        UIElement actions = Ui.row();
        actions.layout(l -> l.widthPercent(100).minWidth(0).flexWrap(FlexWrap.WRAP).gapAll(Ui.GAP_S).flexShrink(0));
        actions.addChildren(Ui.spacer(), recallButton.inRow().element(), dispatchButton.inRow().element());
        ScrollerView summaryViewport = Ui.scroller();
        summaryViewport.addScrollViewChild(routeSummary);
        page.addChildren(picks, summaryViewport, toggles, actions);
        return page;
    }

    private static UIElement pickLine(Component key, Label value) {
        UIElement line = Ui.row();
        line.layout(l -> l.widthPercent(100).gapAll(Ui.GAP_S));
        Ui.alignRight(value).layout(l -> l.flexGrow(1));
        line.addChildren(Ui.muted(key), value);
        return line;
    }

    private UIElement buildAdvancedPage() {
        UIElement page = Ui.column();
        page.layout(l -> l.gapAll(Ui.GAP_XS).height(170));
        importButton = new ActionButton(text("import"), Ui.Tone.SECONDARY, () -> send(PostStationGuiActionPacket.Action.ADV_IMPORT_BOOK));
        reverseButton = new ActionButton(text("reverse"), Ui.Tone.SECONDARY, () -> send(PostStationGuiActionPacket.Action.ADV_REVERSE_ROUTE));
        deleteButton = new ActionButton(text("delete"), Ui.Tone.DANGER, () -> {
            if (confirm.press(CONFIRM_DELETE, ticks())) {
                send(PostStationGuiActionPacket.Action.ADV_DELETE_ROUTE);
            } else {
                refresh();
            }
        });
        takeStorageButton = new ActionButton(text("withdraw"), Ui.Tone.SECONDARY, () -> send(PostStationGuiActionPacket.Action.ADV_TAKE_SELECTED_STORAGE));
        takeWaybillButton = new ActionButton(text("take"), Ui.Tone.PRIMARY, () -> send(PostStationGuiActionPacket.Action.ADV_TAKE_SELECTED_WAYBILL));

        page.addChildren(
                Ui.text(text("advanced.routes"), "sm-muted"),
                section(advancedRoutes, 46, importButton, reverseButton, deleteButton),
                Ui.text(text("advanced.storage"), "sm-muted"),
                section(advancedStorage, 32, takeStorageButton),
                Ui.text(text("advanced.waybill"), "sm-muted"),
                section(advancedWaybills, 32, takeWaybillButton));
        return page;
    }

    private static UIElement section(ScrollerView list, float height, ActionButton... buttons) {
        UIElement row = Ui.row();
        row.layout(l -> l.gapAll(Ui.GAP_S).alignItems(AlignItems.STRETCH).height(height).flexShrink(0));
        list.layout(l -> l.heightAuto().flexGrow(1).minWidth(0));
        UIElement side = Ui.column();
        side.layout(l -> l.width(52).flexShrink(0).gapAll(Ui.GAP_XS));
        for (ActionButton button : buttons) {
            button.element().layout(l -> l.widthPercent(100).height(14));
            side.addChild(button.element());
        }
        row.addChildren(list, side);
        return row;
    }

    private UIElement buildRenameZonePage() {
        UIElement page = Ui.column();
        page.layout(l -> l.gapAll(Ui.GAP_S).height(170));
        renameRow = Ui.row();
        renameRow.layout(l -> l.widthPercent(100).minWidth(0).flexWrap(FlexWrap.WRAP).gapAll(Ui.GAP_S).flexShrink(0));
        nameInput = Ui.field(data.stationName(), text("rename.hint"), value -> { });
        nameInput.layout(l -> l.flexGrow(1).flexBasis(0).flexShrink(1).minWidth(40));
        renameButton = new ActionButton(text("rename.save"), Ui.Tone.PRIMARY, () -> {
            if (pending.begin("rename", ticks())) {
                ModNetwork.CHANNEL.sendToServer(new RenamePostStationPacket(data.stationPos(), nameInput.getValue()));
                refresh();
            }
        });
        renameRow.addChildren(nameInput, renameButton.inRow().element());
        zoneHint = Ui.text(text("zone_hint"), "sm-muted");
        miniMap.layout(l -> l.widthPercent(100).height(100).flexGrow(0).flexShrink(0));
        page.addChildren(renameRow, zoneHint, miniMap);
        return page;
    }

    // ================================================================= actions

    private void send(PostStationGuiActionPacket.Action action) {
        if (!pending.begin(KEY_ACTION, ticks())) {
            return;
        }
        confirm.disarm();
        ModNetwork.CHANNEL.sendToServer(new PostStationGuiActionPacket(data.stationPos(), action));
        refresh();
    }

    private void select(PostStationGuiActionPacket.Action action, int index) {
        confirm.disarm();
        ModNetwork.CHANNEL.sendToServer(new PostStationGuiActionPacket(data.stationPos(), action, index));
    }

    private void sendDispatchSelected() {
        if (!pending.begin(KEY_ACTION, ticks())) {
            return;
        }
        PostStationScreenData.VehicleEntry selectedVehicle = selectedVehicle();
        int selectedVehicleId = selectedVehicle == null ? -1 : selectedVehicle.entityId();
        ModNetwork.CHANNEL.sendToServer(new PostStationGuiActionPacket(data.stationPos(), PostStationGuiActionPacket.Action.DISPATCH_SELECTED, selectedVehicleId));
        refresh();
    }

    // ================================================================= refresh

    @Override
    protected void refresh() {
        boolean manage = data.canManage();
        DockScreenData advanced = data.advancedData();
        titleLabel.setValue(Component.literal(data.stationName()));
        townLabel.setValue(Component.literal(data.townName().isBlank() ? "-" : data.townName()));
        tabs.select(page);
        pages.forEach((p, element) -> element.setDisplay(p == page));

        List<PostStationScreenData.ReachableTownEntry> towns = data.reachableTowns();
        fillList(townList, towns.size(), data.selectedTownIndex(),
                i -> textRow(towns.get(i).townName(), towns.get(i).distanceMeters() + "m", "sm-up"),
                i -> select(PostStationGuiActionPacket.Action.SELECT_DESTINATION_INDEX, i), text("empty.no_reachable_town"));
        List<PostStationScreenData.VehicleEntry> vehicles = data.vehicles();
        fillList(vehicleList, vehicles.size(), data.selectedVehicleIndex(),
                i -> textRow(vehicles.get(i).name(), vehicles.get(i).state(), vehicles.get(i).ownedOrRentable() ? "sm-up" : "sm-down"),
                i -> select(PostStationGuiActionPacket.Action.SELECT_VEHICLE_INDEX, i), text("empty.no_vehicle"));

        // dispatch
        PostStationScreenData.ReachableTownEntry town = selectedTown();
        PostStationScreenData.VehicleEntry vehicle = selectedVehicle();
        dispatchTown.setValue(Component.literal(town == null ? "-" : town.townName()));
        dispatchVehicle.setValue(Component.literal(vehicle == null ? "-" : vehicle.name()));
        routeSummary.clearAllChildren();
        PostStationScreenData.RouteSummary summary = data.selectedRouteSummary();
        if (summary == null || summary.routeName().isBlank()) {
            routeSummary.addChild(Ui.text(text("route.none"), "sm-muted"));
        } else {
            routeSummary.addChild(Ui.text(summary.routeName(), "sm-title"));
            routeSummary.addChild(Ui.text(summary.distanceMeters() + "m / " + summary.etaSeconds() + "s", "sm-muted"));
            if (!summary.passThroughTownNames().isEmpty()) {
                routeSummary.addChild(Ui.text(String.join(" > ", summary.passThroughTownNames())));
            }
        }
        autoReturnButton.setLabel(text(data.autoReturnOnDispatch() ? "auto_return.on" : "auto_return.off"));
        autoUnloadButton.setLabel(text(data.autoUnloadOnDispatch() ? "auto_unload.on" : "auto_unload.off"));
        syncAction(autoReturnButton, manage, KEY_ACTION, null);
        syncAction(autoUnloadButton, manage, KEY_ACTION, null);
        syncAction(dispatchButton, manage && !towns.isEmpty() && vehicle != null && vehicle.ownedOrRentable(), KEY_ACTION, null);
        syncAction(recallButton, manage && vehicle != null && vehicle.recallable(), KEY_ACTION, null);

        // advanced
        fillList(advancedRoutes, advanced.routeNames().size(), advanced.selectedRouteIndex(),
                i -> textRow(advanced.routeNames().get(i), null),
                i -> select(PostStationGuiActionPacket.Action.ADV_SELECT_ROUTE_INDEX, i), text("no_route"));
        fillList(advancedStorage, advanced.storageLines().size(), advanced.selectedStorageIndex(),
                i -> textRow(advanced.storageLines().get(i), null),
                i -> select(PostStationGuiActionPacket.Action.ADV_SELECT_STORAGE_INDEX, i), text("storage_empty"));
        fillList(advancedWaybills, advanced.waybillNames().size(), advanced.selectedWaybillIndex(),
                i -> textRow(advanced.waybillNames().get(i), null),
                i -> select(PostStationGuiActionPacket.Action.ADV_SELECT_WAYBILL_INDEX, i), text("no_waybill"));
        for (ActionButton button : List.of(importButton, reverseButton, deleteButton, takeStorageButton)) {
            button.element().setDisplay(manage);
        }
        syncAction(importButton, manage, KEY_ACTION, null);
        syncAction(reverseButton, manage && !advanced.routeNames().isEmpty(), KEY_ACTION, null);
        syncAction(deleteButton, manage && !advanced.routeNames().isEmpty(), KEY_ACTION, CONFIRM_DELETE);
        syncAction(takeStorageButton, manage && !advanced.storageLines().isEmpty(), KEY_ACTION, null);
        syncAction(takeWaybillButton, !advanced.waybillNames().isEmpty(), KEY_ACTION, null);

        // name & zone
        renameRow.setDisplay(manage);
        zoneHint.setDisplay(manage);
        if (!nameInput.isFocused()) {
            nameInput.setText(data.stationName(), false);
        }
        syncAction(renameButton, manage, "rename", null);
        miniMap.setCenter(advanced.dockPos())
                .setZone(advanced.zoneMinX(), advanced.zoneMaxX(), advanced.zoneMinZ(), advanced.zoneMaxZ())
                .setRoute(advanced.selectedRouteWaypoints())
                .setBoats(advanced.nearbyBoatNames(), advanced.nearbyBoatPositions())
                .setEditable(manage);
    }

    @Override
    protected void onUiTick() {
        super.onUiTick();
        if (deleteButton != null && !confirm.isArmed(CONFIRM_DELETE, ticks()) && deleteButton.element().hasClass("sm-armed")) {
            refresh();
        }
    }

    @Override
    protected void init() {
        super.init();
        send(PostStationGuiActionPacket.Action.REFRESH);
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

    private Component text(String suffix, Object... args) {
        return Component.translatable("screen.sailboatmod.post_station." + suffix, args);
    }

    // Layout of the two halves; recipe-viewer overlays rely on the window covering both.

    static int mainPanelWidthForTest() {
        return CARGO_BAY_WIDTH;
    }

    static int rightPanelWidthForTest() {
        return TERMINAL_WIDTH;
    }

    static int rightPanelGapForTest() {
        return PANEL_GAP;
    }

    static int combinedImageWidthForTest() {
        return CARGO_BAY_WIDTH + PANEL_GAP + TERMINAL_WIDTH;
    }

    static int rightPanelOffsetForTest() {
        return CARGO_BAY_WIDTH + PANEL_GAP;
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
}
