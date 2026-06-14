package com.monpai.sailboatmod.market;

public final class MarketPricePolicy {
    public static ListingPriceWindow listingWindow(boolean constrained,
                                                   int referenceUnitPrice,
                                                   int requestedUnitPrice,
                                                   int minListingPriceBp,
                                                   int maxListingPriceBp) {
        int safeReferenceUnitPrice = Math.max(1, referenceUnitPrice);
        int safeRequestedUnitPrice = Math.max(1, requestedUnitPrice);
        if (!constrained) {
            return new ListingPriceWindow(
                    safeReferenceUnitPrice,
                    safeRequestedUnitPrice,
                    0,
                    1,
                    Integer.MAX_VALUE,
                    requestedUnitPrice > 0,
                    false
            );
        }

        int minAllowedUnitPrice = applyPriceAdjustment(safeReferenceUnitPrice, minListingPriceBp);
        int maxAllowedUnitPrice = applyPriceAdjustment(safeReferenceUnitPrice, maxListingPriceBp);
        int derivedPriceAdjustmentBp = derivePriceAdjustmentBp(safeReferenceUnitPrice, safeRequestedUnitPrice);
        boolean valid = requestedUnitPrice > 0
                && safeRequestedUnitPrice >= minAllowedUnitPrice
                && safeRequestedUnitPrice <= maxAllowedUnitPrice
                && derivedPriceAdjustmentBp >= minListingPriceBp
                && derivedPriceAdjustmentBp <= maxListingPriceBp;
        return new ListingPriceWindow(
                safeReferenceUnitPrice,
                safeRequestedUnitPrice,
                derivedPriceAdjustmentBp,
                minAllowedUnitPrice,
                maxAllowedUnitPrice,
                valid,
                true
        );
    }

    public static int applyPriceAdjustment(int basePrice, int priceAdjustmentBp) {
        return Math.max(1, (int) Math.round(basePrice * (1 + priceAdjustmentBp / 10000.0D)));
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
