package com.monpai.sailboatmod.client.modernui;

import com.monpai.sailboatmod.client.DockClientHooks;
import com.monpai.sailboatmod.client.DockOverviewConsumer;
import com.monpai.sailboatmod.client.screen.DockScreen;
import com.monpai.sailboatmod.dock.DockScreenData;
import com.monpai.sailboatmod.menu.DockMenu;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.DockGuiActionPacket;
import com.monpai.sailboatmod.network.packet.RenameDockPacket;
import com.monpai.sailboatmod.network.packet.RequestAutoRouteDocksPacket;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.mc.ScreenCallback;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public class DockModernFragment extends Fragment implements ScreenCallback, DockOverviewConsumer {
    private static final int PAGE_ROUTES = 0;
    private static final int PAGE_DISPATCH = 1;
    private static final int PAGE_STORAGE = 2;
    private static final int PAGE_WAYBILL = 3;

    private final DockMenu menu;
    private DockScreenData data;

    private int activePage = PAGE_ROUTES;
    private int selectedRouteIndex;
    private int selectedBoatIndex;
    private int selectedStorageIndex;
    private int selectedWaybillIndex;

    private TextView titleView;
    private TextView statusView;
    private TextView summaryFlowBody;
    private TextView summaryRouteBody;
    private TextView summaryZoneBody;
    private TextView leftTitleView;
    private TextView leftBodyView;
    private TextView centerTitleView;
    private TextView centerBodyView;
    private TextView rightTitleView;
    private TextView rightBodyView;

    private EditText dockNameInput;

    private Button routesButton;
    private Button dispatchButton;
    private Button storageButton;
    private Button inboxButton;
    private Button leftPrevButton;
    private Button leftNextButton;
    private Button centerPrevButton;
    private Button centerNextButton;
    private Button primaryActionButton;
    private Button secondaryActionButton;
    private Button tertiaryActionButton;
    private Button renameButton;

    public DockModernFragment(DockMenu menu) {
        this.menu = menu;
        DockScreenData initial = DockClientHooks.consumeFor(menu.getDockPos());
        this.data = initial != null ? initial : empty(menu.getDockPos());
    }

    @Override
    public boolean isForDock(BlockPos pos) {
        return data.dockPos().equals(pos);
    }

    @Override
    public void updateData(DockScreenData data) {
        this.data = data == null ? this.data : data;
        this.selectedRouteIndex = clampSelection(this.selectedRouteIndex, this.data.routeNames().size());
        this.selectedBoatIndex = clampSelection(this.selectedBoatIndex, this.data.nearbyBoatNames().size());
        this.selectedStorageIndex = clampSelection(this.selectedStorageIndex, this.data.storageLines().size());
        this.selectedWaybillIndex = clampSelection(this.selectedWaybillIndex, this.data.waybillNames().size());
        if (titleView != null) {
            titleView.post(this::refreshViews);
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable DataSet savedInstanceState) {
        ScrollView scrollView = new ScrollView(requireContext());
        LinearLayout root = ModernUiScreenHelper.createRoot(new LinearLayout(requireContext()));
        scrollView.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        titleView = ModernUiScreenHelper.createHeader(new TextView(requireContext()), "", 20);
        statusView = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        root.addView(titleView, ModernUiScreenHelper.matchWidthWrap());
        root.addView(statusView, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout topRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        Button refreshButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Refresh");
        refreshButton.setOnClickListener(v -> send(DockGuiActionPacket.Action.REFRESH));
        Button classicButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Classic Controls");
        classicButton.setOnClickListener(v -> openClassicScreen());
        topRow.addView(refreshButton, ModernUiScreenHelper.wrap());
        topRow.addView(classicButton, ModernUiScreenHelper.wrap());
        root.addView(topRow, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout nameRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        TextView nameLabel = new TextView(requireContext());
        nameLabel.setText("Dock Name");
        nameLabel.setTextSize(11);
        dockNameInput = ModernUiScreenHelper.createInput(new EditText(requireContext()), "");
        renameButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Save Name");
        renameButton.setOnClickListener(v -> renameDock());
        nameRow.addView(nameLabel, ModernUiScreenHelper.wrap());
        nameRow.addView(dockNameInput, ModernUiScreenHelper.weightedWrap(1.4f));
        nameRow.addView(renameButton, ModernUiScreenHelper.wrap());
        root.addView(nameRow, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout summaryRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        summaryRow.addView(createSummaryCard("Flow", true), ModernUiScreenHelper.weightedWrap(1f));
        summaryRow.addView(createSummaryCard("Route", false), ModernUiScreenHelper.weightedWrap(1f));
        summaryRow.addView(createSummaryCard("Zone", null), ModernUiScreenHelper.weightedWrap(1f));
        root.addView(summaryRow, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout tabRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        routesButton = createTabButton("Routes", PAGE_ROUTES);
        dispatchButton = createTabButton("Dispatch", PAGE_DISPATCH);
        storageButton = createTabButton("Warehouse", PAGE_STORAGE);
        inboxButton = createTabButton("Inbox", PAGE_WAYBILL);
        tabRow.addView(routesButton, ModernUiScreenHelper.wrap());
        tabRow.addView(dispatchButton, ModernUiScreenHelper.wrap());
        tabRow.addView(storageButton, ModernUiScreenHelper.wrap());
        tabRow.addView(inboxButton, ModernUiScreenHelper.wrap());
        root.addView(tabRow, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout contentRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        contentRow.setGravity(0);

        LinearLayout leftPanel = ModernUiScreenHelper.createCard(new LinearLayout(requireContext()));
        leftTitleView = ModernUiScreenHelper.createHeader(new TextView(requireContext()), "", 14);
        leftBodyView = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        leftPrevButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "<");
        leftPrevButton.setOnClickListener(v -> moveLeft(-1));
        leftNextButton = ModernUiScreenHelper.createButton(new Button(requireContext()), ">");
        leftNextButton.setOnClickListener(v -> moveLeft(1));
        leftPanel.addView(leftTitleView, ModernUiScreenHelper.matchWidthWrap());
        leftPanel.addView(navigationRow(leftPrevButton, leftNextButton), ModernUiScreenHelper.matchWidthWrap());
        leftPanel.addView(leftBodyView, ModernUiScreenHelper.matchWidthWrap());
        contentRow.addView(leftPanel, ModernUiScreenHelper.weighted());

        LinearLayout centerPanel = ModernUiScreenHelper.createCard(new LinearLayout(requireContext()));
        centerTitleView = ModernUiScreenHelper.createHeader(new TextView(requireContext()), "", 14);
        centerBodyView = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        centerPrevButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "<");
        centerPrevButton.setOnClickListener(v -> moveCenter(-1));
        centerNextButton = ModernUiScreenHelper.createButton(new Button(requireContext()), ">");
        centerNextButton.setOnClickListener(v -> moveCenter(1));
        centerPanel.addView(centerTitleView, ModernUiScreenHelper.matchWidthWrap());
        centerPanel.addView(navigationRow(centerPrevButton, centerNextButton), ModernUiScreenHelper.matchWidthWrap());
        centerPanel.addView(centerBodyView, ModernUiScreenHelper.matchWidthWrap());
        contentRow.addView(centerPanel, ModernUiScreenHelper.weighted());

        LinearLayout rightPanel = ModernUiScreenHelper.createCard(new LinearLayout(requireContext()));
        rightTitleView = ModernUiScreenHelper.createHeader(new TextView(requireContext()), "", 14);
        rightBodyView = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        primaryActionButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "");
        primaryActionButton.setOnClickListener(v -> handlePrimaryAction());
        secondaryActionButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "");
        secondaryActionButton.setOnClickListener(v -> handleSecondaryAction());
        tertiaryActionButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "");
        tertiaryActionButton.setOnClickListener(v -> handleTertiaryAction());
        rightPanel.addView(rightTitleView, ModernUiScreenHelper.matchWidthWrap());
        rightPanel.addView(rightBodyView, ModernUiScreenHelper.matchWidthWrap());
        rightPanel.addView(primaryActionButton, ModernUiScreenHelper.matchWidthWrap());
        rightPanel.addView(secondaryActionButton, ModernUiScreenHelper.matchWidthWrap());
        rightPanel.addView(tertiaryActionButton, ModernUiScreenHelper.matchWidthWrap());
        contentRow.addView(rightPanel, ModernUiScreenHelper.weighted());

        root.addView(contentRow, ModernUiScreenHelper.matchWidthWrap());
        refreshViews();
        return scrollView;
    }

    private LinearLayout createSummaryCard(String title, Boolean flowCard) {
        LinearLayout card = ModernUiScreenHelper.createCard(new LinearLayout(requireContext()));
        card.addView(ModernUiScreenHelper.createHeader(new TextView(requireContext()), title, 13), ModernUiScreenHelper.matchWidthWrap());
        TextView body = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        if (flowCard == null) {
            summaryZoneBody = body;
        } else if (flowCard) {
            summaryFlowBody = body;
        } else {
            summaryRouteBody = body;
        }
        card.addView(body, ModernUiScreenHelper.matchWidthWrap());
        return card;
    }

    private Button createTabButton(String text, int page) {
        Button button = ModernUiScreenHelper.createButton(new Button(requireContext()), text);
        button.setOnClickListener(v -> {
            activePage = page;
            refreshViews();
        });
        return button;
    }

    private LinearLayout navigationRow(Button prev, Button next) {
        LinearLayout row = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        row.addView(prev, ModernUiScreenHelper.wrap());
        row.addView(next, ModernUiScreenHelper.wrap());
        return row;
    }

    private void refreshViews() {
        titleView.setText(data.dockName());
        statusView.setText("Owner: " + data.dockOwnerName()
                + " | Manage: " + data.canManageDock()
                + "\nRoute book: " + (data.routeBook().isEmpty() ? "None" : data.routeBook().getHoverName().getString()));
        syncInput(dockNameInput, data.dockName());
        renameButton.setEnabled(data.canManageDock());
        summaryFlowBody.setText(buildFlowSummary());
        summaryRouteBody.setText(buildRouteSummary());
        summaryZoneBody.setText(buildZoneSummary());
        routesButton.setEnabled(activePage != PAGE_ROUTES);
        dispatchButton.setEnabled(activePage != PAGE_DISPATCH);
        storageButton.setEnabled(activePage != PAGE_STORAGE);
        inboxButton.setEnabled(activePage != PAGE_WAYBILL);

        switch (activePage) {
            case PAGE_DISPATCH -> renderDispatchPage();
            case PAGE_STORAGE -> renderStoragePage();
            case PAGE_WAYBILL -> renderWaybillPage();
            default -> renderRoutePage();
        }
    }

    private String buildFlowSummary() {
        return "Routes: " + data.routeNames().size()
                + "\nBoats: " + data.nearbyBoatNames().size()
                + "\nStorage: " + data.storageLines().size()
                + "\nWaybills: " + data.waybillNames().size();
    }

    private String buildRouteSummary() {
        return "Selected route: " + safe(selectedRouteName())
                + "\nWaypoints: " + data.selectedRouteWaypoints().size()
                + "\nAuto return: " + onOff(data.nonOrderAutoReturnEnabled())
                + "\nAuto unload: " + onOff(data.nonOrderAutoUnloadEnabled());
    }

    private String buildZoneSummary() {
        int width = Math.abs(data.zoneMaxX() - data.zoneMinX()) + 1;
        int depth = Math.abs(data.zoneMaxZ() - data.zoneMinZ()) + 1;
        return "X: " + data.zoneMinX() + " .. " + data.zoneMaxX()
                + "\nZ: " + data.zoneMinZ() + " .. " + data.zoneMaxZ()
                + "\nArea: " + width + " x " + depth
                + "\nClassic screen keeps map drag/zone edit.";
    }

    private void renderRoutePage() {
        leftTitleView.setText("Routes");
        leftBodyView.setText(buildWindow("Routes", data.routeNames(), selectedRouteIndex, 7, name -> name));
        centerTitleView.setText("Route Detail");
        centerBodyView.setText("Route: " + safe(selectedRouteName())
                + "\nMeta: " + safe(selectedRouteMeta())
                + "\nWaypoints: " + data.selectedRouteWaypoints().size()
                + "\nBook loaded: " + (!data.routeBook().isEmpty()));
        rightTitleView.setText("Route Actions");
        rightBodyView.setText("Use hand-load for quick import.\nClassic controls keep full minimap and direct zone selection.");
        primaryActionButton.setText("Load From Hand");
        primaryActionButton.setEnabled(data.canManageDock());
        secondaryActionButton.setText("Import / Reverse");
        secondaryActionButton.setEnabled(data.canManageDock() && !data.routeNames().isEmpty());
        tertiaryActionButton.setText("Clear / Delete / Auto");
        tertiaryActionButton.setEnabled(data.canManageDock());
        ModernUiScreenHelper.hide(centerPrevButton, true);
        ModernUiScreenHelper.hide(centerNextButton, true);
    }

    private void renderDispatchPage() {
        leftTitleView.setText("Boats");
        leftBodyView.setText(buildWindow("Boats", data.nearbyBoatNames(), selectedBoatIndex, 6, name -> name));
        centerTitleView.setText("Routes");
        centerBodyView.setText(buildWindow("Routes", data.routeNames(), selectedRouteIndex, 6,
                name -> name + " | " + safe(metaForRoute(routeIndex()))));
        rightTitleView.setText("Dispatch");
        rightBodyView.setText("Boat: " + safe(selectedBoatName())
                + "\nRoute: " + safe(selectedRouteName())
                + "\nAuto return: " + onOff(data.nonOrderAutoReturnEnabled())
                + "\nAuto unload: " + onOff(data.nonOrderAutoUnloadEnabled()));
        primaryActionButton.setText("Assign Selected");
        primaryActionButton.setEnabled(!data.nearbyBoatNames().isEmpty() && !data.routeNames().isEmpty());
        secondaryActionButton.setText("Toggle Return");
        secondaryActionButton.setEnabled(data.canManageDock());
        tertiaryActionButton.setText("Toggle Unload");
        tertiaryActionButton.setEnabled(data.canManageDock());
        ModernUiScreenHelper.hide(centerPrevButton, false);
        ModernUiScreenHelper.hide(centerNextButton, false);
    }

    private void renderStoragePage() {
        leftTitleView.setText("Warehouse");
        leftBodyView.setText(buildWindow("Storage", data.storageLines(), selectedStorageIndex, 7, line -> line));
        centerTitleView.setText("Cargo");
        centerBodyView.setText(data.storageLines().isEmpty()
                ? Component.translatable("screen.sailboatmod.dock.storage_empty").getString()
                : "Selected cargo:\n" + safe(selectedStorageLine())
                + "\nNearby boats: " + data.nearbyBoatNames().size()
                + "\nRoutes loaded: " + data.routeNames().size());
        rightTitleView.setText("Warehouse Actions");
        rightBodyView.setText(data.canManageDock()
                ? "Dispatch selected cargo or withdraw it.\nUse classic controls for direct deposit slot interaction."
                : Component.translatable("screen.sailboatmod.dock.storage_owner_only").getString());
        primaryActionButton.setText("Dispatch Cargo");
        primaryActionButton.setEnabled(data.canManageDock() && !data.storageLines().isEmpty());
        secondaryActionButton.setText("Withdraw");
        secondaryActionButton.setEnabled(data.canManageDock() && !data.storageLines().isEmpty());
        tertiaryActionButton.setText("Refresh");
        tertiaryActionButton.setEnabled(true);
        ModernUiScreenHelper.hide(centerPrevButton, true);
        ModernUiScreenHelper.hide(centerNextButton, true);
    }

    private void renderWaybillPage() {
        leftTitleView.setText("Waybills");
        leftBodyView.setText(buildWindow("Waybills", data.waybillNames(), selectedWaybillIndex, 6, name -> name));
        centerTitleView.setText("Waybill Detail");
        centerBodyView.setText(buildWaybillDetail());
        rightTitleView.setText("Inbox Actions");
        rightBodyView.setText("Claim selected waybill cargo.\nClassic controls remain available for the original compact inbox.");
        primaryActionButton.setText("Take Waybill");
        primaryActionButton.setEnabled(!data.waybillNames().isEmpty());
        secondaryActionButton.setText("Refresh");
        secondaryActionButton.setEnabled(true);
        tertiaryActionButton.setText("Classic");
        tertiaryActionButton.setEnabled(true);
        ModernUiScreenHelper.hide(centerPrevButton, true);
        ModernUiScreenHelper.hide(centerNextButton, true);
    }

    private void handlePrimaryAction() {
        switch (activePage) {
            case PAGE_DISPATCH -> send(DockGuiActionPacket.Action.ASSIGN_SELECTED);
            case PAGE_STORAGE -> send(DockGuiActionPacket.Action.DISPATCH_SELECTED_CARGO);
            case PAGE_WAYBILL -> send(DockGuiActionPacket.Action.TAKE_SELECTED_WAYBILL);
            default -> send(DockGuiActionPacket.Action.LOAD_BOOK_FROM_HAND);
        }
    }

    private void handleSecondaryAction() {
        switch (activePage) {
            case PAGE_DISPATCH -> send(DockGuiActionPacket.Action.TOGGLE_NON_ORDER_AUTO_RETURN);
            case PAGE_STORAGE -> send(DockGuiActionPacket.Action.TAKE_SELECTED_STORAGE);
            case PAGE_WAYBILL -> send(DockGuiActionPacket.Action.REFRESH);
            default -> {
                if (data.canManageDock() && !data.routeNames().isEmpty()) {
                    send(DockGuiActionPacket.Action.IMPORT_BOOK);
                    send(DockGuiActionPacket.Action.REVERSE_ROUTE);
                }
            }
        }
    }

    private void handleTertiaryAction() {
        switch (activePage) {
            case PAGE_DISPATCH -> send(DockGuiActionPacket.Action.TOGGLE_NON_ORDER_AUTO_UNLOAD);
            case PAGE_STORAGE -> send(DockGuiActionPacket.Action.REFRESH);
            case PAGE_WAYBILL -> openClassicScreen();
            default -> {
                if (data.canManageDock()) {
                    send(DockGuiActionPacket.Action.CLEAR_BOOK);
                    if (!data.routeNames().isEmpty()) {
                        send(DockGuiActionPacket.Action.DELETE_ROUTE);
                    } else {
                        ModNetwork.CHANNEL.sendToServer(new RequestAutoRouteDocksPacket(data.dockPos()));
                    }
                }
            }
        }
    }

    private void moveLeft(int delta) {
        switch (activePage) {
            case PAGE_DISPATCH -> {
                selectedBoatIndex = clampSelection(selectedBoatIndex + delta, data.nearbyBoatNames().size());
                send(DockGuiActionPacket.Action.SELECT_BOAT_INDEX, selectedBoatIndex);
            }
            case PAGE_STORAGE -> {
                selectedStorageIndex = clampSelection(selectedStorageIndex + delta, data.storageLines().size());
                send(DockGuiActionPacket.Action.SELECT_STORAGE_INDEX, selectedStorageIndex);
            }
            case PAGE_WAYBILL -> {
                selectedWaybillIndex = clampSelection(selectedWaybillIndex + delta, data.waybillNames().size());
                send(DockGuiActionPacket.Action.SELECT_WAYBILL_INDEX, selectedWaybillIndex);
            }
            default -> {
                selectedRouteIndex = clampSelection(selectedRouteIndex + delta, data.routeNames().size());
                send(DockGuiActionPacket.Action.SELECT_ROUTE_INDEX, selectedRouteIndex);
            }
        }
        refreshViews();
    }

    private void moveCenter(int delta) {
        if (activePage == PAGE_DISPATCH) {
            selectedRouteIndex = clampSelection(selectedRouteIndex + delta, data.routeNames().size());
            send(DockGuiActionPacket.Action.SELECT_ROUTE_INDEX, selectedRouteIndex);
            refreshViews();
        }
    }

    private void renameDock() {
        if (!data.canManageDock()) {
            return;
        }
        String next = dockNameInput.getText().toString().trim();
        if (!next.isBlank() && !next.equals(data.dockName())) {
            ModNetwork.CHANNEL.sendToServer(new RenameDockPacket(data.dockPos(), next));
        }
    }

    private void openClassicScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        Inventory inventory = minecraft.player.getInventory();
        minecraft.setScreen(new DockScreen(menu, inventory, Component.translatable("screen.sailboatmod.dock.title")));
    }

    private void syncInput(EditText input, String value) {
        if (input == null || input.isFocused()) {
            return;
        }
        String next = value == null ? "" : value;
        String current = input.getText().toString();
        if (!next.equals(current)) {
            input.setText(next);
        }
    }

    private void send(DockGuiActionPacket.Action action) {
        ModNetwork.CHANNEL.sendToServer(new DockGuiActionPacket(data.dockPos(), action));
    }

    private void send(DockGuiActionPacket.Action action, int value) {
        ModNetwork.CHANNEL.sendToServer(new DockGuiActionPacket(data.dockPos(), action, value));
    }

    private String buildWaybillDetail() {
        if (data.waybillNames().isEmpty()) {
            return Component.translatable("screen.sailboatmod.dock.no_waybill").getString();
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Selected: ").append(safe(selectedWaybillName()));
        for (String line : data.selectedWaybillCargoLines()) {
            builder.append("\nCargo: ").append(line);
        }
        for (String line : data.selectedWaybillInfoLines()) {
            builder.append("\nInfo: ").append(line);
        }
        return builder.toString();
    }

    private <T> String buildWindow(String title, List<T> values, int selectedIndex, int windowSize, java.util.function.Function<T, String> formatter) {
        if (values == null || values.isEmpty()) {
            return "No " + title.toLowerCase() + " available.";
        }
        int safeIndex = clampSelection(selectedIndex, values.size());
        int start = Math.max(0, safeIndex - Math.max(1, windowSize / 2));
        int end = Math.min(values.size(), start + windowSize);
        if (end - start < windowSize) {
            start = Math.max(0, end - windowSize);
        }
        StringBuilder builder = new StringBuilder();
        builder.append(title).append(" ").append(safeIndex + 1).append("/").append(values.size());
        for (int i = start; i < end; i++) {
            builder.append('\n')
                    .append(i == safeIndex ? "> " : "- ")
                    .append(formatter.apply(values.get(i)));
        }
        return builder.toString();
    }

    private String selectedRouteName() {
        return routeIndex() < data.routeNames().size() ? data.routeNames().get(routeIndex()) : "-";
    }

    private String selectedRouteMeta() {
        return metaForRoute(routeIndex());
    }

    private String selectedBoatName() {
        return selectedBoatIndex < data.nearbyBoatNames().size() ? data.nearbyBoatNames().get(selectedBoatIndex) : "-";
    }

    private String selectedStorageLine() {
        return selectedStorageIndex < data.storageLines().size() ? data.storageLines().get(selectedStorageIndex) : "-";
    }

    private String selectedWaybillName() {
        return selectedWaybillIndex < data.waybillNames().size() ? data.waybillNames().get(selectedWaybillIndex) : "-";
    }

    private String metaForRoute(int index) {
        return index >= 0 && index < data.routeMetas().size() ? data.routeMetas().get(index) : "-";
    }

    private int routeIndex() {
        return clampSelection(selectedRouteIndex, data.routeNames().size());
    }

    private int clampSelection(int index, int size) {
        if (size <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(index, size - 1));
    }

    private String onOff(boolean value) {
        return value ? "On" : "Off";
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private DockScreenData empty(BlockPos pos) {
        return new DockScreenData(
                pos,
                "Dock",
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
