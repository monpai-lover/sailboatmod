package com.monpai.sailboatmod.market.web.map;

import com.mojang.logging.LogUtils;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Addon-owned settings: the main mod does not need a new configuration API. */
public final class MarketWebMapSettings {
    public static final ModConfigSpec COMMON_SPEC;
    private static final ModConfigSpec.BooleanValue UPDATES_ENABLED;
    private static final ModConfigSpec.DoubleValue MAIN_THREAD_BUDGET_MILLIS;
    private static final ModConfigSpec.IntValue MAX_SNAPSHOTS_PER_TICK;
    private static final ModConfigSpec.IntValue MAX_PROBE_STARTS_PER_TICK;
    private static final ModConfigSpec.IntValue MAX_INFLIGHT_PROBES;
    private static final ModConfigSpec.IntValue RENDER_THREADS;
    private static final ModConfigSpec.IntValue LOADED_REFRESH_COOLDOWN_SECONDS;
    private static final ModConfigSpec.IntValue DISCOVERY_COOLDOWN_SECONDS;
    private static volatile Boolean runtimeUpdatesOverride;
    // ConfigValue caches are cleared during reload. Background workers only read this published value.
    private record Values(boolean updatesEnabled, long budgetNanos, int snapshots, int probeStarts,
                          int inflightProbes, int renderThreads, long loadedCooldownMillis,
                          long discoveryCooldownMillis) {}
    private static volatile Values values = new Values(true, 2_000_000L, 8, 4, 32, 1, 30_000L, 60_000L);

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("Map background work; cached map viewing and trading stay available when paused.").push("map");
        UPDATES_ENABLED = builder.comment("Persistent global update switch. Also controlled by /marketweb map pause|resume.")
                .define("updatesEnabled", true);
        MAIN_THREAD_BUDGET_MILLIS = builder.comment("Shared soft deadline for map work on one server tick; an individual atomic capture may overrun.")
                .defineInRange("mainThreadBudgetMillis", 2.0D, 0.25D, 10.0D);
        MAX_SNAPSHOTS_PER_TICK = builder.defineInRange("maxSnapshotsPerTick", 8, 1, 128);
        MAX_PROBE_STARTS_PER_TICK = builder.defineInRange("maxProbeStartsPerTick", 4, 1, 64);
        MAX_INFLIGHT_PROBES = builder.defineInRange("maxInflightProbes", 32, 1, 256);
        RENDER_THREADS = builder.comment("Fixed region-render worker count; leave CPU capacity for gameplay.")
                .defineInRange("renderThreads", 1, 1, 16);
        LOADED_REFRESH_COOLDOWN_SECONDS = builder.comment("Refresh cooldown for unchanged loaded chunks; explicit dirty updates bypass it.")
                .defineInRange("loadedRefreshCooldownSeconds", 30, 1, 3600);
        DISCOVERY_COOLDOWN_SECONDS = builder.comment("Cooldown between missing-tile discovery requests for the same coordinates.")
                .defineInRange("browserDiscoveryCooldownSeconds", 60, 1, 3600);
        builder.pop();
        COMMON_SPEC = builder.build();
    }

    private MarketWebMapSettings() {}

    public static boolean updatesEnabled() {
        Boolean override = runtimeUpdatesOverride;
        return override != null ? override : values.updatesEnabled();
    }

    /** Applies immediately; returns whether it was also persisted to the loaded addon config. */
    public static boolean setUpdatesEnabled(boolean enabled) {
        runtimeUpdatesOverride = enabled;
        if (enabled) MarketWebTellusTileQueue.resumeGlobal();
        if (!COMMON_SPEC.isLoaded()) return false;
        try {
            UPDATES_ENABLED.set(enabled);
            COMMON_SPEC.save();
            return true;
        } catch (RuntimeException exception) {
            LogUtils.getLogger().warn("Map updates changed in memory but the addon configuration could not be saved", exception);
            return false;
        }
    }

    public static void reloadFromConfig() {
        boolean enabled;
        long budgetNanos;
        try { enabled = UPDATES_ENABLED.get(); }
        catch (IllegalStateException exception) { enabled = true; }
        try { budgetNanos = Math.max(1L, (long) (MAIN_THREAD_BUDGET_MILLIS.get() * 1_000_000D)); }
        catch (IllegalStateException exception) { budgetNanos = 2_000_000L; }
        values = new Values(enabled, budgetNanos, value(MAX_SNAPSHOTS_PER_TICK, 8),
                value(MAX_PROBE_STARTS_PER_TICK, 4), value(MAX_INFLIGHT_PROBES, 32),
                value(RENDER_THREADS, 1), value(LOADED_REFRESH_COOLDOWN_SECONDS, 30) * 1000L,
                value(DISCOVERY_COOLDOWN_SECONDS, 60) * 1000L);
        runtimeUpdatesOverride = null;
        if (updatesEnabled()) MarketWebTellusTileQueue.resumeGlobal();
    }

    public static long mainThreadBudgetNanos() { return values.budgetNanos(); }

    public static int maxSnapshotsPerTick() { return values.snapshots(); }
    public static int maxProbeStartsPerTick() { return values.probeStarts(); }
    public static int maxInflightProbes() { return values.inflightProbes(); }
    public static int renderThreads() { return values.renderThreads(); }
    public static long loadedRefreshCooldownMillis() { return values.loadedCooldownMillis(); }
    public static long browserDiscoveryCooldownMillis() { return values.discoveryCooldownMillis(); }

    private static int value(ModConfigSpec.IntValue value, int fallback) {
        try { return value.get(); }
        catch (IllegalStateException exception) { return fallback; }
    }
}
