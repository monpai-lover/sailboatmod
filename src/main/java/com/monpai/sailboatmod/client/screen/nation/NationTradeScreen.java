package com.monpai.sailboatmod.client.screen.nation;

import com.monpai.sailboatmod.client.TradeClientHooks;
import com.monpai.sailboatmod.client.gui.TradeWindowStatePolicy;
import com.monpai.sailboatmod.nation.menu.TradeScreenData;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.TradeScreenActionPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class NationTradeScreen extends Screen {
    private static final int SCREEN_W = 440;
    private static final int SCREEN_H = 300;
    private static final int MAX_TRADE_ITEMS = TradeScreenData.MAX_TRADE_ITEMS;
    private static final int AUTO_REFRESH_TICKS = 40;
    private static final int SLOT_SIZE = 18;
    private static final int HALF_W = SCREEN_W / 2;

    private TradeScreenData data;
    private final List<ItemStack> offerSlots = new ArrayList<>(MAX_TRADE_ITEMS);
    private final List<ItemStack> requestSlots = new ArrayList<>(MAX_TRADE_ITEMS);
    private EditBox offerCurrencyInput;
    private EditBox requestCurrencyInput;
    private Button proposeButton;
    private Button acceptButton;
    private Button rejectButton;
    private Button counterButton;
    private Button cancelButton;
    private int refreshTicks;

    public NationTradeScreen(TradeScreenData data) {
        super(Component.translatable("screen.sailboatmod.trade.title"));
        this.data = data == null ? TradeScreenData.empty() : data;
        loadDraftFromData();
    }

    public void updateData(TradeScreenData newData) {
        TradeScreenData next = newData == null ? TradeScreenData.empty() : newData;
        boolean replaceDraft = TradeWindowStatePolicy.shouldReplaceDraft(
                data.targetNationId(), data.hasExistingProposal(), data.proposalId(),
                next.targetNationId(), next.hasExistingProposal(), next.proposalId()
        );
        this.data = next;
        if (replaceDraft) {
            loadDraftFromData();
        }
        refreshButtons();
    }

    @Override
    protected void init() {
        super.init();
        int left = left();
        int top = top();

        offerCurrencyInput = new EditBox(this.font, left + 12, top + 130, 80, 16,
                Component.translatable("screen.sailboatmod.trade.currency"));
        offerCurrencyInput.setMaxLength(15);
        offerCurrencyInput.setFilter(text -> text.isEmpty() || text.equals(TradeWindowStatePolicy.filterCurrencyText(text)));
        addRenderableWidget(offerCurrencyInput);

        requestCurrencyInput = new EditBox(this.font, left + SCREEN_W / 2 + 12, top + 130, 80, 16,
                Component.translatable("screen.sailboatmod.trade.currency"));
        requestCurrencyInput.setMaxLength(15);
        requestCurrencyInput.setFilter(text -> text.isEmpty() || text.equals(TradeWindowStatePolicy.filterCurrencyText(text)));
        addRenderableWidget(requestCurrencyInput);

        loadCurrencyInputsFromData();

        int btnY = top + SCREEN_H - 32;
        int btnW = 58;
        int btnH = 20;
        int btnGap = 4;
        int btnStartX = left + 12;

        proposeButton = Button.builder(Component.translatable("screen.sailboatmod.trade.propose"),
                b -> sendAction(TradeScreenActionPacket.Action.PROPOSE)).bounds(btnStartX, btnY, btnW, btnH).build();
        addRenderableWidget(proposeButton);

        acceptButton = Button.builder(Component.translatable("screen.sailboatmod.trade.accept"),
                b -> sendAction(TradeScreenActionPacket.Action.ACCEPT)).bounds(btnStartX + (btnW + btnGap), btnY, btnW, btnH).build();
        addRenderableWidget(acceptButton);

        rejectButton = Button.builder(Component.translatable("screen.sailboatmod.trade.reject"),
                b -> sendAction(TradeScreenActionPacket.Action.REJECT)).bounds(btnStartX + 2 * (btnW + btnGap), btnY, btnW, btnH).build();
        addRenderableWidget(rejectButton);

        counterButton = Button.builder(Component.translatable("screen.sailboatmod.trade.counter"),
                b -> sendAction(TradeScreenActionPacket.Action.COUNTER_OFFER)).bounds(btnStartX + 3 * (btnW + btnGap), btnY, btnW, btnH).build();
        addRenderableWidget(counterButton);

        cancelButton = Button.builder(Component.translatable("screen.sailboatmod.trade.cancel"),
                b -> sendAction(TradeScreenActionPacket.Action.CANCEL)).bounds(btnStartX + 4 * (btnW + btnGap), btnY, btnW, btnH).build();
        addRenderableWidget(cancelButton);

        refreshButtons();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        drawContents(guiGraphics, mouseX, mouseY);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        refreshTicks++;
        if (refreshTicks >= AUTO_REFRESH_TICKS) {
            refreshTicks = 0;
            ModNetwork.CHANNEL.sendToServer(
                    new TradeScreenActionPacket(TradeScreenActionPacket.Action.REFRESH, data.targetNationId()));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && !readOnly()) {
            int left = left();
            int top = top();
            // Click on our treasury → add to offer slots
            if (handleTreasuryClick(mouseX, mouseY, left + 12, top + 168, data.ourTreasuryItems(), offerSlots)) {
                return true;
            }
            // Click on target treasury → add to request slots
            if (!data.targetTreasuryItems().isEmpty() &&
                    handleTreasuryClick(mouseX, mouseY, left + HALF_W + 12, top + 168, data.targetTreasuryItems(), requestSlots)) {
                return true;
            }
            // Click on offer trade slot → remove item
            if (handleTradeSlotClick(mouseX, mouseY, left + 12, top + 74, offerSlots)) {
                return true;
            }
            // Click on request trade slot → remove item
            if (handleTradeSlotClick(mouseX, mouseY, left + HALF_W + 12, top + 74, requestSlots)) {
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean handleTreasuryClick(double mouseX, double mouseY, int baseX, int baseY,
                                         List<ItemStack> treasuryItems, List<ItemStack> targetSlots) {
        if (treasuryItems == null || treasuryItems.isEmpty()) return false;
        int cols = 9;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < cols; col++) {
                int idx = row * cols + col;
                if (idx >= treasuryItems.size()) return false;
                int slotX = baseX + col * (SLOT_SIZE + 1);
                int slotY = baseY + row * (SLOT_SIZE + 1);
                if (mouseX >= slotX && mouseX < slotX + SLOT_SIZE && mouseY >= slotY && mouseY < slotY + SLOT_SIZE) {
                    ItemStack item = treasuryItems.get(idx);
                    if (item != null && !item.isEmpty()) {
                        addToFirstEmpty(targetSlots, item.copy());
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean handleTradeSlotClick(double mouseX, double mouseY, int baseX, int baseY, List<ItemStack> slots) {
        for (int i = 0; i < Math.min(slots.size(), MAX_TRADE_ITEMS); i++) {
            int slotX = baseX + i * (SLOT_SIZE + 2);
            if (mouseX >= slotX && mouseX < slotX + SLOT_SIZE && mouseY >= baseY && mouseY < baseY + SLOT_SIZE) {
                if (!slots.get(i).isEmpty()) {
                    slots.set(i, ItemStack.EMPTY);
                    return true;
                }
            }
        }
        return false;
    }

    private void addToFirstEmpty(List<ItemStack> slots, ItemStack item) {
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).isEmpty()) {
                slots.set(i, item);
                return;
            }
        }
    }

    @Override
    public void onClose() {
        TradeClientHooks.onScreenClosed();
        super.onClose();
    }
    private void drawContents(GuiGraphics g, int mouseX, int mouseY) {
        int left = left();
        int top = top();

        // Background panel
        g.fill(left, top, left + SCREEN_W, top + SCREEN_H, 0xCC101820);
        g.fill(left + 1, top + 1, left + SCREEN_W - 1, top + SCREEN_H - 1, 0xCC182632);

        // Title
        g.drawString(this.font, this.title, left + 12, top + 8, 0xFFE7C977);

        // Nation headers
        int halfW = SCREEN_W / 2;
        drawNationHeader(g, left + 12, top + 24, data.ourNationName(), data.ourPrimaryColor(),
                Component.translatable("screen.sailboatmod.trade.balance", data.ourTreasuryBalance()));
        drawNationHeader(g, left + halfW + 12, top + 24, data.targetNationName(), data.targetPrimaryColor(),
                Component.translatable("screen.sailboatmod.trade.balance", data.targetTreasuryBalance()));

        // Section labels
        g.drawString(this.font, Component.translatable("screen.sailboatmod.trade.our_offer"),
                left + 12, top + 60, 0xFFDCEEFF);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.trade.we_request"),
                left + halfW + 12, top + 60, 0xFFDCEEFF);

        // Currency labels
        g.drawString(this.font, Component.translatable("screen.sailboatmod.trade.currency"),
                left + 12, top + 120, 0xFFB8C0C8);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.trade.currency"),
                left + halfW + 12, top + 120, 0xFFB8C0C8);

        // Trade item slots
        drawTradeSlots(g, left + 12, top + 74, offerSlots);
        drawTradeSlots(g, left + halfW + 12, top + 74, requestSlots);

        // Treasury preview labels
        g.drawString(this.font, Component.translatable("screen.sailboatmod.trade.treasury_preview"),
                left + 12, top + 155, 0xFFB8C0C8);
        if (!data.targetTreasuryItems().isEmpty()) {
            g.drawString(this.font, Component.translatable("screen.sailboatmod.trade.target_treasury"),
                    left + halfW + 12, top + 155, 0xFFB8C0C8);
        } else {
            g.drawString(this.font, Component.translatable("screen.sailboatmod.trade.treasury_hidden"),
                    left + halfW + 12, top + 155, 0xFF8D98A3);
        }

        // Treasury items
        drawTreasuryItems(g, left + 12, top + 168, data.ourTreasuryItems());
        if (!data.targetTreasuryItems().isEmpty()) {
            drawTreasuryItems(g, left + halfW + 12, top + 168, data.targetTreasuryItems());
        }

        // Status text
        drawStatusText(g, left + 12, top + SCREEN_H - 50);
    }
    private void drawNationHeader(GuiGraphics g, int x, int y, String name, int color, Component balance) {
        // Color swatch
        int rgb = color & 0xFFFFFF;
        g.fill(x, y, x + 10, y + 10, 0xFF000000 | rgb);
        // Nation name
        g.drawString(this.font, name, x + 14, y + 1, 0xFFFFFFFF);
        // Balance
        g.drawString(this.font, balance, x, y + 14, 0xFFB8C0C8);
    }

    private void drawTradeSlots(GuiGraphics g, int x, int y, List<ItemStack> slots) {
        int displayCount = Math.min(slots.size(), MAX_TRADE_ITEMS);
        for (int i = 0; i < displayCount; i++) {
            int slotX = x + i * (SLOT_SIZE + 2);
            // Slot background
            g.fill(slotX, y, slotX + SLOT_SIZE, y + SLOT_SIZE, 0x66203037);
            g.fill(slotX + 1, y + 1, slotX + SLOT_SIZE - 1, y + SLOT_SIZE - 1, 0x66131C23);
            ItemStack item = slots.get(i);
            if (item != null && !item.isEmpty()) {
                g.renderItem(item, slotX + 1, y + 1);
            }
        }
    }

    private void drawTreasuryItems(GuiGraphics g, int x, int y, List<ItemStack> items) {
        if (items == null || items.isEmpty()) return;
        int cols = 9;
        int rows = Math.min(3, (items.size() + cols - 1) / cols);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int idx = row * cols + col;
                if (idx >= items.size()) return;
                int slotX = x + col * (SLOT_SIZE + 1);
                int slotY = y + row * (SLOT_SIZE + 1);
                g.fill(slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE, 0x44203037);
                ItemStack item = items.get(idx);
                if (item != null && !item.isEmpty()) {
                    g.renderItem(item, slotX + 1, slotY + 1);
                }
            }
        }
    }

    private void drawStatusText(GuiGraphics g, int x, int y) {
        Component status;
        if (data.hasExistingProposal()) {
            int min = data.proposalRemainingSeconds() / 60;
            int sec = data.proposalRemainingSeconds() % 60;
            String time = String.format("%d:%02d", min, sec);
            status = data.weAreProposer()
                    ? Component.translatable("screen.sailboatmod.trade.status.waiting", time)
                    : Component.translatable("screen.sailboatmod.trade.status.incoming", time);
        } else {
            status = Component.translatable("screen.sailboatmod.trade.status.none");
        }
        g.drawString(this.font, status, x, y, 0xFFB8C0C8);
    }
    private void refreshButtons() {
        if (proposeButton == null) return;
        boolean hasProposal = data.hasExistingProposal();
        boolean weProposed = hasProposal && data.weAreProposer();
        boolean theyProposed = hasProposal && !data.weAreProposer();

        proposeButton.visible = !hasProposal;
        proposeButton.active = !hasProposal && data.canManageTreasury();

        acceptButton.visible = theyProposed;
        acceptButton.active = theyProposed && data.canManageTreasury();

        rejectButton.visible = theyProposed;
        rejectButton.active = theyProposed;

        counterButton.visible = theyProposed;
        counterButton.active = theyProposed && data.canManageTreasury();

        cancelButton.visible = weProposed;
        cancelButton.active = weProposed;

        boolean readOnly = readOnly();
        if (offerCurrencyInput != null) offerCurrencyInput.setEditable(!readOnly);
        if (requestCurrencyInput != null) requestCurrencyInput.setEditable(!readOnly);
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

    private void loadCurrencyInputsFromData() {
        if (data.hasExistingProposal()) {
            offerCurrencyInput.setValue(String.valueOf(data.offerCurrency()));
            requestCurrencyInput.setValue(String.valueOf(data.requestCurrency()));
        } else {
            offerCurrencyInput.setValue("");
            requestCurrencyInput.setValue("");
        }
    }

    private void copyItemsIntoSlots(List<ItemStack> source, List<ItemStack> target) {
        for (int i = 0; i < Math.min(source.size(), MAX_TRADE_ITEMS); i++) {
            ItemStack item = source.get(i);
            target.set(i, item == null ? ItemStack.EMPTY : item.copy());
        }
    }

    private void sendAction(TradeScreenActionPacket.Action action) {
        ModNetwork.CHANNEL.sendToServer(
                new TradeScreenActionPacket(
                        action,
                        data.targetNationId(),
                        TradeWindowStatePolicy.parseCurrency(offerCurrencyInput == null ? "" : offerCurrencyInput.getValue()),
                        TradeWindowStatePolicy.parseCurrency(requestCurrencyInput == null ? "" : requestCurrencyInput.getValue()),
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

    private boolean readOnly() {
        return data.hasExistingProposal() && !data.weAreProposer();
    }

    private int left() {
        return (this.width - SCREEN_W) / 2;
    }

    private int top() {
        return (this.height - SCREEN_H) / 2;
    }
}
