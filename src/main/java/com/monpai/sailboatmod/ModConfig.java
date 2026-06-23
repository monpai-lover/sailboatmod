package com.monpai.sailboatmod;

import net.minecraftforge.common.ForgeConfigSpec;

public final class ModConfig {
    public static final ForgeConfigSpec COMMON_SPEC;
    public static final ForgeConfigSpec.IntValue CLAIM_PREVIEW_RADIUS;
    public static final ForgeConfigSpec.BooleanValue MARKET_SQLITE_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> MARKET_SQLITE_FILE_NAME;
    public static final ForgeConfigSpec.BooleanValue MARKET_WEB_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> MARKET_WEB_BIND_HOST;
    public static final ForgeConfigSpec.IntValue MARKET_WEB_PORT;
    public static final ForgeConfigSpec.IntValue MARKET_WEB_LOGIN_TOKEN_TTL_MINUTES;
    public static final ForgeConfigSpec.BooleanValue MARKET_WEB_DEV_MODE;
    public static final ForgeConfigSpec.ConfigValue<String> MARKET_WEB_DEV_ROOT;
    public static final ForgeConfigSpec.IntValue MARKET_WEB_MAP_RENDER_THREADS;
    public static final ForgeConfigSpec.IntValue MARKET_WEB_MAP_SNAPSHOT_CACHE_SIZE;
    public static final ForgeConfigSpec.IntValue MARKET_WEB_MAP_MAX_ACTIVE_SNAPSHOT_REQUESTS;
    public static final ForgeConfigSpec.IntValue MARKET_WEB_MAP_BACKGROUND_MAX_CHUNKS_PER_INTERVAL;
    public static final ForgeConfigSpec.IntValue MARKET_WEB_MAP_BACKGROUND_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue MARKET_WEB_MAP_PARTIAL_REGION_FLUSH_CHUNKS;
    public static final ForgeConfigSpec.IntValue MARKET_WEB_MAP_MAX_TRACKED_REGION_STATES;
    public static final ForgeConfigSpec.IntValue MARKET_WEB_MAP_MAX_DIRTY_CHUNKS;
    public static final ForgeConfigSpec.IntValue MARKET_WEB_MAP_DIRTY_REGION_CHUNKS_PER_INTERVAL;
    public static final ForgeConfigSpec.IntValue MARKET_WEB_MAP_PROGRESS_SAVE_INTERVAL_CHUNKS;
    public static final ForgeConfigSpec.IntValue MARKET_WEB_MAP_IMAGE_IO_BACKLOG_WARNING_THRESHOLD;
    public static final ForgeConfigSpec.IntValue MARKET_WEB_MAP_SNAPSHOT_TASKS_PER_TICK;
    public static final ForgeConfigSpec.IntValue MARKET_WEB_MAP_SAME_TILE_BURST_LIMIT;
    public static final ForgeConfigSpec.BooleanValue MARKET_WEB_MAP_BIOME_COLORS_ENABLED;
    public static final ForgeConfigSpec.IntValue MARKET_ANALYTICS_SNAPSHOT_INTERVAL_MINUTES;
    public static final ForgeConfigSpec.IntValue MARKET_ANALYTICS_RETENTION_DAYS;
    public static final ForgeConfigSpec.BooleanValue BANK_PERSONAL_LOANS_ENABLED;
    public static final ForgeConfigSpec.BooleanValue BANK_NATION_LOANS_ENABLED;
    public static final ForgeConfigSpec.IntValue BANK_PERSONAL_LOAN_MAX;
    public static final ForgeConfigSpec.IntValue BANK_NATION_LOAN_MAX;
    public static final ForgeConfigSpec.IntValue BANK_LOAN_MIN;
    public static final ForgeConfigSpec.DoubleValue BANK_PERSONAL_COLLATERAL_RATIO;
    public static final ForgeConfigSpec.DoubleValue BANK_NATION_COLLATERAL_RATIO;
    public static final ForgeConfigSpec.DoubleValue BANK_LOAN_DAILY_INTEREST_RATE;
    public static final ForgeConfigSpec.IntValue BANK_LOAN_MIN_DAILY_INTEREST;
    public static final ForgeConfigSpec.ConfigValue<String> CURRENCY_STANDARD;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("Nation & Town Settings").push("nation");
        CLAIM_PREVIEW_RADIUS = builder
                .comment("Claim map visualization radius in chunks (default 20, range 5-60)")
                .defineInRange("claimPreviewRadius", 20, 5, 60);
        builder.pop();

        builder.comment("Market Settings").push("market");
        MARKET_SQLITE_ENABLED = builder
                .comment("Enable the SQLite-backed commodity market database.")
                .define("sqliteEnabled", true);
        MARKET_SQLITE_FILE_NAME = builder
                .comment("SQLite database file name stored under world/data/sailboatmod_market/.")
                .define("sqliteFileName", "global_market.db", value ->
                        value instanceof String string
                                && !string.isBlank()
                                && !string.contains("/")
                                && !string.contains("\\"));
        MARKET_WEB_ENABLED = builder
                .comment("Enable the built-in web market service.")
                .define("webEnabled", true);
        MARKET_WEB_BIND_HOST = builder
                .comment("Host/IP address for the built-in web market service.")
                .define("webBindHost", "0.0.0.0", value ->
                        value instanceof String string && !string.isBlank());
        MARKET_WEB_PORT = builder
                .comment("TCP port for the built-in web market service.")
                .defineInRange("webPort", 11450, 1024, 65535);
        MARKET_WEB_LOGIN_TOKEN_TTL_MINUTES = builder
                .comment("One-time login token lifetime in minutes.")
                .defineInRange("webLoginTokenTtlMinutes", 30, 1, 1440);
        MARKET_WEB_DEV_MODE = builder
                .comment("Serve market web static assets from disk and disable cache headers for hot reload during development.")
                .define("webDevMode", false);
        MARKET_WEB_DEV_ROOT = builder
                .comment("Optional disk directory for market web dev assets. Use an absolute path or leave blank to auto-detect common source folders.")
                .define("webDevRoot", "", value -> value instanceof String);
        MARKET_WEB_MAP_RENDER_THREADS = builder
                .comment("Market web map render worker threads. 0 means auto.")
                .defineInRange("webMapRenderThreads", 0, 0, 64);
        MARKET_WEB_MAP_SNAPSHOT_CACHE_SIZE = builder
                .comment("Market web map snapshot FIFO cache size.")
                .defineInRange("webMapSnapshotCacheSize", 2048, 128, 65536);
        MARKET_WEB_MAP_MAX_ACTIVE_SNAPSHOT_REQUESTS = builder
                .comment("Maximum active market web map snapshot reads. 0 means renderThreads * 48.")
                .defineInRange("webMapMaxActiveSnapshotRequests", 0, 0, 65536);
        MARKET_WEB_MAP_BACKGROUND_MAX_CHUNKS_PER_INTERVAL = builder
                .comment("Maximum chunks enqueued by market web map background jobs per interval.")
                .defineInRange("webMapBackgroundMaxChunksPerInterval", 512, 1, 65536);
        MARKET_WEB_MAP_BACKGROUND_INTERVAL_TICKS = builder
                .comment("Ticks between market web map background render enqueue passes.")
                .defineInRange("webMapBackgroundIntervalTicks", 200, 1, 72000);
        MARKET_WEB_MAP_PARTIAL_REGION_FLUSH_CHUNKS = builder
                .comment("Chunks collected in a region before writing a partial square map tile.")
                .defineInRange("webMapPartialRegionFlushChunks", 32, 1, 1024);
        MARKET_WEB_MAP_MAX_TRACKED_REGION_STATES = builder
                .comment("Maximum in-memory partial region render states before the oldest are flushed/evicted.")
                .defineInRange("webMapMaxTrackedRegionStates", 512, 16, 65536);
        MARKET_WEB_MAP_MAX_DIRTY_CHUNKS = builder
                .comment("Maximum persistent dirty chunks retained for market web map background rendering.")
                .defineInRange("webMapMaxDirtyChunks", 200000, 1024, 5000000);
        MARKET_WEB_MAP_DIRTY_REGION_CHUNKS_PER_INTERVAL = builder
                .comment("Maximum chunks expanded from dirty regions per background interval.")
                .defineInRange("webMapDirtyRegionChunksPerInterval", 512, 1, 65536);
        MARKET_WEB_MAP_PROGRESS_SAVE_INTERVAL_CHUNKS = builder
                .comment("Chunk interval between full/radius render progress saves.")
                .defineInRange("webMapProgressSaveIntervalChunks", 512, 1, 65536);
        MARKET_WEB_MAP_IMAGE_IO_BACKLOG_WARNING_THRESHOLD = builder
                .comment("Warn when market web map ImageIO pending tasks exceed this value.")
                .defineInRange("webMapImageIoBacklogWarningThreshold", 100, 1, 100000);
        MARKET_WEB_MAP_SNAPSHOT_TASKS_PER_TICK = builder
                .comment("Market web map snapshot tasks consumed from the queue per server tick.")
                .defineInRange("webMapSnapshotTasksPerTick", 4, 1, 1024);
        MARKET_WEB_MAP_SAME_TILE_BURST_LIMIT = builder
                .comment("Maximum same-square-tile snapshot tasks consumed in one coalesced tick burst.")
                .defineInRange("webMapSameTileBurstLimit", 8, 1, 4096);
        MARKET_WEB_MAP_BIOME_COLORS_ENABLED = builder
                .comment("Enable biome color tinting for market web map terrain. Disabled by default to preserve Sailboat's fixed palette.")
                .define("webMapBiomeColorsEnabled", false);
        MARKET_ANALYTICS_SNAPSHOT_INTERVAL_MINUTES = builder
                .comment("How often market analytics snapshots are recorded into SQLite.")
                .defineInRange("analyticsSnapshotIntervalMinutes", 60, 5, 1440);
        MARKET_ANALYTICS_RETENTION_DAYS = builder
                .comment("How long to retain analytics snapshots in SQLite.")
                .defineInRange("analyticsRetentionDays", 30, 1, 3650);
        builder.pop();

        builder.comment("Bank Settings").push("bank");
        BANK_PERSONAL_LOANS_ENABLED = builder
                .comment("Enable player-facing personal loans in banks.")
                .define("personalLoansEnabled", true);
        BANK_NATION_LOANS_ENABLED = builder
                .comment("Enable nation treasury loans in banks.")
                .define("nationLoansEnabled", true);
        BANK_PERSONAL_LOAN_MAX = builder
                .comment("Absolute cap for personal loans.")
                .defineInRange("personalLoanMax", 5000, 0, Integer.MAX_VALUE);
        BANK_NATION_LOAN_MAX = builder
                .comment("Absolute cap for nation loans.")
                .defineInRange("nationLoanMax", 50000, 0, Integer.MAX_VALUE);
        BANK_LOAN_MIN = builder
                .comment("Minimum amount for a borrow/repay operation.")
                .defineInRange("loanMin", 50, 1, Integer.MAX_VALUE);
        BANK_PERSONAL_COLLATERAL_RATIO = builder
                .comment("Collateral ratio used to derive a personal max loan from player assets.")
                .defineInRange("personalCollateralRatio", 0.40D, 0.0D, 5.0D);
        BANK_NATION_COLLATERAL_RATIO = builder
                .comment("Collateral ratio used to derive a nation max loan from treasury balance.")
                .defineInRange("nationCollateralRatio", 1.50D, 0.0D, 10.0D);
        BANK_LOAN_DAILY_INTEREST_RATE = builder
                .comment("Daily interest rate applied to outstanding loans.")
                .defineInRange("loanDailyInterestRate", 0.02D, 0.0D, 1.0D);
        BANK_LOAN_MIN_DAILY_INTEREST = builder
                .comment("Minimum daily interest charged when a loan is outstanding.")
                .defineInRange("loanMinDailyInterest", 5, 0, Integer.MAX_VALUE);
        builder.pop();

        builder.comment("Economy Settings").push("economy");
        CURRENCY_STANDARD = builder
                .comment(
                        "Market currency standard. AUTO = amethyst standard when MineColonies is installed and no Vault economy is present, otherwise gold standard.",
                        "Allowed: AUTO, GOLD, AMETHYST.")
                .define("currencyStandard", "AUTO", value ->
                        value instanceof String string
                                && ("AUTO".equalsIgnoreCase(string)
                                || "GOLD".equalsIgnoreCase(string)
                                || "AMETHYST".equalsIgnoreCase(string)));
        builder.pop();

        COMMON_SPEC = builder.build();
    }

    public static int claimPreviewRadius() {
        return CLAIM_PREVIEW_RADIUS.get();
    }

    public static boolean marketSqliteEnabled() {
        return MARKET_SQLITE_ENABLED.get();
    }

    public static String marketSqliteFileName() {
        return MARKET_SQLITE_FILE_NAME.get();
    }

    public static boolean marketWebEnabled() {
        return MARKET_WEB_ENABLED.get();
    }

    public static String marketWebBindHost() {
        return MARKET_WEB_BIND_HOST.get();
    }

    public static int marketWebPort() {
        return MARKET_WEB_PORT.get();
    }

    public static int marketWebLoginTokenTtlMinutes() {
        return MARKET_WEB_LOGIN_TOKEN_TTL_MINUTES.get();
    }

    public static boolean marketWebDevMode() {
        return MARKET_WEB_DEV_MODE.get();
    }

    public static String marketWebDevRoot() {
        return MARKET_WEB_DEV_ROOT.get();
    }

    public static int marketWebRenderThreads() {
        return MARKET_WEB_MAP_RENDER_THREADS.get();
    }

    public static int marketWebSnapshotCacheSize() {
        return MARKET_WEB_MAP_SNAPSHOT_CACHE_SIZE.get();
    }

    public static int marketWebMaxActiveSnapshotRequests() {
        return MARKET_WEB_MAP_MAX_ACTIVE_SNAPSHOT_REQUESTS.get();
    }

    public static int marketWebBackgroundMaxChunksPerInterval() {
        return MARKET_WEB_MAP_BACKGROUND_MAX_CHUNKS_PER_INTERVAL.get();
    }

    public static int marketWebBackgroundIntervalTicks() {
        return MARKET_WEB_MAP_BACKGROUND_INTERVAL_TICKS.get();
    }

    public static int marketWebPartialRegionFlushChunks() {
        return MARKET_WEB_MAP_PARTIAL_REGION_FLUSH_CHUNKS.get();
    }

    public static int marketWebMaxTrackedRegionStates() {
        return MARKET_WEB_MAP_MAX_TRACKED_REGION_STATES.get();
    }

    public static int marketWebMaxDirtyChunks() {
        return MARKET_WEB_MAP_MAX_DIRTY_CHUNKS.get();
    }

    public static int marketWebDirtyRegionChunksPerInterval() {
        return MARKET_WEB_MAP_DIRTY_REGION_CHUNKS_PER_INTERVAL.get();
    }

    public static int marketWebProgressSaveIntervalChunks() {
        return MARKET_WEB_MAP_PROGRESS_SAVE_INTERVAL_CHUNKS.get();
    }

    public static int marketWebImageIoBacklogWarningThreshold() {
        return MARKET_WEB_MAP_IMAGE_IO_BACKLOG_WARNING_THRESHOLD.get();
    }

    public static int marketWebSnapshotTasksPerTick() {
        return MARKET_WEB_MAP_SNAPSHOT_TASKS_PER_TICK.get();
    }

    public static int marketWebSameTileBurstLimit() {
        return MARKET_WEB_MAP_SAME_TILE_BURST_LIMIT.get();
    }

    public static boolean marketWebBiomeColorsEnabled() {
        return MARKET_WEB_MAP_BIOME_COLORS_ENABLED.get();
    }

    public static int marketAnalyticsSnapshotIntervalMinutes() {
        return MARKET_ANALYTICS_SNAPSHOT_INTERVAL_MINUTES.get();
    }

    public static int marketAnalyticsRetentionDays() {
        return MARKET_ANALYTICS_RETENTION_DAYS.get();
    }

    public static boolean bankPersonalLoansEnabled() {
        return BANK_PERSONAL_LOANS_ENABLED.get();
    }

    public static boolean bankNationLoansEnabled() {
        return BANK_NATION_LOANS_ENABLED.get();
    }

    public static int bankPersonalLoanMax() {
        return BANK_PERSONAL_LOAN_MAX.get();
    }

    public static int bankNationLoanMax() {
        return BANK_NATION_LOAN_MAX.get();
    }

    public static int bankLoanMin() {
        return BANK_LOAN_MIN.get();
    }

    public static double bankPersonalCollateralRatio() {
        return BANK_PERSONAL_COLLATERAL_RATIO.get();
    }

    public static double bankNationCollateralRatio() {
        return BANK_NATION_COLLATERAL_RATIO.get();
    }

    public static double bankLoanDailyInterestRate() {
        return BANK_LOAN_DAILY_INTEREST_RATE.get();
    }

    public static int bankLoanMinDailyInterest() {
        return BANK_LOAN_MIN_DAILY_INTEREST.get();
    }

    public static String currencyStandard() {
        return CURRENCY_STANDARD.get();
    }

    private ModConfig() {}
}
