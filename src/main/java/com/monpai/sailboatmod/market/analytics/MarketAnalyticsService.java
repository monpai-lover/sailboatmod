package com.monpai.sailboatmod.market.analytics;

import com.monpai.sailboatmod.ModConfig;
import com.monpai.sailboatmod.market.db.MarketDatabase;
import com.monpai.sailboatmod.market.commodity.BuyOrder;
import com.monpai.sailboatmod.market.commodity.CommodityDefinition;
import com.monpai.sailboatmod.market.commodity.CommodityMarketRepository;
import com.monpai.sailboatmod.market.commodity.CommodityMarketService;
import com.monpai.sailboatmod.market.commodity.CommodityMarketState;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.LoanAccountRecord;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MarketAnalyticsService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final CommodityMarketRepository REPOSITORY = new CommodityMarketRepository();
    private static final CommodityMarketService MARKET_SERVICE = new CommodityMarketService();
    private static final long HOUR = 60L * 60L * 1000L;
    private static final long DAY = 24L * HOUR;
    private static final long WEEK = 7L * DAY;
    private static final int DEFAULT_SERIES_LIMIT = 48;

    private long lastSnapshotRunAt;

    public void maybeRecordSnapshots(MinecraftServer server) {
        if (server == null || !ModConfig.marketSqliteEnabled() || !MarketDatabase.isInitialized()) {
            return;
        }
        long now = System.currentTimeMillis();
        long intervalMs = Math.max(5L, ModConfig.marketAnalyticsSnapshotIntervalMinutes()) * 60_000L;
        if (lastSnapshotRunAt > 0L && now - lastSnapshotRunAt < intervalMs) {
            return;
        }
        lastSnapshotRunAt = now;
        try {
            recordSnapshots(server, now);
        } catch (Exception exception) {
            LOGGER.warn("Failed to record market analytics snapshots", exception);
        }
    }

    public List<CommodityCandleSeries> loadCandleSeries(Map<String, String> displayNames) {
        if (displayNames == null || displayNames.isEmpty()) {
            return List.of();
        }
        List<CommodityCandleSeries> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : displayNames.entrySet()) {
            out.add(loadCandleSeries(entry.getKey(), entry.getValue(), "1h", 24));
            out.add(loadCandleSeries(entry.getKey(), entry.getValue(), "1d", 30));
            out.add(loadCandleSeries(entry.getKey(), entry.getValue(), "1w", 12));
        }
        return out;
    }

    public CommodityCandleSeries loadCandleSeries(String commodityKey, String displayName, String timeframe, int limit) {
        String seriesType = seriesTypeForTimeframe(timeframe);
        try {
            List<MarketAnalyticsPoint> raw = REPOSITORY.listAnalyticsSeries(seriesType, commodityKey, Math.max(1, limit));
            List<CommodityCandlePoint> points = new ArrayList<>(raw.size());
            for (MarketAnalyticsPoint point : raw) {
                points.add(new CommodityCandlePoint(
                        point.bucketAt(),
                        point.value(),
                        point.value(),
                        point.value(),
                        point.value(),
                        point.volume(),
                        point.tradeCount()
                ));
            }
            return new CommodityCandleSeries(commodityKey, displayName, timeframe, points);
        } catch (SQLException exception) {
            LOGGER.debug("Failed to read candle series for {}", commodityKey, exception);
            return new CommodityCandleSeries(commodityKey, displayName, timeframe, List.of());
        }
    }

    public List<CommodityImpactSnapshot> loadImpactSnapshots(Map<String, String> displayNames) {
        if (displayNames == null || displayNames.isEmpty()) {
            return List.of();
        }
        List<CommodityImpactSnapshot> out = new ArrayList<>();
        for (String commodityKey : displayNames.keySet()) {
            CommodityImpactSnapshot snapshot = buildImpactSnapshot(commodityKey);
            if (snapshot != null) {
                out.add(snapshot);
            }
        }
        return out;
    }

    public List<MarketAnalyticsSeries> loadAnalyticsSeries(ServerLevel level, Collection<String> categories) {
        List<MarketAnalyticsSeries> out = new ArrayList<>();
        out.add(loadSeries("MARKET_INDEX", "global", "Market Index", DEFAULT_SERIES_LIMIT));
        if (categories != null) {
            categories.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toLowerCase(Locale.ROOT))
                    .distinct()
                    .sorted()
                    .forEach(category -> out.add(loadSeries("CATEGORY_INDEX", category, categoryDisplayName(category), DEFAULT_SERIES_LIMIT)));
        }
        out.add(loadSeries("MACRO_INDEX", "cpi", "Consumer Price Index", DEFAULT_SERIES_LIMIT));
        if (level != null) {
            out.add(loadSeries("MACRO_INDEX", "outstanding_loans", "Outstanding Loans", DEFAULT_SERIES_LIMIT));
        }
        return out;
    }

    private MarketAnalyticsSeries loadSeries(String scopeType, String scopeKey, String displayName, int limit) {
        try {
            return new MarketAnalyticsSeries(scopeType, scopeKey, displayName, REPOSITORY.listAnalyticsSeries(scopeType, scopeKey, limit));
        } catch (SQLException exception) {
            LOGGER.debug("Failed to read analytics series {}:{}", scopeType, scopeKey, exception);
            return new MarketAnalyticsSeries(scopeType, scopeKey, displayName, List.of());
        }
    }

    private void recordSnapshots(MinecraftServer server, long now) throws SQLException {
        List<CommodityMarketRepository.CommodityStateRow> states = REPOSITORY.listAllCommodityStates();
        Map<String, IndexAccumulator> categoryAccumulators = new LinkedHashMap<>();
        IndexAccumulator global = new IndexAccumulator();
        double cpiWeightedRatio = 0.0D;
        int cpiTotalWeight = 0;

        for (CommodityMarketRepository.CommodityStateRow row : states) {
            CommodityDefinition definition = row.definition();
            CommodityMarketState state = row.state();
            List<CommodityTradeTick> hourlyTicks = REPOSITORY.listTradeTicks(definition.commodityKey(), now - DAY);
            List<CommodityTradeTick> dailyTicks = REPOSITORY.listTradeTicks(definition.commodityKey(), now - 30L * DAY);
            List<CommodityTradeTick> weeklyTicks = REPOSITORY.listTradeTicks(definition.commodityKey(), now - 12L * WEEK);
            writeCandles("CANDLE_1H", definition.commodityKey(), aggregateTicks(hourlyTicks, HOUR));
            writeCandles("CANDLE_1D", definition.commodityKey(), aggregateTicks(dailyTicks, DAY));
            writeCandles("CANDLE_1W", definition.commodityKey(), aggregateTicks(weeklyTicks, WEEK));

            CommodityImpactSnapshot impact = buildImpactSnapshot(definition, state, hourlyTicks);
            if (impact == null) {
                continue;
            }
            int weight = Math.max(1, definition.importance());
            global.accept(impact.currentClosePrice(), state.currentStock(), weight);
            categoryAccumulators
                    .computeIfAbsent(definition.category().trim().toLowerCase(Locale.ROOT), ignored -> new IndexAccumulator())
                    .accept(impact.currentClosePrice(), state.currentStock(), weight);
            cpiWeightedRatio += (impact.currentClosePrice() / (double) Math.max(1, state.basePrice())) * Math.max(1, weight);
            cpiTotalWeight += Math.max(1, weight);
        }

        long bucketAt = bucketAt(now, HOUR);
        upsertIndex("MARKET_INDEX", "global", bucketAt, global);
        for (Map.Entry<String, IndexAccumulator> entry : categoryAccumulators.entrySet()) {
            upsertIndex("CATEGORY_INDEX", entry.getKey(), bucketAt, entry.getValue());
        }
        long cpi = consumerPriceIndex(cpiWeightedRatio, cpiTotalWeight);
        REPOSITORY.upsertAnalyticsSnapshot(
                "MACRO_INDEX",
                "cpi",
                bucketAt,
                saturatingInt(cpi),
                saturatingInt(cpi),
                saturatingInt(cpi),
                saturatingInt(cpi),
                states.size(),
                states.size()
        );

        long outstandingLoans = outstandingLoans(server);
        REPOSITORY.upsertAnalyticsSnapshot(
                "MACRO_INDEX",
                "outstanding_loans",
                bucketAt,
                saturatingInt(outstandingLoans),
                saturatingInt(outstandingLoans),
                saturatingInt(outstandingLoans),
                saturatingInt(outstandingLoans),
                0,
                0
        );
        long cutoff = now - Math.max(1L, ModConfig.marketAnalyticsRetentionDays()) * DAY;
        REPOSITORY.deleteAnalyticsSnapshotsBefore(cutoff);
    }

    private void writeCandles(String seriesType, String commodityKey, List<CommodityCandlePoint> candles) throws SQLException {
        for (CommodityCandlePoint candle : candles) {
            REPOSITORY.upsertAnalyticsSnapshot(
                    seriesType,
                    commodityKey,
                    candle.bucketAt(),
                    candle.openUnitPrice(),
                    candle.highUnitPrice(),
                    candle.lowUnitPrice(),
                    candle.closeUnitPrice(),
                    candle.volume(),
                    candle.tradeCount()
            );
        }
    }

    private void upsertIndex(String seriesType, String seriesKey, long bucketAt, IndexAccumulator accumulator) throws SQLException {
        int close = accumulator.value();
        REPOSITORY.upsertAnalyticsSnapshot(seriesType, seriesKey, bucketAt, close, close, close, close, accumulator.volume, accumulator.tradeCount);
    }

    private CommodityImpactSnapshot buildImpactSnapshot(String commodityKey) {
        try {
            CommodityDefinition definition = REPOSITORY.getDefinition(commodityKey);
            CommodityMarketState state = REPOSITORY.getState(commodityKey);
            if (definition == null || state == null) {
                return null;
            }
            return buildImpactSnapshot(definition, state, REPOSITORY.listTradeTicks(commodityKey, System.currentTimeMillis() - DAY));
        } catch (SQLException exception) {
            LOGGER.debug("Failed to build impact snapshot for {}", commodityKey, exception);
            return null;
        }
    }

    private CommodityImpactSnapshot buildImpactSnapshot(CommodityDefinition definition, CommodityMarketState state, List<CommodityTradeTick> recentTicks) throws SQLException {
        int close = recentTicks.isEmpty() ? state.basePrice() : recentTicks.get(recentTicks.size() - 1).unitPrice();
        long weightedPriceSum = 0L;
        long totalVolume = 0L;
        int tradeCount = 0;
        for (CommodityTradeTick tick : recentTicks) {
            weightedPriceSum += (long) tick.unitPrice() * Math.max(1, tick.quantity());
            totalVolume += Math.max(1, tick.quantity());
            tradeCount++;
        }
        int recentAverage = totalVolume <= 0L ? close : (int) Math.round((double) weightedPriceSum / (double) totalVolume);
        List<BuyOrder> buyOrders = MARKET_SERVICE.listBuyOrders(definition.commodityKey());
        int buyDepth = buyOrders.stream().mapToInt(order -> Math.max(0, order.quantity())).sum();
        int liquidity = Math.min(100, Math.max(0, (int) Math.round((Math.min(5000L, totalVolume + buyDepth + state.currentStock()) / 5000.0D) * 100.0D)));
        int inventoryPressureBp = pressureBp(state.stockCeil(), state.currentStock(), false);
        int buyPressureBp = pressureBp(Math.max(1, state.stockCeil()), buyDepth, true);
        int volatilityBp = Math.max(0, Math.min(3000, state.volatility() * 10));
        int reference = clampPrice(
                recentAverage
                        + Math.round(recentAverage * (buyPressureBp - inventoryPressureBp) / 10_000.0F),
                state
        );
        return new CommodityImpactSnapshot(
                definition.commodityKey(),
                reference,
                close,
                liquidity,
                inventoryPressureBp,
                buyPressureBp,
                volatilityBp
        );
    }

    private List<CommodityCandlePoint> aggregateTicks(List<CommodityTradeTick> ticks, long bucketSize) {
        if (ticks == null || ticks.isEmpty()) {
            return List.of();
        }
        Map<Long, MutableCandle> buckets = new LinkedHashMap<>();
        for (CommodityTradeTick tick : ticks) {
            long bucketAt = bucketAt(tick.createdAt(), bucketSize);
            buckets.computeIfAbsent(bucketAt, MutableCandle::new).accept(tick);
        }
        return buckets.values().stream()
                .sorted(Comparator.comparingLong(value -> value.bucketAt))
                .map(MutableCandle::freeze)
                .toList();
    }

    private static long bucketAt(long value, long bucketSize) {
        return (value / bucketSize) * bucketSize;
    }

    private static int pressureBp(int baseline, int observed, boolean upside) {
        if (baseline <= 0 || observed <= 0) {
            return 0;
        }
        double ratio = Math.min(2.0D, observed / (double) baseline);
        int bp = (int) Math.round(ratio * 3000.0D);
        return upside ? bp : Math.min(3000, bp);
    }

    private static int clampPrice(int price, CommodityMarketState state) {
        return Math.max(Math.max(1, state.priceFloor()), Math.min(state.priceCeil(), price));
    }

    private static int saturatingInt(long value) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
    }

    private static String categoryDisplayName(String category) {
        if (category == null || category.isBlank()) {
            return "Category";
        }
        String normalized = category.replace('_', ' ').trim();
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private static String seriesTypeForTimeframe(String timeframe) {
        return switch (timeframe == null ? "" : timeframe.trim().toLowerCase(Locale.ROOT)) {
            case "1d" -> "CANDLE_1D";
            case "1w" -> "CANDLE_1W";
            default -> "CANDLE_1H";
        };
    }

    private static long outstandingLoans(MinecraftServer server) {
        if (server == null) {
            return 0L;
        }
        NationSavedData data = NationSavedData.get(server.overworld());
        long total = 0L;
        for (LoanAccountRecord record : data.getNationLoans()) {
            total += record.outstanding();
        }
        for (LoanAccountRecord record : data.getPersonalLoans()) {
            total += record.outstanding();
        }
        return total;
    }

    private static long consumerPriceIndex(double weightedRatio, int totalWeight) {
        if (totalWeight <= 0) {
            return 100;
        }
        return Math.max(1L, Math.round((weightedRatio / totalWeight) * 100.0D));
    }

    private static final class MutableCandle {
        private final long bucketAt;
        private int open;
        private int high;
        private int low;
        private int close;
        private int volume;
        private int tradeCount;
        private boolean initialized;

        private MutableCandle(long bucketAt) {
            this.bucketAt = bucketAt;
        }

        private void accept(CommodityTradeTick tick) {
            int price = Math.max(1, tick.unitPrice());
            if (!initialized) {
                open = price;
                high = price;
                low = price;
                initialized = true;
            }
            close = price;
            high = Math.max(high, price);
            low = Math.min(low, price);
            volume += Math.max(1, tick.quantity());
            tradeCount++;
        }

        private CommodityCandlePoint freeze() {
            return new CommodityCandlePoint(bucketAt, open, high, low, close, volume, tradeCount);
        }
    }

    private static final class IndexAccumulator {
        private long weightedValue;
        private long totalWeight;
        private int volume;
        private int tradeCount;

        private void accept(int value, int stock, int weight) {
            int safeWeight = Math.max(1, weight);
            weightedValue += (long) Math.max(1, value) * safeWeight;
            totalWeight += safeWeight;
            volume += Math.max(0, stock);
            tradeCount++;
        }

        private int value() {
            if (totalWeight <= 0L) {
                return 0;
            }
            return (int) Math.round((double) weightedValue / (double) totalWeight);
        }
    }
}
