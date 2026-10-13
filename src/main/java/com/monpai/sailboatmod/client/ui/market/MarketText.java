package com.monpai.sailboatmod.client.ui.market;

import com.monpai.sailboatmod.economy.GoldStandardEconomy;
import com.monpai.sailboatmod.market.MarketListing;
import com.monpai.sailboatmod.market.MarketOverviewData;
import com.monpai.sailboatmod.market.analytics.CommodityCandleSeries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Text and item lookups for the market screen. Keys are the existing {@code screen.sailboatmod.market.*} set. */
public final class MarketText {
    private static final String KEY = "screen.sailboatmod.market.";
    private static final Map<String, ItemStack> STACKS = new HashMap<>();

    private MarketText() {
    }

    public static Component market(String key, Object... args) {
        return Component.translatable(KEY + key, args);
    }

    public static Component ui(String key, Object... args) {
        return Component.translatable("screen.sailboatmod.ui." + key, args);
    }

    public static String money(long value) {
        return GoldStandardEconomy.formatBalance(value);
    }

    public static String signedMoney(long value) {
        return GoldStandardEconomy.formatSignedBalance(value);
    }

    public static boolean isInfiniteStock(int availableCount) {
        return availableCount >= MarketListing.INFINITE_THRESHOLD;
    }

    /** Stock count, or ∞ for listings backed by unlimited supply. */
    public static String stock(int availableCount) {
        return isInfiniteStock(availableCount) ? "∞" : Integer.toString(availableCount);
    }

    public static String percent(float value) {
        return Math.round(Math.max(0.0F, value) * 100.0F) + "%";
    }

    public static String distance(int meters) {
        return meters <= 0 ? "-" : String.format(Locale.ROOT, "%dm", meters);
    }

    public static String eta(int seconds) {
        if (seconds <= 0) {
            return "-";
        }
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%dh %02dm", hours, minutes);
        }
        if (minutes > 0) {
            return String.format(Locale.ROOT, "%dm %02ds", minutes, secs);
        }
        return String.format(Locale.ROOT, "%ds", secs);
    }

    public static Component rarity(int rarity) {
        return market("rarity." + switch (MarketViewModel.clampRarity(rarity)) {
            case 5 -> "extraordinary";
            case 4 -> "legend";
            case 3 -> "epic";
            case 2 -> "rare";
            case 1 -> "uncommon";
            default -> "common";
        });
    }

    /** Category label from the market's translations, else a tidied version of the raw ID. */
    public static Component category(String category) {
        if (category == null || category.isBlank()) {
            return market("catalog.filter.other");
        }
        String normalized = category.trim().toLowerCase(Locale.ROOT);
        String key = KEY + "category." + normalized;
        String translated = Component.translatable(key).getString();
        if (!translated.equals(key)) {
            return Component.literal(translated);
        }
        String fallback = normalized.replace('_', ' ').replace('-', ' ').trim();
        return Component.literal(Character.toUpperCase(fallback.charAt(0)) + fallback.substring(1));
    }

    /** Localised order status ({@code order_status.<lower>}), falling back to the raw value. */
    public static Component orderStatus(String status) {
        if (status == null || status.isBlank()) {
            return Component.literal("-");
        }
        String key = KEY + "order_status." + status.toLowerCase(Locale.ROOT);
        String localized = Component.translatable(key).getString();
        return Component.literal(localized.equals(key) ? status : localized);
    }

    /** "Carrier | Route" when both are known; whichever is known otherwise; the fallback when neither is. */
    public static String dispatchCandidateSubtitle(String carrierName, String routeName, String fallback) {
        String safeCarrier = carrierName == null ? "" : carrierName.trim();
        String safeRoute = routeName == null ? "" : routeName.trim();
        String safeFallback = fallback == null ? "" : fallback.trim();
        if (!safeCarrier.isBlank() && !safeRoute.isBlank() && !"-".equals(safeRoute)) {
            return safeCarrier + " | " + safeRoute;
        }
        if (!safeCarrier.isBlank()) {
            return safeCarrier;
        }
        if (!safeRoute.isBlank() && !"-".equals(safeRoute)) {
            return safeRoute;
        }
        return safeFallback;
    }

    /** Display name for a commodity key, preferring names the server sent with charts, books or listings. */
    public static String commodityName(MarketOverviewData data, String commodityKey) {
        if (commodityKey == null || commodityKey.isBlank()) {
            return "-";
        }
        CommodityCandleSeries candles = data.candleSeriesFor(commodityKey, "1h");
        if (candles != null && !candles.displayName().isBlank()) {
            return candles.displayName();
        }
        MarketOverviewData.CommodityBuyBook book = data.buyBookFor(commodityKey);
        if (book != null && !book.displayName().isBlank()) {
            return book.displayName();
        }
        for (MarketOverviewData.ListingEntry entry : data.listingEntries()) {
            if (commodityKey.equals(entry.commodityKey()) && entry.itemName() != null && !entry.itemName().isBlank()) {
                return entry.itemName();
            }
        }
        return commodityKey;
    }

    /** The registered item for a commodity key; a barrier for unknown keys so the slot never looks empty by mistake. */
    public static ItemStack stack(String commodityKey) {
        String key = commodityKey == null ? "" : commodityKey.trim();
        return STACKS.computeIfAbsent(key, k -> {
            ResourceLocation id = ResourceLocation.tryParse(k);
            Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            return new ItemStack(item == null || item == Items.AIR ? Items.BARRIER : item);
        }).copy();
    }
}
