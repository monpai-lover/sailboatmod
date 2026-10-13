package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.monpai.sailboatmod.client.DockClientHooks;
import com.monpai.sailboatmod.client.ui.dock.TransportTerminalScreen;
import com.monpai.sailboatmod.client.ui.kit.ActionButton;
import com.monpai.sailboatmod.client.ui.kit.TabBar;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.dock.DockScreenData;
import com.monpai.sailboatmod.market.TransportTerminalKind;
import com.monpai.sailboatmod.menu.DockMenu;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.DockGuiActionPacket;
import com.monpai.sailboatmod.network.packet.RenameDockPacket;
import com.monpai.sailboatmod.network.packet.RequestAutoRouteDocksPacket;
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
 * Dock terminal: cargo bay on the left, routes / dispatch / storage / inbox on the right.
 *
 * <p>Selection lives on the server: clicking a row sends its index ({@code SELECT_*_INDEX}) and the dock answers with
 * fresh {@link DockScreenData}. Every button sends the same packet as the previous screen; management buttons are
 * disabled for players who cannot manage the dock, and the server checks again. Deleting a route now needs a second
 * press within three seconds.</p>
 */
public class DockScreen<T extends DockMenu> extends TransportTerminalScreen<T> {
    private enum Page { ROUTES, DISPATCH, STORAGE, INBOX }

    private static final String KEY_ACTION = "dock";
    private static final String CONFIRM_DELETE = "delete-route";

    private DockScreenData data;
    private Page page = Page.ROUTES;
    private final Map<Page, UIElement> pages = new EnumMap<>(Page.class);
    private final TabBar<Page> tabs;

    private final Label ownerLabel = Ui.text("", "sm-muted");
    private TextField nameField;
    private ActionButton saveName;

    private final ScrollerView routeList = Ui.scroller();
    private ActionButton importButton;
    private ActionButton reverseButton;
    private ActionButton autoRouteButton;
    private ActionButton deleteButton;

    private final ScrollerView boatList = Ui.scroller();
    private final ScrollerView dispatchRouteList = Ui.scroller();
    private ActionButton assignButton;
    private ActionButton autoReturnButton;
    private ActionButton autoUnloadButton;

    private final ScrollerView storageList = Ui.scroller();
    private ActionButton takeStorageButton;
    private ActionButton shipCargoButton;

    private final ScrollerView waybillList = Ui.scroller();
    private final UIElement waybillInfo = Ui.column("sm-pane");
    private ActionButton takeWaybillButton;

    public DockScreen(T menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        DockScreenData initial = DockClientHooks.consumeFor(menu.getDockPos());
        this.data = initial != null ? initial : empty(menu.getDockPos());

        tabs = TabBar.horizontal(next -> {
            page = next;
            refresh();
        });
        tabs.add(Page.ROUTES, new ItemStack(Items.MAP), screenText("tab.routes"))
                .add(Page.DISPATCH, new ItemStack(Items.OAK_BOAT), screenText("tab.dispatch"))
                .add(Page.STORAGE, new ItemStack(Items.CHEST), screenText("tab.warehouse"))
                .add(Page.INBOX, new ItemStack(Items.PAPER), screenText("tab.waybill"));

        UIElement terminal = terminalPanel();
        terminal.addChildren(buildNameRow(), tabs.element());
        pages.put(Page.ROUTES, buildRoutesPage());
        pages.put(Page.DISPATCH, buildDispatchPage());
        pages.put(Page.STORAGE, buildStoragePage());
        pages.put(Page.INBOX, buildInboxPage());
        pages.values().forEach(terminal::addChild);

        UIElement body = twoHalves(cargoBay(screenText("book_slot"), screenText("storage_slot"), screenText("slot_hint")), terminal);
        UIElement window = window(Component.literal(data.dockName()), body,
                ownerChip(),
                Ui.iconButton(new ItemStack(Items.COMPASS), screenText("refresh"), () -> send(KEY_ACTION, DockGuiActionPacket.Action.REFRESH)));
        install(window);
        bindAllSlots();

        miniMap.setLandPalette(useLandMinimapPalette())
                .onZoneSelected((minX, maxX, minZ, maxZ) -> {
                    if (pending.begin("zone", ticks())) {
                        ModNetwork.CHANNEL.sendToServer(new SetDockZonePacket(data.dockPos(), minX, maxX, minZ, maxZ));
                    }
                });
        refresh();
    }

    public static DockScreen<DockMenu> create(DockMenu menu, Inventory inventory, Component title) {
        return new DockScreen<>(menu, inventory, title);
    }

    public boolean isForDock(BlockPos pos) {
        return data.dockPos().equals(pos);
    }

    public void updateData(DockScreenData updated) {
        this.data = updated;
        onServerData();
        refresh();
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

    // ================================================================= building

    private UIElement ownerChip() {
        UIElement chip = Ui.row("sm-chip");
        chip.layout(l -> l.height(Ui.CONTROL_HEIGHT).paddingHorizontal(Ui.GAP_S).maxWidth(110));
        chip.addChild(ownerLabel);
        return chip;
    }

    private UIElement buildNameRow() {
        UIElement row = Ui.row();
        row.layout(l -> l.widthPercent(100).minWidth(0).flexWrap(FlexWrap.WRAP).gapAll(Ui.GAP_S).flexShrink(0));
        nameField = Ui.field(data.dockName(), screenText("name_input"), value -> { });
        nameField.layout(l -> l.flexGrow(1).flexBasis(0).flexShrink(1).minWidth(40));
        saveName = new ActionButton(screenText("name_save"), Ui.Tone.SECONDARY, () -> {
            if (pending.begin("rename", ticks())) {
                ModNetwork.CHANNEL.sendToServer(new RenameDockPacket(data.dockPos(), nameField.getValue()));
                refresh();
            }
        });
        row.addChildren(Ui.text(screenText("name"), "sm-muted"), nameField, saveName.inRow().element());
        return row;
    }

    private UIElement buildRoutesPage() {
        UIElement page = Ui.row();
        page.layout(l -> l.gapAll(Ui.GAP_S).alignItems(AlignItems.STRETCH).height(150));
        UIElement listColumn = Ui.column();
        listColumn.layout(l -> l.flexGrow(1).minWidth(0).gapAll(Ui.GAP_XS));
        listColumn.addChildren(Ui.text(screenText("routes_title"), "sm-muted"), routeList);

        UIElement side = Ui.column();
        side.layout(l -> l.width(82).flexShrink(0).gapAll(Ui.GAP_XS));
        miniMap.layout(l -> l.width(82).height(54).flexShrink(0));
        importButton = new ActionButton(screenText("import"), Ui.Tone.SECONDARY, () -> send(KEY_ACTION, DockGuiActionPacket.Action.IMPORT_BOOK));
        reverseButton = new ActionButton(screenText("reverse"), Ui.Tone.SECONDARY, () -> send(KEY_ACTION, DockGuiActionPacket.Action.REVERSE_ROUTE));
        autoRouteButton = new ActionButton(screenText("auto_route"), Ui.Tone.SECONDARY, () -> {
            if (pending.begin(KEY_ACTION, ticks())) {
                ModNetwork.CHANNEL.sendToServer(new RequestAutoRouteDocksPacket(data.dockPos()));
                refresh();
            }
        });
        deleteButton = new ActionButton(screenText("delete"), Ui.Tone.DANGER, () -> {
            if (confirm.press(CONFIRM_DELETE, ticks())) {
                send(KEY_ACTION, DockGuiActionPacket.Action.DELETE_ROUTE);
            } else {
                refresh();
            }
        });
        for (ActionButton button : List.of(importButton, reverseButton, autoRouteButton, deleteButton)) {
            button.element().layout(l -> l.widthPercent(100));
        }
        side.addChildren(Ui.text(screenText("zone_map"), "sm-muted"), miniMap,
                importButton.element(), reverseButton.element(), autoRouteButton.element(), deleteButton.element());
        page.addChildren(listColumn, side);
        return page;
    }

    private UIElement buildDispatchPage() {
        UIElement page = Ui.column();
        page.layout(l -> l.gapAll(Ui.GAP_S).height(150));
        UIElement lists = Ui.row();
        lists.layout(l -> l.gapAll(Ui.GAP_S).alignItems(AlignItems.STRETCH).flexGrow(1).minHeight(0));
        UIElement boats = Ui.column();
        boats.layout(l -> l.flexGrow(1).flexBasis(0).minWidth(0).gapAll(Ui.GAP_XS));
        boats.addChildren(Ui.text(screenText("boats_title"), "sm-muted"), boatList);
        UIElement routes = Ui.column();
        routes.layout(l -> l.flexGrow(1).flexBasis(0).minWidth(0).gapAll(Ui.GAP_XS));
        routes.addChildren(Ui.text(screenText("routes_title"), "sm-muted"), dispatchRouteList);
        lists.addChildren(boats, routes);

        assignButton = new ActionButton(screenText("assign"), Ui.Tone.PRIMARY, () -> send(KEY_ACTION, DockGuiActionPacket.Action.ASSIGN_SELECTED));
        autoReturnButton = new ActionButton(Component.empty(), Ui.Tone.SECONDARY, () -> send(KEY_ACTION, DockGuiActionPacket.Action.TOGGLE_NON_ORDER_AUTO_RETURN));
        autoUnloadButton = new ActionButton(Component.empty(), Ui.Tone.SECONDARY, () -> send(KEY_ACTION, DockGuiActionPacket.Action.TOGGLE_NON_ORDER_AUTO_UNLOAD));
        UIElement actions = Ui.row();
        actions.layout(l -> l.widthPercent(100).minWidth(0).flexWrap(FlexWrap.WRAP).gapAll(Ui.GAP_S).flexShrink(0));
        actions.addChildren(assignButton.inRow().element(), Ui.spacer(), autoReturnButton.inRow().element(), autoUnloadButton.inRow().element());
        page.addChildren(lists, actions);
        return page;
    }

    private UIElement buildStoragePage() {
        UIElement page = Ui.column();
        page.layout(l -> l.gapAll(Ui.GAP_XS).height(150));
        takeStorageButton = new ActionButton(screenText("withdraw"), Ui.Tone.SECONDARY, () -> send(KEY_ACTION, DockGuiActionPacket.Action.TAKE_SELECTED_STORAGE));
        shipCargoButton = new ActionButton(screenText("ship_cargo"), Ui.Tone.PRIMARY, () -> send(KEY_ACTION, DockGuiActionPacket.Action.DISPATCH_SELECTED_CARGO));
        UIElement actions = Ui.row();
        actions.layout(l -> l.widthPercent(100).minWidth(0).flexWrap(FlexWrap.WRAP).gapAll(Ui.GAP_S).flexShrink(0));
        actions.addChildren(Ui.spacer(), takeStorageButton.inRow().element(), shipCargoButton.inRow().element());
        page.addChildren(Ui.text(screenText("storage_title"), "sm-muted"), storageList, actions);
        return page;
    }

    private UIElement buildInboxPage() {
        UIElement page = Ui.column();
        page.layout(l -> l.gapAll(Ui.GAP_XS).height(150));
        waybillList.layout(l -> l.height(52).flexGrow(0).flexShrink(0));
        waybillInfo.layout(l -> l.flexGrow(1).minHeight(0).paddingAll(Ui.GAP_S).gapAll(1));
        takeWaybillButton = new ActionButton(screenText("take"), Ui.Tone.PRIMARY, () -> send(KEY_ACTION, DockGuiActionPacket.Action.TAKE_SELECTED_WAYBILL));
        UIElement actions = Ui.row();
        actions.layout(l -> l.widthPercent(100).minWidth(0).flexWrap(FlexWrap.WRAP).flexShrink(0));
        actions.addChildren(Ui.spacer(), takeWaybillButton.inRow().element());
        page.addChildren(waybillList, waybillInfo, actions);
        return page;
    }

    // ================================================================= actions

    private void send(String key, DockGuiActionPacket.Action action) {
        if (!pending.begin(key, ticks())) {
            return;
        }
        confirm.disarm();
        ModNetwork.CHANNEL.sendToServer(new DockGuiActionPacket(data.dockPos(), action));
        refresh();
    }

    private void select(DockGuiActionPacket.Action action, int index) {
        confirm.disarm();
        ModNetwork.CHANNEL.sendToServer(new DockGuiActionPacket(data.dockPos(), action, index));
    }

    // ================================================================= refresh

    @Override
    protected void refresh() {
        boolean manage = data.canManageDock();
        ownerLabel.setValue(Component.translatable("screen.sailboatmod.owner_name", data.dockOwnerName()));
        if (!nameField.isFocused()) {
            nameField.setText(data.dockName(), false);
        }
        nameField.setActive(manage);
        syncAction(saveName, manage, "rename", null);
        titleLabel.setValue(Component.literal(data.dockName()));

        tabs.select(page);
        pages.forEach((p, element) -> element.setDisplay(p == page));

        // routes
        fillList(routeList, data.routeNames().size(), data.selectedRouteIndex(),
                i -> textRow((i + 1) + ". " + safeGet(data.routeNames(), i), safeGet(data.routeMetas(), i)),
                i -> select(DockGuiActionPacket.Action.SELECT_ROUTE_INDEX, i), screenText("no_route"));
        miniMap.setCenter(data.dockPos())
                .setZone(data.zoneMinX(), data.zoneMaxX(), data.zoneMinZ(), data.zoneMaxZ())
                .setRoute(data.selectedRouteWaypoints())
                .setBoats(data.nearbyBoatNames(), data.nearbyBoatPositions())
                .setEditable(manage);
        miniMap.style(s -> s.tooltips(manage ? Component.translatable("screen.sailboatmod.ui.zone_hint") : Component.empty()));
        syncAction(importButton, manage, KEY_ACTION, null);
        syncAction(reverseButton, manage && !data.routeNames().isEmpty(), KEY_ACTION, null);
        syncAction(autoRouteButton, manage, KEY_ACTION, null);
        syncAction(deleteButton, manage && !data.routeNames().isEmpty(), KEY_ACTION, CONFIRM_DELETE);

        // dispatch
        fillList(boatList, data.nearbyBoatNames().size(), data.selectedBoatIndex(),
                i -> textRow((i + 1) + ". " + safeGet(data.nearbyBoatNames(), i), null),
                i -> select(DockGuiActionPacket.Action.SELECT_BOAT_INDEX, i), Component.literal("-"));
        fillList(dispatchRouteList, data.routeNames().size(), data.selectedRouteIndex(),
                i -> textRow((i + 1) + ". " + safeGet(data.routeNames(), i), null),
                i -> select(DockGuiActionPacket.Action.SELECT_ROUTE_INDEX, i), screenText("no_route"));
        syncAction(assignButton, !data.nearbyBoatNames().isEmpty() && !data.routeNames().isEmpty(), KEY_ACTION, null);
        autoReturnButton.setLabel(screenText(data.nonOrderAutoReturnEnabled() ? "non_order_auto.on" : "non_order_auto.off"));
        autoUnloadButton.setLabel(screenText(data.nonOrderAutoUnloadEnabled() ? "non_order_unload.on" : "non_order_unload.off"));
        syncAction(autoReturnButton, manage, KEY_ACTION, null);
        syncAction(autoUnloadButton, manage, KEY_ACTION, null);

        // storage
        if (manage) {
            fillList(storageList, data.storageLines().size(), data.selectedStorageIndex(),
                    i -> textRow((i + 1) + ". " + safeGet(data.storageLines(), i), null),
                    i -> select(DockGuiActionPacket.Action.SELECT_STORAGE_INDEX, i), screenText("storage_empty"));
        } else {
            storageList.clearAllScrollViewChildren();
            storageList.addScrollViewChild(Ui.emptyState(screenText("storage_owner_only")));
        }
        boolean hasCargo = manage && !data.storageLines().isEmpty();
        syncAction(takeStorageButton, hasCargo, KEY_ACTION, null);
        syncAction(shipCargoButton, hasCargo && !data.nearbyBoatNames().isEmpty() && !data.routeNames().isEmpty(), KEY_ACTION, null);

        // inbox
        fillList(waybillList, data.waybillNames().size(), data.selectedWaybillIndex(),
                i -> textRow((i + 1) + ". " + safeGet(data.waybillNames(), i), null),
                i -> select(DockGuiActionPacket.Action.SELECT_WAYBILL_INDEX, i), screenText("no_waybill"));
        waybillInfo.clearAllChildren();
        for (String line : safe(data.selectedWaybillCargoLines())) {
            waybillInfo.addChild(Ui.text(line, "sm-up"));
        }
        for (String line : safe(data.selectedWaybillInfoLines())) {
            waybillInfo.addChild(Ui.text(line));
        }
        syncAction(takeWaybillButton, !data.waybillNames().isEmpty(), KEY_ACTION, null);
    }

    @Override
    protected void onUiTick() {
        super.onUiTick();
        // Let an armed delete button fall back to its normal label after the confirmation window.
        if (deleteButton != null && !confirm.isArmed(CONFIRM_DELETE, ticks()) && deleteButton.element().hasClass("sm-armed")) {
            refresh();
        }
    }

    @Override
    protected void init() {
        super.init();
        send(KEY_ACTION, DockGuiActionPacket.Action.REFRESH);
    }

    private static String safeGet(List<String> values, int index) {
        if (values == null || index < 0 || index >= values.size() || values.get(index) == null) {
            return "";
        }
        return values.get(index);
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

    private DockScreenData empty(BlockPos pos) {
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
}
