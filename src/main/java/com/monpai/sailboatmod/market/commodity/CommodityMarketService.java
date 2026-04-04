package com.monpai.sailboatmod.market.commodity;

import com.monpai.sailboatmod.economy.GoldStandardEconomy;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.sql.SQLException;

public final class CommodityMarketService {
    private static final int INT_SCALE = 10_000;
    private static final int DEFAULT_BASE_PRICE = GoldStandardEconomy.BALANCE_PER_GOLD_INGOT;
    private static final int DEFAULT_VOLATILITY = 100;
    private static final int DEFAULT_SPREAD_BP = 500;
    private static final long PRICE_CHART_BUCKET_MS = 60L * 60L * 1000L;
    private static final int PRICE_CHART_BUCKET_COUNT = 24;

    private final CommodityMarketRepository repository = new CommodityMarketRepository();

    public CommodityQuote quote(ItemStack itemStack, int quantity) throws SQLException {
        return quote(itemStack, quantity, null);
    }

    public CommodityQuote quote(ItemStack itemStack, int quantity, String playerUuid) throws SQLException {
        CommoditySnapshot snapshot = ensureCommodity(itemStack);
        PlayerMarketSettings playerSettings = null;
        if (playerUuid != null && !playerUuid.isEmpty()) {
            playerSettings = repository.getPlayerSettings(playerUuid);
            if (playerSettings == null) {
                playerSettings = PlayerMarketSettings.defaultSettings(playerUuid);
            }
        }
        return buildQuote(snapshot, Math.max(1, quantity), playerSettings);
    }

    public CommodityMarketState adjustStock(ItemStack itemStack, int delta) throws SQLException {
        CommoditySnapshot snapshot = ensureCommodity(itemStack);
        CommodityMarketState current = snapshot.state();
        CommodityMarketState updated = new CommodityMarketState(
                current.commodityKey(),
                current.basePrice(),
                current.currentStock() + delta,
                current.volatility(),
                current.spreadBp(),
                current.stockFloor(),
                current.stockCeil(),
                current.priceFloor(),
                current.priceCeil(),
                current.lastTradeAt(),
                System.currentTimeMillis(),
                current.version() + 1
        );
        repository.upsertState(updated);
        return updated;
    }

    public CommodityQuote applyTrade(ItemStack itemStack, MarketTradeSide tradeSide, int quantity,
                                     String sourceMarketPos, String sourceNationId, String targetNationId,
                                     String actorUuid, String actorName) throws SQLException {
        CommoditySnapshot snapshot = ensureCommodity(itemStack);
        CommodityMarketState current = snapshot.state();
        PlayerMarketSettings playerSettings = null;
        if (actorUuid != null && !actorUuid.isEmpty()) {
            playerSettings = repository.getPlayerSettings(actorUuid);
            if (playerSettings == null) {
                playerSettings = PlayerMarketSettings.defaultSettings(actorUuid);
            }
        }
        CommodityQuote chargedQuote = buildQuote(snapshot, Math.max(1, quantity), playerSettings);
        int nextStock = tradeSide == MarketTradeSide.BUY
                ? current.currentStock() - chargedQuote.quantity()
                : current.currentStock() + chargedQuote.quantity();
        long now = System.currentTimeMillis();
        CommodityMarketState updated = new CommodityMarketState(
                current.commodityKey(),
                current.basePrice(),
                nextStock,
                current.volatility(),
                current.spreadBp(),
                current.stockFloor(),
                current.stockCeil(),
                current.priceFloor(),
                current.priceCeil(),
                now,
                now,
                current.version() + 1
        );
        repository.upsertState(updated);
        repository.appendTrade(new CommodityTradeRecord(
                current.commodityKey(),
                tradeSide,
                chargedQuote.quantity(),
                tradeSide == MarketTradeSide.BUY ? chargedQuote.buyUnitPrice() : chargedQuote.sellUnitPrice(),
                tradeSide == MarketTradeSide.BUY ? chargedQuote.buyPrice() : chargedQuote.sellPrice(),
                sourceMarketPos,
                sourceNationId,
                targetNationId,
                actorUuid,
                actorName,
                now
        ));
        return chargedQuote;
    }

    public void setPlayerPriceAdjustment(String playerUuid, int buyAdjustmentBp, int sellAdjustmentBp) throws SQLException {
        PlayerMarketSettings settings = new PlayerMarketSettings(playerUuid, buyAdjustmentBp, sellAdjustmentBp);
        repository.upsertPlayerSettings(settings);
    }

    public PlayerMarketSettings getPlayerSettings(String playerUuid) throws SQLException {
        PlayerMarketSettings settings = repository.getPlayerSettings(playerUuid);
        return settings != null ? settings : PlayerMarketSettings.defaultSettings(playerUuid);
    }

    public CommodityQuote quoteWithoutStockChange(ItemStack itemStack, MarketTradeSide tradeSide, int quantity,
                                                   String sourceMarketPos, String sourceNationId, String targetNationId,
                                                   String actorUuid, String actorName) throws SQLException {
        CommoditySnapshot snapshot = ensureCommodity(itemStack);
        PlayerMarketSettings playerSettings = null;
        if (actorUuid != null && !actorUuid.isEmpty()) {
            playerSettings = repository.getPlayerSettings(actorUuid);
            if (playerSettings == null) {
                playerSettings = PlayerMarketSettings.defaultSettings(actorUuid);
            }
        }
        CommodityQuote quote = buildQuote(snapshot, Math.max(1, quantity), playerSettings);
        repository.appendTrade(new CommodityTradeRecord(
                snapshot.state().commodityKey(),
                tradeSide,
                quote.quantity(),
                tradeSide == MarketTradeSide.BUY ? quote.buyUnitPrice() : quote.sellUnitPrice(),
                tradeSide == MarketTradeSide.BUY ? quote.buyPrice() : quote.sellPrice(),
                sourceMarketPos,
                sourceNationId,
                targetNationId,
                actorUuid,
                actorName,
                System.currentTimeMillis()
        ));
        return quote;
    }

    public BuyOrder createBuyOrder(ItemStack itemStack, int quantity, int minPriceBp, int maxPriceBp,
                                   String buyerUuid, String buyerName) throws SQLException {
        CommoditySnapshot snapshot = ensureCommodity(itemStack);
        String orderId = java.util.UUID.randomUUID().toString();
        BuyOrder order = new BuyOrder(
                orderId,
                buyerUuid,
                buyerName,
                snapshot.definition().commodityKey(),
                quantity,
                minPriceBp,
                maxPriceBp,
                System.currentTimeMillis(),
                "ACTIVE"
        );
        repository.createBuyOrder(order);
        return order;
    }

    public java.util.List<BuyOrder> listBuyOrders(String commodityKey) throws SQLException {
        return repository.listActiveBuyOrders(commodityKey);
    }

    public java.util.List<BuyOrder> listBuyOrdersForBuyer(String buyerUuid) throws SQLException {
        return repository.listActiveBuyOrdersForBuyer(buyerUuid);
    }

    public void cancelBuyOrder(String orderId) throws SQLException {
        repository.updateBuyOrderStatus(orderId, "CANCELLED");
    }

    public java.util.List<CommodityPriceChartPoint> listPriceChart(String commodityKey) throws SQLException {
        return repository.listTradeHistoryBuckets(commodityKey, PRICE_CHART_BUCKET_MS, PRICE_CHART_BUCKET_COUNT);
    }

    public CommoditySnapshot ensureCommodity(ItemStack itemStack) throws SQLException {
        CommodityDefinition definition = definitionFrom(itemStack);
        definition = CommodityConfigLoader.apply(definition);
        CommodityDefinition storedDefinition = repository.getDefinition(definition.commodityKey());
        if (storedDefinition == null) {
            repository.upsertDefinition(definition);
            storedDefinition = definition;
        } else if (storedDefinition.rarity() != definition.rarity()
                || !storedDefinition.category().equals(definition.category())
                || storedDefinition.importance() != definition.importance()) {
            // Re-sync rarity/category/importance from config/initializer
            storedDefinition = new CommodityDefinition(
                    storedDefinition.commodityKey(), storedDefinition.itemId(), storedDefinition.variantKey(),
                    storedDefinition.displayName(), storedDefinition.unitSize(),
                    definition.category(), storedDefinition.tradeEnabled(),
                    definition.rarity(), definition.importance(), storedDefinition.volume(),
                    definition.elasticity(), definition.baseVolatility());
            repository.upsertDefinition(storedDefinition);
        }

        CommodityMarketState state = repository.getState(definition.commodityKey());
        if (state == null) {
            state = defaultState(storedDefinition);
            repository.upsertState(state);
        }

        return new CommoditySnapshot(storedDefinition, state);
    }

    private CommodityDefinition definitionFrom(ItemStack itemStack) {
        ItemStack safeStack = itemStack == null ? ItemStack.EMPTY : itemStack;
        String itemId = CommodityKeyResolver.resolve(safeStack);
        String displayName = safeStack.isEmpty() ? itemId : safeStack.getHoverName().getString();
        return CommodityInitializer.createDefault(itemId, itemId, displayName);
    }

    public static int estimateBaseUnitPrice(ItemStack itemStack) {
        CommodityDefinition definition = CommodityInitializer.createDefault(
                CommodityKeyResolver.resolve(itemStack),
                CommodityKeyResolver.resolve(itemStack),
                itemStack == null || itemStack.isEmpty() ? CommodityKeyResolver.resolve(itemStack) : itemStack.getHoverName().getString()
        );
        return estimateBaseUnitPrice(definition);
    }

    private CommodityMarketState defaultState(CommodityDefinition definition) {
        long now = System.currentTimeMillis();
        return new CommodityMarketState(
                definition.commodityKey(),
                estimateBaseUnitPrice(definition),
                0,
                DEFAULT_VOLATILITY,
                DEFAULT_SPREAD_BP,
                Integer.MIN_VALUE,
                Integer.MAX_VALUE,
                0,
                Integer.MAX_VALUE,
                0L,
                now,
                0
        );
    }

    private static int estimateBaseUnitPrice(CommodityDefinition definition) {
        int fromConfig = CommodityConfigLoader.getBasePrice(definition.itemId(), -1);
        if (fromConfig >= 0) return fromConfig;
        String itemId = definition == null ? "" : definition.itemId().toLowerCase();
        String category = definition == null ? "" : definition.category().toLowerCase();

        if (itemId.contains("diamond") || itemId.contains("emerald") || itemId.contains("beacon")) {
            return 64;
        }
        if (itemId.contains("netherite")) {
            return 72;
        }
        if (itemId.contains("copper") || itemId.contains("iron") || itemId.contains("gold")) {
            return 18;
        }
        if (itemId.contains("glass") || itemId.contains("lantern") || itemId.contains("torch")) {
            return 8;
        }
        if (itemId.contains("brick") || itemId.contains("stone") || itemId.contains("slab") || itemId.contains("stairs")) {
            return 6;
        }
        if (itemId.contains("log") || itemId.contains("planks") || itemId.contains("wood") || itemId.contains("fence") || itemId.contains("door")) {
            return 5;
        }

        return switch (category) {
            case "luxury" -> 32;
            case "gems" -> 48;
            case "metal" -> 18;
            case "ore" -> 14;
            case "tools" -> 16;
            case "spices" -> 14;
            case "wood" -> 5;
            case "food" -> DEFAULT_BASE_PRICE;
            default -> DEFAULT_BASE_PRICE;
        };
    }

    private CommodityQuote buildQuote(CommoditySnapshot snapshot, int quantity, PlayerMarketSettings playerSettings) {
        CommodityMarketState state = snapshot.state();
        CommodityDefinition definition = snapshot.definition();
        int safeQuantity = Math.max(1, quantity);
        int buyPrice = (int) Math.round(Math.ceil(getBatchPrice(definition, state, state.currentStock(), state.currentStock() - safeQuantity + 1)));
        int sellPrice = (int) Math.round(Math.floor(applySpread(state, getBatchPrice(definition, state, state.currentStock() + safeQuantity, state.currentStock() + 1))));

        if (playerSettings != null) {
            buyPrice = (int) Math.round(buyPrice * (1 + playerSettings.buyPriceAdjustmentBp() / 10000.0));
            sellPrice = (int) Math.round(sellPrice * (1 + playerSettings.sellPriceAdjustmentBp() / 10000.0));
        }

        return new CommodityQuote(
                state.commodityKey(),
                safeQuantity,
                Math.max(0, buyPrice),
                Math.max(0, sellPrice),
                Math.max(0, (int) Math.ceil((double) Math.max(0, buyPrice) / safeQuantity)),
                Math.max(0, (int) Math.floor((double) Math.max(0, sellPrice) / safeQuantity)),
                state.currentStock() - safeQuantity,
                state.currentStock() + safeQuantity
        );
    }

    private double getBatchPrice(CommodityDefinition definition, CommodityMarketState state, int startStock, int endStock) {
        int lowStock = Math.min(startStock, endStock);
        int highStock = Math.max(startStock, endStock);
        int numTerms = highStock - lowStock + 1;
        double lowStockPrice;
        double highStockPrice;
        int fixedStockLimit;

        if (state.volatility() == 0) {
            return numTerms * getStockPrice(definition, state, state.currentStock());
        }
        if (highStock <= state.stockFloor()) {
            return numTerms * getStockPrice(definition, state, state.stockFloor());
        }
        if (lowStock >= state.stockCeil()) {
            return numTerms * getStockPrice(definition, state, state.stockCeil());
        }
        if (lowStock < state.stockFloor()) {
            return ((state.stockFloor() - lowStock) * getStockPrice(definition, state, state.stockFloor())) + getBatchPrice(definition, state, state.stockFloor(), highStock);
        }
        if (highStock > state.stockCeil()) {
            return ((highStock - state.stockCeil()) * getStockPrice(definition, state, state.stockCeil())) + getBatchPrice(definition, state, lowStock, state.stockCeil());
        }

        lowStockPrice = getStockPrice(definition, state, lowStock);
        highStockPrice = getStockPrice(definition, state, highStock);

        if (lowStockPrice <= state.priceFloor()) {
            return numTerms * state.priceFloor();
        }
        if (highStockPrice >= state.priceCeil()) {
            return numTerms * state.priceCeil();
        }
        if (highStockPrice < state.priceFloor()) {
            fixedStockLimit = (int) Math.round(Math.floor(stockAtPrice(definition, state, state.priceFloor())));
            return ((highStock - fixedStockLimit) * state.priceFloor()) + getBatchPrice(definition, state, lowStock, fixedStockLimit);
        }
        if (lowStockPrice > state.priceCeil()) {
            fixedStockLimit = (int) Math.round(Math.ceil(stockAtPrice(definition, state, state.priceCeil())));
            return ((fixedStockLimit - lowStock) * state.priceCeil()) + getBatchPrice(definition, state, fixedStockLimit, highStock);
        }
        return Math.round(lowStockPrice * (1 - Math.pow(1 / getVolFactor(definition, state), numTerms)) / (1 - (1 / getVolFactor(definition, state))));
    }

    private double getStockPrice(CommodityDefinition definition, CommodityMarketState state, int stockLevel) {
        double basePrice = calculateBasePrice(definition, state.basePrice());
        return rangeCrop(
                basePrice * Math.pow(getVolFactor(definition, state), -rangeCrop(stockLevel, state.stockFloor(), state.stockCeil())),
                state.priceFloor(),
                state.priceCeil()
        );
    }

    private double stockAtPrice(CommodityDefinition definition, CommodityMarketState state, int targetPrice) {
        double basePrice = calculateBasePrice(definition, state.basePrice());
        if (state.volatility() == 0) {
            if (targetPrice > basePrice) {
                return Integer.MIN_VALUE;
            }
            if (targetPrice < basePrice) {
                return Integer.MAX_VALUE;
            }
            return state.currentStock();
        }
        return -(Math.log(targetPrice / basePrice) / Math.log(getVolFactor(definition, state)));
    }

    private double calculateBasePrice(CommodityDefinition definition, int stateBasePrice) {
        double rarityCoeff = 1 + (definition.rarity() * 0.5);
        double importanceCoeff = 1 + (definition.importance() * 0.3);
        return stateBasePrice * rarityCoeff * importanceCoeff;
    }

    private double getVolFactor(CommodityDefinition definition, CommodityMarketState state) {
        double adjustedVolatility = definition.baseVolatility() * (1 + definition.elasticity() * 0.2);
        return 1 + adjustedVolatility / INT_SCALE;
    }

    private double applySpread(CommodityMarketState state, double grossPrice) {
        return grossPrice * (1 - ((double) state.spreadBp() / INT_SCALE));
    }

    private static int rangeCrop(int value, int minValue, int maxValue) {
        return Math.min(Math.max(value, minValue), maxValue);
    }

    private static double rangeCrop(double value, double minValue, double maxValue) {
        return Math.min(Math.max(value, minValue), maxValue);
    }

    public record CommoditySnapshot(CommodityDefinition definition, CommodityMarketState state) {
    }
}
