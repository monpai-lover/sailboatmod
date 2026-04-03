package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.economy.GoldStandardEconomy;
import com.monpai.sailboatmod.menu.BankMenu;
import com.monpai.sailboatmod.nation.model.NationTreasuryRecord;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.BankActionPacket;
import com.monpai.sailboatmod.network.packet.SyncTreasuryPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class BankScreen extends AbstractContainerScreen<BankMenu> {
    private static final int SCREEN_W = 340;
    private static final int SCREEN_H = 260;
    private static final int ITEM_ROW_H = 20;
    private static final int ITEM_VISIBLE_ROWS = 6;
    private static final int ITEM_LIST_X = 14;
    private static final int ITEM_LIST_Y = 130;
    private static final int ITEM_LIST_W = SCREEN_W - 28;

    private EditBox amountInput;
    private Button depositButton;
    private Button withdrawButton;
    private Button depositItemButton;
    private Button withdrawItemButton;
    private Component statusLine = Component.empty();
    private int itemScroll;
    private int selectedItemSlot = -1;

    public BankScreen(BankMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = SCREEN_W;
        this.imageHeight = SCREEN_H;
    }

    @Override
    protected void init() {
        super.init();
        int left = (this.width - SCREEN_W) / 2;
        int top = (this.height - SCREEN_H) / 2;

        this.amountInput = new EditBox(this.font, left + 14, top + 60, 120, 18, Component.translatable("screen.sailboatmod.bank.amount"));
        this.amountInput.setMaxLength(16);
        this.addRenderableWidget(this.amountInput);

        this.depositButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.sailboatmod.bank.deposit"), b -> doDeposit()
        ).bounds(left + 140, top + 60, 60, 18).build());

        this.withdrawButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.sailboatmod.bank.withdraw"), b -> doWithdraw()
        ).bounds(left + 206, top + 60, 60, 18).build());

        this.depositItemButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.sailboatmod.bank.deposit_item"), b -> doDepositItem()
        ).bounds(left + 14, top + 100, 120, 18).build());

        this.withdrawItemButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.sailboatmod.bank.withdraw_item"), b -> doWithdrawItem()
        ).bounds(left + 140, top + 100, 120, 18).build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.sailboatmod.route_name.cancel"), b -> onClose()
        ).bounds(left + SCREEN_W - 74, top + SCREEN_H - 24, 60, 18).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
        renderItemTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int left = (this.width - SCREEN_W) / 2;
        int top = (this.height - SCREEN_H) / 2;
        g.fill(left, top, left + SCREEN_W, top + SCREEN_H, 0xCC101820);
        g.fill(left + 1, top + 1, left + SCREEN_W - 1, top + SCREEN_H - 1, 0xCC182632);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, this.title, 14, 10, 0xFFE7C977);

        NationTreasuryRecord treasury = getTreasury();
        String balanceText = treasury == null ? "0" : GoldStandardEconomy.formatBalance(treasury.currencyBalance());
        g.drawString(this.font, Component.translatable("screen.sailboatmod.bank.currency_label"), 14, 34, 0xFFB8C0C8);
        g.drawString(this.font, balanceText, 100, 34, 0xFFE7C977);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.bank.amount_label"), 14, 50, 0xFFDCEEFF);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.bank.item_label"), 14, 90, 0xFFB8C0C8);

        // Item list header
        g.drawString(this.font, Component.translatable("screen.sailboatmod.bank.items_title"), ITEM_LIST_X, ITEM_LIST_Y - 12, 0xFFB8C0C8);
        g.fill(ITEM_LIST_X, ITEM_LIST_Y, ITEM_LIST_X + ITEM_LIST_W, ITEM_LIST_Y + ITEM_VISIBLE_ROWS * ITEM_ROW_H, 0x44000000);

        if (treasury != null) {
            int startIdx = Math.max(0, itemScroll);
            int drawn = 0;
            for (int i = 0; i < NationTreasuryRecord.TREASURY_SLOTS && drawn < ITEM_VISIBLE_ROWS; i++) {
                ItemStack stack = treasury.items().get(i);
                if (stack.isEmpty()) continue;
                if (startIdx > 0) { startIdx--; continue; }
                int rowY = ITEM_LIST_Y + drawn * ITEM_ROW_H + 2;
                if (i == this.selectedItemSlot) {
                    g.fill(ITEM_LIST_X, rowY - 2, ITEM_LIST_X + ITEM_LIST_W, rowY + ITEM_ROW_H - 2, 0x44FFFFFF);
                }
                g.renderItem(stack, ITEM_LIST_X + 2, rowY);
                String name = stack.getHoverName().getString();
                if (name.length() > 20) name = name.substring(0, 20) + "...";
                g.drawString(this.font, name, ITEM_LIST_X + 22, rowY + 4, 0xFFDCEEFF);
                g.drawString(this.font, "x" + stack.getCount(), ITEM_LIST_X + ITEM_LIST_W - 40, rowY + 4, 0xFFE7C977);
                drawn++;
            }
        }

        if (!this.statusLine.getString().isBlank()) {
            g.drawCenteredString(this.font, this.statusLine, SCREEN_W / 2, SCREEN_H - 14, 0xFFF1D98A);
        }
    }

    private void renderItemTooltip(GuiGraphics g, int mouseX, int mouseY) {
        int left = (this.width - SCREEN_W) / 2;
        int top = (this.height - SCREEN_H) / 2;
        int listScreenX = left + ITEM_LIST_X;
        int listScreenY = top + ITEM_LIST_Y;
        if (mouseX < listScreenX || mouseX >= listScreenX + ITEM_LIST_W || mouseY < listScreenY || mouseY >= listScreenY + ITEM_VISIBLE_ROWS * ITEM_ROW_H) return;
        int row = (mouseY - listScreenY) / ITEM_ROW_H;
        int slot = getVisibleSlot(row);
        if (slot < 0) return;
        NationTreasuryRecord treasury = getTreasury();
        if (treasury == null) return;
        ItemStack stack = treasury.items().get(slot);
        if (stack.isEmpty()) return;
        List<Component> tooltip = new ArrayList<>(stack.getTooltipLines(Minecraft.getInstance().player, net.minecraft.world.item.TooltipFlag.NORMAL));
        String depositor = getCachedDepositor(slot);
        if (!depositor.isBlank()) {
            tooltip.add(Component.translatable("screen.sailboatmod.bank.depositor", depositor).withStyle(net.minecraft.ChatFormatting.GRAY));
        }
        g.renderTooltip(this.font, tooltip, java.util.Optional.empty(), mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int left = (this.width - SCREEN_W) / 2;
        int top = (this.height - SCREEN_H) / 2;
        int listScreenX = left + ITEM_LIST_X;
        int listScreenY = top + ITEM_LIST_Y;
        if (mouseX >= listScreenX && mouseX < listScreenX + ITEM_LIST_W && mouseY >= listScreenY && mouseY < listScreenY + ITEM_VISIBLE_ROWS * ITEM_ROW_H) {
            int row = (int) ((mouseY - listScreenY) / ITEM_ROW_H);
            int slot = getVisibleSlot(row);
            this.selectedItemSlot = slot;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int left = (this.width - SCREEN_W) / 2;
        int top = (this.height - SCREEN_H) / 2;
        int listScreenX = left + ITEM_LIST_X;
        int listScreenY = top + ITEM_LIST_Y;
        if (mouseX >= listScreenX && mouseX < listScreenX + ITEM_LIST_W && mouseY >= listScreenY && mouseY < listScreenY + ITEM_VISIBLE_ROWS * ITEM_ROW_H) {
            this.itemScroll = Math.max(0, this.itemScroll - (int) delta);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private int getVisibleSlot(int row) {
        NationTreasuryRecord treasury = getTreasury();
        if (treasury == null) return -1;
        int startIdx = Math.max(0, itemScroll);
        int drawn = 0;
        for (int i = 0; i < NationTreasuryRecord.TREASURY_SLOTS; i++) {
            if (treasury.items().get(i).isEmpty()) continue;
            if (startIdx > 0) { startIdx--; continue; }
            if (drawn == row) return i;
            drawn++;
        }
        return -1;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void containerTick() {
        super.containerTick();
        if (this.amountInput != null) this.amountInput.tick();
    }

    private NationTreasuryRecord getTreasury() {
        SyncTreasuryPacket.ClientTreasuryCache cache = null;
        long bal = SyncTreasuryPacket.ClientTreasuryCache.getBalance();
        net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> items = SyncTreasuryPacket.ClientTreasuryCache.getItems();
        if (bal <= 0 && items.stream().allMatch(net.minecraft.world.item.ItemStack::isEmpty)) return null;
        return new NationTreasuryRecord("", bal, items, new String[NationTreasuryRecord.TREASURY_SLOTS], 0, 0, 0, 0L);
    }

    private String getCachedDepositor(int slot) {
        return SyncTreasuryPacket.ClientTreasuryCache.getDepositor(slot);
    }

    private void doDeposit() {
        long amount = parseAmount();
        if (amount <= 0) {
            this.statusLine = Component.translatable("command.sailboatmod.nation.treasury.invalid_amount");
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new BankActionPacket(BankActionPacket.Action.DEPOSIT_CURRENCY, this.menu.getBankPos(), amount, 0));
        this.statusLine = Component.translatable("screen.sailboatmod.bank.depositing", amount);
    }

    private void doWithdraw() {
        long amount = parseAmount();
        if (amount <= 0) {
            this.statusLine = Component.translatable("command.sailboatmod.nation.treasury.invalid_amount");
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new BankActionPacket(BankActionPacket.Action.WITHDRAW_CURRENCY, this.menu.getBankPos(), amount, 0));
        this.statusLine = Component.translatable("screen.sailboatmod.bank.withdrawing", amount);
    }

    private void doDepositItem() {
        ModNetwork.CHANNEL.sendToServer(new BankActionPacket(BankActionPacket.Action.DEPOSIT_ITEM, this.menu.getBankPos(), 0, 0));
        this.statusLine = Component.translatable("screen.sailboatmod.bank.depositing_item");
    }

    private void doWithdrawItem() {
        if (this.selectedItemSlot < 0) {
            this.statusLine = Component.translatable("screen.sailboatmod.bank.select_item");
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new BankActionPacket(BankActionPacket.Action.WITHDRAW_ITEM, this.menu.getBankPos(), 0, this.selectedItemSlot));
        this.statusLine = Component.translatable("screen.sailboatmod.bank.withdrawing_item");
        this.selectedItemSlot = -1;
    }

    private long parseAmount() {
        if (this.amountInput == null) return 0;
        try {
            return Long.parseLong(this.amountInput.getValue().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
