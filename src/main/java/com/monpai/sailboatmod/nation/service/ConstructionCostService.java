package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.economy.VaultEconomyBridge;
import com.monpai.sailboatmod.market.MarketSavedData;
import com.monpai.sailboatmod.market.commodity.CommodityMarketService;
import com.monpai.sailboatmod.market.commodity.CommodityQuote;
import com.monpai.sailboatmod.market.commodity.MarketTradeSide;
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

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ConstructionCostService {
    private static final CommodityMarketService COMMODITY_MARKET = new CommodityMarketService();

    public record MaterialCost(ItemStack stack, int count, long totalCost) {
    }

    public record BlueprintCostEstimate(List<MaterialCost> materials, long totalCost) {
    }

    public record PaymentResult(boolean success, long totalCost, long treasurySpent, long walletSpent, Component message) {
    }

    private ConstructionCostService() {
    }

    public static BlueprintCostEstimate estimateBlueprintCost(ServerLevel level, BlueprintService.BlueprintPlacement placement) {
        Map<Item, Integer> requiredCounts = collectRequiredCounts(placement);
        List<MaterialCost> materials = new ArrayList<>();
        long totalCost = 0L;
        for (Map.Entry<Item, Integer> entry : requiredCounts.entrySet()) {
            ItemStack stack = new ItemStack(entry.getKey(), entry.getValue());
            long materialCost = estimatePurchaseCost(MarketSavedData.get(level), stack, entry.getValue());
            materials.add(new MaterialCost(new ItemStack(entry.getKey()), entry.getValue(), materialCost));
            totalCost += materialCost;
        }
        materials.sort(Comparator.comparing(material -> material.stack().getHoverName().getString(), String.CASE_INSENSITIVE_ORDER));
        return new BlueprintCostEstimate(List.copyOf(materials), totalCost);
    }

    public static PaymentResult chargeForBlueprint(ServerLevel level, ServerPlayer player, BlueprintService.BlueprintPlacement placement) {
        Map<Item, Integer> requiredCounts = collectRequiredCounts(placement);
        BlueprintCostEstimate estimate = estimateBlueprintCost(level, placement);
        PaymentResult result = chargeAmount(level, player, estimate.totalCost(),
                "Construction cost is too large to process.",
                "Not enough treasury/wallet funds for this blueprint.",
                "Construction cost paid: %d (treasury %d, wallet %d).");
        if (result.success()) {
            applyCommodityPurchases(level, player, requiredCounts);
        }
        return result;
    }

    public static PaymentResult chargeForSingleItem(ServerLevel level, ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return new PaymentResult(true, 0L, 0L, 0L, Component.empty());
        }
        long totalCost = estimateCommodityCost(stack, stack.getCount());
        String itemName = stack.getHoverName().getString();
        PaymentResult result = chargeAmount(level, player, totalCost,
                "Construction material cost is too large to process.",
                "Not enough treasury/wallet funds for " + itemName + ".",
                "Auto-purchased " + itemName + " for " + totalCost + " (treasury %d, wallet %d).");
        if (result.success()) {
            applyCommodityPurchase(level, player, stack, stack.getCount());
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
        return estimateCommodityCost(targetStack, quantity);
    }

    public static int estimateFallbackUnitPrice(ItemStack stack) {
        return defaultUnitPrice(stack);
    }

    private static Map<Item, Integer> collectRequiredCounts(BlueprintService.BlueprintPlacement placement) {
        Map<Item, Integer> requiredCounts = new LinkedHashMap<>();
        for (StructureTemplate.StructureBlockInfo info : placement.blocks()) {
            Item item = info.state().getBlock().asItem();
            if (item == Items.AIR) {
                continue;
            }
            requiredCounts.merge(item, 1, Integer::sum);
        }
        return requiredCounts;
    }

    private static long estimateCommodityCost(ItemStack stack, int quantity) {
        if (stack == null || stack.isEmpty() || quantity <= 0) {
            return 0L;
        }
        try {
            CommodityQuote quote = COMMODITY_MARKET.quote(stack, quantity);
            return quote.buyPrice();
        } catch (SQLException ignored) {
            return (long) quantity * defaultUnitPrice(stack);
        }
    }

    private static void applyCommodityPurchases(ServerLevel level, ServerPlayer player, Map<Item, Integer> requiredCounts) {
        String actorUuid = player == null ? "" : player.getUUID().toString();
        String actorName = player == null ? "" : player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName();
        String nationId = resolveNationId(level, player);
        for (Map.Entry<Item, Integer> entry : requiredCounts.entrySet()) {
            applyCommodityPurchase(level, actorUuid, actorName, nationId, new ItemStack(entry.getKey()), entry.getValue());
        }
    }

    private static void applyCommodityPurchase(ServerLevel level, ServerPlayer player, ItemStack stack, int quantity) {
        String actorUuid = player == null ? "" : player.getUUID().toString();
        String actorName = player == null ? "" : player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName();
        String nationId = resolveNationId(level, player);
        applyCommodityPurchase(level, actorUuid, actorName, nationId, stack, quantity);
    }

    private static void applyCommodityPurchase(ServerLevel level, String actorUuid, String actorName, String nationId,
                                               ItemStack stack, int quantity) {
        if (stack == null || stack.isEmpty() || quantity <= 0) {
            return;
        }
        try {
            COMMODITY_MARKET.applyTrade(
                    stack,
                    MarketTradeSide.BUY,
                    quantity,
                    "construction",
                    nationId,
                    nationId,
                    actorUuid,
                    actorName
            );
        } catch (SQLException ignored) {
        }
    }

    private static String resolveNationId(ServerLevel level, ServerPlayer player) {
        if (level == null || player == null) {
            return "";
        }
        NationSavedData nationData = NationSavedData.get(level);
        NationMemberRecord member = nationData.getMember(player.getUUID());
        return member == null ? "" : member.nationId();
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
}