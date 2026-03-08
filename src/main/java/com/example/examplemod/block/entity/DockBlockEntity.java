package com.example.examplemod.block.entity;

import com.example.examplemod.dock.DockRegistry;
import com.example.examplemod.dock.DockScreenData;
import com.example.examplemod.economy.VaultEconomyBridge;
import com.example.examplemod.entity.SailboatEntity;
import com.example.examplemod.item.RouteBookItem;
import com.example.examplemod.menu.DockMenu;
import com.example.examplemod.registry.ModBlockEntities;
import com.example.examplemod.route.RouteDefinition;
import com.example.examplemod.route.RouteNbtUtil;
import net.minecraft.core.BlockPos;
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
    public static final int ZONE_HALF_X = 12;
    public static final int ZONE_HALF_Z = 8;
    public static final int MINIMAP_RADIUS = 50;
    private final List<RouteDefinition> routes = new ArrayList<>();
    private final List<WaybillEntry> waybills = new ArrayList<>();
    private final Map<UUID, Integer> assignments = new HashMap<>();
    private ItemStack routeBook = ItemStack.EMPTY;
    private String dockName = "";
    private String ownerName = "";
    private String ownerUuid = "";
    private int zoneMinX = -ZONE_HALF_X;
    private int zoneMaxX = ZONE_HALF_X;
    private int zoneMinZ = -ZONE_HALF_Z;
    private int zoneMaxZ = ZONE_HALF_Z;
    private int selectedRouteIndex = 0;
    private int selectedBoatIndex = 0;
    private int selectedWaybillIndex = 0;

    public DockBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DOCK_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            DockRegistry.register(level, worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            DockRegistry.unregister(level, worldPosition);
        }
        super.setRemoved();
    }

    public void setRoutes(List<RouteDefinition> newRoutes, int activeIndex) {
        routes.clear();
        for (RouteDefinition route : newRoutes) {
            routes.add(route.copy());
        }
        selectedRouteIndex = routes.isEmpty() ? 0 : Mth.clamp(activeIndex, 0, routes.size() - 1);
        setChanged();
    }

    public String getDockName() {
        if (dockName == null || dockName.isBlank()) {
            return "Dock-" + worldPosition.getX() + "," + worldPosition.getZ();
        }
        return dockName;
    }

    public void setDockName(String name) {
        dockName = name == null ? "" : name.trim();
        setChanged();
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

    public boolean setDockZone(int minX, int maxX, int minZ, int maxZ) {
        int clampedMinX = Mth.clamp(Math.min(minX, maxX), -MINIMAP_RADIUS, MINIMAP_RADIUS);
        int clampedMaxX = Mth.clamp(Math.max(minX, maxX), -MINIMAP_RADIUS, MINIMAP_RADIUS);
        int clampedMinZ = Mth.clamp(Math.min(minZ, maxZ), -MINIMAP_RADIUS, MINIMAP_RADIUS);
        int clampedMaxZ = Mth.clamp(Math.max(minZ, maxZ), -MINIMAP_RADIUS, MINIMAP_RADIUS);
        if (clampedMaxX - clampedMinX < 2 || clampedMaxZ - clampedMinZ < 2) {
            return false;
        }
        if (level == null || !isZoneMostlyWater(level, clampedMinX, clampedMaxX, clampedMinZ, clampedMaxZ)) {
            return false;
        }
        zoneMinX = clampedMinX;
        zoneMaxX = clampedMaxX;
        zoneMinZ = clampedMinZ;
        zoneMaxZ = clampedMaxZ;
        setChanged();
        return true;
    }

    public boolean loadRouteBookFromPlayer(Player player) {
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof RouteBookItem)) {
            held = player.getOffhandItem();
        }
        if (!(held.getItem() instanceof RouteBookItem)) {
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
        if (!(stack.getItem() instanceof RouteBookItem)) {
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
        if (routes.isEmpty()) {
            selectedRouteIndex = 0;
            return selectedRouteIndex;
        }
        int size = routes.size();
        selectedRouteIndex = (selectedRouteIndex + delta % size + size) % size;
        setChanged();
        return selectedRouteIndex;
    }

    public int selectRouteIndex(int index) {
        if (routes.isEmpty()) {
            selectedRouteIndex = 0;
            return selectedRouteIndex;
        }
        selectedRouteIndex = Mth.clamp(index, 0, routes.size() - 1);
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

    public boolean assignSelectedBoat(Player player, boolean autoStart) {
        List<SailboatEntity> boats = getNearbySailboats(player);
        if (boats.isEmpty()) {
            player.displayClientMessage(Component.translatable("block.sailboatmod.dock.no_target"), true);
            return false;
        }
        int idx = Mth.clamp(selectedBoatIndex, 0, boats.size() - 1);
        return assignBoat(boats.get(idx), autoStart, player);
    }

    public boolean assignBoat(SailboatEntity sailboat, boolean autoStart) {
        return assignBoat(sailboat, autoStart, null);
    }

    private boolean assignBoat(SailboatEntity sailboat, boolean autoStart, Player operator) {
        if (routes.isEmpty()) {
            if (operator != null) {
                operator.displayClientMessage(Component.translatable("block.sailboatmod.dock.no_route"), true);
            }
            return false;
        }
        int rentalFee = Math.max(SailboatEntity.MIN_RENTAL_PRICE, sailboat.getRentalPrice());
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
        }
        int routeIndex = Mth.clamp(selectedRouteIndex, 0, routes.size() - 1);
        assignments.put(sailboat.getUUID(), routeIndex);
        sailboat.setRouteCatalog(routes, routeIndex, worldPosition);
        sailboat.setPendingShipper(operator != null ? operator.getName().getString() : null);
        if (autoStart) {
            boolean started = sailboat.startAutopilotFromRouteStart();
            if (!started && operator != null) {
                operator.displayClientMessage(Component.translatable("screen.sailboatmod.route_start_need_zone"), true);
            }
        }
        setChanged();
        return true;
    }

    public DockScreenData buildScreenData(Player player) {
        List<String> routeNames = new ArrayList<>();
        List<String> routeMetas = new ArrayList<>();
        List<Vec3> selectedPoints = List.of();
        for (int i = 0; i < routes.size(); i++) {
            RouteDefinition route = routes.get(i);
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
        if (!routes.isEmpty()) {
            int selected = Mth.clamp(selectedRouteIndex, 0, routes.size() - 1);
            selectedPoints = List.copyOf(routes.get(selected).waypoints());
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
                routeBook.copy(),
                routeNames,
                routeMetas,
                routes.isEmpty() ? 0 : Mth.clamp(selectedRouteIndex, 0, routes.size() - 1),
                selectedPoints,
                zoneMinX,
                zoneMaxX,
                zoneMinZ,
                zoneMaxZ,
                boatIds,
                boatNames,
                boatPositions,
                safeBoatIndex,
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
        WaybillEntry removed = waybills.remove(idx);
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
        selectedWaybillIndex = waybills.isEmpty() ? 0 : Mth.clamp(idx, 0, waybills.size() - 1);
        setChanged();
        return true;
    }

    public void receiveShipment(
            SailboatEntity sailboat,
            String routeName,
            String shipperName,
            String startDockName,
            String endDockName,
            long departureEpochMillis,
            long elapsedMillis,
            double distanceMeters,
            List<ItemStack> cargo
    ) {
        if (cargo == null || cargo.isEmpty()) {
            return;
        }
        List<ItemStack> packed = new ArrayList<>();
        for (ItemStack stack : cargo) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            packed.add(stack.copy());
        }
        if (packed.isEmpty()) {
            return;
        }

        String safeRoute = sanitize(routeName, "-");
        String safeBoat = sanitize(sailboat.getName().getString(), "Sailboat");
        String safeShipper = sanitize(shipperName, "-");
        String safeStart = sanitize(startDockName, "-");
        String safeEnd = sanitize(endDockName, getDockName());
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
                packed
        ));
        if (waybills.size() > MAX_WAYBILLS) {
            int overflow = waybills.size() - MAX_WAYBILLS;
            for (int i = 0; i < overflow; i++) {
                waybills.remove(0);
            }
        }
        selectedWaybillIndex = Math.max(0, waybills.size() - 1);
        setChanged();
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
        return level.getEntitiesOfClass(SailboatEntity.class, search).stream()
                .filter(Entity::isAlive)
                .filter(boat -> isInsideDockZone(boat.position()))
                .sorted(Comparator
                        .comparing((SailboatEntity boat) -> isBoatOwnedBy(boat, player) ? 0 : 1)
                        .thenComparingDouble(player::distanceToSqr))
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
        String ownership = isBoatOwnedBy(boat, viewer)
                ? ("OWN | RENT " + boat.getRentalPrice())
                : ("RENT " + boat.getRentalPrice());
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
        Boolean vaultResult = VaultEconomyBridge.tryWithdraw(player, rentalFee);
        if (vaultResult != null) {
            return vaultResult;
        }
        return chargeRentalFeeByEmerald(player, rentalFee);
    }

    private static boolean chargeRentalFeeByEmerald(Player player, int emeraldCost) {
        Inventory inventory = player.getInventory();
        int remaining = emeraldCost;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.is(Items.EMERALD)) {
                continue;
            }
            remaining -= stack.getCount();
            if (remaining <= 0) {
                break;
            }
        }
        if (remaining > 0) {
            return false;
        }
        remaining = emeraldCost;
        for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.is(Items.EMERALD)) {
                continue;
            }
            int consume = Math.min(stack.getCount(), remaining);
            stack.shrink(consume);
            remaining -= consume;
        }
        inventory.setChanged();
        player.containerMenu.broadcastChanges();
        return true;
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

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        RouteNbtUtil.writeRoutes(tag, "DockRoutes", routes);
        tag.putInt("SelectedRouteIndex", selectedRouteIndex);
        tag.putInt("SelectedBoatIndex", selectedBoatIndex);
        tag.putInt("SelectedWaybillIndex", selectedWaybillIndex);
        tag.putString("OwnerName", ownerName == null ? "" : ownerName);
        tag.putString("OwnerUuid", ownerUuid == null ? "" : ownerUuid);
        if (!routeBook.isEmpty()) {
            tag.put("RouteBook", routeBook.save(new CompoundTag()));
        }
        tag.putString("DockName", dockName == null ? "" : dockName);
        tag.putInt("ZoneMinX", zoneMinX);
        tag.putInt("ZoneMaxX", zoneMaxX);
        tag.putInt("ZoneMinZ", zoneMinZ);
        tag.putInt("ZoneMaxZ", zoneMaxZ);
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
        selectedWaybillIndex = Math.max(0, tag.getInt("SelectedWaybillIndex"));
        ownerName = tag.getString("OwnerName");
        ownerUuid = tag.getString("OwnerUuid");
        routeBook = tag.contains("RouteBook") ? ItemStack.of(tag.getCompound("RouteBook")) : ItemStack.EMPTY;
        dockName = tag.getString("DockName");
        zoneMinX = tag.contains("ZoneMinX") ? tag.getInt("ZoneMinX") : -ZONE_HALF_X;
        zoneMaxX = tag.contains("ZoneMaxX") ? tag.getInt("ZoneMaxX") : ZONE_HALF_X;
        zoneMinZ = tag.contains("ZoneMinZ") ? tag.getInt("ZoneMinZ") : -ZONE_HALF_Z;
        zoneMaxZ = tag.contains("ZoneMaxZ") ? tag.getInt("ZoneMaxZ") : ZONE_HALF_Z;
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
                List<ItemStack> cargo
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
                    cargo
            );
        }

        private List<String> toInfoLines() {
            List<String> out = new ArrayList<>();
            String dateText = departureEpochMillis <= 0L
                    ? "-"
                    : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(departureEpochMillis));
            out.add("Waybill: " + waybillName);
            out.add("Shipper: " + shipperName + " | Boat: " + boatName);
            out.add("From: " + startDockName + " -> To: " + endDockName);
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
    }
}
