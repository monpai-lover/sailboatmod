package com.monpai.sailboatmod.market;

public final class MarketPricePolicy {
    public static int applyPriceAdjustment(int basePrice, int priceAdjustmentBp) {
        return Math.max(1, (int) Math.round(basePrice * (1 + priceAdjustmentBp / 10000.0D)));
    }

    /** 价格参考：min/max 仅作展示参考，不再约束上架。范围常数集中于此。 */
    public static final double REFERENCE_PRICE_FLOOR_RATIO = 0.5D;
    public static final double REFERENCE_PRICE_CEIL_RATIO = 1.5D;

    /**
     * 基于参考价的上架价格窗口。挂单价由卖家自由定死，参考价 ±50% 区间**仅作展示参考，不再限制上架**。
     * 只要价格 &gt; 0 即有效；constrained=false 表示前端不钳制输入。
     */
    public static ListingPriceWindow referencePriceWindow(int referenceUnitPrice, int requestedUnitPrice) {
        int safeReference = Math.max(1, referenceUnitPrice);
        int safeRequested = Math.max(1, requestedUnitPrice);
        int minAllowed = Math.max(1, (int) Math.floor(safeReference * REFERENCE_PRICE_FLOOR_RATIO));
        int maxAllowed = Math.max(minAllowed, (int) Math.ceil(safeReference * REFERENCE_PRICE_CEIL_RATIO));
        boolean valid = requestedUnitPrice > 0; // 指导价只做参考，不再用区间约束上架
        return new ListingPriceWindow(
                safeReference,
                safeRequested,
                derivePriceAdjustmentBp(safeReference, safeRequested),
                minAllowed,
                maxAllowed,
                valid,
                false // 不约束：参考价仅展示
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
