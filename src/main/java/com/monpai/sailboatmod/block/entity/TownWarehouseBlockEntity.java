package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.dock.DockRegistry;
import com.monpai.sailboatmod.dock.PostStationRegistry;
import com.monpai.sailboatmod.dock.TownWarehouseRegistry;
import com.monpai.sailboatmod.menu.WarehouseMenu;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.service.TownStockpileService;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class TownWarehouseBlockEntity extends BlockEntity implements MenuProvider, Container {
    public static final int STORAGE_SIZE = 54;

    private final NonNullList<ItemStack> storage = NonNullList.withSize(STORAGE_SIZE, ItemStack.EMPTY);
    private String townId = "";
    private String townName = "";
    private boolean terminalsImported = false;

    public TownWarehouseBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TOWN_WAREHOUSE_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            resolveTownBinding();
            tryImportTerminalStorage();
        }
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (level != null && !level.isClientSide) {
            resolveTownBinding();
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            TownWarehouseRegistry.unregister(level, townId, worldPosition);
        }
        super.setRemoved();
    }

    public void resolveTownBinding() {
        if (level == null || level.isClientSide) {
            return;
        }
        TownRecord town = TownService.getTownAt(level, worldPosition);
        String nextTownId = town == null ? "" : town.townId();
        String nextTownName = town == null ? "" : town.name();
        if (!townId.isBlank() && !townId.equals(nextTownId)) {
            TownWarehouseRegistry.unregister(level, townId, worldPosition);
        }
        townId = nextTownId;
        townName = nextTownName;
        if (!townId.isBlank()) {
            TownWarehouseRegistry.register(level, townId, worldPosition);
        }
    }

    public String getTownId() {
        if ((townId == null || townId.isBlank()) && level != null && !level.isClientSide) {
            resolveTownBinding();
        }
        return townId == null ? "" : townId;
    }

    public String getTownName() {
        if ((townName == null || townName.isBlank()) && level != null && !level.isClientSide) {
            resolveTownBinding();
        }
        return townName == null || townName.isBlank() ? fallbackTownName(getTownId()) : townName;
    }

    public boolean canManageWarehouse(@Nullable ServerPlayer player) {
        if (player == null || level == null) {
            return false;
        }
        TownRecord town = TownService.getTownAt(level, worldPosition);
        return town != null && TownService.canManageTown(player, com.monpai.sailboatmod.nation.data.NationSavedData.get(level), town);
    }

    public List<String> getVisibleStorageLines() {
        List<String> lines = new ArrayList<>();
        for (StorageGroup group : getVisibleStorageGroups()) {
            String itemName = group.displayStack().isEmpty() ? "-" : group.displayStack().getHoverName().getString();
            lines.add(clampUtf(itemName + " x" + group.totalCount(), 150));
        }
        return lines;
    }

    public int getVisibleStorageCount() {
        return getVisibleStorageGroups().size();
    }

    public ItemStack getStorageItemForVisibleIndex(int visibleIndex) {
        List<StorageGroup> groups = getVisibleStorageGroups();
        if (visibleIndex < 0 || visibleIndex >= groups.size()) {
            return ItemStack.EMPTY;
        }
        return groups.get(visibleIndex).displayStack().copy();
    }

    public boolean extractVisibleStorage(int visibleIndex, int quantity) {
        ItemStack stack = getStorageItemForVisibleIndex(visibleIndex);
        if (stack.isEmpty()) {
            return false;
        }
        int amount = Math.max(1, Math.min(quantity, countMatchingStock(stack)));
        return extractMatchingStock(stack, amount);
    }

    public int countMatchingStock(ItemStack sample) {
        if (sample == null || sample.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ItemStack stack : storage) {
            if (!stack.isEmpty() && ItemStack.isSameItemSameTags(stack, sample)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public boolean extractMatchingStock(ItemStack sample, int quantity) {
        if (sample == null || sample.isEmpty() || quantity <= 0) {
            return false;
        }
        if (countMatchingStock(sample) < quantity) {
            return false;
        }
        int remaining = quantity;
        for (int i = 0; i < storage.size() && remaining > 0; i++) {
            ItemStack stack = storage.get(i);
            if (stack.isEmpty() || !ItemStack.isSameItemSameTags(stack, sample)) {
                continue;
            }
            int consume = Math.min(stack.getCount(), remaining);
            stack.shrink(consume);
            if (stack.isEmpty()) {
                storage.set(i, ItemStack.EMPTY);
            }
            remaining -= consume;
        }
        if (!getTownId().isBlank()) {
            TownStockpileService.removeItemStack(level, getTownId(), sample, quantity);
        }
        setChanged();
        return remaining <= 0;
    }

    public boolean canInsertCargo(List<ItemStack> cargo) {
        if (cargo == null || cargo.isEmpty()) {
            return true;
        }
        NonNullList<ItemStack> working = NonNullList.withSize(storage.size(), ItemStack.EMPTY);
        for (int i = 0; i < storage.size(); i++) {
            working.set(i, storage.get(i).copy());
        }
        for (ItemStack stack : cargo) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ItemStack remaining = stack.copy();
            mergeIntoStorage(remaining, working);
            if (!remaining.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public boolean insertCargo(List<ItemStack> cargo) {
        if (!canInsertCargo(cargo)) {
            return false;
        }
        for (ItemStack stack : cargo) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ItemStack remaining = stack.copy();
            mergeIntoStorage(remaining, storage);
        }
        if (!getTownId().isBlank()) {
            TownStockpileService.addCargo(level, getTownId(), cargo);
        }
        setChanged();
        return true;
    }

    private void tryImportTerminalStorage() {
        if (terminalsImported || level == null || level.isClientSide) {
            return;
        }
        String currentTownId = getTownId();
        if (currentTownId.isBlank()) {
            return;
        }
        Set<BlockPos> terminals = new LinkedHashSet<>();
        terminals.addAll(DockRegistry.get(level));
        terminals.addAll(PostStationRegistry.get(level));
        for (BlockPos terminalPos : terminals) {
            if (terminalPos.equals(worldPosition)) {
                continue;
            }
            if (!(level.getBlockEntity(terminalPos) instanceof DockBlockEntity terminal)) {
                continue;
            }
            TownRecord terminalTown = TownService.getTownAt(level, terminalPos);
            if (terminalTown == null || !currentTownId.equals(terminalTown.townId())) {
                continue;
            }
            List<ItemStack> cargo = terminal.copyAllStorageCargo();
            if (cargo.isEmpty() || !canInsertCargo(cargo)) {
                continue;
            }
            List<ItemStack> drained = terminal.drainAllStorageCargo();
            if (!drained.isEmpty() && !insertCargo(drained)) {
                terminal.insertCargo(drained);
            }
        }
        terminalsImported = true;
        setChanged();
    }

    private List<StorageGroup> getVisibleStorageGroups() {
        List<StorageGroupAccumulator> groups = new ArrayList<>();
        for (int slot = 0; slot < storage.size(); slot++) {
            ItemStack stack = storage.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            StorageGroupAccumulator target = null;
            for (StorageGroupAccumulator group : groups) {
                if (ItemStack.isSameItemSameTags(group.template, stack)) {
                    target = group;
                    break;
                }
            }
            if (target == null) {
                target = new StorageGroupAccumulator(stack.copy());
                groups.add(target);
            }
            target.totalCount += stack.getCount();
            target.slots.add(slot);
        }
        List<StorageGroup> visible = new ArrayList<>(groups.size());
        for (StorageGroupAccumulator group : groups) {
            ItemStack display = group.template.copy();
            display.setCount(group.totalCount);
            visible.add(new StorageGroup(display, group.totalCount));
        }
        return visible;
    }

    private void mergeIntoStorage(ItemStack remaining, NonNullList<ItemStack> targetStorage) {
        for (int i = 0; i < targetStorage.size() && !remaining.isEmpty(); i++) {
            ItemStack slot = targetStorage.get(i);
            if (slot.isEmpty() || !ItemStack.isSameItemSameTags(slot, remaining)) {
                continue;
            }
            int limit = Math.min(slot.getMaxStackSize(), 64);
            int canMove = Math.min(limit - slot.getCount(), remaining.getCount());
            if (canMove <= 0) {
                continue;
            }
            slot.grow(canMove);
            remaining.shrink(canMove);
        }
        for (int i = 0; i < targetStorage.size() && !remaining.isEmpty(); i++) {
            if (!targetStorage.get(i).isEmpty()) {
                continue;
            }
            int toMove = Math.min(remaining.getMaxStackSize(), remaining.getCount());
            ItemStack moved = remaining.copy();
            moved.setCount(toMove);
            targetStorage.set(i, moved);
            remaining.shrink(toMove);
        }
    }

    private static String fallbackTownName(String id) {
        if (id == null || id.isBlank()) {
            return "Warehouse";
        }
        return id.length() > 24 ? id.substring(0, 24) + "..." : id;
    }

    private static String clampUtf(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("TownId", getTownId());
        tag.putString("TownName", getTownName());
        tag.putBoolean("TerminalsImported", terminalsImported);
        CompoundTag storageTag = new CompoundTag();
        net.minecraft.world.ContainerHelper.saveAllItems(storageTag, storage);
        tag.put("Storage", storageTag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        townId = tag.getString("TownId");
        townName = tag.getString("TownName");
        terminalsImported = tag.getBoolean("TerminalsImported");
        storage.clear();
        net.minecraft.world.ContainerHelper.loadAllItems(tag.getCompound("Storage"), storage);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("screen.sailboatmod.town_warehouse.title", getTownName());
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new WarehouseMenu(containerId, playerInventory, this);
    }

    @Override
    public int getContainerSize() {
        return storage.size();
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : storage) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < storage.size() ? storage.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot < 0 || slot >= storage.size() || amount <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack removed = net.minecraft.world.ContainerHelper.removeItem(storage, slot, amount);
        if (!removed.isEmpty() && !getTownId().isBlank()) {
            TownStockpileService.removeItemStack(level, getTownId(), removed, removed.getCount());
        }
        if (!removed.isEmpty()) {
            setChanged();
        }
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot < 0 || slot >= storage.size()) {
            return ItemStack.EMPTY;
        }
        ItemStack removed = net.minecraft.world.ContainerHelper.takeItem(storage, slot);
        if (!removed.isEmpty() && !getTownId().isBlank()) {
            TownStockpileService.removeItemStack(level, getTownId(), removed, removed.getCount());
        }
        if (!removed.isEmpty()) {
            setChanged();
        }
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= storage.size()) {
            return;
        }
        ItemStack previous = storage.get(slot).copy();
        storage.set(slot, stack == null ? ItemStack.EMPTY : stack);
        if (!getTownId().isBlank()) {
            if (!previous.isEmpty()) {
                TownStockpileService.removeItemStack(level, getTownId(), previous, previous.getCount());
            }
            if (stack != null && !stack.isEmpty()) {
                TownStockpileService.addItemStack(level, getTownId(), stack, stack.getCount());
            }
        }
        if (stack != null && !stack.isEmpty()) {
            stack.setCount(Math.min(stack.getCount(), getMaxStackSize()));
        }
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return player != null
                && level != null
                && level.getBlockEntity(worldPosition) == this
                && player.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D, worldPosition.getZ() + 0.5D) <= 64.0D;
    }

    @Override
    public void clearContent() {
        if (!getTownId().isBlank()) {
            for (ItemStack stack : storage) {
                if (!stack.isEmpty()) {
                    TownStockpileService.removeItemStack(level, getTownId(), stack, stack.getCount());
                }
            }
        }
        storage.clear();
        while (storage.size() < STORAGE_SIZE) {
            storage.add(ItemStack.EMPTY);
        }
        setChanged();
    }

    private record StorageGroup(ItemStack displayStack, int totalCount) {
    }

    private static final class StorageGroupAccumulator {
        private final ItemStack template;
        private final List<Integer> slots = new ArrayList<>();
        private int totalCount;

        private StorageGroupAccumulator(ItemStack template) {
            this.template = template;
        }
    }
}
