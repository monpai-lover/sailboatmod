package com.monpai.sailboatmod.menu;

import com.monpai.sailboatmod.block.entity.TownWarehouseBlockEntity;
import com.monpai.sailboatmod.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class WarehouseMenu extends AbstractContainerMenu {
    private static final int WAREHOUSE_ROWS = 6;
    private static final int WAREHOUSE_COLUMNS = 9;
    private static final int WAREHOUSE_SLOT_COUNT = WAREHOUSE_ROWS * WAREHOUSE_COLUMNS;

    private final Container warehouse;
    private final BlockPos warehousePos;

    public WarehouseMenu(int containerId, Inventory inventory, net.minecraft.network.FriendlyByteBuf extraData) {
        this(containerId, inventory,
                inventory.player.level().getBlockEntity(extraData.readBlockPos()) instanceof TownWarehouseBlockEntity warehouse
                        ? warehouse
                        : null);
    }

    public WarehouseMenu(int containerId, Inventory inventory, TownWarehouseBlockEntity warehouse) {
        super(ModMenus.WAREHOUSE_MENU.get(), containerId);
        this.warehouse = warehouse == null ? new SimpleContainer(WAREHOUSE_SLOT_COUNT) : warehouse;
        this.warehousePos = warehouse == null ? BlockPos.ZERO : warehouse.getBlockPos();

        for (int row = 0; row < WAREHOUSE_ROWS; row++) {
            for (int col = 0; col < WAREHOUSE_COLUMNS; col++) {
                this.addSlot(new Slot(this.warehouse, col + row * WAREHOUSE_COLUMNS, 8 + col * 18, 18 + row * 18));
            }
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 140 + row * 18));
            }
        }

        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(inventory, col, 8 + col * 18, 198));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return warehouse.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        if (index < WAREHOUSE_SLOT_COUNT) {
            if (!moveItemStackTo(stack, WAREHOUSE_SLOT_COUNT, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, 0, WAREHOUSE_SLOT_COUNT, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    public BlockPos getWarehousePos() {
        return warehousePos;
    }
}
