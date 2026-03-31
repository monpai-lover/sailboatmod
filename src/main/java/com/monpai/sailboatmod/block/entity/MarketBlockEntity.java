package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.dock.DockScreenData;
import com.monpai.sailboatmod.economy.VaultEconomyBridge;
import com.monpai.sailboatmod.entity.SailboatEntity;
import com.monpai.sailboatmod.market.MarketOverviewData;
import com.monpai.sailboatmod.market.MarketSavedData;
import com.monpai.sailboatmod.market.PurchaseOrder;
import com.monpai.sailboatmod.market.ShipmentManifestEntry;
import com.monpai.sailboatmod.market.ShippingOrder;
import com.monpai.sailboatmod.market.MarketListing;
import com.monpai.sailboatmod.menu.MarketMenu;
import com.monpai.sailboatmod.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MarketBlockEntity extends BlockEntity implements MenuProvider {
    private static final double LINK_DOCK_RADIUS = 24.0D;

    private String marketName = "";
    private String ownerName = "";
    private String ownerUuid = "";
    @Nullable
    private BlockPos linkedDockPos;

    public MarketBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MARKET_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            bindNearestDockIfAbsent();
        }
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

    public boolean bindNearestDockIfAbsent() {
        if (linkedDockPos != null) {
            return true;
        }
        return bindNearestDock();
    }

    public boolean bindNearestDock() {
        if (level == null || level.isClientSide) {
            return false;
        }
        BlockPos nearest = DockBlockEntity.findNearestRegisteredDock(level, Vec3.atCenterOf(worldPosition), LINK_DOCK_RADIUS);
        if (nearest == null) {
            return false;
        }
        linkedDockPos = nearest.immutable();
        setChanged();
        return true;
    }

    public MarketOverviewData buildOverview(Player player) {
        String dockName = "-";
        String dockPosText = "-";
        boolean linked = false;
        List<String> boatLines = new ArrayList<>();
        boolean dockStorageAccessible = false;
        List<String> dockStorageLines = List.of();
        if (level != null && linkedDockPos != null && level.getBlockEntity(linkedDockPos) instanceof DockBlockEntity dock) {
            dockName = dock.getDockName();
            dockPosText = linkedDockPos.toShortString();
            linked = true;
            if (player != null) {
                DockScreenData dockData = dock.buildScreenData(player);
                boatLines.addAll(dockData.nearbyBoatNames());
                dockStorageAccessible = dockData.canManageDock();
                dockStorageLines = dockData.storageLines();
            }
        }

        List<String> listingLines = new ArrayList<>();
        List<String> orderLines = new ArrayList<>();
        List<String> shippingLines = new ArrayList<>(boatLines);
        if (level != null && !level.isClientSide) {
            MarketSavedData market = MarketSavedData.get(level);
            for (MarketListing listing : market.getListings()) {
                listingLines.add(listing.toSummaryLine());
            }
            List<PurchaseOrder> openOrders = linkedDockPos == null ? List.<PurchaseOrder>of() : market.getOpenOrdersForSourceDock(linkedDockPos);
            for (PurchaseOrder order : openOrders) {
                orderLines.add(order.toSummaryLine());
            }
        }

        return new MarketOverviewData(
                worldPosition,
                getMarketName(),
                getOwnerName(),
                getOwnerUuid(),
                player == null || level == null || level.isClientSide ? 0 : MarketSavedData.get(level).getPendingCredits(player.getUUID().toString()),
                linked,
                dockName,
                dockPosText,
                dockStorageAccessible,
                canManageMarket(player),
                dockStorageLines,
                listingLines,
                orderLines,
                shippingLines
        );
    }

    public boolean createListingFromDockStorage(Player player, int visibleStorageIndex, int quantity, int unitPrice) {
        DockBlockEntity dock = getLinkedDock();
        if (level == null || level.isClientSide || dock == null || player == null) {
            return false;
        }
        ItemStack selected = dock.getStorageItemForVisibleIndex(player, visibleStorageIndex);
        if (selected.isEmpty()) {
            player.displayClientMessage(Component.translatable("screen.sailboatmod.market.storage_empty"), true);
            return false;
        }
        int stocked = selected.getCount();
        int amount = Math.max(1, Math.min(quantity, stocked));
        int price = Math.max(1, unitPrice);
        ItemStack listed = selected.copy();
        listed.setCount(1);
        if (!dock.extractVisibleStorage(player, visibleStorageIndex, amount)) {
            player.displayClientMessage(Component.translatable("screen.sailboatmod.market.storage_empty"), true);
            return false;
        }
        MarketSavedData market = MarketSavedData.get(level);
        market.putListing(new MarketListing(
                market.nextId(),
                player.getUUID().toString(),
                player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName(),
                listed,
                price,
                amount,
                0,
                linkedDockPos,
                dock.getDockName()
        ));
        return true;
    }

    public boolean purchaseListing(Player player, int listingIndex, int quantity) {
        DockBlockEntity dock = getLinkedDock();
        if (level == null || level.isClientSide || dock == null || player == null) {
            return false;
        }
        MarketSavedData market = MarketSavedData.get(level);
        MarketListing listing = market.getListingByVisibleIndex(listingIndex);
        if (listing == null) {
            return false;
        }
        if (player.getUUID().toString().equals(listing.sellerUuid())) {
            player.displayClientMessage(Component.translatable("screen.sailboatmod.market.self_buy_denied"), true);
            return false;
        }
        int amount = Math.max(1, Math.min(quantity, listing.availableCount()));
        int total = amount * Math.max(1, listing.unitPrice());
        if (!chargePlayer(player, total)) {
            player.displayClientMessage(Component.literal("Not enough funds."), true);
            return false;
        }
        int sellerPayout = total;
        com.monpai.sailboatmod.nation.service.TaxService.TaxResult salesTaxResult =
                com.monpai.sailboatmod.nation.service.TaxService.applySalesTax(level, total, this.worldPosition);
        sellerPayout = salesTaxResult.sellerReceives();
        com.monpai.sailboatmod.nation.service.TaxService.TaxResult tariffResult =
                com.monpai.sailboatmod.nation.service.TaxService.applyImportTariff(level, sellerPayout, listing.sourceDockPos(), linkedDockPos);
        sellerPayout = tariffResult.sellerReceives();
        com.monpai.sailboatmod.nation.service.TaxService.recordTrade(level, this.worldPosition);
        paySeller(market, listing.sellerUuid(), listing.sellerName(), sellerPayout);
        market.putListing(new MarketListing(
                listing.listingId(),
                listing.sellerUuid(),
                listing.sellerName(),
                listing.itemStack(),
                listing.unitPrice(),
                Math.max(0, listing.availableCount() - amount),
                listing.reservedCount() + amount,
                listing.sourceDockPos(),
                listing.sourceDockName()
        ));
        PurchaseOrder createdOrder = new PurchaseOrder(
                market.nextId(),
                listing.listingId(),
                player.getUUID().toString(),
                player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName(),
                amount,
                total,
                listing.sourceDockPos(),
                listing.sourceDockName(),
                linkedDockPos,
                dock.getDockName(),
                "WAITING_SHIPMENT"
        );
        market.putPurchaseOrder(createdOrder);
        tryAutoDispatchOrders(player, createdOrder.sourceDockPos());
        return true;
    }

    public boolean cancelListing(Player player, int listingIndex) {
        if (level == null || level.isClientSide || player == null) {
            return false;
        }
        MarketSavedData market = MarketSavedData.get(level);
        MarketListing listing = market.getListingByVisibleIndex(listingIndex);
        if (listing == null) {
            return false;
        }
        if (!player.getUUID().toString().equals(listing.sellerUuid())) {
            player.displayClientMessage(Component.literal("Only the seller can cancel this listing."), true);
            return false;
        }
        if (listing.availableCount() <= 0) {
            player.displayClientMessage(Component.literal("No unsold stock remains on this listing."), true);
            return false;
        }
        DockBlockEntity sourceDock = level.getBlockEntity(listing.sourceDockPos()) instanceof DockBlockEntity dock ? dock : null;
        if (sourceDock == null) {
            player.displayClientMessage(Component.literal("Source dock is unavailable."), true);
            return false;
        }
        List<ItemStack> cargo = splitCargo(listing.itemStack(), listing.availableCount());
        if (!sourceDock.insertCargo(cargo)) {
            player.displayClientMessage(Component.literal("Source dock storage is full."), true);
            return false;
        }
        if (listing.reservedCount() <= 0) {
            market.removeListing(listing.listingId());
        } else {
            market.putListing(new MarketListing(
                    listing.listingId(),
                    listing.sellerUuid(),
                    listing.sellerName(),
                    listing.itemStack(),
                    listing.unitPrice(),
                    0,
                    listing.reservedCount(),
                    listing.sourceDockPos(),
                    listing.sourceDockName()
            ));
        }
        return true;
    }

    public boolean claimPendingCredits(Player player) {
        if (level == null || level.isClientSide || player == null) {
            return false;
        }
        MarketSavedData market = MarketSavedData.get(level);
        int pending = market.getPendingCredits(player.getUUID().toString());
        if (pending <= 0) {
            return false;
        }
        Boolean vaultResult = VaultEconomyBridge.tryDeposit(player, pending);
        if (vaultResult != null && vaultResult) {
            market.clearPendingCredits(player.getUUID().toString());
            return true;
        }
        giveEmeraldPayout(player, pending);
        market.clearPendingCredits(player.getUUID().toString());
        return true;
    }

    public boolean dispatchOrder(Player player, int orderIndex, int boatIndex) {
        return linkedDockPos != null && player != null && tryAutoDispatchOrders(player, linkedDockPos);
    }

    public String getMarketName() {
        if (marketName == null || marketName.isBlank()) {
            return "Market-" + worldPosition.getX() + "," + worldPosition.getZ();
        }
        return marketName;
    }

    public void setMarketName(String name) {
        this.marketName = name == null ? "" : name.trim();
        setChanged();
    }

    public String getOwnerName() {
        return ownerName == null || ownerName.isBlank() ? "-" : ownerName;
    }

    public String getOwnerUuid() {
        return ownerUuid == null ? "" : ownerUuid;
    }

    public boolean canManageMarket(Player player) {
        if (player == null || player.getAbilities().instabuild) {
            return player != null;
        }
        return ownerUuid != null && ownerUuid.equals(player.getUUID().toString());
    }

    @Nullable
    public BlockPos getLinkedDockPos() {
        return linkedDockPos;
    }

    @Nullable
    public DockBlockEntity getLinkedDock() {
        if (level == null || linkedDockPos == null) {
            return null;
        }
        return level.getBlockEntity(linkedDockPos) instanceof DockBlockEntity dock ? dock : null;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal(getMarketName());
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new MarketMenu(containerId, playerInventory, worldPosition);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("MarketName", marketName == null ? "" : marketName);
        tag.putString("OwnerName", ownerName == null ? "" : ownerName);
        tag.putString("OwnerUuid", ownerUuid == null ? "" : ownerUuid);
        if (linkedDockPos != null) {
            tag.putLong("LinkedDockPos", linkedDockPos.asLong());
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        marketName = tag.getString("MarketName");
        ownerName = tag.getString("OwnerName");
        ownerUuid = tag.getString("OwnerUuid");
        linkedDockPos = tag.contains("LinkedDockPos") ? BlockPos.of(tag.getLong("LinkedDockPos")) : null;
    }

    private MarketListing findListingById(MarketSavedData market, String listingId) {
        return market.getListing(listingId);
    }

    private boolean tryAutoDispatchOrders(Player player, BlockPos sourceDockPos) {
        if (level == null || level.isClientSide || player == null || sourceDockPos == null) {
            return false;
        }
        DockBlockEntity sourceDock = level.getBlockEntity(sourceDockPos) instanceof DockBlockEntity dock ? dock : null;
        if (sourceDock == null) {
            return false;
        }
        MarketSavedData market = MarketSavedData.get(level);
        boolean progressedAny = false;
        while (true) {
            boolean progressed = processLocalOrders(sourceDock, market);
            List<SailboatEntity> boats = sourceDock.getAvailableSailboatsForDispatch(player);
            boolean shipped = false;
            for (SailboatEntity boat : boats) {
                ShipmentPlan plan = buildShipmentPlan(sourceDock, market, boat);
                if (plan == null) {
                    continue;
                }
                if (dispatchShipmentPlan(player, boat, sourceDock, market, plan)) {
                    shipped = true;
                    progressed = true;
                    break;
                }
            }
            if (!progressed) {
                break;
            }
            progressedAny = true;
            if (!shipped && sourceDock.getAvailableSailboatsForDispatch(player).isEmpty()) {
                break;
            }
        }
        return progressedAny;
    }

    private boolean processLocalOrders(DockBlockEntity sourceDock, MarketSavedData market) {
        if (sourceDock == null || market == null) {
            return false;
        }
        boolean processed = false;
        List<PurchaseOrder> waiting = new ArrayList<>(market.getOpenOrdersForSourceDock(sourceDock.getBlockPos()));
        for (PurchaseOrder order : waiting) {
            if (!order.sourceDockPos().equals(order.targetDockPos())) {
                continue;
            }
            MarketListing listing = findListingById(market, order.listingId());
            if (listing == null) {
                continue;
            }
            deliverOrderLocally(sourceDock, market, order, listing);
            processed = true;
        }
        return processed;
    }

    private void deliverOrderLocally(DockBlockEntity dock, MarketSavedData market, PurchaseOrder order, MarketListing listing) {
        List<ItemStack> cargo = splitCargo(listing.itemStack(), order.quantity());
        dock.receiveShipment(
                null,
                "LOCAL",
                "LOCAL",
                dock.getDockName(),
                dock.getDockName(),
                System.currentTimeMillis(),
                0L,
                0.0D,
                cargo,
                List.of(new ShipmentManifestEntry(
                        listing.listingId(),
                        listing.itemStack(),
                        order.orderId(),
                        "",
                        order.buyerUuid(),
                        order.buyerName(),
                        order.quantity()
                ))
        );
        applyListingReservationDeltas(market, Map.of(listing.listingId(), order.quantity()));
    }

    @Nullable
    private ShipmentPlan buildShipmentPlan(DockBlockEntity sourceDock, MarketSavedData market, SailboatEntity boat) {
        if (sourceDock == null || market == null || boat == null) {
            return null;
        }
        LinkedHashMap<BlockPos, List<PurchaseOrder>> byTargetDock = new LinkedHashMap<>();
        for (PurchaseOrder order : market.getOpenOrdersForSourceDock(sourceDock.getBlockPos())) {
            if (order.sourceDockPos().equals(order.targetDockPos())) {
                continue;
            }
            int routeIndex = sourceDock.findRouteIndexByDestinationDock(order.targetDockPos(), order.targetDockName());
            if (routeIndex < 0) {
                continue;
            }
            byTargetDock.computeIfAbsent(order.targetDockPos(), ignored -> new ArrayList<>()).add(order);
        }

        ShipmentPlan bestPlan = null;
        for (Map.Entry<BlockPos, List<PurchaseOrder>> entry : byTargetDock.entrySet()) {
            int routeIndex = sourceDock.findRouteIndexByDestinationDock(entry.getKey(), entry.getValue().get(0).targetDockName());
            ShipmentPlan candidate = buildShipmentPlanForTarget(sourceDock, market, boat, routeIndex, entry.getKey(), entry.getValue());
            if (candidate == null) {
                continue;
            }
            if (bestPlan == null
                    || candidate.selections().size() > bestPlan.selections().size()
                    || (candidate.selections().size() == bestPlan.selections().size()
                    && candidate.totalQuantity() > bestPlan.totalQuantity())) {
                bestPlan = candidate;
            }
        }
        return bestPlan;
    }

    @Nullable
    private ShipmentPlan buildShipmentPlanForTarget(DockBlockEntity sourceDock, MarketSavedData market, SailboatEntity boat,
                                                    int routeIndex, BlockPos targetDockPos, List<PurchaseOrder> orders) {
        if (routeIndex < 0 || orders == null || orders.isEmpty()) {
            return null;
        }
        List<ItemStack> cargo = new ArrayList<>();
        List<ShipmentOrderSelection> selections = new ArrayList<>();
        for (PurchaseOrder order : orders) {
            MarketListing listing = findListingById(market, order.listingId());
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
            selections.add(new ShipmentOrderSelection(split.shipped(), split.remainder(), listing));
            if (shippableQuantity < order.quantity()) {
                break;
            }
        }
        if (cargo.isEmpty() || selections.isEmpty()) {
            return null;
        }
        return new ShipmentPlan(routeIndex, targetDockPos, cargo, selections);
    }

    private boolean dispatchShipmentPlan(Player player, SailboatEntity boat, DockBlockEntity sourceDock,
                                         MarketSavedData market, ShipmentPlan plan) {
        if (player == null || boat == null || sourceDock == null || market == null || plan == null) {
            return false;
        }
        if (!boat.canLoadCargo(plan.cargo()) || !boat.loadCargo(plan.cargo())) {
            return false;
        }

        List<ShipmentManifestEntry> manifest = new ArrayList<>();
        Map<String, Integer> listingReservationDeltas = new LinkedHashMap<>();
        List<ShippingOrder> shippingOrders = new ArrayList<>();
        for (ShipmentOrderSelection selection : plan.selections()) {
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
            shippingOrders.add(new ShippingOrder(
                    shippingOrderId,
                    order.orderId(),
                    player.getUUID().toString(),
                    player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName(),
                    boat.getUUID().toString(),
                    boat.getName().getString(),
                    boat.isOwnedBy(player) ? "OWN" : "RENTED",
                    sourceDock.getRouteName(plan.routeIndex()),
                    order.sourceDockPos(),
                    order.sourceDockName(),
                    order.targetDockPos(),
                    order.targetDockName(),
                    boat.isOwnedBy(player) ? 0 : boat.getRentalPrice(),
                    "SAILING"
            ));
        }
        boat.setPendingShipmentManifest(manifest);
        if (!sourceDock.assignLoadedBoatToRouteIndex(boat, plan.routeIndex(), true, player)) {
            sourceDock.insertCargo(boat.unloadAllCargo());
            boat.clearPendingMarketDelivery();
            return false;
        }

        for (ShipmentOrderSelection selection : plan.selections()) {
            PurchaseOrder order = selection.dispatchOrder();
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
        }
        applyListingReservationDeltas(market, listingReservationDeltas);
        for (ShippingOrder shippingOrder : shippingOrders) {
            market.putShippingOrder(shippingOrder);
        }
        return true;
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
                    listing.sourceDockName()
            ));
        }
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

    private record ShipmentPlan(int routeIndex, BlockPos targetDockPos, List<ItemStack> cargo,
                                List<ShipmentOrderSelection> selections) {
        private int totalQuantity() {
            int total = 0;
            for (ShipmentOrderSelection selection : selections) {
                total += selection.dispatchOrder().quantity();
            }
            return total;
        }
    }

    private record ShipmentOrderSelection(PurchaseOrder dispatchOrder, @Nullable PurchaseOrder remainderOrder,
                                          MarketListing listing) {
    }

    private record PurchaseSplit(PurchaseOrder shipped, @Nullable PurchaseOrder remainder) {
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

    private static boolean chargePlayer(Player player, int amount) {
        if (player == null || amount <= 0 || player.getAbilities().instabuild) {
            return true;
        }
        Boolean vaultResult = VaultEconomyBridge.tryWithdraw(player, amount);
        if (vaultResult != null) {
            return vaultResult;
        }
        Inventory inventory = player.getInventory();
        int remaining = amount;
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
        remaining = amount;
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

    private void paySeller(MarketSavedData market, String sellerUuid, String sellerName, int amount) {
        if (amount <= 0 || market == null || sellerUuid == null || sellerUuid.isBlank()) {
            return;
        }
        Boolean deposited = VaultEconomyBridge.tryDepositByIdentity(parseUuid(sellerUuid), sellerName, amount);
        if (deposited != null && deposited) {
            return;
        }
        market.addPendingCredits(sellerUuid, amount);
    }

    @Nullable
    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void giveEmeraldPayout(Player player, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            int stackSize = Math.min(64, remaining);
            ItemStack stack = new ItemStack(Items.EMERALD, stackSize);
            boolean added = player.getInventory().add(stack);
            if (!added || !stack.isEmpty()) {
                player.drop(stack, false);
            }
            remaining -= stackSize;
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
    }
}
