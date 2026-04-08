package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.dock.DockRegistry;
import com.monpai.sailboatmod.dock.PostStationRegistry;
import com.monpai.sailboatmod.dock.TownWarehouseRegistry;
import com.monpai.sailboatmod.menu.WarehouseMenu;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.model.TownStockpileRecord;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.nation.service.TownStockpileService;
import com.monpai.sailboatmod.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class TownWarehouseBlockEntity extends BlockEntity implements MenuProvider {
    public static final int STORAGE_SIZE = 54;

    private static final String TAG_PLAYER_STORAGE = "PlayerStorage";
    private static final String TAG_PLAYER_UUID = "PlayerUuid";
    private static final String TAG_ITEMS = "Items";
    private static final String TAG_LEGACY_STORAGE = "Storage";

    private final Map<UUID, NonNullList<ItemStack>> playerStorage = new LinkedHashMap<>();
    private final NonNullList<ItemStack> legacySharedStorage = NonNullList.withSize(STORAGE_SIZE, ItemStack.EMPTY);

    private String townId = "";
    private String townName = "";
    private boolean terminalsImported = false;
    private boolean legacyStorageMigrated = true;

    public TownWarehouseBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TOWN_WAREHOUSE_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            resolveTownBinding();
            migrateLegacyStorageToStockpile();
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
        return town != null && TownService.canManageTown(player, NationSavedData.get(level), town);
    }

    public boolean canAccessWarehouse(@Nullable Player player) {
        if (player == null || level == null) {
            return false;
        }
        TownRecord town = TownService.getTownAt(level, worldPosition);
        if (town == null) {
            return false;
        }
        if (player.getUUID().equals(town.mayorUuid())) {
            return true;
        }
        if (level.isClientSide) {
            return true;
        }
        if (player instanceof ServerPlayer serverPlayer && TownService.canManageTown(serverPlayer, NationSavedData.get(level), town)) {
            return true;
        }
        if (town.nationId().isBlank()) {
            return false;
        }
        NationMemberRecord member = NationSavedData.get(level).getMember(player.getUUID());
        return member != null && town.nationId().equalsIgnoreCase(member.nationId());
    }

    public Container createPersonalContainer(Player player) {
        UUID ownerId = player == null ? new UUID(0L, 0L) : player.getUUID();
        return new PersonalWarehouseContainer(ownerId);
    }

    public List<String> getVisibleStorageLines() {
        List<String> lines = new ArrayList<>();
        for (StorageGroup group : getVisibleStorageGroups()) {
            String itemName = group.displayName();
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
        ItemStack stack = groups.get(visibleIndex).displayStack().copy();
        stack.setCount(groups.get(visibleIndex).totalCount());
        return stack;
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
        if (sample == null || sample.isEmpty() || level == null || getTownId().isBlank()) {
            return 0;
        }
        return TownStockpileService.getAvailable(level, getTownId(), commodityKey(sample));
    }

    public boolean extractMatchingStock(ItemStack sample, int quantity) {
        if (sample == null || sample.isEmpty() || quantity <= 0 || level == null || getTownId().isBlank()) {
            return false;
        }
        boolean removed = TownStockpileService.removeItemStack(level, getTownId(), sample, quantity);
        if (removed) {
            setChanged();
        }
        return removed;
    }

    public boolean canInsertCargo(List<ItemStack> cargo) {
        return level != null && !getTownId().isBlank();
    }

    public boolean insertCargo(List<ItemStack> cargo) {
        if (level == null || level.isClientSide || getTownId().isBlank() || cargo == null || cargo.isEmpty()) {
            return false;
        }
        TownStockpileService.addCargo(level, getTownId(), cargo);
        setChanged();
        return true;
    }

    private void migrateLegacyStorageToStockpile() {
        if (legacyStorageMigrated || level == null || level.isClientSide) {
            return;
        }
        if (getTownId().isBlank()) {
            return;
        }
        List<ItemStack> legacyCargo = new ArrayList<>();
        for (ItemStack stack : legacySharedStorage) {
            if (!stack.isEmpty()) {
                legacyCargo.add(stack.copy());
            }
        }
        if (!legacyCargo.isEmpty()) {
            TownStockpileService.addCargo(level, getTownId(), legacyCargo);
        }
        clearLegacyStorage();
        legacyStorageMigrated = true;
        setChanged();
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
            List<ItemStack> drained = terminal.drainAllStorageCargo();
            if (!drained.isEmpty()) {
                TownStockpileService.addCargo(level, currentTownId, drained);
            }
        }
        terminalsImported = true;
        setChanged();
    }

    private NonNullList<ItemStack> getOrCreatePlayerStorage(UUID playerId) {
        return playerStorage.computeIfAbsent(playerId, ignored -> NonNullList.withSize(STORAGE_SIZE, ItemStack.EMPTY));
    }

    private List<StorageGroup> getVisibleStorageGroups() {
        if (level == null || getTownId().isBlank()) {
            return List.of();
        }
        TownStockpileRecord stockpile = TownStockpileService.getStockpile(level, getTownId());
        List<StorageGroup> groups = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : stockpile.commodityAmounts().entrySet()) {
            int amount = Math.max(0, entry.getValue() == null ? 0 : entry.getValue());
            if (amount <= 0) {
                continue;
            }
            ItemStack sample = sampleStack(entry.getKey(), amount);
            String displayName = sample.isEmpty() ? entry.getKey() : sample.getHoverName().getString();
            groups.add(new StorageGroup(sample, displayName, amount));
        }
        groups.sort((left, right) -> Integer.compare(right.totalCount(), left.totalCount()));
        return groups;
    }

    private ItemStack sampleStack(String commodityKey, int amount) {
        ResourceLocation key = ResourceLocation.tryParse(commodityKey == null ? "" : commodityKey.trim().toLowerCase(Locale.ROOT));
        if (key == null) {
            return ItemStack.EMPTY;
        }
        Item item = ForgeRegistries.ITEMS.getValue(key);
        if (item == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = item.getDefaultInstance();
        stack.setCount(Math.max(1, Math.min(amount, stack.getMaxStackSize())));
        return stack;
    }

    private static String commodityKey(ItemStack stack) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key == null ? "" : key.toString();
    }

    private void clearLegacyStorage() {
        legacySharedStorage.clear();
        while (legacySharedStorage.size() < STORAGE_SIZE) {
            legacySharedStorage.add(ItemStack.EMPTY);
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
        tag.putBoolean("LegacyStorageMigrated", legacyStorageMigrated);

        ListTag players = new ListTag();
        for (Map.Entry<UUID, NonNullList<ItemStack>> entry : playerStorage.entrySet()) {
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID(TAG_PLAYER_UUID, entry.getKey());
            CompoundTag itemsTag = new CompoundTag();
            ContainerHelper.saveAllItems(itemsTag, entry.getValue());
            playerTag.put(TAG_ITEMS, itemsTag);
            players.add(playerTag);
        }
        tag.put(TAG_PLAYER_STORAGE, players);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        townId = tag.getString("TownId");
        townName = tag.getString("TownName");
        terminalsImported = tag.getBoolean("TerminalsImported");
        legacyStorageMigrated = !tag.contains(TAG_LEGACY_STORAGE, Tag.TAG_COMPOUND) || tag.getBoolean("LegacyStorageMigrated");

        playerStorage.clear();
        ListTag players = tag.getList(TAG_PLAYER_STORAGE, Tag.TAG_COMPOUND);
        for (Tag raw : players) {
            if (!(raw instanceof CompoundTag playerTag) || !playerTag.hasUUID(TAG_PLAYER_UUID)) {
                continue;
            }
            UUID playerId = playerTag.getUUID(TAG_PLAYER_UUID);
            NonNullList<ItemStack> storage = NonNullList.withSize(STORAGE_SIZE, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(playerTag.getCompound(TAG_ITEMS), storage);
            playerStorage.put(playerId, storage);
        }

        clearLegacyStorage();
        if (tag.contains(TAG_LEGACY_STORAGE, Tag.TAG_COMPOUND)) {
            ContainerHelper.loadAllItems(tag.getCompound(TAG_LEGACY_STORAGE), legacySharedStorage);
            legacyStorageMigrated = false;
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("screen.sailboatmod.town_warehouse.title", getTownName());
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new WarehouseMenu(containerId, playerInventory, this, createPersonalContainer(player));
    }

    public boolean stillValid(Player player) {
        return player != null
                && level != null
                && level.getBlockEntity(worldPosition) == this
                && player.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D, worldPosition.getZ() + 0.5D) <= 64.0D;
    }

    private final class PersonalWarehouseContainer implements Container {
        private final UUID ownerId;

        private PersonalWarehouseContainer(UUID ownerId) {
            this.ownerId = ownerId;
        }

        @Override
        public int getContainerSize() {
            return STORAGE_SIZE;
        }

        @Override
        public boolean isEmpty() {
            for (ItemStack stack : getOrCreatePlayerStorage(ownerId)) {
                if (!stack.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            NonNullList<ItemStack> storage = getOrCreatePlayerStorage(ownerId);
            return slot >= 0 && slot < storage.size() ? storage.get(slot) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            if (slot < 0 || amount <= 0) {
                return ItemStack.EMPTY;
            }
            NonNullList<ItemStack> storage = getOrCreatePlayerStorage(ownerId);
            if (slot >= storage.size()) {
                return ItemStack.EMPTY;
            }
            ItemStack removed = ContainerHelper.removeItem(storage, slot, amount);
            if (!removed.isEmpty()) {
                setChanged();
            }
            return removed;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            NonNullList<ItemStack> storage = getOrCreatePlayerStorage(ownerId);
            if (slot < 0 || slot >= storage.size()) {
                return ItemStack.EMPTY;
            }
            ItemStack removed = ContainerHelper.takeItem(storage, slot);
            if (!removed.isEmpty()) {
                setChanged();
            }
            return removed;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            NonNullList<ItemStack> storage = getOrCreatePlayerStorage(ownerId);
            if (slot < 0 || slot >= storage.size()) {
                return;
            }
            ItemStack safeStack = stack == null ? ItemStack.EMPTY : stack.copy();
            if (!safeStack.isEmpty()) {
                safeStack.setCount(Math.min(safeStack.getCount(), getMaxStackSize()));
            }
            storage.set(slot, safeStack);
            setChanged();
        }

        @Override
        public void setChanged() {
            TownWarehouseBlockEntity.this.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return TownWarehouseBlockEntity.this.stillValid(player);
        }

        @Override
        public void clearContent() {
            NonNullList<ItemStack> storage = getOrCreatePlayerStorage(ownerId);
            storage.clear();
            while (storage.size() < STORAGE_SIZE) {
                storage.add(ItemStack.EMPTY);
            }
            setChanged();
        }
    }

    private record StorageGroup(ItemStack displayStack, String displayName, int totalCount) {
    }
}
