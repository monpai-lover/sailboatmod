package com.monpai.sailboatmod;

import net.minecraftforge.common.ForgeConfigSpec;

public final class ModConfig {
    public static final ForgeConfigSpec COMMON_SPEC;
    public static final ForgeConfigSpec.IntValue CLAIM_PREVIEW_RADIUS;
    public static final ForgeConfigSpec.BooleanValue MARKET_SQLITE_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> MARKET_SQLITE_FILE_NAME;

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

    private ModConfig() {}
}