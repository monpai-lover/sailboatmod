package com.monpai.sailboatmod.nation.model;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record TradeProposalRecord(
        String proposalId,
        String proposerNationId,
        String targetNationId,
        long offerCurrency,
        List<ItemStack> offerItems,
        long requestCurrency,
        List<ItemStack> requestItems,
        long createdAt
) {
    public static final long EXPIRY_MILLIS = 300_000L;

    public TradeProposalRecord {
        proposalId = proposalId == null ? "" : proposalId.trim();
        proposerNationId = proposerNationId == null ? "" : proposerNationId.trim().toLowerCase(Locale.ROOT);
        targetNationId = targetNationId == null ? "" : targetNationId.trim().toLowerCase(Locale.ROOT);
        offerCurrency = Math.max(0L, offerCurrency);
        requestCurrency = Math.max(0L, requestCurrency);
        if (offerItems == null) offerItems = List.of();
        if (requestItems == null) requestItems = List.of();
    }

    public boolean isExpired() {
        return System.currentTimeMillis() - createdAt > EXPIRY_MILLIS;
    }

    public String key() {
        String a = proposerNationId.compareTo(targetNationId) <= 0 ? proposerNationId : targetNationId;
        String b = proposerNationId.compareTo(targetNationId) <= 0 ? targetNationId : proposerNationId;
        return a + "|" + b;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", proposalId);
        tag.putString("Proposer", proposerNationId);
        tag.putString("Target", targetNationId);
        tag.putLong("OfferCurrency", offerCurrency);
        tag.putLong("RequestCurrency", requestCurrency);
        tag.putLong("CreatedAt", createdAt);
        ListTag offerList = new ListTag();
        for (ItemStack item : offerItems) { if (!item.isEmpty()) offerList.add(item.save(new CompoundTag())); }
        tag.put("OfferItems", offerList);
        ListTag requestList = new ListTag();
        for (ItemStack item : requestItems) { if (!item.isEmpty()) requestList.add(item.save(new CompoundTag())); }
        tag.put("RequestItems", requestList);
        return tag;
    }

    public static TradeProposalRecord load(CompoundTag tag) {
        List<ItemStack> offerItems = new ArrayList<>();
        ListTag offerList = tag.getList("OfferItems", Tag.TAG_COMPOUND);
        for (int i = 0; i < offerList.size(); i++) offerItems.add(ItemStack.of(offerList.getCompound(i)));
        List<ItemStack> requestItems = new ArrayList<>();
        ListTag requestList = tag.getList("RequestItems", Tag.TAG_COMPOUND);
        for (int i = 0; i < requestList.size(); i++) requestItems.add(ItemStack.of(requestList.getCompound(i)));
        return new TradeProposalRecord(
                tag.getString("Id"), tag.getString("Proposer"), tag.getString("Target"),
                tag.getLong("OfferCurrency"), offerItems,
                tag.getLong("RequestCurrency"), requestItems,
                tag.getLong("CreatedAt"));
    }
}
