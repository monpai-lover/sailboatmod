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

    private ModConfig() {}
}
