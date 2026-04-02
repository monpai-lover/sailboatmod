package com.monpai.sailboatmod.client.modernui;

import com.monpai.sailboatmod.client.MarketClientHooks;
import com.monpai.sailboatmod.client.MarketOverviewConsumer;
import com.monpai.sailboatmod.client.screen.MarketScreen;
import com.monpai.sailboatmod.market.MarketOverviewData;
import com.monpai.sailboatmod.menu.MarketMenu;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.CancelMarketListingPacket;
import com.monpai.sailboatmod.network.packet.ClaimMarketCreditsPacket;
import com.monpai.sailboatmod.network.packet.CreateMarketListingPacket;
import com.monpai.sailboatmod.network.packet.DispatchMarketOrderPacket;
import com.monpai.sailboatmod.network.packet.MarketGuiActionPacket;
import com.monpai.sailboatmod.network.packet.PurchaseMarketListingPacket;
import com.monpai.sailboatmod.network.packet.RenameMarketPacket;
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

import java.util.List;

public class MarketModernFragment extends Fragment implements ScreenCallback, MarketOverviewConsumer {
    private static final int PAGE_GOODS = 0;
    private static final int PAGE_SELL = 1;
    private static final int PAGE_DISPATCH = 2;
    private static final int PAGE_FINANCE = 3;

    private final MarketMenu menu;
    private MarketOverviewData data;

    private int activePage = PAGE_GOODS;
    private int selectedListingIndex;
    private int selectedOrderIndex;
    private int selectedBoatIndex;
    private int selectedStorageIndex;

    private TextView titleView;
    private TextView statusView;
    private TextView summaryMarketBody;
    private TextView summaryFlowBody;
    private TextView summaryModeBody;
    private TextView leftTitleView;
    private TextView leftBodyView;
    private TextView centerTitleView;
    private TextView centerBodyView;
    private TextView rightTitleView;
    private TextView rightBodyView;
    private EditText marketNameInput;
    private EditText qtyInput;
    private LinearLayout qtyPresetRow;
    private Button leftPrevButton;
    private Button leftNextButton;
    private Button centerPrevButton;
    private Button centerNextButton;
    private Button primaryActionButton;
    private Button secondaryActionButton;
    private Button goodsButton;
    private Button sellButton;
    private Button dispatchButton;
    private Button financeButton;
    private Button renameButton;
    private Button qtyOneButton;
    private Button qtySixteenButton;
    private Button qtyStackButton;
    private Button qtyMaxButton;

    public MarketModernFragment(MarketMenu menu) {
        this.menu = menu;
        MarketOverviewData initial = MarketClientHooks.consumeFor(menu.getMarketPos());
        this.data = initial != null ? initial : empty(menu.getMarketPos());
    }

    @Override
    public boolean isForMarket(BlockPos pos) {
        return data.marketPos().equals(pos);
    }

    @Override
    public void updateData(MarketOverviewData updated) {
        data = updated == null ? data : updated;
        selectedListingIndex = clampSelection(selectedListingIndex, data.listingEntries().size());
        selectedOrderIndex = clampSelection(selectedOrderIndex, data.orderEntries().size());
        selectedBoatIndex = clampSelection(selectedBoatIndex, data.shippingEntries().size());
        selectedStorageIndex = clampSelection(selectedStorageIndex, data.dockStorageEntries().size());
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

        LinearLayout tabRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        goodsButton = createTabButton("Goods", PAGE_GOODS);
        sellButton = createTabButton("Sell", PAGE_SELL);
        dispatchButton = createTabButton("Dispatch", PAGE_DISPATCH);
        financeButton = createTabButton("Finance", PAGE_FINANCE);
        tabRow.addView(goodsButton, ModernUiScreenHelper.wrap());
        tabRow.addView(sellButton, ModernUiScreenHelper.wrap());
        tabRow.addView(dispatchButton, ModernUiScreenHelper.wrap());
        tabRow.addView(financeButton, ModernUiScreenHelper.wrap());
        root.addView(tabRow, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout utilityRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        Button refreshButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Refresh");
        refreshButton.setOnClickListener(v -> send(MarketGuiActionPacket.Action.REFRESH));
        Button bindButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Bind Dock");
        bindButton.setOnClickListener(v -> send(MarketGuiActionPacket.Action.BIND_NEAREST_DOCK));
        Button classicButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Classic");
        classicButton.setOnClickListener(v -> openClassicScreen());
        utilityRow.addView(refreshButton, ModernUiScreenHelper.wrap());
        utilityRow.addView(bindButton, ModernUiScreenHelper.wrap());
        utilityRow.addView(classicButton, ModernUiScreenHelper.wrap());
        root.addView(utilityRow, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout renameRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        TextView renameLabel = new TextView(requireContext());
        renameLabel.setText("Market Name");
        renameLabel.setTextSize(11);
        marketNameInput = ModernUiScreenHelper.createInput(new EditText(requireContext()), "");
        renameButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Save Name");
        renameButton.setOnClickListener(v -> renameMarket());
        renameRow.addView(renameLabel, ModernUiScreenHelper.wrap());
        renameRow.addView(marketNameInput, ModernUiScreenHelper.weightedWrap(1.4f));
        renameRow.addView(renameButton, ModernUiScreenHelper.wrap());
        root.addView(renameRow, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout summaryRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        summaryRow.addView(createSummaryCard("Market"), ModernUiScreenHelper.weightedWrap(1f));
        summaryRow.addView(createFlowSummaryCard("Flow"), ModernUiScreenHelper.weightedWrap(1f));
        summaryRow.addView(createModeSummaryCard("Focus"), ModernUiScreenHelper.weightedWrap(1f));
        root.addView(summaryRow, ModernUiScreenHelper.matchWidthWrap());

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
        qtyInput = ModernUiScreenHelper.createInput(new EditText(requireContext()), "1");
        qtyPresetRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        qtyOneButton = createQtyPresetButton("1", 1);
        qtySixteenButton = createQtyPresetButton("16", 16);
        qtyStackButton = createQtyPresetButton("64", 64);
        qtyMaxButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Max");
        qtyMaxButton.setOnClickListener(v -> applyMaxQuantity());
        qtyPresetRow.addView(qtyOneButton, ModernUiScreenHelper.wrap());
        qtyPresetRow.addView(qtySixteenButton, ModernUiScreenHelper.wrap());
        qtyPresetRow.addView(qtyStackButton, ModernUiScreenHelper.wrap());
        qtyPresetRow.addView(qtyMaxButton, ModernUiScreenHelper.wrap());
        primaryActionButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "");
        primaryActionButton.setOnClickListener(v -> handlePrimaryAction());
        secondaryActionButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "");
        secondaryActionButton.setOnClickListener(v -> handleSecondaryAction());
        rightPanel.addView(rightTitleView, ModernUiScreenHelper.matchWidthWrap());
        rightPanel.addView(rightBodyView, ModernUiScreenHelper.matchWidthWrap());
        rightPanel.addView(qtyInput, ModernUiScreenHelper.matchWidthWrap());
        rightPanel.addView(qtyPresetRow, ModernUiScreenHelper.matchWidthWrap());
        rightPanel.addView(primaryActionButton, ModernUiScreenHelper.matchWidthWrap());
        rightPanel.addView(secondaryActionButton, ModernUiScreenHelper.matchWidthWrap());
        contentRow.addView(rightPanel, ModernUiScreenHelper.weighted());

        root.addView(contentRow, ModernUiScreenHelper.matchWidthWrap());
        refreshViews();
        return scrollView;
    }

    private Button createTabButton(String text, int page) {
        Button button = ModernUiScreenHelper.createButton(new Button(requireContext()), text);
        button.setOnClickListener(v -> {
            activePage = page;
            refreshViews();
        });
        return button;
    }

    private LinearLayout createSummaryCard(String title) {
        LinearLayout card = ModernUiScreenHelper.createCard(new LinearLayout(requireContext()));
        card.addView(ModernUiScreenHelper.createHeader(new TextView(requireContext()), title, 13), ModernUiScreenHelper.matchWidthWrap());
        summaryMarketBody = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        card.addView(summaryMarketBody, ModernUiScreenHelper.matchWidthWrap());
        return card;
    }

    private LinearLayout createFlowSummaryCard(String title) {
        LinearLayout card = ModernUiScreenHelper.createCard(new LinearLayout(requireContext()));
        card.addView(ModernUiScreenHelper.createHeader(new TextView(requireContext()), title, 13), ModernUiScreenHelper.matchWidthWrap());
        summaryFlowBody = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        card.addView(summaryFlowBody, ModernUiScreenHelper.matchWidthWrap());
        return card;
    }

    private LinearLayout createModeSummaryCard(String title) {
        LinearLayout card = ModernUiScreenHelper.createCard(new LinearLayout(requireContext()));
        card.addView(ModernUiScreenHelper.createHeader(new TextView(requireContext()), title, 13), ModernUiScreenHelper.matchWidthWrap());
        summaryModeBody = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        card.addView(summaryModeBody, ModernUiScreenHelper.matchWidthWrap());
        return card;
    }

    private Button createQtyPresetButton(String text, int amount) {
        Button button = ModernUiScreenHelper.createButton(new Button(requireContext()), text);
        button.setOnClickListener(v -> {
            qtyInput.setText(String.valueOf(amount));
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
        titleView.setText(data.marketName());
        statusView.setText(currentDockLine()
                + "\nOwner: " + data.ownerName()
                + " | Manage: " + data.canManage()
                + " | Pending credits: " + data.pendingCredits());
        syncInput(marketNameInput, data.marketName());
        renameButton.setEnabled(data.canManage());
        summaryMarketBody.setText(buildMarketSummary());
        summaryFlowBody.setText(buildFlowSummary());
        summaryModeBody.setText(buildModeSummary());
        goodsButton.setEnabled(activePage != PAGE_GOODS);
        sellButton.setEnabled(activePage != PAGE_SELL);
        dispatchButton.setEnabled(activePage != PAGE_DISPATCH);
        financeButton.setEnabled(activePage != PAGE_FINANCE);

        switch (activePage) {
            case PAGE_SELL -> renderSellPage();
            case PAGE_DISPATCH -> renderDispatchPage();
            case PAGE_FINANCE -> renderFinancePage();
            default -> renderGoodsPage();
        }
    }

    private String buildMarketSummary() {
        return "Name: " + data.marketName()
                + "\nOwner: " + data.ownerName()
                + "\nPending: " + data.pendingCredits()
                + "\nDock: " + (data.linkedDock() ? safe(data.linkedDockName()) : "Unlinked");
    }

    private String buildFlowSummary() {
        return "Storage: " + data.dockStorageEntries().size()
                + "\nListings: " + data.listingEntries().size()
                + "\nOrders: " + data.orderEntries().size()
                + "\nFleet: " + data.shippingEntries().size();
    }

    private String buildModeSummary() {
        return "Page: " + pageName()
                + "\nSelection: " + selectedSummary()
                + "\nQty: " + currentQtyText()
                + "\nActions: " + actionSummary();
    }

    private void renderGoodsPage() {
        MarketOverviewData.ListingEntry entry = selectedListingEntry();
        leftTitleView.setText("Goods");
        leftBodyView.setText(buildWindow("Listings", data.listingEntries(), selectedListingIndex, 7,
                listing -> listing.label() + " | " + listing.unitPrice() + "c"));
        centerTitleView.setText("Quote");
        centerBodyView.setText(entry == null ? "No data"
                : "Item: " + entry.itemName()
                + "\nUnit price: " + entry.unitPrice()
                + "\nAvailable: " + entry.availableCount()
                + "\nReserved: " + entry.reservedCount()
                + "\nSeller: " + entry.sellerName()
                + "\nDock: " + entry.sourceDockName()
                + "\nNation: " + safe(entry.nationId())
                + "\nImmediate fill: " + Math.max(0, entry.availableCount())
                + "\nMarket depth: " + (entry.availableCount() + entry.reservedCount())
                + "\nOne stack estimate: " + (entry.unitPrice() * 64));
        rightTitleView.setText("Actions");
        int qty = parsePositive(qtyInput.getText().toString(), 1);
        rightBodyView.setText(entry == null ? "Select a listing first."
                : "Buy qty: " + qty
                + "\nEstimated total: " + (entry.unitPrice() * qty)
                + "\nPer unit after buy: " + entry.unitPrice()
                + "\nUse cancel to withdraw your own listing.");
        qtyInput.setVisibility(View.VISIBLE);
        qtyPresetRow.setVisibility(View.VISIBLE);
        primaryActionButton.setText("Buy");
        primaryActionButton.setEnabled(entry != null && data.linkedDock());
        secondaryActionButton.setText("Cancel Listing");
        secondaryActionButton.setEnabled(entry != null);
        updateQtyPresetButtons(entry == null ? 0 : entry.availableCount());
        ModernUiScreenHelper.hide(centerPrevButton, true);
        ModernUiScreenHelper.hide(centerNextButton, true);
    }

    private void renderSellPage() {
        MarketOverviewData.StorageEntry entry = selectedStorageEntry();
        leftTitleView.setText("Dock Storage");
        leftBodyView.setText(data.dockStorageEntries().isEmpty()
                ? Component.translatable("screen.sailboatmod.market.storage_empty").getString()
                : buildWindow("Storage", data.dockStorageEntries(), selectedStorageIndex, 7,
                storage -> storage.label() + " | x" + storage.quantity()));
        centerTitleView.setText("Pricing");
        centerBodyView.setText(entry == null ? "No cargo selected."
                : "Item: " + entry.itemName()
                + "\nStored: " + entry.quantity()
                + "\nSuggested price: " + entry.suggestedUnitPrice()
                + "\nSource: " + entry.detail()
                + "\nSuggested stack value: " + (entry.suggestedUnitPrice() * entry.quantity())
                + "\nSell 16 estimate: " + (entry.suggestedUnitPrice() * Math.min(16, entry.quantity()))
                + "\n" + Component.translatable("screen.sailboatmod.market.auto_price_help").getString());
        rightTitleView.setText("Post");
        int qty = parsePositive(qtyInput.getText().toString(), 1);
        rightBodyView.setText(data.dockStorageAccessible()
                ? (entry == null ? "Select storage cargo first." : "Post qty: " + qty + "\nLive market estimate: " + (entry.suggestedUnitPrice() * qty))
                : Component.translatable("screen.sailboatmod.market.storage_owner_only").getString());
        qtyInput.setVisibility(View.VISIBLE);
        qtyPresetRow.setVisibility(View.VISIBLE);
        primaryActionButton.setText("List");
        primaryActionButton.setEnabled(entry != null && data.linkedDock() && data.dockStorageAccessible());
        secondaryActionButton.setText("Refresh");
        secondaryActionButton.setEnabled(true);
        updateQtyPresetButtons(entry == null ? 0 : entry.quantity());
        ModernUiScreenHelper.hide(centerPrevButton, true);
        ModernUiScreenHelper.hide(centerNextButton, true);
    }

    private void renderDispatchPage() {
        MarketOverviewData.OrderEntry order = selectedOrderEntry();
        MarketOverviewData.ShippingEntry boat = selectedBoatEntry();
        leftTitleView.setText("Orders");
        leftBodyView.setText(buildWindow("Orders", data.orderEntries(), selectedOrderIndex, 6,
                orderEntry -> orderEntry.label() + " | " + orderEntry.status()));
        centerTitleView.setText("Boats");
        centerBodyView.setText(buildWindow("Fleet", data.shippingEntries(), selectedBoatIndex, 6,
                shipping -> shipping.boatName() + " | " + safe(shipping.routeName()) + " | " + safe(shipping.mode()))
                + (boat == null ? "" : "\n\nSelected boat:\nRoute: " + safe(boat.routeName()) + "\nMode: " + safe(boat.mode())));
        rightTitleView.setText("Dispatch");
        rightBodyView.setText(order == null ? "Select an order."
                : "From: " + order.sourceDockName()
                + "\nTo: " + order.targetDockName()
                + "\nQty: " + order.quantity()
                + "\nStatus: " + order.status()
                + (boat == null ? "\nBoat: -" : "\nBoat: " + boat.boatName() + "\nRoute: " + safe(boat.routeName())));
        qtyInput.setVisibility(View.GONE);
        qtyPresetRow.setVisibility(View.GONE);
        primaryActionButton.setText("Dispatch");
        primaryActionButton.setEnabled(order != null && boat != null && data.linkedDock());
        secondaryActionButton.setText("Refresh");
        secondaryActionButton.setEnabled(true);
        ModernUiScreenHelper.hide(centerPrevButton, false);
        ModernUiScreenHelper.hide(centerNextButton, false);
    }

    private void renderFinancePage() {
        leftTitleView.setText("Finance");
        leftBodyView.setText("Pending credits: " + data.pendingCredits()
                + "\nOwner: " + data.ownerName()
                + "\nCan manage: " + data.canManage()
                + "\nListings live: " + data.listingEntries().size()
                + "\nOrders open: " + data.orderEntries().size());
        centerTitleView.setText("Link");
        centerBodyView.setText(currentDockLine()
                + "\nStorage access: " + data.dockStorageAccessible()
                + "\nShip pool: " + data.shippingEntries().size()
                + "\nRename available: " + data.canManage());
        rightTitleView.setText("Settlement");
        rightBodyView.setText(data.pendingCredits() > 0
                ? "Claim your pending market revenue.\nAmount ready: " + data.pendingCredits()
                : "No pending credits right now.\nUse this page to manage dock linkage and market naming.");
        qtyInput.setVisibility(View.GONE);
        qtyPresetRow.setVisibility(View.GONE);
        primaryActionButton.setText("Claim");
        primaryActionButton.setEnabled(data.pendingCredits() > 0);
        secondaryActionButton.setText("Bind Dock");
        secondaryActionButton.setEnabled(true);
        ModernUiScreenHelper.hide(centerPrevButton, true);
        ModernUiScreenHelper.hide(centerNextButton, true);
    }

    private void handlePrimaryAction() {
        int qty = parsePositive(qtyInput.getText().toString(), 1);
        switch (activePage) {
            case PAGE_SELL -> ModNetwork.CHANNEL.sendToServer(new CreateMarketListingPacket(data.marketPos(), selectedStorageIndex, qty, 0));
            case PAGE_DISPATCH -> ModNetwork.CHANNEL.sendToServer(new DispatchMarketOrderPacket(data.marketPos(), selectedOrderIndex, selectedBoatIndex));
            case PAGE_FINANCE -> ModNetwork.CHANNEL.sendToServer(new ClaimMarketCreditsPacket(data.marketPos()));
            default -> ModNetwork.CHANNEL.sendToServer(new PurchaseMarketListingPacket(data.marketPos(), selectedListingIndex, qty));
        }
    }

    private void handleSecondaryAction() {
        switch (activePage) {
            case PAGE_GOODS -> ModNetwork.CHANNEL.sendToServer(new CancelMarketListingPacket(data.marketPos(), selectedListingIndex));
            case PAGE_FINANCE -> send(MarketGuiActionPacket.Action.BIND_NEAREST_DOCK);
            default -> send(MarketGuiActionPacket.Action.REFRESH);
        }
    }

    private void moveLeft(int delta) {
        if (activePage == PAGE_SELL) {
            selectedStorageIndex = clampSelection(selectedStorageIndex + delta, data.dockStorageEntries().size());
        } else if (activePage == PAGE_DISPATCH) {
            selectedOrderIndex = clampSelection(selectedOrderIndex + delta, data.orderEntries().size());
        } else if (activePage == PAGE_FINANCE) {
            return;
        } else {
            selectedListingIndex = clampSelection(selectedListingIndex + delta, data.listingEntries().size());
        }
        refreshViews();
    }

    private void moveCenter(int delta) {
        if (activePage == PAGE_DISPATCH) {
            selectedBoatIndex = clampSelection(selectedBoatIndex + delta, data.shippingEntries().size());
            refreshViews();
        }
    }

    private void renameMarket() {
        if (!data.canManage()) {
            return;
        }
        String nextName = marketNameInput.getText().toString().trim();
        if (nextName.isBlank() || nextName.equals(data.marketName())) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new RenameMarketPacket(data.marketPos(), nextName));
    }

    private void applyMaxQuantity() {
        int max = switch (activePage) {
            case PAGE_SELL -> {
                MarketOverviewData.StorageEntry entry = selectedStorageEntry();
                yield entry == null ? 1 : Math.max(1, entry.quantity());
            }
            case PAGE_GOODS -> {
                MarketOverviewData.ListingEntry entry = selectedListingEntry();
                yield entry == null ? 1 : Math.max(1, entry.availableCount());
            }
            default -> 1;
        };
        qtyInput.setText(String.valueOf(max));
        refreshViews();
    }

    private void updateQtyPresetButtons(int maxAvailable) {
        boolean allowOne = maxAvailable >= 1;
        boolean allowSixteen = maxAvailable >= 16;
        boolean allowStack = maxAvailable >= 64;
        qtyOneButton.setEnabled(allowOne);
        qtySixteenButton.setEnabled(allowSixteen);
        qtyStackButton.setEnabled(allowStack);
        qtyMaxButton.setEnabled(allowOne);
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

    private void send(MarketGuiActionPacket.Action action) {
        ModNetwork.CHANNEL.sendToServer(new MarketGuiActionPacket(data.marketPos(), action));
    }

    private void openClassicScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        Inventory inventory = minecraft.player.getInventory();
        minecraft.setScreen(new MarketScreen(menu, inventory, Component.translatable("screen.sailboatmod.market.title")));
    }

    private MarketOverviewData.ListingEntry selectedListingEntry() {
        return selectedListingIndex >= 0 && selectedListingIndex < data.listingEntries().size()
                ? data.listingEntries().get(selectedListingIndex)
                : null;
    }

    private MarketOverviewData.StorageEntry selectedStorageEntry() {
        return selectedStorageIndex >= 0 && selectedStorageIndex < data.dockStorageEntries().size()
                ? data.dockStorageEntries().get(selectedStorageIndex)
                : null;
    }

    private MarketOverviewData.OrderEntry selectedOrderEntry() {
        return selectedOrderIndex >= 0 && selectedOrderIndex < data.orderEntries().size()
                ? data.orderEntries().get(selectedOrderIndex)
                : null;
    }

    private MarketOverviewData.ShippingEntry selectedBoatEntry() {
        return selectedBoatIndex >= 0 && selectedBoatIndex < data.shippingEntries().size()
                ? data.shippingEntries().get(selectedBoatIndex)
                : null;
    }

    private String pageName() {
        return switch (activePage) {
            case PAGE_SELL -> "Sell";
            case PAGE_DISPATCH -> "Dispatch";
            case PAGE_FINANCE -> "Finance";
            default -> "Goods";
        };
    }

    private String selectedSummary() {
        return switch (activePage) {
            case PAGE_SELL -> {
                MarketOverviewData.StorageEntry entry = selectedStorageEntry();
                yield entry == null ? "-" : entry.itemName() + " x" + entry.quantity();
            }
            case PAGE_DISPATCH -> {
                MarketOverviewData.OrderEntry order = selectedOrderEntry();
                MarketOverviewData.ShippingEntry boat = selectedBoatEntry();
                if (order == null) {
                    yield "-";
                }
                yield order.targetDockName() + (boat == null ? "" : " via " + boat.boatName());
            }
            case PAGE_FINANCE -> data.pendingCredits() > 0 ? "Claimable" : "Idle";
            default -> {
                MarketOverviewData.ListingEntry entry = selectedListingEntry();
                yield entry == null ? "-" : entry.itemName() + " @" + entry.unitPrice();
            }
        };
    }

    private String currentQtyText() {
        return activePage == PAGE_DISPATCH || activePage == PAGE_FINANCE
                ? "-"
                : String.valueOf(parsePositive(qtyInput.getText().toString(), 1));
    }

    private String actionSummary() {
        return switch (activePage) {
            case PAGE_SELL -> data.dockStorageAccessible() ? "List/Refresh" : "Owner only";
            case PAGE_DISPATCH -> data.linkedDock() ? "Dispatch/Refresh" : "Bind dock";
            case PAGE_FINANCE -> "Claim/Bind Dock";
            default -> data.linkedDock() ? "Buy/Cancel" : "Bind dock first";
        };
    }

    private String currentDockLine() {
        return data.linkedDock()
                ? Component.translatable("screen.sailboatmod.market.linked_dock_value", data.linkedDockName(), data.linkedDockPosText()).getString()
                : Component.translatable("screen.sailboatmod.market.linked_dock_missing").getString();
    }

    private <T> String buildWindow(String title, List<T> values, int selectedIndex, int windowSize,
                                   java.util.function.Function<T, String> formatter) {
        if (values == null || values.isEmpty()) {
            return "No " + title.toLowerCase() + " available.";
        }
        StringBuilder builder = new StringBuilder();
        int safeIndex = clampSelection(selectedIndex, values.size());
        builder.append(title).append(" ").append(safeIndex + 1).append("/").append(values.size());
        int start = Math.max(0, safeIndex - Math.max(1, windowSize / 2));
        int end = Math.min(values.size(), start + windowSize);
        if (end - start < windowSize) {
            start = Math.max(0, end - windowSize);
        }
        for (int i = start; i < end; i++) {
            builder.append('\n')
                    .append(i == safeIndex ? "> " : "- ")
                    .append(formatter.apply(values.get(i)));
        }
        return builder.toString();
    }

    private int clampSelection(int current, int size) {
        if (size <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(current, size - 1));
    }

    private int parsePositive(String value, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private MarketOverviewData empty(BlockPos pos) {
        return new MarketOverviewData(pos, "Market", "-", "", 0, false, "-", "-", false, false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
