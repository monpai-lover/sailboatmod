package com.monpai.sailboatmod.client.screen.nation;

import com.monpai.sailboatmod.nation.menu.TradeScreenData;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.TradeScreenActionPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

public class TradeScreen extends Screen {
    private static final int SCREEN_W = 520;
    private static final int SCREEN_H = 360;
    private static final int PANEL_W = 240;
    private static final int PANEL_GAP = 16;
    private static final int SLOT_SIZE = 18;
    private static final int GRID_COLS = 3;
    private static final int GRID_ROWS = 3;
    private static final int MAX_TRADE_ITEMS = 9;
    private static final int TREASURY_COLS = 9;
    private static final int TREASURY_VISIBLE_ROWS = 2;
    private static final int AUTO_REFRESH_TICKS = 40;

    // Colors matching NationHomeScreen
    private static final int COLOR_BG_OUTER = 0xCC101820;
    private static final int COLOR_BG_INNER = 0xCC182632;
    private static final int COLOR_GOLD = 0xFFE7C977;
    private static final int COLOR_TEXT = 0xFFDCEFFF;
    private static final int COLOR_LABEL = 0xFFB8C0C8;
    private static final int COLOR_HINT = 0xFF8D98A3;
    private static final int COLOR_SLOT_BG = 0x66203037;
    private static final int COLOR_SLOT_HOVER = 0x66487EA1;
    private static final int COLOR_PANEL_BORDER = 0xFF2A3A4A;
    private static final int COLOR_DIVIDER = 0xFF3A4A5A;

    private TradeScreenData data;
    private EditBox offerCurrencyInput;
    private EditBox requestCurrencyInput;
    private final List<ItemStack> offerSlots = new ArrayList<>();
    private final List<ItemStack> requestSlots = new ArrayList<>();
    private int ourTreasuryScroll;
    private int targetTreasuryScroll;
    private int refreshTicks;
    private boolean readOnly;

    private Button proposeButton;
    private Button acceptButton;
    private Button rejectButton;
    private Button counterButton;
    private Button cancelButton;
    public TradeScreen(TradeScreenData data) {
        super(Component.translatable("screen.sailboatmod.trade.title"));
        this.data = data;
        initSlots();
    }

    private void initSlots() {
        offerSlots.clear();
        requestSlots.clear();
        for (int i = 0; i < MAX_TRADE_ITEMS; i++) {
            offerSlots.add(ItemStack.EMPTY);
            requestSlots.add(ItemStack.EMPTY);
        }
        if (data.hasExistingProposal()) {
            for (int i = 0; i < Math.min(data.offerItems().size(), MAX_TRADE_ITEMS); i++) {
                offerSlots.set(i, data.offerItems().get(i).copy());
            }
            for (int i = 0; i < Math.min(data.requestItems().size(), MAX_TRADE_ITEMS); i++) {
                requestSlots.set(i, data.requestItems().get(i).copy());
            }
        }
        readOnly = data.hasExistingProposal() && !data.weAreProposer();
    }

    @Override
    protected void init() {
        super.init();
        int left = (this.width - SCREEN_W) / 2;
        int top = (this.height - SCREEN_H) / 2;

        // Currency inputs
        int leftPanelX = left + 12;
        int rightPanelX = left + 12 + PANEL_W + PANEL_GAP;
        int currencyY = top + 72;

        this.offerCurrencyInput = new EditBox(this.font, leftPanelX + 60, currencyY, 100, 16, Component.literal(""));
        this.offerCurrencyInput.setMaxLength(15);
        this.offerCurrencyInput.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        if (data.hasExistingProposal()) {
            this.offerCurrencyInput.setValue(String.valueOf(data.offerCurrency()));
        }
        this.addRenderableWidget(this.offerCurrencyInput);

        this.requestCurrencyInput = new EditBox(this.font, rightPanelX + 60, currencyY, 100, 16, Component.literal(""));
        this.requestCurrencyInput.setMaxLength(15);
        this.requestCurrencyInput.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        if (data.hasExistingProposal()) {
            this.requestCurrencyInput.setValue(String.valueOf(data.requestCurrency()));
        }
        this.addRenderableWidget(this.requestCurrencyInput);

        // Buttons
        int btnY = top + SCREEN_H - 40;
        this.proposeButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.sailboatmod.trade.propose"), b -> sendAction(TradeScreenActionPacket.Action.PROPOSE)
        ).bounds(left + 12, btnY, 90, 20).build());

        this.acceptButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.sailboatmod.trade.accept"), b -> sendAction(TradeScreenActionPacket.Action.ACCEPT)
        ).bounds(left + 108, btnY, 70, 20).build());

        this.rejectButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.sailboatmod.trade.reject"), b -> sendAction(TradeScreenActionPacket.Action.REJECT)
        ).bounds(left + 184, btnY, 70, 20).build());

        this.counterButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.sailboatmod.trade.counter"), b -> sendAction(TradeScreenActionPacket.Action.COUNTER_OFFER)
        ).bounds(left + 260, btnY, 70, 20).build());

        this.cancelButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.sailboatmod.trade.cancel"), b -> sendAction(TradeScreenActionPacket.Action.CANCEL)
        ).bounds(left + 336, btnY, 70, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.sailboatmod.trade.close"), b -> this.onClose()
        ).bounds(left + SCREEN_W - 62, top + 8, 50, 16).build());

        updateButtonState();
    }

    private void updateButtonState() {
        boolean hasProposal = data.hasExistingProposal();
        boolean weProposed = hasProposal && data.weAreProposer();
        boolean theyProposed = hasProposal && !data.weAreProposer();

        proposeButton.visible = !hasProposal;
        proposeButton.active = data.canManageTreasury();
        acceptButton.visible = theyProposed;
        acceptButton.active = data.canManageTreasury();
        rejectButton.visible = theyProposed;
        counterButton.visible = theyProposed;
        counterButton.active = data.canManageTreasury();
        cancelButton.visible = weProposed;

        readOnly = theyProposed;
        if (offerCurrencyInput != null) offerCurrencyInput.setEditable(!readOnly);
        if (requestCurrencyInput != null) requestCurrencyInput.setEditable(!readOnly);
    }

    private void sendAction(TradeScreenActionPacket.Action action) {
        long offerC = parseLong(offerCurrencyInput.getValue());
        long requestC = parseLong(requestCurrencyInput.getValue());
        List<ItemStack> offer = new ArrayList<>();
        List<ItemStack> request = new ArrayList<>();
        for (ItemStack s : offerSlots) { if (!s.isEmpty()) offer.add(s.copy()); }
        for (ItemStack s : requestSlots) { if (!s.isEmpty()) request.add(s.copy()); }
        ModNetwork.CHANNEL.send(PacketDistributor.SERVER.noArg(),
                new TradeScreenActionPacket(action, data.targetNationId(), offerC, requestC, offer, request));
    }

    private static long parseLong(String s) {
        if (s == null || s.isBlank()) return 0L;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0L; }
    }

    public void updateData(TradeScreenData newData) {
        this.data = newData;
        initSlots();
        if (offerCurrencyInput != null && data.hasExistingProposal()) {
            offerCurrencyInput.setValue(String.valueOf(data.offerCurrency()));
            requestCurrencyInput.setValue(String.valueOf(data.requestCurrency()));
        }
        updateButtonState();
    }

    @Override
    public void tick() {
        super.tick();
        if (offerCurrencyInput != null) offerCurrencyInput.tick();
        if (requestCurrencyInput != null) requestCurrencyInput.tick();
        refreshTicks++;
        if (refreshTicks >= AUTO_REFRESH_TICKS) {
            refreshTicks = 0;
            ModNetwork.CHANNEL.send(PacketDistributor.SERVER.noArg(),
                    new TradeScreenActionPacket(TradeScreenActionPacket.Action.REFRESH, data.targetNationId()));
        }
    }
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        int left = (this.width - SCREEN_W) / 2;
        int top = (this.height - SCREEN_H) / 2;

        // Outer background
        g.fill(left, top, left + SCREEN_W, top + SCREEN_H, COLOR_BG_OUTER);
        g.fill(left + 2, top + 2, left + SCREEN_W - 2, top + SCREEN_H - 2, COLOR_BG_INNER);

        // Title bar
        g.fill(left + 2, top + 2, left + SCREEN_W - 2, top + 30, 0xCC0D1520);
        // Nation color badges
        g.fill(left + 10, top + 8, left + 18, top + 22, 0xFF000000 | data.ourPrimaryColor());
        g.drawString(this.font, data.ourNationName(), left + 22, top + 11, COLOR_GOLD, false);
        int midX = left + SCREEN_W / 2;
        g.drawString(this.font, "\u27F7", midX - 4, top + 11, COLOR_HINT, false);
        g.fill(left + SCREEN_W - 200, top + 8, left + SCREEN_W - 192, top + 22, 0xFF000000 | data.targetPrimaryColor());
        g.drawString(this.font, data.targetNationName(), left + SCREEN_W - 190, top + 11, COLOR_GOLD, false);

        // Divider
        g.fill(midX - 1, top + 32, midX + 1, top + SCREEN_H - 48, COLOR_DIVIDER);

        int leftPanelX = left + 12;
        int rightPanelX = left + 12 + PANEL_W + PANEL_GAP;

        // Panel headers
        g.drawString(this.font, Component.translatable("screen.sailboatmod.trade.our_offer"), leftPanelX, top + 38, COLOR_GOLD, false);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.trade.we_request"), rightPanelX, top + 38, COLOR_GOLD, false);

        // Treasury balance labels
        g.drawString(this.font, Component.translatable("screen.sailboatmod.trade.balance", data.ourTreasuryBalance()), leftPanelX, top + 54, COLOR_LABEL, false);
        if (!data.targetTreasuryItems().isEmpty()) {
            g.drawString(this.font, Component.translatable("screen.sailboatmod.trade.balance", data.targetTreasuryBalance()), rightPanelX, top + 54, COLOR_LABEL, false);
        }

        // Currency labels
        g.drawString(this.font, Component.translatable("screen.sailboatmod.trade.currency"), leftPanelX, top + 75, COLOR_TEXT, false);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.trade.currency"), rightPanelX, top + 75, COLOR_TEXT, false);

        // Item grid
        int gridY = top + 96;
        drawItemGrid(g, leftPanelX, gridY, offerSlots, mouseX, mouseY);
        drawItemGrid(g, rightPanelX, gridY, requestSlots, mouseX, mouseY);

        // Treasury preview labels
        int treasuryY = gridY + GRID_ROWS * SLOT_SIZE + 12;
        g.drawString(this.font, Component.translatable("screen.sailboatmod.trade.treasury_preview"), leftPanelX, treasuryY - 10, COLOR_HINT, false);
        drawTreasuryPreview(g, leftPanelX, treasuryY, data.ourTreasuryItems(), ourTreasuryScroll, mouseX, mouseY);

        if (!data.targetTreasuryItems().isEmpty()) {
            g.drawString(this.font, Component.translatable("screen.sailboatmod.trade.target_treasury"), rightPanelX, treasuryY - 10, COLOR_HINT, false);
            drawTreasuryPreview(g, rightPanelX, treasuryY, data.targetTreasuryItems(), targetTreasuryScroll, mouseX, mouseY);
        } else {
            g.drawString(this.font, Component.translatable("screen.sailboatmod.trade.treasury_hidden"), rightPanelX, treasuryY, COLOR_HINT, false);
        }

        // Status line
        int statusY = top + SCREEN_H - 18;
        if (data.hasExistingProposal()) {
            int min = data.proposalRemainingSeconds() / 60;
            int sec = data.proposalRemainingSeconds() % 60;
            String timeStr = String.format("%d:%02d", min, sec);
            Component status = data.weAreProposer()
                    ? Component.translatable("screen.sailboatmod.trade.status.waiting", timeStr)
                    : Component.translatable("screen.sailboatmod.trade.status.incoming", timeStr);
            g.drawString(this.font, status, left + 12, statusY, COLOR_LABEL, false);
        } else {
            g.drawString(this.font, Component.translatable("screen.sailboatmod.trade.status.none"), left + 12, statusY, COLOR_HINT, false);
        }

        super.render(g, mouseX, mouseY, partialTick);

        // Tooltips (render after widgets)
        renderItemTooltips(g, leftPanelX, gridY, offerSlots, mouseX, mouseY);
        renderItemTooltips(g, rightPanelX, gridY, requestSlots, mouseX, mouseY);
        renderTreasuryTooltips(g, leftPanelX, gridY + GRID_ROWS * SLOT_SIZE + 14, data.ourTreasuryItems(), ourTreasuryScroll, mouseX, mouseY);
        if (!data.targetTreasuryItems().isEmpty()) {
            renderTreasuryTooltips(g, rightPanelX, gridY + GRID_ROWS * SLOT_SIZE + 14, data.targetTreasuryItems(), targetTreasuryScroll, mouseX, mouseY);
        }
    }

    private void drawItemGrid(GuiGraphics g, int x, int y, List<ItemStack> slots, int mouseX, int mouseY) {
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int idx = row * GRID_COLS + col;
                int sx = x + col * SLOT_SIZE;
                int sy = y + row * SLOT_SIZE;
                boolean hovered = mouseX >= sx && mouseX < sx + SLOT_SIZE && mouseY >= sy && mouseY < sy + SLOT_SIZE;
                g.fill(sx, sy, sx + SLOT_SIZE, sy + SLOT_SIZE, hovered ? COLOR_SLOT_HOVER : COLOR_SLOT_BG);
                // Border
                g.fill(sx, sy, sx + SLOT_SIZE, sy + 1, COLOR_PANEL_BORDER);
                g.fill(sx, sy + SLOT_SIZE - 1, sx + SLOT_SIZE, sy + SLOT_SIZE, COLOR_PANEL_BORDER);
                g.fill(sx, sy, sx + 1, sy + SLOT_SIZE, COLOR_PANEL_BORDER);
                g.fill(sx + SLOT_SIZE - 1, sy, sx + SLOT_SIZE, sy + SLOT_SIZE, COLOR_PANEL_BORDER);
                if (idx < slots.size() && !slots.get(idx).isEmpty()) {
                    g.renderItem(slots.get(idx), sx + 1, sy + 1);
                    g.renderItemDecorations(this.font, slots.get(idx), sx + 1, sy + 1);
                }
            }
        }
    }
    private void drawTreasuryPreview(GuiGraphics g, int x, int y, List<ItemStack> items, int scroll, int mouseX, int mouseY) {
        int startSlot = scroll * TREASURY_COLS;
        for (int row = 0; row < TREASURY_VISIBLE_ROWS; row++) {
            for (int col = 0; col < TREASURY_COLS; col++) {
                int idx = startSlot + row * TREASURY_COLS + col;
                int sx = x + col * SLOT_SIZE;
                int sy = y + row * SLOT_SIZE;
                boolean hovered = mouseX >= sx && mouseX < sx + SLOT_SIZE && mouseY >= sy && mouseY < sy + SLOT_SIZE;
                g.fill(sx, sy, sx + SLOT_SIZE, sy + SLOT_SIZE, hovered ? COLOR_SLOT_HOVER : COLOR_SLOT_BG);
                g.fill(sx, sy, sx + SLOT_SIZE, sy + 1, COLOR_PANEL_BORDER);
                g.fill(sx, sy + SLOT_SIZE - 1, sx + SLOT_SIZE, sy + SLOT_SIZE, COLOR_PANEL_BORDER);
                g.fill(sx, sy, sx + 1, sy + SLOT_SIZE, COLOR_PANEL_BORDER);
                g.fill(sx + SLOT_SIZE - 1, sy, sx + SLOT_SIZE, sy + SLOT_SIZE, COLOR_PANEL_BORDER);
                if (idx >= 0 && idx < items.size() && !items.get(idx).isEmpty()) {
                    // Dim items already in trade slots
                    boolean inTrade = isItemInTradeSlots(items.get(idx));
                    if (inTrade) {
                        g.fill(sx + 1, sy + 1, sx + SLOT_SIZE - 1, sy + SLOT_SIZE - 1, 0x88000000);
                    }
                    g.renderItem(items.get(idx), sx + 1, sy + 1);
                    g.renderItemDecorations(this.font, items.get(idx), sx + 1, sy + 1);
                }
            }
        }
    }

    private boolean isItemInTradeSlots(ItemStack stack) {
        for (ItemStack s : offerSlots) {
            if (!s.isEmpty() && ItemStack.isSameItemSameTags(s, stack)) return true;
        }
        for (ItemStack s : requestSlots) {
            if (!s.isEmpty() && ItemStack.isSameItemSameTags(s, stack)) return true;
        }
        return false;
    }

    private void renderItemTooltips(GuiGraphics g, int x, int y, List<ItemStack> slots, int mouseX, int mouseY) {
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int idx = row * GRID_COLS + col;
                int sx = x + col * SLOT_SIZE;
                int sy = y + row * SLOT_SIZE;
                if (mouseX >= sx && mouseX < sx + SLOT_SIZE && mouseY >= sy && mouseY < sy + SLOT_SIZE) {
                    if (idx < slots.size() && !slots.get(idx).isEmpty()) {
                        g.renderTooltip(this.font, slots.get(idx), mouseX, mouseY);
                    }
                }
            }
        }
    }

    private void renderTreasuryTooltips(GuiGraphics g, int x, int y, List<ItemStack> items, int scroll, int mouseX, int mouseY) {
        int startSlot = scroll * TREASURY_COLS;
        for (int row = 0; row < TREASURY_VISIBLE_ROWS; row++) {
            for (int col = 0; col < TREASURY_COLS; col++) {
                int idx = startSlot + row * TREASURY_COLS + col;
                int sx = x + col * SLOT_SIZE;
                int sy = y + row * SLOT_SIZE;
                if (mouseX >= sx && mouseX < sx + SLOT_SIZE && mouseY >= sy && mouseY < sy + SLOT_SIZE) {
                    if (idx >= 0 && idx < items.size() && !items.get(idx).isEmpty()) {
                        g.renderTooltip(this.font, items.get(idx), mouseX, mouseY);
                    }
                }
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || readOnly) return super.mouseClicked(mouseX, mouseY, button);

        int left = (this.width - SCREEN_W) / 2;
        int top = (this.height - SCREEN_H) / 2;
        int leftPanelX = left + 12;
        int rightPanelX = left + 12 + PANEL_W + PANEL_GAP;
        int gridY = top + 96;
        int treasuryY = gridY + GRID_ROWS * SLOT_SIZE + 14;

        // Click on offer grid slot -> remove item
        int offerIdx = getGridSlotAt(leftPanelX, gridY, mouseX, mouseY);
        if (offerIdx >= 0 && offerIdx < offerSlots.size() && !offerSlots.get(offerIdx).isEmpty()) {
            offerSlots.set(offerIdx, ItemStack.EMPTY);
            return true;
        }

        // Click on request grid slot -> remove item
        int requestIdx = getGridSlotAt(rightPanelX, gridY, mouseX, mouseY);
        if (requestIdx >= 0 && requestIdx < requestSlots.size() && !requestSlots.get(requestIdx).isEmpty()) {
            requestSlots.set(requestIdx, ItemStack.EMPTY);
            return true;
        }

        // Click on our treasury -> add to offer slots
        int ourTreasuryIdx = getTreasurySlotAt(leftPanelX, treasuryY, ourTreasuryScroll, mouseX, mouseY);
        if (ourTreasuryIdx >= 0 && ourTreasuryIdx < data.ourTreasuryItems().size()) {
            ItemStack clicked = data.ourTreasuryItems().get(ourTreasuryIdx);
            if (!clicked.isEmpty()) {
                addToFirstEmpty(offerSlots, clicked.copy());
                return true;
            }
        }

        // Click on target treasury -> add to request slots
        if (!data.targetTreasuryItems().isEmpty()) {
            int targetTreasuryIdx = getTreasurySlotAt(rightPanelX, treasuryY, targetTreasuryScroll, mouseX, mouseY);
            if (targetTreasuryIdx >= 0 && targetTreasuryIdx < data.targetTreasuryItems().size()) {
                ItemStack clicked = data.targetTreasuryItems().get(targetTreasuryIdx);
                if (!clicked.isEmpty()) {
                    addToFirstEmpty(requestSlots, clicked.copy());
                    return true;
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private int getGridSlotAt(int gridX, int gridY, double mouseX, double mouseY) {
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int sx = gridX + col * SLOT_SIZE;
                int sy = gridY + row * SLOT_SIZE;
                if (mouseX >= sx && mouseX < sx + SLOT_SIZE && mouseY >= sy && mouseY < sy + SLOT_SIZE) {
                    return row * GRID_COLS + col;
                }
            }
        }
        return -1;
    }

    private int getTreasurySlotAt(int x, int y, int scroll, double mouseX, double mouseY) {
        int startSlot = scroll * TREASURY_COLS;
        for (int row = 0; row < TREASURY_VISIBLE_ROWS; row++) {
            for (int col = 0; col < TREASURY_COLS; col++) {
                int sx = x + col * SLOT_SIZE;
                int sy = y + row * SLOT_SIZE;
                if (mouseX >= sx && mouseX < sx + SLOT_SIZE && mouseY >= sy && mouseY < sy + SLOT_SIZE) {
                    return startSlot + row * TREASURY_COLS + col;
                }
            }
        }
        return -1;
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
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int left = (this.width - SCREEN_W) / 2;
        int top = (this.height - SCREEN_H) / 2;
        int leftPanelX = left + 12;
        int rightPanelX = left + 12 + PANEL_W + PANEL_GAP;
        int gridY = top + 96;
        int treasuryY = gridY + GRID_ROWS * SLOT_SIZE + 14;
        int treasuryH = TREASURY_VISIBLE_ROWS * SLOT_SIZE;
        int treasuryW = TREASURY_COLS * SLOT_SIZE;

        // Our treasury scroll
        if (mouseX >= leftPanelX && mouseX < leftPanelX + treasuryW && mouseY >= treasuryY && mouseY < treasuryY + treasuryH) {
            int maxScroll = Math.max(0, (data.ourTreasuryItems().size() + TREASURY_COLS - 1) / TREASURY_COLS - TREASURY_VISIBLE_ROWS);
            ourTreasuryScroll = Math.max(0, Math.min(maxScroll, ourTreasuryScroll + (delta > 0 ? -1 : 1)));
            return true;
        }

        // Target treasury scroll
        if (mouseX >= rightPanelX && mouseX < rightPanelX + treasuryW && mouseY >= treasuryY && mouseY < treasuryY + treasuryH) {
            int maxScroll = Math.max(0, (data.targetTreasuryItems().size() + TREASURY_COLS - 1) / TREASURY_COLS - TREASURY_VISIBLE_ROWS);
            targetTreasuryScroll = Math.max(0, Math.min(maxScroll, targetTreasuryScroll + (delta > 0 ? -1 : 1)));
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
