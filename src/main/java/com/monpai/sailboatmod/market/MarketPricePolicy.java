package com.monpai.sailboatmod.market;

public final class MarketPricePolicy {
    public static int applyPriceAdjustment(int basePrice, int priceAdjustmentBp) {
        return Math.max(1, (int) Math.round(basePrice * (1 + priceAdjustmentBp / 10000.0D)));
    }

    /** 价格保护：挂单价必须落在 [参考价 × 0.5, 参考价 × 1.5]。范围常数集中于此。 */
    public static final double REFERENCE_PRICE_FLOOR_RATIO = 0.5D;
    public static final double REFERENCE_PRICE_CEIL_RATIO = 1.5D;

    /**
     * 基于参考价的 ±50% 上架价格窗口。挂单价由卖家定死，本窗口只做"不太高也不太低"的保护。
     * 不再区分"约束/无约束"两套——所有商品统一按参考价 ±50%。
     */
    public static ListingPriceWindow referencePriceWindow(int referenceUnitPrice, int requestedUnitPrice) {
        int safeReference = Math.max(1, referenceUnitPrice);
        int safeRequested = Math.max(1, requestedUnitPrice);
        int minAllowed = Math.max(1, (int) Math.floor(safeReference * REFERENCE_PRICE_FLOOR_RATIO));
        int maxAllowed = Math.max(minAllowed, (int) Math.ceil(safeReference * REFERENCE_PRICE_CEIL_RATIO));
        boolean valid = requestedUnitPrice > 0
                && safeRequested >= minAllowed
                && safeRequested <= maxAllowed;
        return new ListingPriceWindow(
                safeReference,
                safeRequested,
                derivePriceAdjustmentBp(safeReference, safeRequested),
                minAllowed,
                maxAllowed,
                valid,
                true
        );
    }

    public static int derivePriceAdjustmentBp(int basePrice, int requestedUnitPrice) {
        if (basePrice <= 0 || requestedUnitPrice <= 0) {
            return 0;
        }
        double ratio = (requestedUnitPrice / (double) Math.max(1, basePrice)) - 1.0D;
        return (int) Math.round(ratio * 10000.0D);
    }

    public record ListingPriceWindow(int referenceUnitPrice,
                                     int requestedUnitPrice,
                                     int derivedPriceAdjustmentBp,
                                     int minAllowedUnitPrice,
                                     int maxAllowedUnitPrice,
                                     boolean valid,
                                     boolean constrained) {
    }

    private MarketPricePolicy() {
    }
}
