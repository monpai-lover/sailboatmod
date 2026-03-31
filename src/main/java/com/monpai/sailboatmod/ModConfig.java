package com.monpai.sailboatmod;

import net.minecraftforge.common.ForgeConfigSpec;

public final class ModConfig {
    public static final ForgeConfigSpec COMMON_SPEC;
    public static final ForgeConfigSpec.IntValue CLAIM_PREVIEW_RADIUS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("Nation & Town Settings").push("nation");
        CLAIM_PREVIEW_RADIUS = builder
                .comment("Claim map visualization radius in chunks (default 20, range 5-60)")
                .defineInRange("claimPreviewRadius", 20, 5, 60);
        builder.pop();
        COMMON_SPEC = builder.build();
    }

    public static int claimPreviewRadius() {
        return CLAIM_PREVIEW_RADIUS.get();
    }

    private ModConfig() {}
}
