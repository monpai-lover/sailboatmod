package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.menu.BankMenu;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationTreasuryRecord;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.BankActionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class BankScreen extends AbstractContainerScreen<BankMenu> {
    private static final int SCREEN_W = 280;
    private static final int SCREEN_H = 180;

    private EditBox amountInput;
    private Button depositButton;
    private Button withdrawButton;
    private Button depositItemButton;
    private Component statusLine = Component.empty();

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

        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.sailboatmod.route_name.cancel"), b -> onClose()
        ).bounds(left + SCREEN_W - 74, top + SCREEN_H - 24, 60, 18).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
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
        g.drawString(this.font, Component.translatable("screen.sailboatmod.bank.currency_label"), 14, 34, 0xFFB8C0C8);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.bank.amount_label"), 14, 50, 0xFFDCEEFF);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.bank.item_label"), 14, 90, 0xFFB8C0C8);
        g.drawString(this.font, Component.translatable("screen.sailboatmod.bank.item_hint"), 14, 124, 0xFF8D98A3);
        if (!this.statusLine.getString().isBlank()) {
            g.drawCenteredString(this.font, this.statusLine, SCREEN_W / 2, SCREEN_H - 14, 0xFFF1D98A);
        }
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

    private long parseAmount() {
        if (this.amountInput == null) return 0;
        try {
            return Long.parseLong(this.amountInput.getValue().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
