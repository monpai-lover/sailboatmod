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
    private static final int ROUTE_BOOK_SLOT = 0;
    private static final int STORAGE_START = 1;
    private static final int STORAGE_COUNT = DockBlockEntity.STORAGE_SIZE;
    private static final int PLAYER_INV_START = STORAGE_START + STORAGE_COUNT;
    private final BlockPos dockPos;
    private final Level level;
    private final Container dockSlotContainer;
    private final Container dockStorageContainer;
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

        this.dockStorageContainer = new Container() {
            @Override
            public int getContainerSize() {
                return dock == null ? 0 : dock.getStorageSize();
            }

            @Override
            public boolean isEmpty() {
                if (dock == null) {
                    return true;
                }
                for (int i = 0; i < dock.getStorageSize(); i++) {
                    if (!dock.getStorageItem(i).isEmpty()) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public ItemStack getItem(int slot) {
                return dock == null ? ItemStack.EMPTY : dock.getStorageItem(slot);
            }

            @Override
            public ItemStack removeItem(int slot, int amount) {
                return dock == null ? ItemStack.EMPTY : dock.removeStorageItem(slot, amount);
            }

            @Override
            public ItemStack removeItemNoUpdate(int slot) {
                return dock == null ? ItemStack.EMPTY : dock.removeStorageItemNoUpdate(slot);
            }

            @Override
            public void setItem(int slot, ItemStack stack) {
                if (dock != null) {
                    dock.setStorageItem(slot, stack);
                }
            }

            @Override
            public void setChanged() {
                if (dock != null) {
                    dock.storageChanged();
                }
            }

            @Override
            public boolean stillValid(Player player) {
                return DockMenu.this.stillValid(player);
            }

            @Override
            public void clearContent() {
                if (dock == null) {
                    return;
                }
                for (int i = 0; i < dock.getStorageSize(); i++) {
                    dock.setStorageItem(i, ItemStack.EMPTY);
                }
            }
        };

        addDockStorageSlots();

        addPlayerInventory(inventory);
        addPlayerHotbar(inventory);
    }

    private void addDockStorageSlots() {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slotIndex = col + row * 9;
                this.addSlot(new Slot(dockStorageContainer, slotIndex, 28 + col * 18, 20 + row * 18));
            }
        }
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

        if (index == ROUTE_BOOK_SLOT) {
            if (!moveItemStackTo(stack, PLAYER_INV_START, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (index >= STORAGE_START && index < PLAYER_INV_START) {
            if (!moveItemStackTo(stack, PLAYER_INV_START, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (stack.getItem() instanceof RouteBookItem) {
                if (!moveItemStackTo(stack, ROUTE_BOOK_SLOT, ROUTE_BOOK_SLOT + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, STORAGE_START, PLAYER_INV_START, false)) {
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
