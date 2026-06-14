package com.monpai.sailboatmod.market.commodity;

import com.monpai.sailboatmod.economy.GoldStandardEconomy;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.UUID;

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
                0L,
                System.currentTimeMillis(),
                "ACTIVE"
        );
        repository.createBuyOrder(order);
        return order;
    }

    public BuyOrder createFundedBuyOrder(ItemStack itemStack, int quantity, int minPriceBp, int maxPriceBp,
                                         String buyerUuid, String buyerName, @Nullable Player onlinePlayer) throws SQLException {
        CommoditySnapshot snapshot = ensureCommodity(itemStack);
        int safeQuantity = Math.max(1, quantity);
        int reserveBp = Math.max(minPriceBp, maxPriceBp);
        CommodityQuote quote = quote(itemStack, safeQuantity, buyerUuid);
        long reservedBalance = onlinePlayer != null && onlinePlayer.getAbilities().instabuild
                ? 0L
                : reservedBalanceForBuyOrder(quote.buyUnitPrice(), safeQuantity, reserveBp);
        if (reservedBalance > Integer.MAX_VALUE) {
            return null;
        }
        if (reservedBalance > 0L && !withdrawBuyer(buyerUuid, buyerName, onlinePlayer, reservedBalance)) {
            return null;
        }
        String orderId = java.util.UUID.randomUUID().toString();
        BuyOrder order = new BuyOrder(
                orderId,
                buyerUuid,
                buyerName,
                snapshot.definition().commodityKey(),
                safeQuantity,
                minPriceBp,
                maxPriceBp,
                reservedBalance,
                System.currentTimeMillis(),
                "ACTIVE"
        );
        try {
            repository.createBuyOrder(order);
            return order;
        } catch (SQLException exception) {
            refundBuyer(buyerUuid, buyerName, onlinePlayer, reservedBalance);
            throw exception;
        }
    }

    public BuyOrder createReservedBuyOrder(ItemStack itemStack, int quantity, int minPriceBp, int maxPriceBp,
                                           String buyerUuid, String buyerName, long reservedBalance) throws SQLException {
        CommoditySnapshot snapshot = ensureCommodity(itemStack);
        int safeQuantity = Math.max(1, quantity);
        String orderId = java.util.UUID.randomUUID().toString();
        BuyOrder order = new BuyOrder(
                orderId,
                buyerUuid,
                buyerName,
                snapshot.definition().commodityKey(),
                safeQuantity,
                minPriceBp,
                maxPriceBp,
                Math.max(0L, reservedBalance),
                System.currentTimeMillis(),
                "ACTIVE"
        );
        repository.createBuyOrder(order);
        return order;
    }

    public boolean cancelBuyOrderForBuyer(String orderId, String buyerUuid, String buyerName, @Nullable Player onlinePlayer) throws SQLException {
        BuyOrder order = repository.getBuyOrder(orderId);
        if (order == null || !order.isActive() || buyerUuid == null || !buyerUuid.trim().equals(order.buyerUuid())) {
            return false;
        }
        repository.updateBuyOrderStatus(order.orderId(), "CANCELLED");
        if (!refundBuyer(buyerUuid, buyerName, onlinePlayer, order.reservedBalance())) {
            repository.updateBuyOrderStatus(order.orderId(), "ACTIVE");
            return false;
        }
        return true;
    }

    public BuyOrder cancelBuyOrderForBuyerReturningOrder(String orderId, String buyerUuid) throws SQLException {
        BuyOrder order = repository.getBuyOrder(orderId);
        if (order == null || !order.isActive() || buyerUuid == null || !buyerUuid.trim().equals(order.buyerUuid())) {
            return null;
        }
        repository.updateBuyOrderStatus(order.orderId(), "CANCELLED");
        return order;
    }

    public java.util.List<BuyOrder> listBuyOrders(String commodityKey) throws SQLException {
        return repository.listActiveBuyOrders(commodityKey);
    }

    public java.util.List<BuyOrder> listBuyOrdersForBuyer(String buyerUuid) throws SQLException {
        return repository.listActiveBuyOrdersForBuyer(buyerUuid);
    }

    public java.util.List<CommodityDefinition> listActiveBuyOrderCommodityDefinitions() throws SQLException {
        return repository.listActiveBuyOrderCommodityDefinitions();
    }

    public void cancelBuyOrder(String orderId) throws SQLException {
        repository.updateBuyOrderStatus(orderId, "CANCELLED");
    }

    public static long reservedBalanceForBuyOrder(long referenceUnitPrice, int quantity, int maxPriceBp) {
        long safeUnitPrice = Math.max(1L, referenceUnitPrice);
        int safeQuantity = Math.max(1, quantity);
        int safeBp = Math.max(-5000, Math.min(5000, maxPriceBp));
        double multiplier = 1.0D + safeBp / 10000.0D;
        return Math.max(1L, Math.round(safeUnitPrice * safeQuantity * multiplier));
    }

    private static boolean withdrawBuyer(String buyerUuid, String buyerName, @Nullable Player player, long amount) {
        if (amount <= 0L) {
            return true;
        }
        if (player != null) {
            return Boolean.TRUE.equals(GoldStandardEconomy.tryWithdraw(player, amount));
        }
        return Boolean.TRUE.equals(GoldStandardEconomy.tryWithdrawByIdentity(parseUuid(buyerUuid), buyerName, amount));
    }

    private static boolean refundBuyer(String buyerUuid, String buyerName, @Nullable Player player, long amount) {
        if (amount <= 0L) {
            return true;
        }
        if (player != null) {
            return Boolean.TRUE.equals(GoldStandardEconomy.tryDeposit(player, amount));
        }
        return Boolean.TRUE.equals(GoldStandardEconomy.tryDepositByIdentity(parseUuid(buyerUuid), buyerName, amount));
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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
        } else if (state.priceFloor() == 0 && state.priceCeil() == Integer.MAX_VALUE) {
            // Migrate existing states to ±15% price band
            int base = state.basePrice();
            int floor = Math.max(1, (int) Math.floor(base * 0.85));
            int ceil = (int) Math.ceil(base * 1.15);
            state = new CommodityMarketState(state.commodityKey(), base, state.currentStock(),
                    state.volatility(), state.spreadBp(), state.stockFloor(), state.stockCeil(),
                    floor, ceil, state.lastTradeAt(), state.updatedAt(), state.version());
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

    /** 参考价取样：最近 N 笔成交（spec 决策 N=20，可调）。 */
    public static final int REFERENCE_TRADE_SAMPLE_SIZE = 20;

    /**
     * 参考价回退决策（纯函数，可单测）：
     * 最近成交均价(>0) → 在售最低价(>0) → 基准价 basePrice（定价模型兜底，至少 1）。
     */
    public static int resolveReferencePrice(int recentTradeAverage, int lowestActiveAsk, int basePrice) {
        if (recentTradeAverage > 0) {
            return recentTradeAverage;
        }
        if (lowestActiveAsk > 0) {
            return lowestActiveAsk;
        }
        return Math.max(1, basePrice);
    }

    /**
     * 参考价 = 最近 N 笔成交均价 → 在售最低价 → 基准价（定价模型兜底）。
     * 仅用于上架价格保护、建议价、求购单/建筑成本定价，不驱动任何实际成交价。
     * lowestActiveAsk：调用方从市场活跃挂单扫出的该商品最低单价；无则传 0。
     */
    public int referencePrice(ItemStack itemStack, int lowestActiveAsk) {
        int basePrice = Math.max(1, estimateBaseUnitPrice(itemStack));
        if (itemStack == null || itemStack.isEmpty()) {
            return resolveReferencePrice(0, lowestActiveAsk, basePrice);
        }
        int avg = 0;
        try {
            String commodityKey = CommodityKeyResolver.resolve(itemStack);
            avg = repository.recentTradeAveragePrice(commodityKey, REFERENCE_TRADE_SAMPLE_SIZE);
        } catch (SQLException ignored) {
            avg = 0;
        }
        return resolveReferencePrice(avg, lowestActiveAsk, basePrice);
    }

    /** 无在售挂单信息时的参考价：成交均价 → 基准价。 */
    public int referencePrice(ItemStack itemStack) {
        return referencePrice(itemStack, 0);
    }

    private CommodityMarketState defaultState(CommodityDefinition definition) {
        long now = System.currentTimeMillis();
        int base = estimateBaseUnitPrice(definition);
        int floor = Math.max(1, (int) Math.floor(base * 0.85));
        int ceil = (int) Math.ceil(base * 1.15);
        return new CommodityMarketState(
                definition.commodityKey(),
                base,
                0,
                DEFAULT_VOLATILITY,
                DEFAULT_SPREAD_BP,
                Integer.MIN_VALUE,
                Integer.MAX_VALUE,
                floor,
                ceil,
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
            case "building", "construction", "decoration", "furniture", "lighting", "flooring", "landscaping", "machinery" -> 6;
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
        return stateBasePrice;
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
