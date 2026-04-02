package com.monpai.sailboatmod.market.commodity;

public record PlayerMarketSettings(
        String playerUuid,
        int buyPriceAdjustmentBp,
        int sellPriceAdjustmentBp
) {
    public PlayerMarketSettings {
        playerUuid = playerUuid == null ? "" : playerUuid;
        buyPriceAdjustmentBp = Math.max(-1000, Math.min(1000, buyPriceAdjustmentBp));
        sellPriceAdjustmentBp = Math.max(-1000, Math.min(1000, sellPriceAdjustmentBp));
    }

    public static PlayerMarketSettings defaultSettings(String playerUuid) {
        return new PlayerMarketSettings(playerUuid, 0, 0);
    }
}
