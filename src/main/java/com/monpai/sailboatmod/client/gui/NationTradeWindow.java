package com.monpai.sailboatmod.client.gui;

import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.ItemIcon;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.controls.TextField;
import com.ldtteam.blockui.views.Box;
import com.ldtteam.blockui.views.ScrollingList;
import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.nation.menu.TradeScreenData;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.TradeScreenActionPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class NationTradeWindow extends AbstractSailboatWindow {
    private static final ResourceLocation LAYOUT = new ResourceLocation(SailboatMod.MODID, "gui/nation/trade.xml");
    private static final int MAX_TRADE_ITEMS = TradeScreenData.MAX_TRADE_ITEMS;
    private static final int TREASURY_COLUMNS = 5;
    private static final int AUTO_REFRESH_TICKS = 40;

    private static final TextField.Filter CURRENCY_FILTER = new TextField.Filter() {
        @Override
        public String filter(String text) {
            return TradeWindowStatePolicy.filterCurrencyText(text);
        }

        @Override
        public boolean isAllowedCharacter(char c) {
            return TradeWindowStatePolicy.isCurrencyCharacterAllowed(c);
        }
    };

    private TradeScreenData data;
    private final List<ItemStack> offerSlots = new ArrayList<>(MAX_TRADE_ITEMS);
    private final List<ItemStack> requestSlots = new ArrayList<>(MAX_TRADE_ITEMS);
    private TextField offerCurrencyInput;
    private TextField requestCurrencyInput;
    private ScrollingList ourTreasuryList;
    private ScrollingList targetTreasuryList;
    private int refreshTicks;

    public NationTradeWindow(TradeScreenData data) {
        super(LAYOUT);
        this.data = data == null ? TradeScreenData.empty() : data;
        loadDraftFromData();

        registerButton("close", this::close);
        registerButton("propose", () -> sendAction(TradeScreenActionPacket.Action.PROPOSE));
        registerButton("accept", () -> sendAction(TradeScreenActionPacket.Action.ACCEPT));
        registerButton("reject", () -> sendAction(TradeScreenActionPacket.Action.REJECT));
        registerButton("counter", () -> sendAction(TradeScreenActionPacket.Action.COUNTER_OFFER));
        registerButton("cancel", () -> sendAction(TradeScreenActionPacket.Action.CANCEL));
        for (int i = 0; i < MAX_TRADE_ITEMS; i++) {
            final int slot = i;
            registerButton("offerSlot" + i, () -> removeTradeSlot(offerSlots, slot));
            registerButton("requestSlot" + i, () -> removeTradeSlot(requestSlots, slot));
        }
        for (int i = 0; i < TREASURY_COLUMNS; i++) {
            final int column = i;
            registerButton("ourTreasurySlot" + i, button -> addTreasuryItem(button, true, column));
            registerButton("targetTreasurySlot" + i, button -> addTreasuryItem(button, false, column));
        }
    }

    @Override
    public void onOpened() {
        super.onOpened();
        offerCurrencyInput = findPaneOfTypeByID("offerCurrency", TextField.class);
        requestCurrencyInput = findPaneOfTypeByID("requestCurrency", TextField.class);
        ourTreasuryList = findPaneOfTypeByID("ourTreasuryList", ScrollingList.class);
        targetTreasuryList = findPaneOfTypeByID("targetTreasuryList", ScrollingList.class);
        configureCurrencyInput(offerCurrencyInput);
        configureCurrencyInput(requestCurrencyInput);
        refreshAll();
    }

    public void updateData(TradeScreenData newData) {
        TradeScreenData next = newData == null ? TradeScreenData.empty() : newData;
        boolean replaceDraft = TradeWindowStatePolicy.shouldReplaceDraft(
                data.targetNationId(),
                data.hasExistingProposal(),
                data.proposalId(),
                next.targetNationId(),
                next.hasExistingProposal(),
                next.proposalId()
        );
        data = next;
        if (replaceDraft) {
            loadDraftFromData();
        }
        refreshAll();
    }

    @Override
    public void onClosed() {
        super.onClosed();
        com.monpai.sailboatmod.client.TradeClientHooks.onScreenClosed();
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        refreshTicks++;
        if (refreshTicks >= AUTO_REFRESH_TICKS) {
            refreshTicks = 0;
            ModNetwork.CHANNEL.sendToServer(
                    new TradeScreenActionPacket(TradeScreenActionPacket.Action.REFRESH, data.targetNationId()));
        }
    }

    private void configureCurrencyInput(TextField input) {
        if (input == null) {
            return;
        }
        input.setMaxTextLength(15);
        input.setFilter(CURRENCY_FILTER);
    }

    private void loadDraftFromData() {
        offerSlots.clear();
        requestSlots.clear();
        for (int i = 0; i < MAX_TRADE_ITEMS; i++) {
            offerSlots.add(ItemStack.EMPTY);
            requestSlots.add(ItemStack.EMPTY);
        }
        if (data.hasExistingProposal()) {
            copyItemsIntoSlots(data.offerItems(), offerSlots);
            copyItemsIntoSlots(data.requestItems(), requestSlots);
        }
        if (offerCurrencyInput != null && requestCurrencyInput != null) {
            loadCurrencyInputsFromData();
        }
    }

    private void copyItemsIntoSlots(List<ItemStack> source, List<ItemStack> target) {
        for (int i = 0; i < Math.min(source.size(), MAX_TRADE_ITEMS); i++) {
            ItemStack item = source.get(i);
            target.set(i, item == null ? ItemStack.EMPTY : item.copy());
        }
    }

    private void loadCurrencyInputsFromData() {
        if (data.hasExistingProposal()) {
            offerCurrencyInput.setText(String.valueOf(data.offerCurrency()));
            requestCurrencyInput.setText(String.valueOf(data.requestCurrency()));
        } else {
            offerCurrencyInput.setText("");
            requestCurrencyInput.setText("");
        }
    }

    private void refreshAll() {
        setText("title", Component.translatable("screen.sailboatmod.trade.title"));
        setText("ourName", Component.literal(data.ourNationName()));
        setText("targetName", Component.literal(data.targetNationName()));
        setText("offerTitle", Component.translatable("screen.sailboatmod.trade.our_offer"));
        setText("requestTitle", Component.translatable("screen.sailboatmod.trade.we_request"));
        setText("ourBalance", Component.translatable("screen.sailboatmod.trade.balance", data.ourTreasuryBalance()));
        setText("targetBalance", Component.translatable(
                "screen.sailboatmod.trade.balance.tier." + tierKey(data.targetTreasuryTier())));
        setText("offerCurrencyLabel", Component.translatable("screen.sailboatmod.trade.currency"));
        setText("requestCurrencyLabel", Component.translatable("screen.sailboatmod.trade.currency"));
        setText("ourTreasuryTitle", Component.translatable("screen.sailboatmod.trade.treasury_preview"));
        setText("targetTreasuryTitle", targetTreasuryVisible()
                ? Component.translatable("screen.sailboatmod.trade.target_treasury")
                : Component.empty());
        setText("targetTreasuryHidden", targetTreasuryVisible()
                ? Component.empty()
                : Component.translatable("screen.sailboatmod.trade.treasury_hidden"));
        setStatusText();
        setNationColor("ourColor", data.ourPrimaryColor());
        setNationColor("targetColor", data.targetPrimaryColor());
        if (offerCurrencyInput != null && offerCurrencyInput.getText().isEmpty() && data.hasExistingProposal()) {
            loadCurrencyInputsFromData();
        }
        refreshTradeSlots();
        refreshTreasuryLists();
        refreshButtons();
    }

    private void setStatusText() {
        if (data.hasExistingProposal()) {
            int min = data.proposalRemainingSeconds() / 60;
            int sec = data.proposalRemainingSeconds() % 60;
            String time = String.format("%d:%02d", min, sec);
            setText("status", data.weAreProposer()
                    ? Component.translatable("screen.sailboatmod.trade.status.waiting", time)
                    : Component.translatable("screen.sailboatmod.trade.status.incoming", time));
        } else {
            setText("status", Component.translatable("screen.sailboatmod.trade.status.none"));
        }
    }

    private void setNationColor(String id, int rgb) {
        Box box = findPaneOfTypeByID(id, Box.class);
        if (box == null) {
            return;
        }
        int color = rgb & 0xFFFFFF;
        box.setColor((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF);
    }

    private void refreshTradeSlots() {
        boolean readOnly = readOnly();
        for (int i = 0; i < MAX_TRADE_ITEMS; i++) {
            updateTradeSlot("offer", offerSlots, i, readOnly);
            updateTradeSlot("request", requestSlots, i, readOnly);
        }
    }

    private void updateTradeSlot(String prefix, List<ItemStack> slots, int index, boolean readOnly) {
        ItemStack item = index < slots.size() ? slots.get(index) : ItemStack.EMPTY;
        ItemIcon icon = findPaneOfTypeByID(prefix + "Icon" + index, ItemIcon.class);
        if (icon != null) {
            icon.setItem(item == null ? ItemStack.EMPTY : item);
        }
        Button button = findPaneOfTypeByID(prefix + "Slot" + index, Button.class);
        if (button != null) {
            button.setEnabled(!readOnly);
        }
    }

    private void refreshTreasuryLists() {
        if (ourTreasuryList != null) {
            ourTreasuryList.setDataProvider(treasuryProvider(data.ourTreasuryItems(), true));
            ourTreasuryList.refreshElementPanes();
        }
        boolean showTarget = targetTreasuryVisible();
        Pane hidden = findPaneByID("targetTreasuryHidden");
        if (hidden != null) {
            hidden.setVisible(!showTarget);
        }
        if (targetTreasuryList != null) {
            targetTreasuryList.setVisible(showTarget);
            if (showTarget) {
                targetTreasuryList.setDataProvider(treasuryProvider(data.targetTreasuryItems(), false));
                targetTreasuryList.refreshElementPanes();
            }
        }
    }

    private ScrollingList.DataProvider treasuryProvider(List<ItemStack> items, boolean ours) {
        List<ItemStack> safeItems = items == null ? List.of() : items;
        String prefix = ours ? "our" : "target";
        return new ScrollingList.DataProvider() {
            @Override
            public int getElementCount() {
                return (safeItems.size() + TREASURY_COLUMNS - 1) / TREASURY_COLUMNS;
            }

            @Override
            public void updateElement(int index, Pane rowPane) {
                for (int column = 0; column < TREASURY_COLUMNS; column++) {
                    int itemIndex = index * TREASURY_COLUMNS + column;
                    boolean hasIndex = itemIndex < safeItems.size();
                    ItemStack item = hasIndex ? safeItems.get(itemIndex) : ItemStack.EMPTY;
                    Button button = rowPane.findPaneOfTypeByID(prefix + "TreasurySlot" + column, Button.class);
                    if (button != null) {
                        button.setVisible(hasIndex);
                        button.setEnabled(hasIndex && !readOnly());
                    }
                    ItemIcon icon = rowPane.findPaneOfTypeByID(prefix + "TreasuryIcon" + column, ItemIcon.class);
                    if (icon != null) {
                        icon.setVisible(hasIndex);
                        icon.setItem(item == null ? ItemStack.EMPTY : item);
                    }
                }
            }
        };
    }

    private void refreshButtons() {
        boolean hasProposal = data.hasExistingProposal();
        boolean weProposed = hasProposal && data.weAreProposer();
        boolean theyProposed = hasProposal && !data.weAreProposer();

        setButton("propose", Component.translatable("screen.sailboatmod.trade.propose"), !hasProposal, data.canManageTreasury());
        setButton("accept", Component.translatable("screen.sailboatmod.trade.accept"), theyProposed, data.canManageTreasury());
        setButton("reject", Component.translatable("screen.sailboatmod.trade.reject"), theyProposed, true);
        setButton("counter", Component.translatable("screen.sailboatmod.trade.counter"), theyProposed, data.canManageTreasury());
        setButton("cancel", Component.translatable("screen.sailboatmod.trade.cancel"), weProposed, true);

        boolean readOnly = readOnly();
        if (offerCurrencyInput != null) {
            offerCurrencyInput.setEnabled(!readOnly);
        }
        if (requestCurrencyInput != null) {
            requestCurrencyInput.setEnabled(!readOnly);
        }
    }

    private void setButton(String id, Component label, boolean visible, boolean enabled) {
        Button button = findPaneOfTypeByID(id, Button.class);
        if (button == null) {
            return;
        }
        button.setText(label);
        button.setVisible(visible);
        button.setEnabled(enabled);
    }

    private void removeTradeSlot(List<ItemStack> slots, int slot) {
        if (readOnly() || slot < 0 || slot >= slots.size() || slots.get(slot).isEmpty()) {
            return;
        }
        slots.set(slot, ItemStack.EMPTY);
        refreshTradeSlots();
    }

    private void addTreasuryItem(Button button, boolean ours, int column) {
        if (readOnly()) {
            return;
        }
        ScrollingList list = ours ? ourTreasuryList : targetTreasuryList;
        if (list == null) {
            return;
        }
        int row = list.getListElementIndexByPane(button);
        if (row < 0) {
            return;
        }
        int itemIndex = row * TREASURY_COLUMNS + column;
        List<ItemStack> source = ours ? data.ourTreasuryItems() : data.targetTreasuryItems();
        if (itemIndex < 0 || itemIndex >= source.size()) {
            return;
        }
        ItemStack item = source.get(itemIndex);
        if (item == null || item.isEmpty()) {
            return;
        }
        addToFirstEmpty(ours ? offerSlots : requestSlots, item.copy());
        refreshTradeSlots();
    }

    private void addToFirstEmpty(List<ItemStack> slots, ItemStack item) {
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).isEmpty()) {
                slots.set(i, item);
                return;
            }
        }
    }

    private void sendAction(TradeScreenActionPacket.Action action) {
        ModNetwork.CHANNEL.sendToServer(
                new TradeScreenActionPacket(
                        action,
                        data.targetNationId(),
                        TradeWindowStatePolicy.parseCurrency(offerCurrencyInput == null ? "" : offerCurrencyInput.getText()),
                        TradeWindowStatePolicy.parseCurrency(requestCurrencyInput == null ? "" : requestCurrencyInput.getText()),
                        compactItems(offerSlots),
                        compactItems(requestSlots)
                ));
    }

    private List<ItemStack> compactItems(List<ItemStack> source) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack item : source) {
            if (item != null && !item.isEmpty()) {
                result.add(item.copy());
            }
        }
        return result;
    }

    private boolean targetTreasuryVisible() {
        return !data.targetTreasuryItems().isEmpty();
    }

    private static String tierKey(String tier) {
        return switch (tier == null ? "" : tier) {
            case "minimal", "modest", "substantial", "wealthy", "prosperous" -> tier;
            default -> "unknown";
        };
    }

    private boolean readOnly() {
        return data.hasExistingProposal() && !data.weAreProposer();
    }

    private void setText(String id, Component value) {
        Text text = findPaneOfTypeByID(id, Text.class);
        if (text != null) {
            text.setText(value);
        }
    }
}
