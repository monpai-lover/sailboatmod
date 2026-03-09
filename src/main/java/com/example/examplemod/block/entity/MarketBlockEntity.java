package com.example.examplemod.block.entity;

import com.example.examplemod.dock.DockScreenData;
import com.example.examplemod.economy.VaultEconomyBridge;
import com.example.examplemod.entity.SailboatEntity;
import com.example.examplemod.market.MarketOverviewData;
import com.example.examplemod.market.MarketSavedData;
import com.example.examplemod.market.PurchaseOrder;
import com.example.examplemod.market.ShippingOrder;
import com.example.examplemod.market.MarketListing;
import com.example.examplemod.menu.MarketMenu;
import com.example.examplemod.registry.ModBlockEntities;
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
import java.util.List;
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
        if (level != null && linkedDockPos != null && level.getBlockEntity(linkedDockPos) instanceof DockBlockEntity dock) {
            dockName = dock.getDockName();
            dockPosText = linkedDockPos.toShortString();
            linked = true;
            if (player != null) {
                DockScreenData dockData = dock.buildScreenData(player);
                boatLines.addAll(dockData.nearbyBoatNames());
            }
        }

        List<String> listingLines = new ArrayList<>();
        List<String> orderLines = new ArrayList<>();
        List<String> shippingLines = new ArrayList<>(boatLines);
        if (level != null && !level.isClientSide) {
            MarketSavedData market = MarketSavedData.get(level);
            for (MarketListing listing : market.getListings()) {
                listingLines.add(listing.toSummaryLine());
                if (listingLines.size() >= 8) {
                    break;
                }
            }
            List<PurchaseOrder> openOrders = linkedDockPos == null ? List.<PurchaseOrder>of() : market.getOpenOrdersForSourceDock(linkedDockPos);
            for (PurchaseOrder order : openOrders) {
                orderLines.add(order.toSummaryLine());
                if (orderLines.size() >= 8) {
                    break;
                }
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
                listingLines,
                orderLines,
                shippingLines
        );
    }

    public boolean createListingFromHeldItem(Player player, int quantity, int unitPrice) {
        DockBlockEntity dock = getLinkedDock();
        if (level == null || level.isClientSide || dock == null || player == null) {
            return false;
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            held = player.getOffhandItem();
        }
        if (held.isEmpty()) {
            player.displayClientMessage(Component.literal("Hold a sample item to list from dock stock."), true);
            return false;
        }
        int stocked = dock.countMatchingStock(held);
        if (stocked <= 0) {
            player.displayClientMessage(Component.literal("Linked dock has no matching stock."), true);
            return false;
        }
        int amount = Math.max(1, Math.min(quantity, stocked));
        int price = Math.max(1, unitPrice);
        ItemStack listed = held.copy();
        listed.setCount(1);
        if (!dock.extractMatchingStock(held, amount)) {
            player.displayClientMessage(Component.literal("Not enough matching dock stock."), true);
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
        int amount = Math.max(1, Math.min(quantity, listing.availableCount()));
        int total = amount * Math.max(1, listing.unitPrice());
        if (!chargePlayer(player, total)) {
            player.displayClientMessage(Component.literal("Not enough funds."), true);
            return false;
        }
        paySeller(market, listing.sellerUuid(), listing.sellerName(), total);
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
        market.putPurchaseOrder(new PurchaseOrder(
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
        ));
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
        DockBlockEntity sourceDock = getLinkedDock();
        if (level == null || level.isClientSide || sourceDock == null || player == null || linkedDockPos == null) {
            return false;
        }
        MarketSavedData market = MarketSavedData.get(level);
        PurchaseOrder order = market.getSourceOrderByIndex(linkedDockPos, orderIndex);
        if (order == null) {
            return false;
        }
        MarketListing listing = findListingById(market, order.listingId());
        if (listing == null) {
            player.displayClientMessage(Component.literal("Listing not found."), true);
            return false;
        }
        List<SailboatEntity> boats = sourceDock.getAssignableSailboats(player);
        if (boats.isEmpty()) {
            player.displayClientMessage(Component.translatable("block.sailboatmod.dock.no_target"), true);
            return false;
        }
        int safeBoatIndex = Math.max(0, Math.min(boatIndex, boats.size() - 1));
        SailboatEntity boat = boats.get(safeBoatIndex);
        int routeIndex = sourceDock.findRouteIndexByEndDockName(order.targetDockName());
        if (routeIndex < 0) {
            player.displayClientMessage(Component.literal("No matching route to target dock."), true);
            return false;
        }
        List<ItemStack> cargo = splitCargo(listing.itemStack(), order.quantity());
        if (!boat.canLoadCargo(cargo)) {
            player.displayClientMessage(Component.literal("Boat cargo hold is full."), true);
            return false;
        }
        if (!boat.loadCargo(cargo)) {
            player.displayClientMessage(Component.literal("Boat cargo load failed."), true);
            return false;
        }
        String shippingOrderId = market.nextId();
        boat.setPendingMarketDelivery(order.buyerName(), order.buyerUuid(), order.orderId(), shippingOrderId);
        if (!sourceDock.assignBoatToRouteIndex(boat, routeIndex, true, player)) {
            sourceDock.insertCargo(boat.unloadAllCargo());
            boat.clearPendingMarketDelivery();
            return false;
        }
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
        market.putListing(new MarketListing(
                listing.listingId(),
                listing.sellerUuid(),
                listing.sellerName(),
                listing.itemStack(),
                listing.unitPrice(),
                listing.availableCount(),
                Math.max(0, listing.reservedCount() - order.quantity()),
                listing.sourceDockPos(),
                listing.sourceDockName()
        ));
        market.putShippingOrder(new ShippingOrder(
                shippingOrderId,
                order.orderId(),
                player.getUUID().toString(),
                player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName(),
                boat.getUUID().toString(),
                boat.getName().getString(),
                boat.isOwnedBy(player) ? "OWN" : "RENTED",
                sourceDock.getRouteName(routeIndex),
                order.sourceDockPos(),
                order.sourceDockName(),
                order.targetDockPos(),
                order.targetDockName(),
                boat.isOwnedBy(player) ? 0 : boat.getRentalPrice(),
                "SAILING"
        ));
        return true;
    }

    public String getMarketName() {
        if (marketName == null || marketName.isBlank()) {
            return "Market-" + worldPosition.getX() + "," + worldPosition.getZ();
        }
        return marketName;
    }

    public String getOwnerName() {
        return ownerName == null || ownerName.isBlank() ? "-" : ownerName;
    }

    public String getOwnerUuid() {
        return ownerUuid == null ? "" : ownerUuid;
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
