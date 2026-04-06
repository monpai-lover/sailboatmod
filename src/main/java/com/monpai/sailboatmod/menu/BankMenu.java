package com.monpai.sailboatmod.menu;

import com.monpai.sailboatmod.block.entity.BankBlockEntity;
import com.monpai.sailboatmod.economy.GoldStandardEconomy;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationPermission;
import com.monpai.sailboatmod.nation.model.NationTreasuryRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.service.BankLoanService;
import com.monpai.sailboatmod.nation.service.NationService;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.SyncTreasuryPacket;
import com.monpai.sailboatmod.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

public class BankMenu extends AbstractContainerMenu {
    private static final BankLoanService BANK_LOANS = new BankLoanService();
    private static final int PLAYER_INV_START = 0;
    public static final int PLAYER_INV_ROWS = 3;
    public static final int PLAYER_INV_COLS = 9;
    public static final int PLAYER_INV_COUNT = PLAYER_INV_ROWS * PLAYER_INV_COLS;
    public static final int HOTBAR_COUNT = 9;
    public static final int STORAGE_INV_X = 14;
    public static final int STORAGE_INV_Y = 186;
    public static final int STORAGE_HOTBAR_Y = 244;

    private final BlockPos bankPos;
    private final BankBlockEntity bank;
    private final List<ToggleableSlot> playerSlots = new ArrayList<>();

    public BankMenu(int containerId, Inventory inventory, net.minecraft.network.FriendlyByteBuf extraData) {
        this(containerId, inventory, extraData.readBlockPos());
    }

    public BankMenu(int containerId, Inventory inventory, BlockPos bankPos) {
        super(ModMenus.BANK_MENU.get(), containerId);
        this.bankPos = bankPos;
        this.bank = inventory.player.level().getBlockEntity(bankPos) instanceof BankBlockEntity be ? be : null;
        addPlayerInventory(inventory);
        addPlayerHotbar(inventory);

        if (!inventory.player.level().isClientSide && inventory.player instanceof ServerPlayer serverPlayer) {
            TownRecord town = TownService.getTownAt(serverPlayer.level(), bankPos);
            if (town != null && !town.nationId().isBlank()) {
                NationSavedData data = NationSavedData.get(serverPlayer.level());
                NationTreasuryRecord treasury = data.getOrCreateTreasury(town.nationId());
                ModNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> serverPlayer),
                        new SyncTreasuryPacket(
                                treasury,
                                BANK_LOANS.buildPersonalView(serverPlayer),
                                BANK_LOANS.buildNationView(serverPlayer.level(), town.nationId())
                        )
                );
            }
        }
    }

    @Override
    public boolean stillValid(Player player) {
        if (bank == null || bank.isRemoved()) {
            return false;
        }
        return player.distanceToSqr(bankPos.getX() + 0.5D, bankPos.getY() + 0.5D, bankPos.getZ() + 0.5D) <= 64.0D;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < PLAYER_INV_START || index >= this.slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return ItemStack.EMPTY;
        }

        TownRecord town = TownService.getTownAt(serverPlayer.level(), bankPos);
        if (town == null || town.nationId().isBlank()) {
            player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.bank.no_nation"));
            return ItemStack.EMPTY;
        }

        NationSavedData data = NationSavedData.get(serverPlayer.level());
        NationMemberRecord member = data.getMember(player.getUUID());
        if (member == null || !member.nationId().equals(town.nationId())) {
            player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.bank.not_member"));
            return ItemStack.EMPTY;
        }
        if (!NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_TREASURY)) {
            player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.treasury.no_permission"));
            return ItemStack.EMPTY;
        }

        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        NationTreasuryRecord treasury = data.getOrCreateTreasury(town.nationId());
        long goldValue = GoldStandardEconomy.goldItemMarketValue(stack);
        if (goldValue > 0L) {
            data.putTreasury(treasury.withBalance(treasury.currencyBalance() + goldValue));
            stack.setCount(0);
            slot.set(ItemStack.EMPTY);
            player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.treasury.deposit.success", GoldStandardEconomy.formatBalance(goldValue)));
            sync(serverPlayer, data.getOrCreateTreasury(town.nationId()), town.nationId());
            return copy;
        }

        int deposited = treasury.addItem(stack.copy(), player.getScoreboardName());
        if (deposited <= 0) {
            return ItemStack.EMPTY;
        }
        stack.shrink(deposited);
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        data.putTreasury(treasury);
        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.bank.deposit_item.success"));
        sync(serverPlayer, data.getOrCreateTreasury(town.nationId()), town.nationId());
        return copy;
    }

    public BlockPos getBankPos() {
        return bankPos;
    }

    public int playerInventoryStart() {
        return PLAYER_INV_START;
    }

    public int playerInventoryEnd() {
        return PLAYER_INV_START + PLAYER_INV_COUNT + HOTBAR_COUNT;
    }

    public void setPlayerInventoryVisible(boolean visible) {
        for (ToggleableSlot slot : playerSlots) {
            slot.setVisible(visible);
        }
    }

    private void addPlayerInventory(Inventory inventory) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                ToggleableSlot slot = new ToggleableSlot(inventory, col + row * 9 + 9, STORAGE_INV_X + col * 18, STORAGE_INV_Y + row * 18);
                this.playerSlots.add(slot);
                this.addSlot(slot);
            }
        }
    }

    private void addPlayerHotbar(Inventory inventory) {
        for (int col = 0; col < 9; col++) {
            ToggleableSlot slot = new ToggleableSlot(inventory, col, STORAGE_INV_X + col * 18, STORAGE_HOTBAR_Y);
            this.playerSlots.add(slot);
            this.addSlot(slot);
        }
    }

    private void sync(ServerPlayer player, NationTreasuryRecord treasury, String nationId) {
        ModNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new SyncTreasuryPacket(
                        treasury,
                        BANK_LOANS.buildPersonalView(player),
                        BANK_LOANS.buildNationView(player.level(), nationId)
                )
        );
    }

    private static final class ToggleableSlot extends Slot {
        private boolean visible = true;

        private ToggleableSlot(Inventory inventory, int slot, int x, int y) {
            super(inventory, slot, x, y);
        }

        @Override
        public boolean isActive() {
            return visible;
        }

        private void setVisible(boolean visible) {
            this.visible = visible;
        }
    }
}
