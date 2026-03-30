package com.monpai.sailboatmod.menu;

import com.monpai.sailboatmod.block.entity.BankBlockEntity;
import com.monpai.sailboatmod.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public class BankMenu extends AbstractContainerMenu {
    private final BlockPos bankPos;
    private final BankBlockEntity bank;

    public BankMenu(int containerId, Inventory inventory, net.minecraft.network.FriendlyByteBuf extraData) {
        this(containerId, inventory, extraData.readBlockPos());
    }

    public BankMenu(int containerId, Inventory inventory, BlockPos bankPos) {
        super(ModMenus.BANK_MENU.get(), containerId);
        this.bankPos = bankPos;
        this.bank = inventory.player.level().getBlockEntity(bankPos) instanceof BankBlockEntity be ? be : null;
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
        return ItemStack.EMPTY;
    }

    public BlockPos getBankPos() {
        return bankPos;
    }
}
