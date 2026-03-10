package com.example.examplemod.market;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

public record ShipmentManifestEntry(
        String listingId,
        ItemStack itemStack,
        String purchaseOrderId,
        String shippingOrderId,
        String recipientUuid,
        String recipientName,
        int quantity
) {
    public ShipmentManifestEntry {
        listingId = sanitize(listingId);
        itemStack = itemStack == null ? ItemStack.EMPTY : itemStack.copy();
        purchaseOrderId = sanitize(purchaseOrderId);
        shippingOrderId = sanitize(shippingOrderId);
        recipientUuid = sanitize(recipientUuid);
        recipientName = sanitize(recipientName);
        quantity = Math.max(0, quantity);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("ListingId", listingId);
        tag.put("ItemStack", itemStack.save(new CompoundTag()));
        tag.putString("PurchaseOrderId", purchaseOrderId);
        tag.putString("ShippingOrderId", shippingOrderId);
        tag.putString("RecipientUuid", recipientUuid);
        tag.putString("RecipientName", recipientName);
        tag.putInt("Quantity", quantity);
        return tag;
    }

    public static ShipmentManifestEntry load(CompoundTag tag) {
        return new ShipmentManifestEntry(
                tag.getString("ListingId"),
                ItemStack.of(tag.getCompound("ItemStack")),
                tag.getString("PurchaseOrderId"),
                tag.getString("ShippingOrderId"),
                tag.getString("RecipientUuid"),
                tag.getString("RecipientName"),
                tag.getInt("Quantity")
        );
    }

    public boolean isMarketOrder() {
        return !purchaseOrderId.isBlank();
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
