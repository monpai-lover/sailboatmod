package com.example.examplemod.market;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MarketSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_market";

    private final Map<String, MarketListing> listings = new LinkedHashMap<>();
    private final Map<String, PurchaseOrder> purchaseOrders = new LinkedHashMap<>();
    private final Map<String, ShippingOrder> shippingOrders = new LinkedHashMap<>();
    private final Map<String, Integer> pendingCredits = new LinkedHashMap<>();

    public static MarketSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new MarketSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(MarketSavedData::load, MarketSavedData::new, DATA_NAME);
    }

    public static MarketSavedData load(CompoundTag tag) {
        MarketSavedData data = new MarketSavedData();
        ListTag listingTag = tag.getList("Listings", Tag.TAG_COMPOUND);
        for (Tag raw : listingTag) {
            if (raw instanceof CompoundTag compound) {
                MarketListing listing = MarketListing.load(compound);
                if (!listing.listingId().isBlank()) {
                    data.listings.put(listing.listingId(), listing);
                }
            }
        }
        ListTag orderTag = tag.getList("PurchaseOrders", Tag.TAG_COMPOUND);
        for (Tag raw : orderTag) {
            if (raw instanceof CompoundTag compound) {
                PurchaseOrder order = PurchaseOrder.load(compound);
                if (!order.orderId().isBlank()) {
                    data.purchaseOrders.put(order.orderId(), order);
                }
            }
        }
        ListTag shippingTag = tag.getList("ShippingOrders", Tag.TAG_COMPOUND);
        for (Tag raw : shippingTag) {
            if (raw instanceof CompoundTag compound) {
                ShippingOrder order = ShippingOrder.load(compound);
                if (!order.shippingOrderId().isBlank()) {
                    data.shippingOrders.put(order.shippingOrderId(), order);
                }
            }
        }
        CompoundTag creditsTag = tag.getCompound("PendingCredits");
        for (String key : creditsTag.getAllKeys()) {
            data.pendingCredits.put(key, Math.max(0, creditsTag.getInt(key)));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag listingTag = new ListTag();
        for (MarketListing listing : listings.values()) {
            listingTag.add(listing.save());
        }
        tag.put("Listings", listingTag);

        ListTag orderTag = new ListTag();
        for (PurchaseOrder order : purchaseOrders.values()) {
            orderTag.add(order.save());
        }
        tag.put("PurchaseOrders", orderTag);

        ListTag shippingTag = new ListTag();
        for (ShippingOrder order : shippingOrders.values()) {
            shippingTag.add(order.save());
        }
        tag.put("ShippingOrders", shippingTag);

        CompoundTag creditsTag = new CompoundTag();
        for (Map.Entry<String, Integer> entry : pendingCredits.entrySet()) {
            creditsTag.putInt(entry.getKey(), Math.max(0, entry.getValue()));
        }
        tag.put("PendingCredits", creditsTag);
        return tag;
    }

    public List<MarketListing> getListings() {
        List<MarketListing> out = new ArrayList<>();
        for (MarketListing listing : listings.values()) {
            if (listing.availableCount() > 0) {
                out.add(listing);
            }
        }
        return out;
    }

    public List<PurchaseOrder> getOrdersForBuyer(String buyerUuid) {
        List<PurchaseOrder> out = new ArrayList<>();
        for (PurchaseOrder order : purchaseOrders.values()) {
            if (buyerUuid != null && !buyerUuid.isBlank() && buyerUuid.equals(order.buyerUuid())) {
                out.add(order);
            }
        }
        return out;
    }

    public List<PurchaseOrder> getOpenOrdersForSourceDock(net.minecraft.core.BlockPos sourceDockPos) {
        List<PurchaseOrder> out = new ArrayList<>();
        if (sourceDockPos == null) {
            return out;
        }
        for (PurchaseOrder order : purchaseOrders.values()) {
            if (sourceDockPos.equals(order.sourceDockPos()) && "WAITING_SHIPMENT".equals(order.status())) {
                out.add(order);
            }
        }
        return out;
    }

    public List<ShippingOrder> getShippingOrdersForPlayer(String playerUuid) {
        List<ShippingOrder> out = new ArrayList<>();
        for (ShippingOrder order : shippingOrders.values()) {
            if (playerUuid != null && !playerUuid.isBlank() && playerUuid.equals(order.shipperUuid())) {
                out.add(order);
            }
        }
        return out;
    }

    public void putListing(MarketListing listing) {
        listings.put(listing.listingId(), listing);
        setDirty();
    }

    public void putPurchaseOrder(PurchaseOrder order) {
        purchaseOrders.put(order.orderId(), order);
        setDirty();
    }

    public void putShippingOrder(ShippingOrder order) {
        shippingOrders.put(order.shippingOrderId(), order);
        setDirty();
    }

    public PurchaseOrder getPurchaseOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return null;
        }
        return purchaseOrders.get(orderId);
    }

    public ShippingOrder getShippingOrder(String shippingOrderId) {
        if (shippingOrderId == null || shippingOrderId.isBlank()) {
            return null;
        }
        return shippingOrders.get(shippingOrderId);
    }

    public int getPendingCredits(String sellerUuid) {
        if (sellerUuid == null || sellerUuid.isBlank()) {
            return 0;
        }
        return Math.max(0, pendingCredits.getOrDefault(sellerUuid, 0));
    }

    public void addPendingCredits(String sellerUuid, int amount) {
        if (sellerUuid == null || sellerUuid.isBlank() || amount <= 0) {
            return;
        }
        pendingCredits.put(sellerUuid, getPendingCredits(sellerUuid) + amount);
        setDirty();
    }

    public int clearPendingCredits(String sellerUuid) {
        if (sellerUuid == null || sellerUuid.isBlank()) {
            return 0;
        }
        int amount = getPendingCredits(sellerUuid);
        pendingCredits.remove(sellerUuid);
        if (amount > 0) {
            setDirty();
        }
        return amount;
    }

    public void removeListing(String listingId) {
        if (listingId == null || listingId.isBlank()) {
            return;
        }
        if (listings.remove(listingId) != null) {
            setDirty();
        }
    }

    public MarketListing getListingByVisibleIndex(int index) {
        List<MarketListing> visible = getListings();
        if (index < 0 || index >= visible.size()) {
            return null;
        }
        return visible.get(index);
    }

    public MarketListing getListing(String listingId) {
        if (listingId == null || listingId.isBlank()) {
            return null;
        }
        return listings.get(listingId);
    }

    public PurchaseOrder getSourceOrderByIndex(net.minecraft.core.BlockPos sourceDockPos, int index) {
        List<PurchaseOrder> orders = getOpenOrdersForSourceDock(sourceDockPos);
        if (index < 0 || index >= orders.size()) {
            return null;
        }
        return orders.get(index);
    }

    public String nextId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
