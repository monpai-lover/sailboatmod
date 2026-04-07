package com.monpai.sailboatmod.menu;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.item.TransportRouteBook;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.OpenDockScreenPacket;
import com.monpai.sailboatmod.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class DockMenu extends AbstractContainerMenu {
    private static final int ROUTE_BOOK_SLOT = 0;
    private static final int STORAGE_START = 1;
    private static final int STORAGE_COUNT = 1;
    private static final int PLAYER_INV_START = STORAGE_START + STORAGE_COUNT;
    private final BlockPos dockPos;
    private final Level level;
    private final Player player;
    private final Container dockSlotContainer;
    private final Container dockStorageContainer;
    private final boolean canManageDock;
    private boolean updatingDepositSlot = false;
    @Nullable
    private final DockBlockEntity dock;

    public DockMenu(int containerId, Inventory inventory, net.minecraft.network.FriendlyByteBuf extraData) {
        this(containerId, inventory, extraData.readBlockPos());
    }

    public DockMenu(int containerId, Inventory inventory, BlockPos dockPos) {
        this(ModMenus.DOCK_MENU.get(), containerId, inventory, dockPos);
    }

    protected DockMenu(MenuType<?> menuType, int containerId, Inventory inventory, BlockPos dockPos) {
        super(menuType, containerId);
        this.dockPos = dockPos;
        this.level = inventory.player.level();
        this.player = inventory.player;
        this.dock = level.getBlockEntity(dockPos) instanceof DockBlockEntity be ? be : null;
        this.canManageDock = dock != null && dock.canManageDock(inventory.player);

        ItemStack initial = canManageDock && dock != null ? dock.buildScreenData(inventory.player).routeBook() : ItemStack.EMPTY;
        this.dockSlotContainer = new SimpleContainer(1) {
            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                return canManageDock && stack.getItem() instanceof TransportRouteBook;
            }

            @Override
            public void setChanged() {
                super.setChanged();
                if (canManageDock && dock != null) {
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
                return canManageDock && stack.getItem() instanceof TransportRouteBook;
            }

            @Override
            public boolean mayPickup(Player player) {
                return canManageDock;
            }
        });

        this.dockStorageContainer = new SimpleContainer(1) {
            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                return canManageDock;
            }

            @Override
            public void setChanged() {
                super.setChanged();
                if (updatingDepositSlot || !canManageDock || dock == null) {
                    return;
                }
                ItemStack stack = getItem(0);
                if (stack.isEmpty()) {
                    return;
                }
                ItemStack copy = stack.copy();
                if (dock.insertCargo(List.of(copy))) {
                    updatingDepositSlot = true;
                    setItem(0, ItemStack.EMPTY);
                    updatingDepositSlot = false;
                    sendDockUpdate();
                }
            }
        };

        addDockStorageSlots();

        addPlayerInventory(inventory);
        addPlayerHotbar(inventory);
    }

    private void addDockStorageSlots() {
        this.addSlot(new Slot(dockStorageContainer, 0, 10, 46) {
            @Override
            public boolean mayPickup(Player player) {
                return canManageDock;
            }
        });
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
            if (stack.getItem() instanceof TransportRouteBook) {
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

    private void sendDockUpdate() {
        if (dock == null || player == null) {
            return;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> serverPlayer),
                    new OpenDockScreenPacket(dock.buildScreenData(serverPlayer))
            );
        }
    }
}
