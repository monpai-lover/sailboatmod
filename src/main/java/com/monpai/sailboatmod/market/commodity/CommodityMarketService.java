package com.monpai.sailboatmod.market.commodity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.sql.SQLException;

public final class CommodityMarketService {
    private static final int INT_SCALE = 10_000;
    private static final int DEFAULT_BASE_PRICE = 10;
    private static final int DEFAULT_VOLATILITY = 100;
    private static final int DEFAULT_SPREAD_BP = 500;

    private final CommodityMarketRepository repository = new CommodityMarketRepository();

    public CommodityQuote quote(ItemStack itemStack, int quantity) throws SQLException {
        CommoditySnapshot snapshot = ensureCommodity(itemStack);
        return buildQuote(snapshot.state(), Math.max(1, quantity));
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
        CommodityQuote chargedQuote = buildQuote(current, Math.max(1, quantity));
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

    public CommoditySnapshot ensureCommodity(ItemStack itemStack) throws SQLException {
        CommodityDefinition definition = definitionFrom(itemStack);
        CommodityDefinition storedDefinition = repository.getDefinition(definition.commodityKey());
        if (storedDefinition == null) {
            repository.upsertDefinition(definition);
            storedDefinition = definition;
        }

        CommodityMarketState state = repository.getState(definition.commodityKey());
        if (state == null) {
            state = defaultState(definition.commodityKey());
            repository.upsertState(state);
        }

        return new CommoditySnapshot(storedDefinition, state);
    }

    private CommodityDefinition definitionFrom(ItemStack itemStack) {
        ItemStack safeStack = itemStack == null ? ItemStack.EMPTY : itemStack;
        ResourceLocation itemKey = ForgeRegistries.ITEMS.getKey(safeStack.getItem());
        String itemId = CommodityKeyResolver.resolve(safeStack);
        String displayName = safeStack.isEmpty() ? itemId : safeStack.getHoverName().getString();
        return new CommodityDefinition(itemId, itemId, "", displayName, 1, "", true);
    }

    private CommodityMarketState defaultState(String commodityKey) {
        long now = System.currentTimeMillis();
        return new CommodityMarketState(
                commodityKey,
                DEFAULT_BASE_PRICE,
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

    private CommodityQuote buildQuote(CommodityMarketState state, int quantity) {
        int safeQuantity = Math.max(1, quantity);
        int buyPrice = (int) Math.round(Math.ceil(getBatchPrice(state, state.currentStock(), state.currentStock() - safeQuantity + 1)));
        int sellPrice = (int) Math.round(Math.floor(applySpread(state, getBatchPrice(state, state.currentStock() + safeQuantity, state.currentStock() + 1))));
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

    private double getBatchPrice(CommodityMarketState state, int startStock, int endStock) {
        int lowStock = Math.min(startStock, endStock);
        int highStock = Math.max(startStock, endStock);
        int numTerms = highStock - lowStock + 1;
        double lowStockPrice;
        double highStockPrice;
        int fixedStockLimit;

        if (state.volatility() == 0) {
            return numTerms * getStockPrice(state, state.currentStock());
        }
        if (highStock <= state.stockFloor()) {
            return numTerms * getStockPrice(state, state.stockFloor());
        }
        if (lowStock >= state.stockCeil()) {
            return numTerms * getStockPrice(state, state.stockCeil());
        }
        if (lowStock < state.stockFloor()) {
            return ((state.stockFloor() - lowStock) * getStockPrice(state, state.stockFloor())) + getBatchPrice(state, state.stockFloor(), highStock);
        }
        if (highStock > state.stockCeil()) {
            return ((highStock - state.stockCeil()) * getStockPrice(state, state.stockCeil())) + getBatchPrice(state, lowStock, state.stockCeil());
        }

        lowStockPrice = getStockPrice(state, lowStock);
        highStockPrice = getStockPrice(state, highStock);

        if (lowStockPrice <= state.priceFloor()) {
            return numTerms * state.priceFloor();
        }
        if (highStockPrice >= state.priceCeil()) {
            return numTerms * state.priceCeil();
        }
        if (highStockPrice < state.priceFloor()) {
            fixedStockLimit = (int) Math.round(Math.floor(stockAtPrice(state, state.priceFloor())));
            return ((highStock - fixedStockLimit) * state.priceFloor()) + getBatchPrice(state, lowStock, fixedStockLimit);
        }
        if (lowStockPrice > state.priceCeil()) {
            fixedStockLimit = (int) Math.round(Math.ceil(stockAtPrice(state, state.priceCeil())));
            return ((fixedStockLimit - lowStock) * state.priceCeil()) + getBatchPrice(state, fixedStockLimit, highStock);
        }
        return Math.round(lowStockPrice * (1 - Math.pow(1 / getVolFactor(state), numTerms)) / (1 - (1 / getVolFactor(state))));
    }

    private double getStockPrice(CommodityMarketState state, int stockLevel) {
        return rangeCrop(
                state.basePrice() * Math.pow(getVolFactor(state), -rangeCrop(stockLevel, state.stockFloor(), state.stockCeil())),
                state.priceFloor(),
                state.priceCeil()
        );
    }

    private double stockAtPrice(CommodityMarketState state, int targetPrice) {
        if (state.volatility() == 0) {
            if (targetPrice > state.basePrice()) {
                return Integer.MIN_VALUE;
            }
            if (targetPrice < state.basePrice()) {
                return Integer.MAX_VALUE;
            }
            return state.currentStock();
        }
        return -(Math.log((double) targetPrice / state.basePrice()) / Math.log(getVolFactor(state)));
    }

    private double getVolFactor(CommodityMarketState state) {
        return 1 + (double) state.volatility() / INT_SCALE;
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
