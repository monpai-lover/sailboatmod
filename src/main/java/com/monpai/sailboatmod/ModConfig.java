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

    private ModConfig() {}
}
