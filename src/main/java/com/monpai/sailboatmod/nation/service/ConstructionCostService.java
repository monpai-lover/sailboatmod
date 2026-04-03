package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.economy.GoldStandardEconomy;
import com.monpai.sailboatmod.market.MarketSavedData;
import com.monpai.sailboatmod.market.ProcurementRecord;
import com.monpai.sailboatmod.market.ProcurementService;
import com.monpai.sailboatmod.market.commodity.CommodityKeyResolver;
import com.monpai.sailboatmod.market.commodity.CommodityMarketService;
import com.monpai.sailboatmod.market.commodity.CommodityQuote;
import com.monpai.sailboatmod.market.commodity.MarketTradeSide;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationTreasuryRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
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

    private record CommodityDemand(String commodityKey, ItemStack template, int requiredCount) {
    }

    private record StockpileAllocation(String commodityKey, ItemStack template, int requiredCount,
                                       int stockpileUsedCount, int purchasedCount, long purchaseCost) {
    }

    private record PurchasePlan(String townId, List<StockpileAllocation> allocations, long totalPurchaseCost) {
    }

    private ConstructionCostService() {
    }

    public static BlueprintCostEstimate estimateBlueprintCost(ServerLevel level, BlueprintService.BlueprintPlacement placement) {
        List<CommodityDemand> requiredDemands = collectRequiredDemands(placement);
        List<MaterialCost> materials = new ArrayList<>();
        long totalCost = 0L;
        for (CommodityDemand demand : requiredDemands) {
            long materialCost = estimatePurchaseCost(MarketSavedData.get(level), demand.template(), demand.requiredCount());
            materials.add(new MaterialCost(demand.template(), demand.requiredCount(), materialCost));
            totalCost += materialCost;
        }
        materials.sort(Comparator.comparing(material -> material.stack().getHoverName().getString(), String.CASE_INSENSITIVE_ORDER));
        return new BlueprintCostEstimate(List.copyOf(materials), totalCost);
    }

    public static PaymentResult chargeForBlueprint(ServerLevel level, ServerPlayer player, BlueprintService.BlueprintPlacement placement) {
        return chargeForBlueprint(level, player, placement, defaultProjectId(placement));
    }

    public static PaymentResult chargeForBlueprint(ServerLevel level, ServerPlayer player,
                                                   BlueprintService.BlueprintPlacement placement, String projectId) {
        PurchasePlan purchasePlan = buildPurchasePlan(level, player, collectRequiredDemands(placement));
        PaymentResult result = chargeAmount(level, player, purchasePlan.totalPurchaseCost(),
                "Construction cost is too large to process.",
                "Not enough treasury/wallet funds for this blueprint.",
                "Construction cost paid: %d (treasury %d, wallet %d).");
        if (result.success() && !player.getAbilities().instabuild) {
            applyPurchasePlan(level, player, purchasePlan, projectId);
        }
        return result;
    }

    public static PaymentResult chargeForSingleItem(ServerLevel level, ServerPlayer player, ItemStack stack) {
        return chargeForSingleItem(level, player, stack, defaultProjectId(stack));
    }

    public static PaymentResult chargeForSingleItem(ServerLevel level, ServerPlayer player, ItemStack stack, String projectId) {
        if (stack == null || stack.isEmpty()) {
            return new PaymentResult(true, 0L, 0L, 0L, Component.empty());
        }
        PurchasePlan purchasePlan = buildPurchasePlan(level, player, List.of(new CommodityDemand(
                CommodityKeyResolver.resolve(stack),
                CommodityKeyResolver.normalizedTemplate(stack),
                stack.getCount()
        )));
        long totalCost = purchasePlan.totalPurchaseCost();
        String itemName = stack.getHoverName().getString();
        PaymentResult result = chargeAmount(level, player, totalCost,
                "Construction material cost is too large to process.",
                "Not enough treasury/wallet funds for " + itemName + ".",
                "Auto-purchased " + itemName + " for " + totalCost + " (treasury %d, wallet %d).");
        if (result.success() && !player.getAbilities().instabuild) {
            applyPurchasePlan(level, player, purchasePlan, projectId);
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
            Boolean walletResult = GoldStandardEconomy.tryWithdraw(player, walletSpent);
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
                Component.literal(String.format(Locale.ROOT, successFormat,
                        GoldStandardEconomy.formatBalance(treasurySpent),
                        GoldStandardEconomy.formatBalance(walletSpent))));
    }

    public static long estimatePurchaseCost(MarketSavedData marketData, ItemStack targetStack, int quantity) {
        return estimateCommodityCost(targetStack, quantity);
    }

    public static int estimateFallbackUnitPrice(ItemStack stack) {
        return defaultUnitPrice(stack);
    }

    private static List<CommodityDemand> collectRequiredDemands(BlueprintService.BlueprintPlacement placement) {
        Map<String, CommodityDemandAccumulator> requiredCounts = new LinkedHashMap<>();
        for (StructureTemplate.StructureBlockInfo info : placement.blocks()) {
            Item item = info.state().getBlock().asItem();
            if (item == Items.AIR) {
                continue;
            }
            ItemStack template = new ItemStack(item);
            String commodityKey = CommodityKeyResolver.resolve(template);
            requiredCounts.computeIfAbsent(commodityKey,
                    ignored -> new CommodityDemandAccumulator(commodityKey, CommodityKeyResolver.normalizedTemplate(template)))
                    .increment();
        }
        List<CommodityDemand> demands = new ArrayList<>(requiredCounts.size());
        for (CommodityDemandAccumulator accumulator : requiredCounts.values()) {
            demands.add(accumulator.toDemand());
        }
        return List.copyOf(demands);
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
            COMMODITY_MARKET.quoteWithoutStockChange(
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

    private static String resolveTownId(ServerLevel level, ServerPlayer player) {
        if (level == null || player == null) {
            return "";
        }
        NationSavedData nationData = NationSavedData.get(level);
        NationMemberRecord member = nationData.getMember(player.getUUID());
        TownRecord town = TownService.getTownForMember(nationData, member);
        return town == null ? "" : town.townId();
    }

    private static PurchasePlan buildPurchasePlan(ServerLevel level, ServerPlayer player, List<CommodityDemand> demands) {
        String townId = resolveTownId(level, player);
        List<StockpileAllocation> allocations = new ArrayList<>(demands.size());
        long totalPurchaseCost = 0L;
        for (CommodityDemand demand : demands) {
            int stockpileAvailable = townId.isBlank() ? 0 : TownStockpileService.getAvailable(level, townId, demand.commodityKey());
            int stockpileUsed = Math.min(stockpileAvailable, demand.requiredCount());
            int purchasedCount = Math.max(0, demand.requiredCount() - stockpileUsed);
            long purchaseCost = 0L;
            if (purchasedCount > 0) {
                purchaseCost = estimateCommodityCost(demand.template(), purchasedCount);
                totalPurchaseCost += purchaseCost;
            }
            allocations.add(new StockpileAllocation(
                    demand.commodityKey(),
                    demand.template(),
                    demand.requiredCount(),
                    stockpileUsed,
                    purchasedCount,
                    purchaseCost
            ));
        }
        return new PurchasePlan(townId, List.copyOf(allocations), totalPurchaseCost);
    }

    private static void applyPurchasePlan(ServerLevel level, ServerPlayer player, PurchasePlan purchasePlan, String projectId) {
        for (StockpileAllocation allocation : purchasePlan.allocations()) {
            ConstructionMaterialRequestService.recordRequest(
                    level,
                    projectId,
                    purchasePlan.townId(),
                    allocation.commodityKey(),
                    allocation.requiredCount(),
                    allocation.stockpileUsedCount(),
                    allocation.purchasedCount()
            );
            if (allocation.stockpileUsedCount() > 0 && !purchasePlan.townId().isBlank()) {
                TownStockpileService.remove(level, purchasePlan.townId(), allocation.commodityKey(), allocation.stockpileUsedCount());
            }
            if (allocation.purchasedCount() > 0) {
                ProcurementRecord procurement = ProcurementService.createProcurement(
                        level,
                        purchasePlan.townId(),
                        "",
                        allocation.commodityKey(),
                        allocation.purchasedCount(),
                        allocation.purchasedCount() <= 0 ? 0L : allocation.purchaseCost() / allocation.purchasedCount(),
                        allocation.purchaseCost(),
                        "CONSTRUCTION",
                        projectId,
                        "",
                        "",
                        ""
                );
                if (!purchasePlan.townId().isBlank() && allocation.purchaseCost() > 0L) {
                    TownFinanceLedgerService.recordExpense(
                            level,
                            purchasePlan.townId(),
                            "CONSTRUCTION_EXPENSE",
                            allocation.purchaseCost(),
                            GoldStandardEconomy.LEDGER_CURRENCY,
                            allocation.commodityKey(),
                            allocation.purchasedCount(),
                            procurement == null ? projectId : procurement.procurementId()
                    );
                }
                applyCommodityPurchase(level, player, allocation.template(), allocation.purchasedCount());
                TownDeliveryService.deliverDirectProcurement(
                        level,
                        purchasePlan.townId(),
                        allocation.template(),
                        allocation.purchasedCount(),
                        procurement == null ? "" : procurement.procurementId()
                );
                if (!purchasePlan.townId().isBlank()) {
                    TownStockpileService.remove(level, purchasePlan.townId(), allocation.commodityKey(), allocation.purchasedCount());
                }
            }
        }
    }

    private static String defaultProjectId(BlueprintService.BlueprintPlacement placement) {
        if (placement == null || placement.bounds() == null) {
            return "construction:" + System.currentTimeMillis();
        }
        return "construction:" + placement.blueprintId() + ":" + placement.bounds().min().asLong() + ":" + placement.rotation();
    }

    private static String defaultProjectId(ItemStack stack) {
        return "construction_item:" + CommodityKeyResolver.resolve(stack);
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
        return GoldStandardEconomy.BALANCE_PER_GOLD_INGOT;
    }

    private static final class CommodityDemandAccumulator {
        private final String commodityKey;
        private final ItemStack template;
        private int requiredCount;

        private CommodityDemandAccumulator(String commodityKey, ItemStack template) {
            this.commodityKey = commodityKey;
            this.template = template;
        }

        private CommodityDemandAccumulator increment() {
            requiredCount++;
            return this;
        }

        private CommodityDemand toDemand() {
            return new CommodityDemand(commodityKey, template, requiredCount);
        }
    }
}
