package com.monpai.sailboatmod.transport.rail;

import com.monpai.sailboatmod.block.entity.TownWarehouseBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Stored with the warehouse inventory so an insertion and its receipt share a chunk snapshot. */
public final class RailWarehouseReceipts {
    private static final Pattern SHIPPING_ID = Pattern.compile("[0-9a-f]{32}");
    private static final int VERSION = 1;
    private static final int MAX_RECEIPTS = 4096;
    private static final int MAX_QUANTITY = TownWarehouseBlockEntity.STORAGE_SIZE * 64;
    private static final Set<String> ROOT_KEYS = Set.of("Version", "Entries");
    private static final Set<String> ENTRY_KEYS = Set.of("ShippingOrderId", "BuyerId", "Quantity");

    public enum Result { INSERTED, ALREADY_RECEIVED, REJECTED }

    private record Receipt(UUID buyerId, int quantity) {}

    private final Map<String, Receipt> receipts = new LinkedHashMap<>();
    private final @Nullable Tag invalidEncoding;

    private RailWarehouseReceipts(@Nullable Tag invalidEncoding) {
        this.invalidEncoding = invalidEncoding;
    }

    public static RailWarehouseReceipts load(@Nullable Tag encoded) {
        RailWarehouseReceipts loaded = new RailWarehouseReceipts(null);
        if (encoded == null) return loaded;
        if (!(encoded instanceof CompoundTag root) || !root.getAllKeys().equals(ROOT_KEYS)
                || !root.contains("Version", Tag.TAG_INT) || root.getInt("Version") != VERSION
                || !(root.get("Entries") instanceof ListTag entries) || entries.size() > MAX_RECEIPTS
                || !entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND) {
            return new RailWarehouseReceipts(encoded.copy());
        }

        for (Tag entry : entries) {
            CompoundTag row = (CompoundTag) entry;
            String order = row.getString("ShippingOrderId");
            int quantity = row.getInt("Quantity");
            if (!row.getAllKeys().equals(ENTRY_KEYS) || !row.contains("ShippingOrderId", Tag.TAG_STRING)
                    || !isShippingId(order) || !row.hasUUID("BuyerId") || !row.contains("Quantity", Tag.TAG_INT)
                    || !isQuantity(quantity) || loaded.receipts.putIfAbsent(order, new Receipt(row.getUUID("BuyerId"), quantity)) != null) {
                return new RailWarehouseReceipts(encoded.copy());
            }
        }
        return loaded;
    }

    public @Nullable Tag save() {
        if (invalidEncoding != null) return invalidEncoding.copy();
        if (receipts.isEmpty()) return null;
        ListTag entries = new ListTag();
        for (Map.Entry<String, Receipt> entry : receipts.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putString("ShippingOrderId", entry.getKey());
            row.putUUID("BuyerId", entry.getValue().buyerId());
            row.putInt("Quantity", entry.getValue().quantity());
            entries.add(row);
        }
        CompoundTag encoded = new CompoundTag();
        encoded.putInt("Version", VERSION);
        encoded.put("Entries", entries);
        return encoded;
    }

    public boolean hasReceipt(String order, UUID buyer, int quantity) {
        if (invalidEncoding != null || !isShippingId(order) || buyer == null || !isQuantity(quantity)) return false;
        return new Receipt(buyer, quantity).equals(receipts.get(order));
    }

    /** The callback must stage the entire inventory change before writing it on the server thread. */
    public Result insertOnce(String order, UUID buyer, List<ItemStack> cargo, BooleanSupplier insertInventory) {
        if (invalidEncoding != null || !isShippingId(order) || buyer == null || cargo == null || insertInventory == null) {
            return Result.REJECTED;
        }

        int quantity = 0;
        for (ItemStack stack : cargo) {
            if (stack == null || stack.isEmpty() || RailCargoCustody.hasMarker(stack)
                    || stack.getCount() > MAX_QUANTITY - quantity) return Result.REJECTED;
            quantity += stack.getCount();
        }
        if (!isQuantity(quantity)) return Result.REJECTED;

        Receipt expected = new Receipt(buyer, quantity);
        Receipt previous = receipts.get(order);
        if (previous != null) return previous.equals(expected) ? Result.ALREADY_RECEIVED : Result.REJECTED;
        if (receipts.size() >= MAX_RECEIPTS || !insertInventory.getAsBoolean()) return Result.REJECTED;

        receipts.put(order, expected);
        return Result.INSERTED;
    }

    private static boolean isShippingId(String id) {
        return id != null && id.length() == 32 && SHIPPING_ID.matcher(id).matches();
    }

    private static boolean isQuantity(int quantity) {
        return quantity > 0 && quantity <= MAX_QUANTITY;
    }
}
