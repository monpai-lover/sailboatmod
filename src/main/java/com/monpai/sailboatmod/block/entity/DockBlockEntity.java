package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.dock.DockRegistry;
import com.monpai.sailboatmod.integration.bluemap.BlueMapIntegration;
import com.monpai.sailboatmod.dock.DockScreenData;
import com.monpai.sailboatmod.economy.GoldStandardEconomy;
import com.monpai.sailboatmod.entity.CarriageEntity;
import com.monpai.sailboatmod.entity.SailboatEntity;
import com.monpai.sailboatmod.item.RouteBookItem;
import com.monpai.sailboatmod.item.TransportRouteBook;
import com.monpai.sailboatmod.market.MarketListing;
import com.monpai.sailboatmod.market.MarketSavedData;
import com.monpai.sailboatmod.market.ProcurementService;
import com.monpai.sailboatmod.market.PurchaseOrder;
import com.monpai.sailboatmod.market.ShipmentManifestEntry;
import com.monpai.sailboatmod.market.ShippingOrder;
import com.monpai.sailboatmod.market.commodity.CommodityKeyResolver;
import com.monpai.sailboatmod.nation.service.DockTownResolver;
import com.monpai.sailboatmod.nation.service.TownDeliveryService;
import com.monpai.sailboatmod.nation.service.TownStockpileService;
import com.monpai.sailboatmod.menu.DockMenu;
import com.monpai.sailboatmod.registry.ModBlockEntities;
import com.monpai.sailboatmod.route.RouteDefinition;
import com.monpai.sailboatmod.route.RouteNbtUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Collections;

public class DockBlockEntity extends BlockEntity implements MenuProvider {
    private static final double ASSIGN_RADIUS = 64.0D;
    private static final int MAX_WAYBILLS = 64;
    public static final int STORAGE_SIZE = 27;
    public static final int ZONE_HALF_X = 12;
    public static final int ZONE_HALF_Z = 8;
    public static final int MINIMAP_RADIUS = 50;
    private final List<RouteDefinition> routes = new ArrayList<>();
    private final List<WaybillEntry> waybills = new ArrayList<>();
    private final Map<UUID, Integer> assignments = new HashMap<>();
    private final NonNullList<ItemStack> storage = NonNullList.withSize(STORAGE_SIZE, ItemStack.EMPTY);
    private ItemStack routeBook = ItemStack.EMPTY;
    private String dockName = "";
    private String ownerName = "";
    private String ownerUuid = "";
    private String townId = "";
    private String nationId = "";
    private int zoneMinX = -ZONE_HALF_X;
    private int zoneMaxX = ZONE_HALF_X;
    private int zoneMinZ = -ZONE_HALF_Z;
    private int zoneMaxZ = ZONE_HALF_Z;
    private int selectedRouteIndex = 0;
    private int selectedBoatIndex = 0;
    private int selectedStorageIndex = 0;
    private int selectedWaybillIndex = 0;
    private boolean nonOrderAutoReturnEnabled = false;
    private boolean nonOrderAutoUnloadEnabled = false;

    public DockBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.DOCK_BLOCK_ENTITY.get(), pos, state);
    }

    protected DockBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            DockTownResolver.resolve(level, worldPosition);
            registerFacility(level, worldPosition);
            syncFacilityMarkers();
        }
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (level != null && !level.isClientSide) {
            syncFacilityMarkers();
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            unregisterFacility(level, worldPosition);
        }
        super.setRemoved();
    }

    protected void registerFacility(Level level, BlockPos pos) {
        DockRegistry.register(level, pos);
    }

    protected void unregisterFacility(Level level, BlockPos pos) {
        DockRegistry.unregister(level, pos);
    }

    protected void syncFacilityMarkers() {
        BlueMapIntegration.syncDock(this);
    }

    public void setRoutes(List<RouteDefinition> newRoutes, int activeIndex) {
        routes.clear();
        for (RouteDefinition route : newRoutes) {
            routes.add(route.copy());
        }
        selectedRouteIndex = routes.isEmpty() ? 0 : Mth.clamp(activeIndex, 0, routes.size() - 1);
        setChanged();
    }

    protected List<RouteDefinition> availableRoutes() {
        return routes.stream().map(RouteDefinition::copy).toList();
    }

    public String getDockName() {
        if (dockName == null || dockName.isBlank()) {
            return ownerName != null && !ownerName.isBlank() ? ownerName + defaultFacilityNameSuffix() : defaultFacilityName();
        }
        return dockName;
    }

    protected String defaultFacilityName() {
        return "Dock";
    }

    protected String defaultFacilityNameSuffix() {
        return "'s Dock";
    }

    public void setDockName(String name) {
        dockName = name == null ? "" : name.trim();
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    public void initializeOwnerIfAbsent(Player player) {
        if (level == null || level.isClientSide || player == null) {
            return;
        }
        if (ownerUuid != null && !ownerUuid.isBlank()) {
            return;
        }
        ownerUuid = player.getUUID().toString();
        ownerName = player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName();
        setChanged();
    }

    public String getOwnerName() {
        return ownerName == null || ownerName.isBlank() ? "-" : ownerName;
    }

    public String getOwnerUuid() {
        return ownerUuid == null ? "" : ownerUuid;
    }

    public String getTownId() {
        return townId == null ? "" : townId;
    }

    public void setTownId(String id) {
        this.townId = id == null ? "" : id;
        setChanged();
    }

    public String getNationId() {
        return nationId == null ? "" : nationId;
    }

    public void setNationId(String id) {
        this.nationId = id == null ? "" : id;
        setChanged();
    }

    public boolean canManageDock(@Nullable Player player) {
        if (player == null) {
            return false;
        }
        return canManageDock(player.getUUID().toString());
    }

    public boolean canManageDock(String playerUuid) {
        String currentOwner = getOwnerUuid();
        String safePlayerUuid = playerUuid == null ? "" : playerUuid.trim();
        return !currentOwner.isBlank() && currentOwner.equals(safePlayerUuid);
    }

    public boolean setDockZone(int minX, int maxX, int minZ, int maxZ) {
        int clampedMinX = Mth.clamp(Math.min(minX, maxX), -MINIMAP_RADIUS, MINIMAP_RADIUS);
        int clampedMaxX = Mth.clamp(Math.max(minX, maxX), -MINIMAP_RADIUS, MINIMAP_RADIUS);
        int clampedMinZ = Mth.clamp(Math.min(minZ, maxZ), -MINIMAP_RADIUS, MINIMAP_RADIUS);
        int clampedMaxZ = Mth.clamp(Math.max(minZ, maxZ), -MINIMAP_RADIUS, MINIMAP_RADIUS);
        if (clampedMaxX - clampedMinX < 2 || clampedMaxZ - clampedMinZ < 2) {
            return false;
        }
        if (level == null || !isValidFacilityZone(level, clampedMinX, clampedMaxX, clampedMinZ, clampedMaxZ)) {
            return false;
        }
        zoneMinX = clampedMinX;
        zoneMaxX = clampedMaxX;
        zoneMinZ = clampedMinZ;
        zoneMaxZ = clampedMaxZ;
        setChanged();
        return true;
    }

    protected boolean isValidFacilityZone(Level level, int minX, int maxX, int minZ, int maxZ) {
        return isZoneMostlyWater(level, minX, maxX, minZ, maxZ);
    }

    protected boolean acceptsRouteBook(ItemStack stack) {
        return stack.getItem() instanceof TransportRouteBook;
    }

    protected boolean supportsTransportEntity(SailboatEntity entity) {
        return !(entity instanceof CarriageEntity);
    }

    protected String noAssignableTransportTranslationKey() {
        return "block.sailboatmod.dock.no_target";
    }

    protected String noRouteTranslationKey() {
        return "block.sailboatmod.dock.no_route";
    }

    protected String transportNotReadyTranslationKey() {
        return "screen.sailboatmod.dock.boat_not_ready";
    }

    protected String transportCargoFullTranslationKey() {
        return "screen.sailboatmod.dock.error.boat_cargo_full";
    }

    protected String transportLoadFailedTranslationKey() {
        return "screen.sailboatmod.dock.error.boat_load_failed";
    }

    public boolean loadRouteBookFromPlayer(Player player) {
        ItemStack held = player.getMainHandItem();
        if (!acceptsRouteBook(held)) {
            held = player.getOffhandItem();
        }
        if (!acceptsRouteBook(held)) {
            return false;
        }
        routeBook = held.copy();
        routeBook.setCount(1);
        setChanged();
        return true;
    }

    public boolean setRouteBookFromInventorySlot(Player player, int inventorySlot) {
        if (inventorySlot < 0 || inventorySlot >= player.getInventory().items.size()) {
            return false;
        }
        ItemStack stack = player.getInventory().items.get(inventorySlot);
        if (!acceptsRouteBook(stack)) {
            return false;
        }
        routeBook = stack.copy();
        routeBook.setCount(1);
        setChanged();
        return true;
    }

    public void setRouteBookStack(ItemStack stack) {
        routeBook = stack.copy();
        routeBook.setCount(1);
        setChanged();
    }

    public int importRouteBook() {
        if (routeBook.isEmpty()) {
            return 0;
        }
        List<RouteDefinition> imported = RouteBookItem.getRoutes(routeBook);
        int added = 0;
        for (RouteDefinition route : imported) {
            if (route.waypoints().size() < 2) {
                continue;
            }
            if (isDuplicateRoute(route)) {
                continue;
            }
            routes.add(route.copy());
            added++;
        }
        if (added > 0) {
            if (routes.size() == added) {
                selectedRouteIndex = 0;
            } else {
                selectedRouteIndex = routes.size() - added;
            }
            setChanged();
        }
        return added;
    }

    public boolean deleteSelectedRoute() {
        if (routes.isEmpty()) {
            return false;
        }
        int idx = Mth.clamp(selectedRouteIndex, 0, routes.size() - 1);
        routes.remove(idx);
        if (routes.isEmpty()) {
            selectedRouteIndex = 0;
        } else {
            selectedRouteIndex = Mth.clamp(selectedRouteIndex, 0, routes.size() - 1);
        }
        setChanged();
        return true;
    }

    public boolean loadRouteBookFromInventorySlot(Player player, int inventorySlot) {
        return setRouteBookFromInventorySlot(player, inventorySlot);
    }

    public void clearRouteBook() {
        routeBook = ItemStack.EMPTY;
        setChanged();
    }

    public int selectRouteDelta(int delta) {
        int size = availableRoutes().size();
        if (size == 0) {
            selectedRouteIndex = 0;
            return selectedRouteIndex;
        }
        selectedRouteIndex = (selectedRouteIndex + delta % size + size) % size;
        setChanged();
        return selectedRouteIndex;
    }

    public int selectRouteIndex(int index) {
        int size = availableRoutes().size();
        if (size == 0) {
            selectedRouteIndex = 0;
            return selectedRouteIndex;
        }
        selectedRouteIndex = Mth.clamp(index, 0, size - 1);
        setChanged();
        return selectedRouteIndex;
    }

    public boolean reverseSelectedRoute() {
        if (routes.isEmpty()) {
            return false;
        }
        int idx = Mth.clamp(selectedRouteIndex, 0, routes.size() - 1);
        RouteDefinition route = routes.get(idx);
        List<Vec3> reversed = new ArrayList<>(route.waypoints());
        Collections.reverse(reversed);
        String name = route.name();
        if (name == null || name.isBlank()) {
            name = "Route-" + (idx + 1);
        }
        final String revSuffix = " (REV)";
        if (name.endsWith(revSuffix)) {
            name = name.substring(0, name.length() - revSuffix.length());
        } else {
            name = name + revSuffix;
        }
        routes.set(idx, new RouteDefinition(
                name,
                reversed,
                route.authorName(),
                route.authorUuid(),
                route.createdAtEpochMillis(),
                route.routeLengthMeters(),
                route.endDockName(),
                route.startDockName()
        ));
        setChanged();
        return true;
    }

    public int selectBoatDelta(int delta, Player player) {
        List<SailboatEntity> boats = getNearbySailboats(player);
        if (boats.isEmpty()) {
            selectedBoatIndex = 0;
            return selectedBoatIndex;
        }
        int size = boats.size();
        selectedBoatIndex = (selectedBoatIndex + delta % size + size) % size;
        setChanged();
        return selectedBoatIndex;
    }

    public int selectBoatIndex(int index, Player player) {
        List<SailboatEntity> boats = getNearbySailboats(player);
        if (boats.isEmpty()) {
            selectedBoatIndex = 0;
            return selectedBoatIndex;
        }
        selectedBoatIndex = Mth.clamp(index, 0, boats.size() - 1);
        setChanged();
        return selectedBoatIndex;
    }

    public int selectStorageIndex(int index, Player player) {
        if (!canManageDock(player)) {
            selectedStorageIndex = 0;
            return 0;
        }
        List<StorageGroup> visibleGroups = getVisibleStorageGroups();
        if (visibleGroups.isEmpty()) {
            selectedStorageIndex = 0;
            return 0;
        }
        selectedStorageIndex = Mth.clamp(index, 0, visibleGroups.size() - 1);
        setChanged();
        return selectedStorageIndex;
    }

    public boolean assignSelectedBoat(Player player, boolean autoStart) {
        List<SailboatEntity> boats = getNearbySailboats(player);
        if (boats.isEmpty()) {
            player.displayClientMessage(Component.translatable(noAssignableTransportTranslationKey()), true);
            return false;
        }
        int idx = Mth.clamp(selectedBoatIndex, 0, boats.size() - 1);
        SailboatEntity boat = boats.get(idx);
        boat.setAllowNonOrderAutoReturn(nonOrderAutoReturnEnabled);
        boat.setAllowNonOrderAutoUnload(nonOrderAutoUnloadEnabled);
        boolean assigned = assignLoadedBoatToRouteIndex(boat, Mth.clamp(selectedRouteIndex, 0, availableRoutes().size() - 1), autoStart, player);
        if (!assigned) {
            boat.setAllowNonOrderAutoReturn(false);
            boat.setAllowNonOrderAutoUnload(false);
        }
        return assigned;
    }

    public boolean dispatchSelectedStorage(Player player) {
        if (!canManageDock(player)) {
            player.displayClientMessage(Component.translatable("screen.sailboatmod.dock.storage_owner_only"), true);
            return false;
        }
        List<StorageGroup> visibleGroups = getVisibleStorageGroups();
        if (visibleGroups.isEmpty()) {
            player.displayClientMessage(Component.translatable("screen.sailboatmod.dock.storage_empty"), true);
            return false;
        }
        int safeStorageIndex = Mth.clamp(selectedStorageIndex, 0, visibleGroups.size() - 1);
        selectedStorageIndex = safeStorageIndex;
        StorageGroup group = visibleGroups.get(safeStorageIndex);
        ItemStack stack = group.displayStack();
        if (stack.isEmpty() || group.totalCount() <= 0) {
            player.displayClientMessage(Component.translatable("screen.sailboatmod.dock.storage_empty"), true);
            return false;
        }
        List<SailboatEntity> boats = getAvailableSailboatsForDispatch(player);
        if (boats.isEmpty()) {
            player.displayClientMessage(Component.translatable(noAssignableTransportTranslationKey()), true);
            return false;
        }
        List<RouteDefinition> availableRoutes = availableRoutes();
        if (availableRoutes.isEmpty()) {
            player.displayClientMessage(Component.translatable(noRouteTranslationKey()), true);
            return false;
        }
        int safeBoatIndex = Mth.clamp(selectedBoatIndex, 0, boats.size() - 1);
        int safeRouteIndex = Mth.clamp(selectedRouteIndex, 0, availableRoutes.size() - 1);
        SailboatEntity boat = boats.get(safeBoatIndex);
        List<ItemStack> cargo = splitCargo(stack, group.totalCount());
        if (!boat.canLoadCargo(cargo)) {
            player.displayClientMessage(Component.translatable(transportCargoFullTranslationKey()), true);
            return false;
        }
        List<ItemStack> removed = removeVisibleStorageGroup(safeStorageIndex);
        if (removed.isEmpty()) {
            player.displayClientMessage(Component.translatable("screen.sailboatmod.dock.storage_empty"), true);
            return false;
        }
        cargo = removed.stream().map(ItemStack::copy).toList();
        if (!boat.loadCargo(cargo)) {
            insertCargo(removed);
            player.displayClientMessage(Component.translatable(transportLoadFailedTranslationKey()), true);
            return false;
        }
        boat.setPendingMarketDelivery(
                player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName(),
                player.getUUID().toString(),
                "",
                ""
        );
        boat.setAllowNonOrderAutoReturn(nonOrderAutoReturnEnabled);
        boat.setAllowNonOrderAutoUnload(nonOrderAutoUnloadEnabled);
        if (!assignLoadedBoatToRouteIndex(boat, safeRouteIndex, true, player)) {
            insertCargo(boat.unloadAllCargo());
            boat.clearPendingMarketDelivery();
            boat.setAllowNonOrderAutoReturn(false);
            boat.setAllowNonOrderAutoUnload(false);
            return false;
        }
        setChanged();
        return true;
    }

    public boolean isNonOrderAutoReturnEnabled() {
        return nonOrderAutoReturnEnabled;
    }

    public void toggleNonOrderAutoReturn() {
        nonOrderAutoReturnEnabled = !nonOrderAutoReturnEnabled;
        setChanged();
    }

    public boolean isNonOrderAutoUnloadEnabled() {
        return nonOrderAutoUnloadEnabled;
    }

    public void toggleNonOrderAutoUnload() {
        nonOrderAutoUnloadEnabled = !nonOrderAutoUnloadEnabled;
        setChanged();
    }

    public boolean takeSelectedStorage(Player player) {
        if (!canManageDock(player)) {
            player.displayClientMessage(Component.translatable("screen.sailboatmod.dock.storage_owner_only"), true);
            return false;
        }
        List<StorageGroup> visibleGroups = getVisibleStorageGroups();
        if (visibleGroups.isEmpty()) {
            player.displayClientMessage(Component.translatable("screen.sailboatmod.dock.storage_empty"), true);
            return false;
        }
        int safeStorageIndex = Mth.clamp(selectedStorageIndex, 0, visibleGroups.size() - 1);
        selectedStorageIndex = safeStorageIndex;
        List<ItemStack> removedStacks = removeVisibleStorageGroup(safeStorageIndex);
        if (removedStacks.isEmpty()) {
            player.displayClientMessage(Component.translatable("screen.sailboatmod.dock.storage_empty"), true);
            return false;
        }
        for (ItemStack removed : removedStacks) {
            ItemStack remaining = removed.copy();
            boolean added = player.getInventory().add(remaining);
            if (!added || !remaining.isEmpty()) {
                player.drop(remaining, false);
            }
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        setChanged();
        return true;
    }

    public boolean assignBoat(SailboatEntity sailboat, boolean autoStart) {
        return assignBoatToRouteIndex(sailboat, Mth.clamp(selectedRouteIndex, 0, availableRoutes().size() - 1), autoStart, null);
    }

    public boolean assignBoatToRouteIndex(SailboatEntity sailboat, int routeIndex, boolean autoStart, @Nullable Player operator) {
        return assignBoatToRouteIndex(sailboat, routeIndex, autoStart, operator, false);
    }

    public boolean assignLoadedBoatToRouteIndex(SailboatEntity sailboat, int routeIndex, boolean autoStart, @Nullable Player operator) {
        return assignBoatToRouteIndex(sailboat, routeIndex, autoStart, operator, true);
    }

    private boolean assignBoatToRouteIndex(SailboatEntity sailboat, int routeIndex, boolean autoStart,
                                           @Nullable Player operator, boolean allowCargo) {
        List<RouteDefinition> availableRoutes = availableRoutes();
        if (availableRoutes.isEmpty()) {
            if (operator != null) {
                operator.displayClientMessage(Component.translatable(noRouteTranslationKey()), true);
            }
            return false;
        }
        if (sailboat == null || !sailboat.isAlive() || !isInsideDockZone(sailboat.position()) || sailboat.isAutopilotActive() || (!allowCargo && sailboat.hasCargo())) {
            if (operator != null) {
                operator.displayClientMessage(Component.translatable(transportNotReadyTranslationKey()), true);
            }
            return false;
        }
        int safeRouteIndex = Mth.clamp(routeIndex, 0, availableRoutes.size() - 1);
        if (operator != null && !isBoatOwnedBy(sailboat, operator) && !sailboat.isAvailableForRent()) {
            operator.displayClientMessage(Component.translatable("block.sailboatmod.dock.not_for_rent", sailboat.getName()), true);
            return false;
        }
        int rentalFee = Math.max(0, sailboat.getRentalPrice());
        boolean chargedRental = false;
        if (operator != null && rentalFee > 0 && !isBoatOwnedBy(sailboat, operator)) {
            if (!chargeRentalFee(operator, rentalFee)) {
                operator.displayClientMessage(Component.translatable(
                        "block.sailboatmod.dock.rent_not_enough",
                        sailboat.getName(),
                        rentalFee
                ), true);
                return false;
            }
            operator.displayClientMessage(Component.translatable(
                    "block.sailboatmod.dock.rent_paid",
                    sailboat.getName(),
                    rentalFee
            ), true);
            chargedRental = true;
        }
        assignments.put(sailboat.getUUID(), safeRouteIndex);
        sailboat.setRouteCatalog(availableRoutes, safeRouteIndex, worldPosition);
        sailboat.setPendingShipper(operator != null ? operator.getName().getString() : null);
        if (autoStart) {
            boolean started = sailboat.startAutopilotFromRouteStart();
            if (!started && operator != null) {
                if (chargedRental) {
                    refundRentalFee(operator, rentalFee);
                }
                operator.displayClientMessage(Component.translatable("screen.sailboatmod.route_start_need_zone"), true);
                assignments.remove(sailboat.getUUID());
                setChanged();
                return false;
            }
        }
        setChanged();
        return true;
    }

    public List<SailboatEntity> getAssignableSailboats(Player player) {
        return getNearbySailboats(player);
    }

    public List<SailboatEntity> getAvailableSailboatsForDispatch(Player player) {
        return getNearbySailboats(player).stream()
                .filter(boat -> isBoatAvailableForDispatch(boat, player))
                .toList();
    }

    public int findRouteIndexByEndDockName(String dockName) {
        return findRouteIndexByDestinationDock(null, dockName);
    }

    public int findRouteIndexByDestinationDock(@Nullable BlockPos dockPos, @Nullable String dockName) {
        if (dockPos != null) {
            List<RouteDefinition> availableRoutes = availableRoutes();
            for (int i = 0; i < availableRoutes.size(); i++) {
                BlockPos routeDockPos = findRouteEndDockPos(availableRoutes.get(i));
                if (dockPos.equals(routeDockPos)) {
                    return i;
                }
            }
        }
        if (dockName == null || dockName.isBlank()) {
            return -1;
        }
        List<RouteDefinition> availableRoutes = availableRoutes();
        for (int i = 0; i < availableRoutes.size(); i++) {
            RouteDefinition route = availableRoutes.get(i);
            if (dockName.equalsIgnoreCase(route.endDockName())) {
                return i;
            }
        }
        return -1;
    }

    @Nullable
    private BlockPos findRouteEndDockPos(RouteDefinition route) {
        if (level == null || route == null || route.waypoints().isEmpty()) {
            return null;
        }
        Vec3 endWaypoint = route.waypoints().get(route.waypoints().size() - 1);
        BlockPos exact = findDockZoneContains(level, endWaypoint);
        if (exact != null) {
            return exact;
        }
        return findNearestRegisteredDock(level, endWaypoint, 256.0D);
    }

    public String getRouteName(int routeIndex) {
        List<RouteDefinition> availableRoutes = availableRoutes();
        if (availableRoutes.isEmpty()) {
            return "-";
        }
        int safeIndex = Mth.clamp(routeIndex, 0, availableRoutes.size() - 1);
        String name = availableRoutes.get(safeIndex).name();
        return name == null || name.isBlank() ? "Route-" + (safeIndex + 1) : name;
    }

    public DockScreenData buildScreenData(Player player) {
        boolean canManageDock = canManageDock(player);
        List<RouteDefinition> availableRoutes = availableRoutes();
        List<String> routeNames = new ArrayList<>();
        List<String> routeMetas = new ArrayList<>();
        List<Vec3> selectedPoints = List.of();
        for (int i = 0; i < availableRoutes.size(); i++) {
            RouteDefinition route = availableRoutes.get(i);
            String name = route.name();
            if (name == null || name.isBlank()) {
                name = "Route-" + (i + 1);
            }
            routeNames.add(name + " (" + route.waypoints().size() + ")");
            String author = route.authorName() == null || route.authorName().isBlank() ? "-" : route.authorName();
            String time = route.createdAtEpochMillis() <= 0L
                    ? "-"
                    : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(route.createdAtEpochMillis()));
            routeMetas.add(String.format(Locale.ROOT, "Len %.0fm | By %s | %s", route.routeLengthMeters(), author, time));
        }
        if (!availableRoutes.isEmpty()) {
            int selected = Mth.clamp(selectedRouteIndex, 0, availableRoutes.size() - 1);
            selectedPoints = List.copyOf(availableRoutes.get(selected).waypoints());
        }

        List<SailboatEntity> boats = getNearbySailboats(player);
        List<Integer> boatIds = new ArrayList<>();
        List<String> boatNames = new ArrayList<>();
        List<Vec3> boatPositions = new ArrayList<>();
        for (SailboatEntity boat : boats) {
            boatIds.add(boat.getId());
            boatNames.add(buildBoatDisplayName(boat, player));
            boatPositions.add(boat.position());
        }
        int safeBoatIndex = boats.isEmpty() ? 0 : Mth.clamp(selectedBoatIndex, 0, boats.size() - 1);
        selectedBoatIndex = safeBoatIndex;
        List<StorageGroup> visibleStorageGroups = canManageDock ? getVisibleStorageGroups() : List.of();
        List<String> storageLines = canManageDock ? getVisibleStorageLines(player) : List.of();
        int safeStorageIndex = visibleStorageGroups.isEmpty() ? 0 : Mth.clamp(selectedStorageIndex, 0, visibleStorageGroups.size() - 1);
        selectedStorageIndex = safeStorageIndex;
        int safeWaybillIndex = waybills.isEmpty() ? 0 : Mth.clamp(selectedWaybillIndex, 0, waybills.size() - 1);
        selectedWaybillIndex = safeWaybillIndex;

        List<String> waybillNames = new ArrayList<>();
        for (int i = 0; i < waybills.size(); i++) {
            WaybillEntry entry = waybills.get(i);
            String stamp = entry.departureEpochMillis <= 0L
                    ? "-"
                    : new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date(entry.departureEpochMillis));
            waybillNames.add(clampUtf(entry.waybillName + " | " + stamp + " | " + entry.cargo.size() + " items", 180));
        }
        List<String> waybillInfoLines = List.of();
        List<String> waybillCargoLines = List.of();
        if (!waybills.isEmpty()) {
            WaybillEntry selectedWaybill = waybills.get(safeWaybillIndex);
            waybillInfoLines = selectedWaybill.toInfoLines().stream().map(line -> clampUtf(line, 180)).toList();
            waybillCargoLines = selectedWaybill.toCargoLines().stream().map(line -> clampUtf(line, 150)).toList();
        }

        return new DockScreenData(
                worldPosition,
                getDockName(),
                getOwnerName(),
                getOwnerUuid(),
                canManageDock,
                nonOrderAutoReturnEnabled,
                nonOrderAutoUnloadEnabled,
                routeBook.copy(),
                routeNames,
                routeMetas,
                availableRoutes.isEmpty() ? 0 : Mth.clamp(selectedRouteIndex, 0, availableRoutes.size() - 1),
                selectedPoints,
                zoneMinX,
                zoneMaxX,
                zoneMinZ,
                zoneMaxZ,
                boatIds,
                boatNames,
                boatPositions,
                safeBoatIndex,
                storageLines,
                safeStorageIndex,
                waybillNames,
                safeWaybillIndex,
                waybillInfoLines,
                waybillCargoLines
        );
    }

    public int selectWaybillIndex(int index) {
        if (waybills.isEmpty()) {
            selectedWaybillIndex = 0;
            return 0;
        }
        selectedWaybillIndex = Mth.clamp(index, 0, waybills.size() - 1);
        setChanged();
        return selectedWaybillIndex;
    }

    public boolean claimSelectedWaybill(Player player) {
        if (waybills.isEmpty()) {
            selectedWaybillIndex = 0;
            return false;
        }
        int idx = Mth.clamp(selectedWaybillIndex, 0, waybills.size() - 1);
        WaybillEntry removed = waybills.get(idx);
        if (!removed.canClaim(player)) {
            player.displayClientMessage(Component.translatable(
                    "block.sailboatmod.dock.waybill_claim_denied",
                    removed.recipientNameOrFallback()
            ), true);
            return false;
        }
        String stockpileTownId = resolvedTownId();
        if (!stockpileTownId.isBlank() && !TownStockpileService.canRemoveCargo(level, stockpileTownId, removed.cargo)) {
            player.displayClientMessage(Component.translatable("screen.sailboatmod.dock.error.stockpile_shipment_missing"), true);
            return false;
        }
        waybills.remove(idx);
        if (!stockpileTownId.isBlank()) {
            TownStockpileService.removeCargo(level, stockpileTownId, removed.cargo);
        }
        for (ItemStack cargo : removed.cargo) {
            if (cargo.isEmpty()) {
                continue;
            }
            ItemStack left = cargo.copy();
            player.getInventory().add(left);
            if (!left.isEmpty()) {
                player.drop(left, false);
            }
        }
        markClaimedOrders(removed);
        selectedWaybillIndex = waybills.isEmpty() ? 0 : Mth.clamp(idx, 0, waybills.size() - 1);
        setChanged();
        return true;
    }

    public void receiveShipment(
            @Nullable SailboatEntity sailboat,
            String routeName,
            String shipperName,
            String startDockName,
            String endDockName,
            long departureEpochMillis,
            long elapsedMillis,
            double distanceMeters,
            List<ItemStack> cargo
    ) {
        receiveShipment(
                sailboat,
                routeName,
                shipperName,
                startDockName,
                endDockName,
                departureEpochMillis,
                elapsedMillis,
                distanceMeters,
                cargo,
                List.of()
        );
    }

    public void receiveShipment(
            @Nullable SailboatEntity sailboat,
            String routeName,
            String shipperName,
            String startDockName,
            String endDockName,
            long departureEpochMillis,
            long elapsedMillis,
            double distanceMeters,
            List<ItemStack> cargo,
            List<ShipmentManifestEntry> manifest
    ) {
        List<ItemStack> packed = copyCargo(cargo);
        if (packed.isEmpty()) {
            return;
        }
        List<ShipmentManifestEntry> safeManifest = manifest == null ? List.of() : manifest.stream()
                .filter(entry -> entry != null)
                .toList();
        if (safeManifest.isEmpty()) {
            TownDeliveryService.deliverDockArrival(level, worldPosition, townId, packed, safeManifest);
            appendWaybillEntry(sailboat, routeName, shipperName, startDockName, endDockName,
                    departureEpochMillis, elapsedMillis, distanceMeters, packed,
                    "", "", "", "");
            return;
        }

        List<ItemStack> cargoPool = copyCargo(packed);
        for (int i = 0; i < safeManifest.size(); i++) {
            ShipmentManifestEntry entry = safeManifest.get(i);
            List<ItemStack> entryCargo;
            if (entry.quantity() > 0 && entry.itemStack() != null && !entry.itemStack().isEmpty()) {
                entryCargo = extractMatchingCargo(cargoPool, entry.itemStack(), entry.quantity());
            } else if (i == safeManifest.size() - 1) {
                entryCargo = copyCargo(cargoPool);
                cargoPool.clear();
            } else {
                entryCargo = List.of();
            }
            if (entryCargo.isEmpty() && i == safeManifest.size() - 1 && !cargoPool.isEmpty()) {
                entryCargo = copyCargo(cargoPool);
                cargoPool.clear();
            }
            if (entryCargo.isEmpty()) {
                continue;
            }
            if (tryDeliverManifestEntryToWarehouse(entryCargo, entry)) {
                continue;
            }
            TownDeliveryService.deliverDockArrival(level, worldPosition, townId, entryCargo, List.of(entry));
            appendWaybillEntry(sailboat, routeName, shipperName, startDockName, endDockName,
                    departureEpochMillis, elapsedMillis, distanceMeters, entryCargo,
                    entry.recipientName(), entry.recipientUuid(), entry.purchaseOrderId(), entry.shippingOrderId());
        }
        if (!cargoPool.isEmpty()) {
            TownDeliveryService.deliverDockArrival(level, worldPosition, townId, cargoPool, List.of());
            appendWaybillEntry(sailboat, routeName, shipperName, startDockName, endDockName,
                    departureEpochMillis, elapsedMillis, distanceMeters, cargoPool,
                    "", "", "", "");
        }
    }

    private boolean tryDeliverManifestEntryToWarehouse(List<ItemStack> cargo, ShipmentManifestEntry entry) {
        if (level == null || level.isClientSide || cargo == null || cargo.isEmpty() || entry == null || !entry.isMarketOrder()) {
            return false;
        }
        MarketSavedData market = MarketSavedData.get(level);
        PurchaseOrder order = market.getPurchaseOrder(entry.purchaseOrderId());
        if (order == null) {
            return false;
        }
        if (!(level.getBlockEntity(order.targetDockPos()) instanceof TownWarehouseBlockEntity warehouse)) {
            return false;
        }
        if (!warehouse.insertCargo(cargo)) {
            return false;
        }
        int deliveredQuantity = 0;
        for (ItemStack stack : cargo) {
            deliveredQuantity += stack.getCount();
        }
        int resolvedQuantity = Math.max(deliveredQuantity, entry.quantity());
        market.putPurchaseOrder(new PurchaseOrder(
                order.orderId(),
                order.listingId(),
                order.buyerUuid(),
                order.buyerName(),
                order.quantity(),
                order.totalPrice(),
                order.sourceDockPos(),
                order.sourceDockName(),
                order.targetDockPos(),
                order.targetDockName(),
                "CLAIMED"
        ));
        if (entry.shippingOrderId() != null && !entry.shippingOrderId().isBlank()) {
            ShippingOrder shippingOrder = market.getShippingOrder(entry.shippingOrderId());
            if (shippingOrder != null) {
                market.putShippingOrder(new ShippingOrder(
                        shippingOrder.shippingOrderId(),
                        shippingOrder.purchaseOrderId(),
                        shippingOrder.shipperUuid(),
                        shippingOrder.shipperName(),
                        shippingOrder.boatUuid(),
                        shippingOrder.boatName(),
                        shippingOrder.boatMode(),
                        shippingOrder.transportMode(),
                        shippingOrder.routeName(),
                        shippingOrder.sourceDockPos(),
                        shippingOrder.sourceDockName(),
                        shippingOrder.targetDockPos(),
                        shippingOrder.targetDockName(),
                        shippingOrder.sourceTerminalName(),
                        shippingOrder.targetTerminalName(),
                        shippingOrder.distanceMeters(),
                        shippingOrder.etaSeconds(),
                        shippingOrder.rentalFee(),
                        "DELIVERED"
                ));
            }
        }
        ProcurementService.markDeliveredByOrder(
                level,
                order.orderId(),
                entry.shippingOrderId(),
                warehouse.getTownId(),
                CommodityKeyResolver.resolve(entry.itemStack()),
                resolvedQuantity
        );
        return true;
    }

    private void appendWaybillEntry(
            @Nullable SailboatEntity sailboat,
            String routeName,
            String shipperName,
            String startDockName,
            String endDockName,
            long departureEpochMillis,
            long elapsedMillis,
            double distanceMeters,
            List<ItemStack> cargo,
            String recipientName,
            String recipientUuid,
            String purchaseOrderId,
            String shippingOrderId
    ) {
        List<ItemStack> packed = copyCargo(cargo);
        if (packed.isEmpty()) {
            return;
        }

        String safeRoute = sanitize(routeName, "-");
        String safeBoat = sailboat == null ? "Direct" : sanitize(sailboat.getName().getString(), "Sailboat");
        String safeShipper = sanitize(shipperName, "-");
        String safeStart = sanitize(startDockName, "-");
        String safeEnd = sanitize(endDockName, getDockName());
        String safeRecipientName = sanitize(recipientName, "");
        String safeRecipientUuid = sanitize(recipientUuid, "");
        String safePurchaseOrderId = sanitize(purchaseOrderId, "");
        String safeShippingOrderId = sanitize(shippingOrderId, "");
        long safeDeparture = departureEpochMillis > 0L ? departureEpochMillis : System.currentTimeMillis();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date(safeDeparture));
        String waybillName = clampUtf(safeRoute + "+" + safeBoat + "+" + safeShipper + "+" + safeStart + "->" + safeEnd + "+" + stamp, 170);

        waybills.add(new WaybillEntry(
                waybillName,
                safeRoute,
                safeBoat,
                safeShipper,
                safeStart,
                safeEnd,
                safeDeparture,
                Math.max(0L, elapsedMillis),
                Math.max(0.0D, distanceMeters),
                packed,
                safeRecipientName,
                safeRecipientUuid,
                safePurchaseOrderId,
                safeShippingOrderId
        ));
        if (waybills.size() > MAX_WAYBILLS) {
            int overflow = waybills.size() - MAX_WAYBILLS;
            for (int i = 0; i < overflow; i++) {
                waybills.remove(0);
            }
        }
        markArrivedOrders(safePurchaseOrderId, safeShippingOrderId);
        selectedWaybillIndex = Math.max(0, waybills.size() - 1);
        setChanged();
    }

    private static List<ItemStack> copyCargo(List<ItemStack> cargo) {
        List<ItemStack> packed = new ArrayList<>();
        if (cargo == null) {
            return packed;
        }
        for (ItemStack stack : cargo) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            packed.add(stack.copy());
        }
        return packed;
    }

    private static List<ItemStack> extractMatchingCargo(List<ItemStack> pool, ItemStack template, int quantity) {
        List<ItemStack> extracted = new ArrayList<>();
        if (pool == null || template == null || template.isEmpty() || quantity <= 0) {
            return extracted;
        }
        int remaining = quantity;
        for (ItemStack stack : pool) {
            if (remaining <= 0) {
                break;
            }
            if (stack.isEmpty() || !ItemStack.isSameItemSameTags(stack, template)) {
                continue;
            }
            int take = Math.min(stack.getCount(), remaining);
            if (take <= 0) {
                continue;
            }
            ItemStack moved = stack.copy();
            moved.setCount(take);
            extracted.add(moved);
            stack.shrink(take);
            remaining -= take;
        }
        pool.removeIf(ItemStack::isEmpty);
        return extracted;
    }

    public int getStorageSize() {
        return storage.size();
    }

    public ItemStack getStorageItem(int slot) {
        if (slot < 0 || slot >= storage.size()) {
            return ItemStack.EMPTY;
        }
        return storage.get(slot);
    }

    public void setStorageItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= storage.size()) {
            return;
        }
        ItemStack previous = storage.get(slot).copy();
        storage.set(slot, stack);
        String stockpileTownId = resolvedTownId();
        if (!stockpileTownId.isBlank()) {
            if (!previous.isEmpty()) {
                TownStockpileService.removeItemStack(level, stockpileTownId, previous, previous.getCount());
            }
            if (stack != null && !stack.isEmpty()) {
                TownStockpileService.addItemStack(level, stockpileTownId, stack, stack.getCount());
            }
        }
        setChanged();
    }

    public ItemStack removeStorageItem(int slot, int amount) {
        if (slot < 0 || slot >= storage.size() || amount <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack removed = net.minecraft.world.ContainerHelper.removeItem(storage, slot, amount);
        if (!removed.isEmpty()) {
            String stockpileTownId = resolvedTownId();
            if (!stockpileTownId.isBlank()) {
                TownStockpileService.removeItemStack(level, stockpileTownId, removed, removed.getCount());
            }
            setChanged();
        }
        return removed;
    }

    public ItemStack removeStorageItemNoUpdate(int slot) {
        if (slot < 0 || slot >= storage.size()) {
            return ItemStack.EMPTY;
        }
        ItemStack removed = net.minecraft.world.ContainerHelper.takeItem(storage, slot);
        if (!removed.isEmpty()) {
            String stockpileTownId = resolvedTownId();
            if (!stockpileTownId.isBlank()) {
                TownStockpileService.removeItemStack(level, stockpileTownId, removed, removed.getCount());
            }
            setChanged();
        }
        return removed;
    }

    public void storageChanged() {
        setChanged();
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
        String stockpileTownId = resolvedTownId();
        if (!stockpileTownId.isBlank()) {
            TownStockpileService.removeItemStack(level, stockpileTownId, sample, quantity);
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
        String stockpileTownId = resolvedTownId();
        if (!stockpileTownId.isBlank()) {
            TownStockpileService.addCargo(level, stockpileTownId, cargo);
        }
        setChanged();
        return true;
    }

    public List<ItemStack> copyAllStorageCargo() {
        List<ItemStack> cargo = new ArrayList<>();
        for (ItemStack stack : storage) {
            if (!stack.isEmpty()) {
                cargo.add(stack.copy());
            }
        }
        return cargo;
    }

    public List<ItemStack> drainAllStorageCargo() {
        List<ItemStack> cargo = new ArrayList<>();
        for (int i = 0; i < storage.size(); i++) {
            ItemStack removed = removeStorageItemNoUpdate(i);
            if (!removed.isEmpty()) {
                cargo.add(removed);
            }
        }
        return cargo;
    }

    public List<String> getVisibleStorageLines(Player player) {
        if (!canManageDock(player)) {
            return List.of();
        }
        List<StorageGroup> visibleGroups = getVisibleStorageGroups();
        List<String> lines = new ArrayList<>(visibleGroups.size());
        for (StorageGroup group : visibleGroups) {
            String itemName = group.displayStack().isEmpty() ? "-" : group.displayStack().getHoverName().getString();
            lines.add(clampUtf(itemName + " x" + group.totalCount(), 150));
        }
        return lines;
    }

    public int getVisibleStorageCount(Player player) {
        return canManageDock(player) ? getVisibleStorageGroups().size() : 0;
    }

    public int getVisibleStorageCount(String playerUuid) {
        return canManageDock(playerUuid) ? getVisibleStorageGroups().size() : 0;
    }

    public ItemStack getStorageItemForVisibleIndex(Player player, int visibleIndex) {
        if (!canManageDock(player)) {
            return ItemStack.EMPTY;
        }
        List<StorageGroup> visibleGroups = getVisibleStorageGroups();
        if (visibleIndex < 0 || visibleIndex >= visibleGroups.size()) {
            return ItemStack.EMPTY;
        }
        return visibleGroups.get(visibleIndex).displayStack().copy();
    }

    public ItemStack getStorageItemForVisibleIndex(String playerUuid, int visibleIndex) {
        if (!canManageDock(playerUuid)) {
            return ItemStack.EMPTY;
        }
        List<StorageGroup> visibleGroups = getVisibleStorageGroups();
        if (visibleIndex < 0 || visibleIndex >= visibleGroups.size()) {
            return ItemStack.EMPTY;
        }
        return visibleGroups.get(visibleIndex).displayStack().copy();
    }

    public boolean extractVisibleStorage(Player player, int visibleIndex, int quantity) {
        ItemStack stack = getStorageItemForVisibleIndex(player, visibleIndex);
        if (stack.isEmpty()) {
            return false;
        }
        int amount = Math.max(1, Math.min(quantity, countMatchingStock(stack)));
        return extractMatchingStock(stack, amount);
    }

    public boolean extractVisibleStorage(String playerUuid, int visibleIndex, int quantity) {
        ItemStack stack = getStorageItemForVisibleIndex(playerUuid, visibleIndex);
        if (stack.isEmpty()) {
            return false;
        }
        int amount = Math.max(1, Math.min(quantity, countMatchingStock(stack)));
        return extractMatchingStock(stack, amount);
    }

    public boolean tryLoadReturnCargo(SailboatEntity boat, @Nullable BlockPos targetDockPos,
                                      @Nullable String buyerUuid, @Nullable String buyerName) {
        if (level == null || level.isClientSide || boat == null || targetDockPos == null) {
            return false;
        }
        String safeBuyerUuid = buyerUuid == null ? "" : buyerUuid.trim();
        if (safeBuyerUuid.isBlank()) {
            return false;
        }
        MarketSavedData market = MarketSavedData.get(level);
        List<PurchaseOrder> candidates = market.getOpenOrdersForSourceDock(worldPosition, targetDockPos, safeBuyerUuid);
        List<ItemStack> cargo = new ArrayList<>();
        List<ReturnLoadSelection> selections = new ArrayList<>();
        for (PurchaseOrder order : candidates) {
            MarketListing listing = market.getListing(order.listingId());
            if (listing == null) {
                continue;
            }
            int shippableQuantity = findMaxLoadableQuantity(boat, cargo, listing.itemStack(), order.quantity());
            if (shippableQuantity <= 0) {
                if (!cargo.isEmpty()) {
                    break;
                }
                continue;
            }
            PurchaseSplit split = splitOrderForShipment(market, order, shippableQuantity);
            if (split == null) {
                continue;
            }
            cargo.addAll(splitCargo(listing.itemStack(), split.shipped().quantity()));
            selections.add(new ReturnLoadSelection(split.shipped(), split.remainder(), listing));
            if (shippableQuantity < order.quantity()) {
                break;
            }
        }
        if (cargo.isEmpty() || selections.isEmpty() || !boat.canLoadCargo(cargo) || !boat.loadCargo(cargo)) {
            return false;
        }

        List<ShipmentManifestEntry> manifest = new ArrayList<>();
        Map<String, Integer> listingReservationDeltas = new HashMap<>();
        for (ReturnLoadSelection selection : selections) {
            String shippingOrderId = market.nextId();
            PurchaseOrder order = selection.dispatchOrder();
            manifest.add(new ShipmentManifestEntry(
                    selection.listing().listingId(),
                    selection.listing().itemStack(),
                    order.orderId(),
                    shippingOrderId,
                    order.buyerUuid(),
                    order.buyerName(),
                    order.quantity()
            ));
            listingReservationDeltas.merge(selection.listing().listingId(), order.quantity(), Integer::sum);
            market.putPurchaseOrder(new PurchaseOrder(
                    order.orderId(),
                    order.listingId(),
                    order.buyerUuid(),
                    order.buyerName(),
                    order.quantity(),
                    order.totalPrice(),
                    order.sourceDockPos(),
                    order.sourceDockName(),
                    order.targetDockPos(),
                    order.targetDockName(),
                    "IN_TRANSIT"
            ));
            if (selection.remainderOrder() != null) {
                market.putPurchaseOrder(selection.remainderOrder());
            }
            market.putShippingOrder(new ShippingOrder(
                    shippingOrderId,
                    order.orderId(),
                    boat.getOwnerUuid(),
                    boat.getOwnerName(),
                    boat.getUUID().toString(),
                    boat.getName().getString(),
                    "OWN",
                    this instanceof PostStationBlockEntity ? "POST_STATION" : "PORT",
                    getDockName() + " Return",
                    order.sourceDockPos(),
                    order.sourceDockName(),
                    order.targetDockPos(),
                    order.targetDockName(),
                    order.sourceDockName(),
                    order.targetDockName(),
                    0,
                    0,
                    0,
                    "SAILING"
            ));
            ProcurementService.markInTransit(level, order.orderId(), shippingOrderId);
        }
        applyListingReservationDeltas(market, listingReservationDeltas);
        boat.setPendingShipmentManifest(manifest);
        return true;
    }

    private List<Integer> getVisibleStorageSlots() {
        List<Integer> visible = new ArrayList<>();
        for (int i = 0; i < storage.size(); i++) {
            if (!storage.get(i).isEmpty()) {
                visible.add(i);
            }
        }
        return visible;
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
            target.slots.add(slot);
            target.totalCount += stack.getCount();
        }

        List<StorageGroup> visibleGroups = new ArrayList<>(groups.size());
        for (StorageGroupAccumulator group : groups) {
            ItemStack displayStack = group.template.copy();
            displayStack.setCount(group.totalCount);
            visibleGroups.add(new StorageGroup(displayStack, List.copyOf(group.slots), group.totalCount));
        }
        return visibleGroups;
    }

    private List<ItemStack> removeVisibleStorageGroup(int visibleIndex) {
        List<StorageGroup> groups = getVisibleStorageGroups();
        if (visibleIndex < 0 || visibleIndex >= groups.size()) {
            return List.of();
        }
        List<ItemStack> removed = new ArrayList<>();
        for (int slot : groups.get(visibleIndex).slots()) {
            ItemStack stack = removeStorageItemNoUpdate(slot);
            if (!stack.isEmpty()) {
                removed.add(stack);
            }
        }
        return removed;
    }

    private record StorageGroup(ItemStack displayStack, List<Integer> slots, int totalCount) {
    }

    private static final class StorageGroupAccumulator {
        private final ItemStack template;
        private final List<Integer> slots = new ArrayList<>();
        private int totalCount;

        private StorageGroupAccumulator(ItemStack template) {
            this.template = template;
        }
    }

    private boolean isBoatAvailableForDispatch(SailboatEntity boat, @Nullable Player player) {
        return boat != null
                && boat.isAlive()
                && isInsideDockZone(boat.position())
                && !boat.isAutopilotActive()
                && !boat.hasCargo()
                && (player == null || isBoatOwnedBy(boat, player) || boat.isAvailableForRent());
    }

    private static List<ItemStack> splitCargo(ItemStack template, int quantity) {
        List<ItemStack> cargo = new ArrayList<>();
        if (template == null || template.isEmpty() || quantity <= 0) {
            return cargo;
        }
        int remaining = quantity;
        int stackSize = Math.max(1, template.getMaxStackSize());
        while (remaining > 0) {
            int amount = Math.min(stackSize, remaining);
            ItemStack stack = template.copy();
            stack.setCount(amount);
            cargo.add(stack);
            remaining -= amount;
        }
        return cargo;
    }

    private int findMaxLoadableQuantity(SailboatEntity boat, List<ItemStack> currentCargo, ItemStack template, int maxQuantity) {
        if (boat == null || template == null || template.isEmpty() || maxQuantity <= 0) {
            return 0;
        }
        int low = 0;
        int high = maxQuantity;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            List<ItemStack> candidate = new ArrayList<>(currentCargo);
            candidate.addAll(splitCargo(template, mid));
            if (boat.canLoadCargo(candidate)) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    @Nullable
    private PurchaseSplit splitOrderForShipment(MarketSavedData market, PurchaseOrder order, int shippedQuantity) {
        if (market == null || order == null || shippedQuantity <= 0) {
            return null;
        }
        if (shippedQuantity >= order.quantity()) {
            return new PurchaseSplit(order, null);
        }
        int shippedTotal = order.totalPrice() * shippedQuantity / Math.max(1, order.quantity());
        int remainderQuantity = order.quantity() - shippedQuantity;
        int remainderTotal = Math.max(0, order.totalPrice() - shippedTotal);
        PurchaseOrder shipped = new PurchaseOrder(
                order.orderId(),
                order.listingId(),
                order.buyerUuid(),
                order.buyerName(),
                shippedQuantity,
                shippedTotal,
                order.sourceDockPos(),
                order.sourceDockName(),
                order.targetDockPos(),
                order.targetDockName(),
                order.status()
        );
        PurchaseOrder remainder = new PurchaseOrder(
                market.nextId(),
                order.listingId(),
                order.buyerUuid(),
                order.buyerName(),
                remainderQuantity,
                remainderTotal,
                order.sourceDockPos(),
                order.sourceDockName(),
                order.targetDockPos(),
                order.targetDockName(),
                "WAITING_SHIPMENT"
        );
        return new PurchaseSplit(shipped, remainder);
    }

    private void applyListingReservationDeltas(MarketSavedData market, Map<String, Integer> listingReservationDeltas) {
        for (Map.Entry<String, Integer> entry : listingReservationDeltas.entrySet()) {
            MarketListing listing = market.getListing(entry.getKey());
            if (listing == null) {
                continue;
            }
            int nextReserved = Math.max(0, listing.reservedCount() - Math.max(0, entry.getValue()));
            if (listing.availableCount() <= 0 && nextReserved <= 0) {
                market.removeListing(listing.listingId());
                continue;
            }
            market.putListing(new MarketListing(
                    listing.listingId(),
                    listing.sellerUuid(),
                    listing.sellerName(),
                    listing.itemStack(),
                    listing.unitPrice(),
                    listing.availableCount(),
                    nextReserved,
                    listing.sourceDockPos(),
                    listing.sourceDockName(),
                    listing.townId(),
                    listing.nationId(),
                    listing.priceAdjustmentBp(),
                    listing.sellerNote()
            ));
        }
    }

    private record ReturnLoadSelection(PurchaseOrder dispatchOrder, @Nullable PurchaseOrder remainderOrder,
                                       MarketListing listing) {
    }

    private record PurchaseSplit(PurchaseOrder shipped, @Nullable PurchaseOrder remainder) {
    }

    private static String sanitize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String clampUtf(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (maxChars <= 0 || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }

    public static boolean isInsideDockZone(BlockPos dockPos, Vec3 point) {
        return Math.abs(point.x - (dockPos.getX() + 0.5D)) <= ZONE_HALF_X && Math.abs(point.z - (dockPos.getZ() + 0.5D)) <= ZONE_HALF_Z;
    }

    public static boolean isInsideDockZone(Level level, BlockPos dockPos, Vec3 point) {
        if (level.getBlockEntity(dockPos) instanceof DockBlockEntity dock) {
            return dock.isInsideDockZone(point);
        }
        return isInsideDockZone(dockPos, point);
    }

    public boolean isInsideDockZone(Vec3 point) {
        double dx = point.x - (worldPosition.getX() + 0.5D);
        double dz = point.z - (worldPosition.getZ() + 0.5D);
        return dx >= zoneMinX && dx <= zoneMaxX && dz >= zoneMinZ && dz <= zoneMaxZ;
    }

    public int getZoneMinX() {
        return zoneMinX;
    }

    public int getZoneMaxX() {
        return zoneMaxX;
    }

    public int getZoneMinZ() {
        return zoneMinZ;
    }

    public int getZoneMaxZ() {
        return zoneMaxZ;
    }

    public static BlockPos findDockZoneContains(Level level, Vec3 point) {
        Set<BlockPos> docks = DockRegistry.get(level);
        BlockPos nearest = null;
        double nearestSq = Double.MAX_VALUE;
        for (BlockPos dockPos : docks) {
            if (!(level.getBlockEntity(dockPos) instanceof DockBlockEntity dock)) {
                continue;
            }
            if (!dock.isInsideDockZone(point)) {
                continue;
            }
            double dx = point.x - (dockPos.getX() + 0.5D);
            double dz = point.z - (dockPos.getZ() + 0.5D);
            double distSq = dx * dx + dz * dz;
            if (distSq < nearestSq) {
                nearestSq = distSq;
                nearest = dockPos.immutable();
            }
        }
        return nearest;
    }

    @Nullable
    public static BlockPos findNearestRegisteredDock(Level level, Vec3 point, double maxDistance) {
        Set<BlockPos> docks = DockRegistry.get(level);
        if (docks.isEmpty()) {
            return null;
        }
        double maxSq = maxDistance <= 0.0D ? Double.MAX_VALUE : maxDistance * maxDistance;
        BlockPos nearest = null;
        double nearestSq = Double.MAX_VALUE;
        for (BlockPos dockPos : docks) {
            double dx = point.x - (dockPos.getX() + 0.5D);
            double dz = point.z - (dockPos.getZ() + 0.5D);
            double distSq = dx * dx + dz * dz;
            if (distSq > maxSq || distSq >= nearestSq) {
                continue;
            }
            nearestSq = distSq;
            nearest = dockPos.immutable();
        }
        return nearest;
    }

    private boolean isZoneMostlyWater(Level level, int minX, int maxX, int minZ, int maxZ) {
        int water = 0;
        int total = 0;
        for (int ox = minX; ox <= maxX; ox += 2) {
            for (int oz = minZ; oz <= maxZ; oz += 2) {
                int x = worldPosition.getX() + ox;
                int z = worldPosition.getZ() + oz;
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                if (y >= level.getMinBuildHeight()) {
                    total++;
                    if (level.getFluidState(new BlockPos(x, y, z)).is(net.minecraft.tags.FluidTags.WATER)) {
                        water++;
                    }
                }
            }
        }
        return total > 0 && water * 100 / total >= 60;
    }

    public static String getDockDisplayName(Level level, BlockPos dockPos) {
        if (level.getBlockEntity(dockPos) instanceof DockBlockEntity dock) {
            return dock.getDockName();
        }
        return "Dock-" + dockPos.getX() + "," + dockPos.getZ();
    }

    private boolean isDuplicateRoute(RouteDefinition incoming) {
        for (RouteDefinition existing : routes) {
            if (sameRouteShape(existing, incoming)) {
                return true;
            }
        }
        return false;
    }

    private boolean sameRouteShape(RouteDefinition a, RouteDefinition b) {
        if (a.waypoints().size() != b.waypoints().size()) {
            return false;
        }
        for (int i = 0; i < a.waypoints().size(); i++) {
            Vec3 pa = a.waypoints().get(i);
            Vec3 pb = b.waypoints().get(i);
            if (Math.abs(pa.x - pb.x) > 0.01D || Math.abs(pa.z - pb.z) > 0.01D) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal(getDockName());
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new DockMenu(containerId, playerInventory, worldPosition);
    }

    private List<SailboatEntity> getNearbySailboats(Player player) {
        if (level == null) {
            return List.of();
        }
        AABB search = new AABB(worldPosition).inflate(ASSIGN_RADIUS);
        Comparator<SailboatEntity> comparator = Comparator
                .comparing((SailboatEntity boat) -> isBoatOwnedBy(boat, player) ? 0 : 1);
        if (player != null) {
            comparator = comparator.thenComparingDouble(player::distanceToSqr);
        } else {
            comparator = comparator.thenComparingInt(Entity::getId);
        }
        return level.getEntitiesOfClass(SailboatEntity.class, search).stream()
                .filter(Entity::isAlive)
                .filter(this::supportsTransportEntity)
                .filter(boat -> isInsideDockZone(boat.position()))
                .sorted(comparator)
                .toList();
    }

    private String buildBoatDisplayName(SailboatEntity boat, Player viewer) {
        String boatName = boat.getName().getString();
        String route = boat.isAutopilotActive() ? boat.getAutopilotRouteName() : boat.getSelectedRouteName();
        if (route == null || route.isBlank()) {
            route = "-";
        }
        String state;
        if (!boat.isAutopilotActive()) {
            state = "IDLE";
        } else if (boat.isAutopilotPaused()) {
            state = "PAUSED";
        } else {
            state = "RUN";
        }
        String rentState = boat.isAvailableForRent() ? Integer.toString(boat.getRentalPrice()) : "OFF";
        String ownership = isBoatOwnedBy(boat, viewer)
                ? ("OWN | RENT " + rentState)
                : ("RENT " + rentState);
        return boatName + " | " + route + " | " + state + " | " + ownership;
    }

    private static boolean isBoatOwnedBy(SailboatEntity boat, Player player) {
        if (boat == null || player == null) {
            return false;
        }
        String boatOwner = boat.getOwnerUuid();
        return boatOwner != null && !boatOwner.isBlank() && boatOwner.equals(player.getUUID().toString());
    }

    private static boolean chargeRentalFee(Player player, int rentalFee) {
        if (player == null || rentalFee <= 0 || player.getAbilities().instabuild) {
            return true;
        }
        return Boolean.TRUE.equals(GoldStandardEconomy.tryWithdraw(player, rentalFee));
    }

    private static void refundRentalFee(Player player, int amount) {
        if (player == null || amount <= 0) {
            return;
        }
        GoldStandardEconomy.tryDeposit(player, amount);
    }

    public String getSelectedRouteName() {
        if (routes.isEmpty()) {
            return "-";
        }
        int routeIndex = Mth.clamp(selectedRouteIndex, 0, routes.size() - 1);
        String name = routes.get(routeIndex).name();
        return name.isBlank() ? "Route-" + (routeIndex + 1) : name;
    }

    public int getRouteCount() {
        return routes.size();
    }

    public List<RouteDefinition> getRoutesForMap() {
        return routes.stream()
                .map(RouteDefinition::copy)
                .toList();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        RouteNbtUtil.writeRoutes(tag, "DockRoutes", routes);
        tag.putInt("SelectedRouteIndex", selectedRouteIndex);
        tag.putInt("SelectedBoatIndex", selectedBoatIndex);
        tag.putInt("SelectedStorageIndex", selectedStorageIndex);
        tag.putInt("SelectedWaybillIndex", selectedWaybillIndex);
        tag.putString("OwnerName", ownerName == null ? "" : ownerName);
        tag.putString("OwnerUuid", ownerUuid == null ? "" : ownerUuid);
        tag.putString("TownId", townId == null ? "" : townId);
        tag.putString("NationId", nationId == null ? "" : nationId);
        if (!routeBook.isEmpty()) {
            tag.put("RouteBook", routeBook.save(new CompoundTag()));
        }
        tag.putString("DockName", dockName == null ? "" : dockName);
        tag.putBoolean("NonOrderAutoReturn", nonOrderAutoReturnEnabled);
        tag.putBoolean("NonOrderAutoUnload", nonOrderAutoUnloadEnabled);
        tag.putInt("ZoneMinX", zoneMinX);
        tag.putInt("ZoneMaxX", zoneMaxX);
        tag.putInt("ZoneMinZ", zoneMinZ);
        tag.putInt("ZoneMaxZ", zoneMaxZ);
        CompoundTag storageTag = new CompoundTag();
        net.minecraft.world.ContainerHelper.saveAllItems(storageTag, storage);
        tag.put("Storage", storageTag);
        CompoundTag assignmentsTag = new CompoundTag();
        for (Map.Entry<UUID, Integer> entry : assignments.entrySet()) {
            assignmentsTag.putInt(entry.getKey().toString(), entry.getValue());
        }
        tag.put("Assignments", assignmentsTag);
        ListTag waybillTag = new ListTag();
        for (WaybillEntry waybill : waybills) {
            waybillTag.add(waybill.save());
        }
        tag.put("Waybills", waybillTag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        routes.clear();
        routes.addAll(RouteNbtUtil.readRoutes(tag, "DockRoutes"));
        selectedRouteIndex = routes.isEmpty() ? 0 : Mth.clamp(tag.getInt("SelectedRouteIndex"), 0, routes.size() - 1);
        selectedBoatIndex = Math.max(0, tag.getInt("SelectedBoatIndex"));
        selectedStorageIndex = Math.max(0, tag.getInt("SelectedStorageIndex"));
        selectedWaybillIndex = Math.max(0, tag.getInt("SelectedWaybillIndex"));
        ownerName = tag.getString("OwnerName");
        ownerUuid = tag.getString("OwnerUuid");
        townId = tag.contains("TownId") ? tag.getString("TownId") : "";
        nationId = tag.contains("NationId") ? tag.getString("NationId") : "";
        routeBook = tag.contains("RouteBook") ? ItemStack.of(tag.getCompound("RouteBook")) : ItemStack.EMPTY;
        dockName = tag.getString("DockName");
        nonOrderAutoReturnEnabled = tag.getBoolean("NonOrderAutoReturn");
        nonOrderAutoUnloadEnabled = tag.getBoolean("NonOrderAutoUnload");
        zoneMinX = tag.contains("ZoneMinX") ? tag.getInt("ZoneMinX") : -ZONE_HALF_X;
        zoneMaxX = tag.contains("ZoneMaxX") ? tag.getInt("ZoneMaxX") : ZONE_HALF_X;
        zoneMinZ = tag.contains("ZoneMinZ") ? tag.getInt("ZoneMinZ") : -ZONE_HALF_Z;
        zoneMaxZ = tag.contains("ZoneMaxZ") ? tag.getInt("ZoneMaxZ") : ZONE_HALF_Z;
        storage.clear();
        net.minecraft.world.ContainerHelper.loadAllItems(tag.getCompound("Storage"), storage);
        assignments.clear();
        CompoundTag assignmentsTag = tag.getCompound("Assignments");
        for (String key : assignmentsTag.getAllKeys()) {
            try {
                UUID boatId = UUID.fromString(key);
                assignments.put(boatId, assignmentsTag.getInt(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
        waybills.clear();
        ListTag waybillTag = tag.getList("Waybills", Tag.TAG_COMPOUND);
        for (Tag raw : waybillTag) {
            if (raw instanceof CompoundTag compound) {
                WaybillEntry loaded = WaybillEntry.load(compound);
                if (loaded != null) {
                    waybills.add(loaded);
                }
            }
        }
        if (waybills.isEmpty()) {
            selectedWaybillIndex = 0;
        } else {
            selectedWaybillIndex = Mth.clamp(selectedWaybillIndex, 0, waybills.size() - 1);
        }
    }

    private void markArrivedOrders(String purchaseOrderId, String shippingOrderId) {
        if (level == null || level.isClientSide) {
            return;
        }
        MarketSavedData market = MarketSavedData.get(level);
        if (purchaseOrderId != null && !purchaseOrderId.isBlank()) {
            PurchaseOrder order = market.getPurchaseOrder(purchaseOrderId);
            if (order != null) {
                market.putPurchaseOrder(new PurchaseOrder(
                        order.orderId(),
                        order.listingId(),
                        order.buyerUuid(),
                        order.buyerName(),
                        order.quantity(),
                        order.totalPrice(),
                        order.sourceDockPos(),
                        order.sourceDockName(),
                        order.targetDockPos(),
                        order.targetDockName(),
                        "ARRIVED"
                ));
            }
        }
        if (shippingOrderId != null && !shippingOrderId.isBlank()) {
            ShippingOrder order = market.getShippingOrder(shippingOrderId);
            if (order != null) {
                market.putShippingOrder(new ShippingOrder(
                        order.shippingOrderId(),
                        order.purchaseOrderId(),
                        order.shipperUuid(),
                        order.shipperName(),
                        order.boatUuid(),
                        order.boatName(),
                        order.boatMode(),
                        order.transportMode(),
                        order.routeName(),
                        order.sourceDockPos(),
                        order.sourceDockName(),
                        order.targetDockPos(),
                        order.targetDockName(),
                        order.sourceTerminalName(),
                        order.targetTerminalName(),
                        order.distanceMeters(),
                        order.etaSeconds(),
                        order.rentalFee(),
                        "DELIVERED"
                ));
            }
        }
    }

    private void markClaimedOrders(WaybillEntry entry) {
        if (level == null || level.isClientSide || entry == null || entry.purchaseOrderId == null || entry.purchaseOrderId.isBlank()) {
            return;
        }
        MarketSavedData market = MarketSavedData.get(level);
        PurchaseOrder order = market.getPurchaseOrder(entry.purchaseOrderId);
        if (order != null) {
            market.putPurchaseOrder(new PurchaseOrder(
                    order.orderId(),
                    order.listingId(),
                    order.buyerUuid(),
                    order.buyerName(),
                    order.quantity(),
                    order.totalPrice(),
                    order.sourceDockPos(),
                    order.sourceDockName(),
                    order.targetDockPos(),
                    order.targetDockName(),
                    "CLAIMED"
            ));
        }
    }

    private String resolvedTownId() {
        return DockTownResolver.resolveTownForArrival(level, worldPosition, townId);
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

    private static String formatElapsed(long elapsedMillis) {
        long totalSeconds = Math.max(0L, elapsedMillis) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private static final class WaybillEntry {
        private final String waybillName;
        private final String routeName;
        private final String boatName;
        private final String shipperName;
        private final String startDockName;
        private final String endDockName;
        private final long departureEpochMillis;
        private final long elapsedMillis;
        private final double distanceMeters;
        private final List<ItemStack> cargo;
        private final String recipientName;
        private final String recipientUuid;
        private final String purchaseOrderId;
        private final String shippingOrderId;

        private WaybillEntry(
                String waybillName,
                String routeName,
                String boatName,
                String shipperName,
                String startDockName,
                String endDockName,
                long departureEpochMillis,
                long elapsedMillis,
                double distanceMeters,
                List<ItemStack> cargo,
                String recipientName,
                String recipientUuid,
                String purchaseOrderId,
                String shippingOrderId
        ) {
            this.waybillName = sanitize(waybillName, "-");
            this.routeName = sanitize(routeName, "-");
            this.boatName = sanitize(boatName, "Sailboat");
            this.shipperName = sanitize(shipperName, "-");
            this.startDockName = sanitize(startDockName, "-");
            this.endDockName = sanitize(endDockName, "-");
            this.departureEpochMillis = Math.max(0L, departureEpochMillis);
            this.elapsedMillis = Math.max(0L, elapsedMillis);
            this.distanceMeters = Math.max(0.0D, distanceMeters);
            this.cargo = new ArrayList<>();
            this.recipientName = sanitize(recipientName, "");
            this.recipientUuid = sanitize(recipientUuid, "");
            this.purchaseOrderId = sanitize(purchaseOrderId, "");
            this.shippingOrderId = sanitize(shippingOrderId, "");
            for (ItemStack stack : cargo) {
                if (stack != null && !stack.isEmpty()) {
                    this.cargo.add(stack.copy());
                }
            }
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("WaybillName", waybillName);
            tag.putString("RouteName", routeName);
            tag.putString("BoatName", boatName);
            tag.putString("ShipperName", shipperName);
            tag.putString("StartDockName", startDockName);
            tag.putString("EndDockName", endDockName);
            tag.putLong("DepartureEpochMillis", departureEpochMillis);
            tag.putLong("ElapsedMillis", elapsedMillis);
            tag.putDouble("DistanceMeters", distanceMeters);
            tag.putString("RecipientName", recipientName);
            tag.putString("RecipientUuid", recipientUuid);
            tag.putString("PurchaseOrderId", purchaseOrderId);
            tag.putString("ShippingOrderId", shippingOrderId);
            ListTag cargoTag = new ListTag();
            for (ItemStack stack : cargo) {
                cargoTag.add(stack.save(new CompoundTag()));
            }
            tag.put("Cargo", cargoTag);
            return tag;
        }

        @Nullable
        private static WaybillEntry load(CompoundTag tag) {
            List<ItemStack> cargo = new ArrayList<>();
            ListTag cargoTag = tag.getList("Cargo", Tag.TAG_COMPOUND);
            for (Tag raw : cargoTag) {
                if (raw instanceof CompoundTag compound) {
                    ItemStack stack = ItemStack.of(compound);
                    if (!stack.isEmpty()) {
                        cargo.add(stack);
                    }
                }
            }
            if (cargo.isEmpty()) {
                return null;
            }
            return new WaybillEntry(
                    tag.getString("WaybillName"),
                    tag.getString("RouteName"),
                    tag.getString("BoatName"),
                    tag.getString("ShipperName"),
                    tag.getString("StartDockName"),
                    tag.getString("EndDockName"),
                    tag.getLong("DepartureEpochMillis"),
                    tag.getLong("ElapsedMillis"),
                    tag.getDouble("DistanceMeters"),
                    cargo,
                    tag.getString("RecipientName"),
                    tag.getString("RecipientUuid"),
                    tag.getString("PurchaseOrderId"),
                    tag.getString("ShippingOrderId")
            );
        }

        private boolean canClaim(Player player) {
            if (player == null) {
                return false;
            }
            if (recipientUuid == null || recipientUuid.isBlank()) {
                return true;
            }
            return recipientUuid.equals(player.getUUID().toString());
        }

        private String recipientNameOrFallback() {
            if (recipientName == null || recipientName.isBlank()) {
                return "-";
            }
            return recipientName;
        }

        private List<String> toInfoLines() {
            List<String> out = new ArrayList<>();
            String dateText = departureEpochMillis <= 0L
                    ? "-"
                    : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(departureEpochMillis));
            out.add("Waybill: " + waybillName);
            out.add("Shipper: " + shipperName + " | Boat: " + boatName);
            out.add("From: " + startDockName + " -> To: " + endDockName);
            if (recipientUuid != null && !recipientUuid.isBlank()) {
                out.add("Recipient: " + recipientNameOrFallback());
            }
            if ((purchaseOrderId != null && !purchaseOrderId.isBlank()) || (shippingOrderId != null && !shippingOrderId.isBlank())) {
                out.add("Order: " + shortId(purchaseOrderId) + " | Ship: " + shortId(shippingOrderId));
            }
            out.add("Date: " + dateText);
            out.add(String.format(Locale.ROOT, "Time: %s | Distance: %.0fm | Route: %s", formatElapsed(elapsedMillis), distanceMeters, routeName));
            return out;
        }

        private List<String> toCargoLines() {
            List<String> out = new ArrayList<>();
            out.add("Cargo:");
            int shown = 0;
            for (ItemStack stack : cargo) {
                if (stack.isEmpty()) {
                    continue;
                }
                shown++;
                if (shown > 5) {
                    out.add("... +" + (cargo.size() - 5) + " more");
                    break;
                }
                out.add("- " + stack.getHoverName().getString() + " x" + stack.getCount());
            }
            if (shown == 0) {
                out.add("- (empty)");
            }
            return out;
        }

        private static String shortId(String value) {
            if (value == null || value.isBlank()) {
                return "-";
            }
            return value.length() <= 8 ? value : value.substring(0, 8);
        }
    }
}
