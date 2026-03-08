package com.example.examplemod.menu;

import com.example.examplemod.block.entity.DockBlockEntity;
import com.example.examplemod.item.RouteBookItem;
import com.example.examplemod.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public class DockMenu extends AbstractContainerMenu {
    private final BlockPos dockPos;
    private final Level level;
    private final Container dockSlotContainer;
    @Nullable
    private final DockBlockEntity dock;

    public DockMenu(int containerId, Inventory inventory, net.minecraft.network.FriendlyByteBuf extraData) {
        this(containerId, inventory, extraData.readBlockPos());
    }

    public DockMenu(int containerId, Inventory inventory, BlockPos dockPos) {
        super(ModMenus.DOCK_MENU.get(), containerId);
        this.dockPos = dockPos;
        this.level = inventory.player.level();
        this.dock = level.getBlockEntity(dockPos) instanceof DockBlockEntity be ? be : null;

        ItemStack initial = dock != null ? dock.buildScreenData(inventory.player).routeBook() : ItemStack.EMPTY;
        this.dockSlotContainer = new SimpleContainer(1) {
            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                return stack.getItem() instanceof RouteBookItem;
            }

            @Override
            public void setChanged() {
                super.setChanged();
                if (dock != null) {
                    ItemStack stack = getItem(0);
                    if (stack.isEmpty()) {
                        dock.clearRouteBook();
                    } else {
                        dock.setRouteBookStack(stack);
                    }
                }
            }
        };
        this.dockSlotContainer.setItem(0, initial);

        this.addSlot(new Slot(dockSlotContainer, 0, 10, 20) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof RouteBookItem;
            }
        });

        addPlayerInventory(inventory);
        addPlayerHotbar(inventory);
    }

    private void addPlayerInventory(Inventory inventory) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(inventory, col + row * 9 + 9, 10 + col * 18, 86 + row * 18));
            }
        }
    }

    private void addPlayerHotbar(Inventory inventory) {
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(inventory, col, 10 + col * 18, 144));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        if (dock == null || dock.isRemoved()) {
            return false;
        }
        return player.distanceToSqr(dockPos.getX() + 0.5D, dockPos.getY() + 0.5D, dockPos.getZ() + 0.5D) <= 64.0D;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack empty = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) {
            return empty;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        if (index == 0) {
            if (!moveItemStackTo(stack, 1, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (!(stack.getItem() instanceof RouteBookItem)) {
                return ItemStack.EMPTY;
            }
            if (!moveItemStackTo(stack, 0, 1, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    public BlockPos getDockPos() {
        return dockPos;
    }
}
