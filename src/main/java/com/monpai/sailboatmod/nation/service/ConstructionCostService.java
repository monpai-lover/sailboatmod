package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.economy.VaultEconomyBridge;
import com.monpai.sailboatmod.market.MarketListing;
import com.monpai.sailboatmod.market.MarketSavedData;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationTreasuryRecord;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ConstructionCostService {
    private record PurchaseSlice(String listingId, int quantity, long totalCost) {
    }

    public record MaterialCost(ItemStack stack, int count, long totalCost) {
    }

    public record BlueprintCostEstimate(List<MaterialCost> materials, long totalCost) {
    }

    public record PaymentResult(boolean success, long totalCost, long treasurySpent, long walletSpent, Component message) {
    }

    private ConstructionCostService() {
    }

    public static BlueprintCostEstimate estimateBlueprintCost(ServerLevel level, BlueprintService.BlueprintPlacement placement) {
        Map<Item, Integer> requiredCounts = new LinkedHashMap<>();
        for (StructureTemplate.StructureBlockInfo info : placement.blocks()) {
            Item item = info.state().getBlock().asItem();
            if (item == Items.AIR) {
                continue;
            }
            requiredCounts.merge(item, 1, Integer::sum);
        }

        List<MaterialCost> materials = new ArrayList<>();
        long totalCost = 0L;
        MarketSavedData marketData = MarketSavedData.get(level);
        for (Map.Entry<Item, Integer> entry : requiredCounts.entrySet()) {
            ItemStack stack = new ItemStack(entry.getKey());
            int count = entry.getValue();
            long materialCost = estimatePurchaseCost(marketData, stack, count);
            materials.add(new MaterialCost(stack, count, materialCost));
            totalCost += materialCost;
        }
        materials.sort(Comparator.comparing(material -> material.stack().getHoverName().getString(), String.CASE_INSENSITIVE_ORDER));
        return new BlueprintCostEstimate(List.copyOf(materials), totalCost);
    }

    public static PaymentResult chargeForBlueprint(ServerLevel level, ServerPlayer player, BlueprintService.BlueprintPlacement placement) {
        BlueprintCostEstimate estimate = estimateBlueprintCost(level, placement);
        Map<Item, Integer> requiredCounts = new LinkedHashMap<>();
        for (StructureTemplate.StructureBlockInfo info : placement.blocks()) {
            Item item = info.state().getBlock().asItem();
            if (item != Items.AIR) {
                requiredCounts.merge(item, 1, Integer::sum);
            }
        }

        List<PurchaseSlice> consumedListings = new ArrayList<>();
        long listingCost = 0L;
        MarketSavedData marketData = MarketSavedData.get(level);
        for (Map.Entry<Item, Integer> entry : requiredCounts.entrySet()) {
            PurchasePlan plan = buildPurchasePlan(marketData, new ItemStack(entry.getKey()), entry.getValue());
            consumedListings.addAll(plan.slices());
            listingCost += plan.marketCost();
        }

        PaymentResult result = chargeAmount(level, player, estimate.totalCost(),
                "Construction cost is too large to process.",
                "Not enough treasury/wallet funds for this blueprint.",
                "Construction cost paid: %d (treasury %d, wallet %d).");
        if (result.success()) {
            applyPurchaseSlices(marketData, consumedListings);
        }
        return result;
    }

    public static PaymentResult chargeForSingleItem(ServerLevel level, ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return new PaymentResult(true, 0L, 0L, 0L, Component.empty());
        }
        MarketSavedData marketData = MarketSavedData.get(level);
        PurchasePlan plan = buildPurchasePlan(marketData, stack, stack.getCount());
        long totalCost = plan.marketCost() + plan.fallbackCost();
        String itemName = stack.getHoverName().getString();
        PaymentResult result = chargeAmount(level, player, totalCost,
                "Construction material cost is too large to process.",
                "Not enough treasury/wallet funds for " + itemName + ".",
                "Auto-purchased " + itemName + " for " + totalCost + " (treasury %d, wallet %d).");
        if (result.success()) {
            applyPurchaseSlices(marketData, plan.slices());
        }
        return result;
    }

    private static PaymentResult chargeAmount(ServerLevel level, ServerPlayer player, long totalCost,
                                              String oversizedMessage, String insufficientFundsMessage,
                                              String successFormat) {
        if (totalCost <= 0L || player.getAbilities().instabuild) {
            return new PaymentResult(true, totalCost, 0L, 0L, Component.empty());
        }
        if (totalCost > Integer.MAX_VALUE) {
            return new PaymentResult(false, totalCost, 0L, 0L, Component.literal(oversizedMessage));
        }

        NationSavedData nationData = NationSavedData.get(level);
        NationMemberRecord member = nationData.getMember(player.getUUID());
        long treasuryAvailable = 0L;
        String nationId = "";
        if (member != null && !member.nationId().isBlank()) {
            nationId = member.nationId();
            NationTreasuryRecord treasury = nationData.getOrCreateTreasury(member.nationId());
            treasuryAvailable = Math.max(0L, treasury.currencyBalance());
        }

        long treasurySpent = Math.min(totalCost, treasuryAvailable);
        long walletSpent = totalCost - treasurySpent;

        if (walletSpent > 0L) {
            Boolean walletResult = VaultEconomyBridge.tryWithdraw(player, (int) walletSpent);
            if (!Boolean.TRUE.equals(walletResult)) {
                return new PaymentResult(false, totalCost, treasurySpent, walletSpent,
                        Component.literal(insufficientFundsMessage));
            }
        }

        if (treasurySpent > 0L && !nationId.isBlank()) {
            NationTreasuryRecord treasury = nationData.getOrCreateTreasury(nationId);
            nationData.putTreasury(treasury.withBalance(treasury.currencyBalance() - treasurySpent));
        }

        return new PaymentResult(true, totalCost, treasurySpent, walletSpent,
                Component.literal(String.format(Locale.ROOT, successFormat, treasurySpent, walletSpent)));
    }

    public static long estimatePurchaseCost(MarketSavedData marketData, ItemStack targetStack, int quantity) {
        PurchasePlan plan = buildPurchasePlan(marketData, targetStack, quantity);
        return plan.marketCost() + plan.fallbackCost();
    }

    public static int estimateFallbackUnitPrice(ItemStack stack) {
        return defaultUnitPrice(stack);
    }

    private static PurchasePlan buildPurchasePlan(MarketSavedData marketData, ItemStack targetStack, int quantity) {
        int remaining = Math.max(0, quantity);
        long marketCost = 0L;
        List<MarketListing> matchingListings = new ArrayList<>();
        for (MarketListing listing : marketData.getListings()) {
            if (ItemStack.isSameItem(listing.itemStack(), targetStack)) {
                matchingListings.add(listing);
            }
        }
        matchingListings.sort(Comparator.comparingInt(MarketListing::unitPrice));
        List<PurchaseSlice> slices = new ArrayList<>();

        for (MarketListing listing : matchingListings) {
            if (remaining <= 0) {
                break;
            }
            int purchased = Math.min(remaining, listing.availableCount());
            long sliceCost = (long) purchased * Math.max(1, listing.unitPrice());
            marketCost += sliceCost;
            slices.add(new PurchaseSlice(listing.listingId(), purchased, sliceCost));
            remaining -= purchased;
        }

        long fallbackCost = 0L;
        if (remaining > 0) {
            fallbackCost = (long) remaining * defaultUnitPrice(targetStack);
        }
        return new PurchasePlan(List.copyOf(slices), marketCost, fallbackCost);
    }

    private static void applyPurchaseSlices(MarketSavedData marketData, List<PurchaseSlice> slices) {
        for (PurchaseSlice slice : slices) {
            MarketListing listing = marketData.getListing(slice.listingId());
            if (listing == null) {
                continue;
            }
            int nextAvailable = Math.max(0, listing.availableCount() - slice.quantity());
            if (nextAvailable <= 0 && listing.reservedCount() <= 0) {
                marketData.removeListing(listing.listingId());
                continue;
            }
            marketData.putListing(new MarketListing(
                    listing.listingId(),
                    listing.sellerUuid(),
                    listing.sellerName(),
                    listing.itemStack(),
                    listing.unitPrice(),
                    nextAvailable,
                    listing.reservedCount(),
                    listing.sourceDockPos(),
                    listing.sourceDockName(),
                    listing.townId(),
                    listing.nationId()
            ));
        }
    }

    private static int defaultUnitPrice(ItemStack stack) {
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (path.contains("diamond") || path.contains("emerald") || path.contains("beacon")) {
            return 64;
        }
        if (path.contains("copper") || path.contains("iron") || path.contains("gold")) {
            return 18;
        }
        if (path.contains("glass") || path.contains("lantern") || path.contains("torch")) {
            return 8;
        }
        if (path.contains("brick") || path.contains("stone") || path.contains("slab") || path.contains("stairs")) {
            return 6;
        }
        if (path.contains("log") || path.contains("plank") || path.contains("wood") || path.contains("fence") || path.contains("door")) {
            return 5;
        }
        return 10;
    }

    private record PurchasePlan(List<PurchaseSlice> slices, long marketCost, long fallbackCost) {
    }
}
