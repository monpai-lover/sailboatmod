package com.monpai.sailboatmod.market;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

public record MarketListing(
        String listingId,
        String sellerUuid,
        String sellerName,
        ItemStack itemStack,
        int unitPrice,
        int availableCount,
        int reservedCount,
        BlockPos sourceDockPos,
        String sourceDockName,
        String townId,
        String nationId
) {
    public MarketListing {
        listingId = sanitize(listingId);
        sellerUuid = sanitize(sellerUuid);
        sellerName = sanitize(sellerName);
        itemStack = itemStack == null ? ItemStack.EMPTY : itemStack.copy();
        unitPrice = Math.max(0, unitPrice);
        availableCount = Math.max(0, availableCount);
        reservedCount = Math.max(0, reservedCount);
        sourceDockPos = sourceDockPos == null ? BlockPos.ZERO : sourceDockPos.immutable();
        sourceDockName = sanitize(sourceDockName);
        townId = sanitize(townId);
        nationId = sanitize(nationId);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("ListingId", listingId);
        tag.putString("SellerUuid", sellerUuid);
        tag.putString("SellerName", sellerName);
        tag.put("ItemStack", itemStack.save(new CompoundTag()));
        tag.putInt("UnitPrice", unitPrice);
        tag.putInt("AvailableCount", availableCount);
        tag.putInt("ReservedCount", reservedCount);
        tag.putLong("SourceDockPos", sourceDockPos.asLong());
        tag.putString("SourceDockName", sourceDockName);
        tag.putString("TownId", townId);
        tag.putString("NationId", nationId);
        return tag;
    }

    public static MarketListing load(CompoundTag tag) {
        return new MarketListing(
                tag.getString("ListingId"),
                tag.getString("SellerUuid"),
                tag.getString("SellerName"),
                ItemStack.of(tag.getCompound("ItemStack")),
                tag.getInt("UnitPrice"),
                tag.getInt("AvailableCount"),
                tag.getInt("ReservedCount"),
                BlockPos.of(tag.getLong("SourceDockPos")),
                tag.getString("SourceDockName"),
                tag.contains("TownId") ? tag.getString("TownId") : "",
                tag.contains("NationId") ? tag.getString("NationId") : ""
        );
    }

    public String toSummaryLine() {
        String itemName = itemStack.isEmpty() ? "-" : itemStack.getHoverName().getString();
        return String.format(
                Locale.ROOT,
                "%s x%d | %d ea | %s",
                itemName,
                availableCount,
                unitPrice,
                sourceDockName.isBlank() ? sourceDockPos.toShortString() : sourceDockName
        );
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
