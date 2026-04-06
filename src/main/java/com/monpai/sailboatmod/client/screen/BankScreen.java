package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.economy.GoldStandardEconomy;
import com.monpai.sailboatmod.menu.BankMenu;
import com.monpai.sailboatmod.nation.model.LoanAccountView;
import com.monpai.sailboatmod.nation.model.NationTreasuryRecord;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.BankActionPacket;
import com.monpai.sailboatmod.network.packet.SyncTreasuryPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class BankScreen extends AbstractContainerScreen<BankMenu> {
    private static final int SCREEN_W = 340;
    private static final int SCREEN_H = 272;
    private static final int ITEM_ROW_H = 20;
    private static final int ITEM_VISIBLE_ROWS = 4;
    private static final int ITEM_LIST_X = 14;
    private static final int ITEM_LIST_Y = 78;
    private static final int ITEM_LIST_W = SCREEN_W - 28;
    private static final int CONTENT_TOP = 54;
    private static final int CONTENT_BOTTOM = 174;
    private static final int INVENTORY_TOP = BankMenu.STORAGE_INV_Y;
    private static final int INVENTORY_BOTTOM = BankMenu.STORAGE_HOTBAR_Y + 18;
    private static final int STATUS_Y = SCREEN_H - 44;
    private static final int STORAGE_STATUS_Y = 170;

    private final List<Button> tabButtons = new ArrayList<>();
    private final List<Button> treasuryButtons = new ArrayList<>();
    private final List<Button> storageButtons = new ArrayList<>();
    private final List<Button> loanButtons = new ArrayList<>();
    private final List<Button> loanSubtabButtons = new ArrayList<>();
    private EditBox amountInput;
    private EditBox loanAmountInput;
    private Button closeButton;
    private Component statusLine = Component.empty();
    private int itemScroll;
    private int selectedItemSlot = -1;
    private BankTab activeTab = BankTab.TREASURY;
    private LoanTab activeLoanTab = LoanTab.PERSONAL;

    public BankScreen(BankMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = SCREEN_W;
        this.imageHeight = SCREEN_H;
        this.inventoryLabelY = INVENTORY_TOP - 12;
        this.titleLabelX = 14;
        this.titleLabelY = 10;
    }

    @Override
    protected void init() {
        super.init();
        this.tabButtons.clear();
        this.treasuryButtons.clear();
        this.storageButtons.clear();
        this.loanButtons.clear();
        this.loanSubtabButtons.clear();

        int left = this.leftPos;
        int top = this.topPos;

        this.amountInput = this.addRenderableWidget(new EditBox(this.font, left + 14, top + 78, 136, 18, Component.translatable("screen.sailboatmod.bank.amount")));
        this.amountInput.setMaxLength(16);

        this.loanAmountInput = this.addRenderableWidget(new EditBox(this.font, left + 14, top + 130, 136, 18, Component.translatable("screen.sailboatmod.bank.amount")));
        this.loanAmountInput.setMaxLength(16);

        int tabY = top + 30;
        this.tabButtons.add(this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.bank.mode_treasury"), b -> switchTab(BankTab.TREASURY)).bounds(left + 14, tabY, 96, 18).build()));
        this.tabButtons.add(this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.bank.mode_storage"), b -> switchTab(BankTab.STORAGE)).bounds(left + 122, tabY, 96, 18).build()));
        this.tabButtons.add(this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.bank.mode_loans"), b -> switchTab(BankTab.LOANS)).bounds(left + 230, tabY, 96, 18).build()));

        this.treasuryButtons.add(this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.bank.deposit"), b -> doDeposit())
                .bounds(left + 156, top + 78, 78, 18).build()));
        this.treasuryButtons.add(this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.bank.withdraw"), b -> doWithdraw())
                .bounds(left + 242, top + 78, 78, 18).build()));
        this.treasuryButtons.add(this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.bank.withdraw_as_gold"), b -> doWithdrawAsGold())
                .bounds(left + 156, top + 104, 164, 18).build()));

        this.storageButtons.add(this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.bank.withdraw_item"), b -> doWithdrawItem())
                .bounds(left + 200, top + 168, 120, 18).build()));
        this.storageButtons.add(this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.bank.storage_refresh"), b -> refreshStatus())
                .bounds(left + 200, top + 192, 120, 18).build()));

        this.loanSubtabButtons.add(this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.bank.loan_personal"), b -> activeLoanTab = LoanTab.PERSONAL)
                .bounds(left + 14, top + 76, 96, 18).build()));
        this.loanSubtabButtons.add(this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.bank.loan_nation"), b -> activeLoanTab = LoanTab.NATION)
                .bounds(left + 118, top + 76, 96, 18).build()));

        this.loanButtons.add(this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.bank.loan_borrow"), b -> doLoanBorrow())
                .bounds(left + 156, top + 130, 78, 18).build()));
        this.loanButtons.add(this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.bank.loan_repay"), b -> doLoanRepay())
                .bounds(left + 242, top + 130, 78, 18).build()));

        this.closeButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.route_name.cancel"), b -> onClose())
                .bounds(left + SCREEN_W - 74, top + SCREEN_H - 30, 60, 18).build());

        updateWidgetVisibility();
    }

    @Override
    public void containerTick() {
        super.containerTick();
        if (this.amountInput != null) {
            this.amountInput.tick();
        }
        if (this.loanAmountInput != null) {
            this.loanAmountInput.tick();
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderTreasuryItemTooltip(guiGraphics, mouseX, mouseY);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int left = this.leftPos;
        int top = this.topPos;
        g.fill(left, top, left + SCREEN_W, top + SCREEN_H, 0xCC101820);
        g.fill(left + 1, top + 1, left + SCREEN_W - 1, top + SCREEN_H - 1, 0xCC182632);

        g.fill(left + 12, top + CONTENT_TOP, left + SCREEN_W - 12, top + CONTENT_BOTTOM, 0x40202F3B);
        if (activeTab == BankTab.STORAGE) {
            g.fill(left + 12, top + INVENTORY_TOP, left + SCREEN_W - 12, top + INVENTORY_BOTTOM + 12, 0x34263341);
        }

        for (Button button : tabButtons) {
            if (isActiveTab(button)) {
                g.fill(button.getX() - 1, button.getY() - 1, button.getX() + button.getWidth() + 1, button.getY() + button.getHeight() + 1, 0x55E7C977);
            }
        }
        if (activeTab == BankTab.LOANS) {
            for (Button button : loanSubtabButtons) {
                if ((button == loanSubtabButtons.get(0) && activeLoanTab == LoanTab.PERSONAL)
                        || (button == loanSubtabButtons.get(1) && activeLoanTab == LoanTab.NATION)) {
                    g.fill(button.getX() - 1, button.getY() - 1, button.getX() + button.getWidth() + 1, button.getY() + button.getHeight() + 1, 0x4487D7C3);
                }
            }
        }
        if (activeTab == BankTab.STORAGE) {
            int listBottom = ITEM_LIST_Y + ITEM_VISIBLE_ROWS * ITEM_ROW_H;
            g.fill(left + ITEM_LIST_X, top + ITEM_LIST_Y, left + ITEM_LIST_X + ITEM_LIST_W, top + listBottom, 0x42000000);
            for (int row = 0; row < ITEM_VISIBLE_ROWS; row++) {
                int rowTop = top + ITEM_LIST_Y + row * ITEM_ROW_H;
                g.fill(left + ITEM_LIST_X + 1, rowTop + 1, left + ITEM_LIST_X + ITEM_LIST_W - 1, rowTop + ITEM_ROW_H - 1, 0x182A3946);
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, this.title, 14, 10, 0xFFE7C977);
        NationTreasuryRecord treasury = getTreasury();

        if (activeTab == BankTab.TREASURY) {
            String balanceText = treasury == null ? "0" : GoldStandardEconomy.formatBalance(treasury.currencyBalance());
            g.drawString(this.font, Component.translatable("screen.sailboatmod.bank.currency_label"), 14, 58, 0xFFB8C0C8);
            g.drawString(this.font, balanceText, 120, 58, 0xFFE7C977);
            g.drawString(this.font, Component.translatable("screen.sailboatmod.bank.amount_label"), 14, 66, 0xFFDCEEFF);
            drawWrapped(g, Component.translatable("screen.sailboatmod.bank.treasury_help"), 14, 132, 218, 0xFFB8C0C8);
        } else if (activeTab == BankTab.STORAGE) {
            g.drawString(this.font, Component.translatable("screen.sailboatmod.bank.items_title"), ITEM_LIST_X, 62, 0xFFDCEEFF);
            drawWrapped(g, Component.translatable("screen.sailboatmod.bank.storage_shift_hint"), 14, 58, SCREEN_W - 28, 0xFFB8C0C8);

            if (treasury != null) {
                int startIdx = Math.max(0, itemScroll);
                int drawn = 0;
                for (int i = 0; i < treasury.items().size() && drawn < ITEM_VISIBLE_ROWS; i++) {
                    ItemStack stack = treasury.items().get(i);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    if (startIdx > 0) {
                        startIdx--;
                        continue;
                    }
                    int rowY = ITEM_LIST_Y + drawn * ITEM_ROW_H + 3;
                    if (i == this.selectedItemSlot) {
                        g.fill(ITEM_LIST_X + 1, rowY - 1, ITEM_LIST_X + ITEM_LIST_W - 1, rowY + ITEM_ROW_H - 2, 0x44FFFFFF);
                    }
                    g.renderItem(stack, ITEM_LIST_X + 4, rowY);
                    String name = trimName(stack.getHoverName().getString(), 22);
                    g.drawString(this.font, name, ITEM_LIST_X + 26, rowY + 5, 0xFFDCEEFF);
                    g.drawString(this.font, "x" + stack.getCount(), ITEM_LIST_X + ITEM_LIST_W - 42, rowY + 5, 0xFFE7C977);
                    drawn++;
                }
            }
            g.drawString(this.font, this.playerInventoryTitle, 14, this.inventoryLabelY, 0xFFB8C0C8);
        } else {
            String balanceText = treasury == null ? "0" : GoldStandardEconomy.formatBalance(treasury.currencyBalance());
            g.drawString(this.font, Component.translatable("screen.sailboatmod.bank.currency_label"), 14, 58, 0xFFB8C0C8);
            g.drawString(this.font, balanceText, 120, 58, 0xFFE7C977);
            LoanAccountView view = activeLoanView();
            g.drawString(this.font, Component.translatable("screen.sailboatmod.bank.amount_label"), 14, 118, 0xFFDCEEFF);
            if (!view.enabled()) {
                g.drawString(this.font, Component.translatable("screen.sailboatmod.bank.loan_disabled"), 14, 106, 0xFFE57373);
            } else {
                g.drawString(this.font, Component.translatable("screen.sailboatmod.bank.loan_outstanding", GoldStandardEconomy.formatBalance(view.outstanding())), 14, 104, 0xFFDCEEFF);
                g.drawString(this.font, Component.translatable("screen.sailboatmod.bank.loan_max", GoldStandardEconomy.formatBalance(view.maxBorrowable())), 14, 116, 0xFFB8C0C8);
                g.drawString(this.font, Component.translatable("screen.sailboatmod.bank.loan_interest", GoldStandardEconomy.formatBalance(view.nextInterestCharge())), 14, 156, 0xFFB8C0C8);
                g.drawString(this.font, Component.translatable("screen.sailboatmod.bank.loan_due", formatWhen(view.nextDueAt())), 14, 168, 0xFFB8C0C8);
                g.drawString(this.font, Component.translatable("screen.sailboatmod.bank.loan_principal", GoldStandardEconomy.formatBalance(view.principal())), 14, 180, 0xFFB8C0C8);
                g.drawString(this.font, Component.translatable("screen.sailboatmod.bank.loan_interest_total", GoldStandardEconomy.formatBalance(view.accruedInterest())), 14, 192, 0xFFB8C0C8);
                g.drawString(this.font, Component.translatable("screen.sailboatmod.bank.loan_delinquent", yesNo(view.delinquent())), 14, 204, view.delinquent() ? 0xFFE57373 : 0xFF82D7A3);
            }
        }

        if (!this.statusLine.getString().isBlank()) {
            g.drawCenteredString(this.font, this.statusLine, SCREEN_W / 2, activeTab == BankTab.STORAGE ? STORAGE_STATUS_Y : STATUS_Y, 0xFFF1D98A);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (activeTab == BankTab.STORAGE) {
            int listScreenX = this.leftPos + ITEM_LIST_X;
            int listScreenY = this.topPos + ITEM_LIST_Y;
            if (mouseX >= listScreenX && mouseX < listScreenX + ITEM_LIST_W
                    && mouseY >= listScreenY && mouseY < listScreenY + ITEM_VISIBLE_ROWS * ITEM_ROW_H) {
                int row = (int) ((mouseY - listScreenY) / ITEM_ROW_H);
                this.selectedItemSlot = getVisibleSlot(row);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (activeTab == BankTab.STORAGE) {
            int listScreenX = this.leftPos + ITEM_LIST_X;
            int listScreenY = this.topPos + ITEM_LIST_Y;
            if (mouseX >= listScreenX && mouseX < listScreenX + ITEM_LIST_W
                    && mouseY >= listScreenY && mouseY < listScreenY + ITEM_VISIBLE_ROWS * ITEM_ROW_H) {
                this.itemScroll = Math.max(0, this.itemScroll - (int) delta);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void switchTab(BankTab tab) {
        this.activeTab = tab;
        if (tab != BankTab.STORAGE) {
            this.selectedItemSlot = -1;
        }
        refreshStatus();
        updateWidgetVisibility();
    }

    private void updateWidgetVisibility() {
        boolean treasury = activeTab == BankTab.TREASURY;
        boolean storage = activeTab == BankTab.STORAGE;
        boolean loans = activeTab == BankTab.LOANS;
        if (this.amountInput != null) {
            this.amountInput.visible = treasury;
            this.amountInput.setEditable(treasury);
        }
        if (this.loanAmountInput != null) {
            this.loanAmountInput.visible = loans;
            this.loanAmountInput.setEditable(loans);
        }
        setVisible(this.treasuryButtons, treasury);
        setVisible(this.storageButtons, storage);
        setVisible(this.loanButtons, loans);
        setVisible(this.loanSubtabButtons, loans);
        if (this.closeButton != null) {
            this.closeButton.visible = true;
            this.closeButton.active = true;
        }
        applyPlayerInventoryLayout(storage);
    }

    private void applyPlayerInventoryLayout(boolean storageVisible) {
        this.menu.setPlayerInventoryVisible(storageVisible);
    }

    private void setVisible(List<Button> buttons, boolean visible) {
        for (Button button : buttons) {
            button.visible = visible;
            button.active = visible;
        }
    }

    private boolean isActiveTab(Button button) {
        return (activeTab == BankTab.TREASURY && button == tabButtons.get(0))
                || (activeTab == BankTab.STORAGE && button == tabButtons.get(1))
                || (activeTab == BankTab.LOANS && button == tabButtons.get(2));
    }

    private void refreshStatus() {
        if (activeTab == BankTab.STORAGE) {
            this.statusLine = Component.translatable("screen.sailboatmod.bank.storage_hint");
            return;
        }
        if (activeTab == BankTab.LOANS) {
            this.statusLine = Component.translatable(activeLoanTab == LoanTab.PERSONAL
                    ? "screen.sailboatmod.bank.loan_personal_hint"
                    : "screen.sailboatmod.bank.loan_nation_hint");
            return;
        }
        this.statusLine = Component.empty();
    }

    private void renderTreasuryItemTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (activeTab != BankTab.STORAGE) {
            return;
        }
        int listScreenX = this.leftPos + ITEM_LIST_X;
        int listScreenY = this.topPos + ITEM_LIST_Y;
        if (mouseX < listScreenX || mouseX >= listScreenX + ITEM_LIST_W
                || mouseY < listScreenY || mouseY >= listScreenY + ITEM_VISIBLE_ROWS * ITEM_ROW_H) {
            return;
        }
        int row = (mouseY - listScreenY) / ITEM_ROW_H;
        int slot = getVisibleSlot(row);
        if (slot < 0) {
            return;
        }
        NationTreasuryRecord treasury = getTreasury();
        if (treasury == null) {
            return;
        }
        ItemStack stack = treasury.items().get(slot);
        if (stack.isEmpty()) {
            return;
        }
        List<Component> tooltip = new ArrayList<>(stack.getTooltipLines(Minecraft.getInstance().player, net.minecraft.world.item.TooltipFlag.NORMAL));
        String depositor = getCachedDepositor(slot);
        if (!depositor.isBlank()) {
            tooltip.add(Component.translatable("screen.sailboatmod.bank.depositor", depositor).withStyle(ChatFormatting.GRAY));
        }
        g.renderTooltip(this.font, tooltip, java.util.Optional.empty(), mouseX, mouseY);
    }

    private NationTreasuryRecord getTreasury() {
        long bal = SyncTreasuryPacket.ClientTreasuryCache.getBalance();
        net.minecraft.core.NonNullList<ItemStack> items = SyncTreasuryPacket.ClientTreasuryCache.getItems();
        if (bal <= 0 && items.stream().allMatch(ItemStack::isEmpty)) {
            return null;
        }
        return new NationTreasuryRecord("", bal, items, new String[NationTreasuryRecord.TREASURY_SLOTS], 0, 0, 0, 0L);
    }

    private String getCachedDepositor(int slot) {
        return SyncTreasuryPacket.ClientTreasuryCache.getDepositor(slot);
    }

    private int getVisibleSlot(int row) {
        NationTreasuryRecord treasury = getTreasury();
        if (treasury == null) {
            return -1;
        }
        int startIdx = Math.max(0, itemScroll);
        int drawn = 0;
        for (int i = 0; i < treasury.items().size(); i++) {
            if (treasury.items().get(i).isEmpty()) {
                continue;
            }
            if (startIdx > 0) {
                startIdx--;
                continue;
            }
            if (drawn == row) {
                return i;
            }
            drawn++;
        }
        return -1;
    }

    private void doDeposit() {
        long amount = parseAmount(this.amountInput);
        if (amount <= 0) {
            this.statusLine = Component.translatable("command.sailboatmod.nation.treasury.invalid_amount");
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new BankActionPacket(BankActionPacket.Action.DEPOSIT_CURRENCY, this.menu.getBankPos(), amount, 0));
        this.statusLine = Component.translatable("screen.sailboatmod.bank.depositing", amount);
    }

    private void doWithdraw() {
        long amount = parseAmount(this.amountInput);
        if (amount <= 0) {
            this.statusLine = Component.translatable("command.sailboatmod.nation.treasury.invalid_amount");
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new BankActionPacket(BankActionPacket.Action.WITHDRAW_CURRENCY, this.menu.getBankPos(), amount, 0));
        this.statusLine = Component.translatable("screen.sailboatmod.bank.withdrawing", amount);
    }

    private void doWithdrawAsGold() {
        long amount = parseAmount(this.amountInput);
        if (amount <= 0) {
            this.statusLine = Component.translatable("command.sailboatmod.nation.treasury.invalid_amount");
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new BankActionPacket(BankActionPacket.Action.WITHDRAW_AS_GOLD, this.menu.getBankPos(), amount, 0));
        this.statusLine = Component.translatable("screen.sailboatmod.bank.withdrawing", amount);
    }

    private void doWithdrawItem() {
        if (this.selectedItemSlot < 0) {
            this.statusLine = Component.translatable("screen.sailboatmod.bank.select_item");
            return;
        }
        NationTreasuryRecord treasury = getTreasury();
        if (treasury == null) {
            return;
        }
        ItemStack selected = treasury.items().get(this.selectedItemSlot);
        if (selected.isEmpty()) {
            this.statusLine = Component.translatable("screen.sailboatmod.bank.select_item");
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new BankActionPacket(BankActionPacket.Action.WITHDRAW_ITEM, this.menu.getBankPos(), selected.getCount(), this.selectedItemSlot, selected.copy()));
        this.statusLine = Component.translatable("screen.sailboatmod.bank.withdrawing_item");
        this.selectedItemSlot = -1;
    }

    private void doLoanBorrow() {
        long amount = parseAmount(this.loanAmountInput);
        if (amount <= 0) {
            this.statusLine = Component.translatable("command.sailboatmod.nation.treasury.invalid_amount");
            return;
        }
        BankActionPacket.Action action = activeLoanTab == LoanTab.PERSONAL
                ? BankActionPacket.Action.BORROW_PERSONAL
                : BankActionPacket.Action.BORROW_NATION;
        ModNetwork.CHANNEL.sendToServer(new BankActionPacket(action, this.menu.getBankPos(), amount, 0));
        this.statusLine = Component.translatable("screen.sailboatmod.bank.borrowing", amount);
    }

    private void doLoanRepay() {
        long amount = parseAmount(this.loanAmountInput);
        if (amount <= 0) {
            this.statusLine = Component.translatable("command.sailboatmod.nation.treasury.invalid_amount");
            return;
        }
        BankActionPacket.Action action = activeLoanTab == LoanTab.PERSONAL
                ? BankActionPacket.Action.REPAY_PERSONAL
                : BankActionPacket.Action.REPAY_NATION;
        ModNetwork.CHANNEL.sendToServer(new BankActionPacket(action, this.menu.getBankPos(), amount, 0));
        this.statusLine = Component.translatable("screen.sailboatmod.bank.repaying", amount);
    }

    private LoanAccountView activeLoanView() {
        return activeLoanTab == LoanTab.PERSONAL
                ? SyncTreasuryPacket.ClientTreasuryCache.getPersonalLoan()
                : SyncTreasuryPacket.ClientTreasuryCache.getNationLoan();
    }

    private long parseAmount(EditBox input) {
        if (input == null) {
            return 0L;
        }
        try {
            return Long.parseLong(input.getValue().trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private void drawWrapped(GuiGraphics g, Component text, int x, int y, int width, int color) {
        int lineY = y;
        for (var line : this.font.split(text, width)) {
            g.drawString(this.font, line, x, lineY, color);
            lineY += 10;
        }
    }

    private String trimName(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(1, maxChars - 3)) + "...";
    }

    private String formatWhen(long epochMillis) {
        if (epochMillis <= 0L) {
            return "-";
        }
        return new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date(epochMillis));
    }

    private String yesNo(boolean value) {
        return value ? Component.translatable("screen.sailboatmod.bank.loan_yes").getString()
                : Component.translatable("screen.sailboatmod.bank.loan_no").getString();
    }

    private enum BankTab {
        TREASURY,
        STORAGE,
        LOANS
    }

    private enum LoanTab {
        PERSONAL,
        NATION
    }
}
