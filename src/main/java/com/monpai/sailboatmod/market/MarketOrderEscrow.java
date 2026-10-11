package com.monpai.sailboatmod.market;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

/** The buyer's held payment and the recipients fixed when the order was placed. */
public record MarketOrderEscrow(
        String orderId,
        String sellerUuid,
        String sellerName,
        int totalPrice,
        int sellerPayout,
        String salesTaxNationId,
        int salesTax,
        String importTariffNationId,
        int importTariff
) {
    public MarketOrderEscrow {
        orderId = normalize(orderId);
        sellerUuid = normalize(sellerUuid);
        sellerName = normalize(sellerName);
        salesTaxNationId = normalize(salesTaxNationId).toLowerCase(Locale.ROOT);
        importTariffNationId = normalize(importTariffNationId).toLowerCase(Locale.ROOT);
        totalPrice = Math.max(0, totalPrice);
        sellerPayout = Math.max(0, sellerPayout);
        salesTax = Math.max(0, salesTax);
        importTariff = Math.max(0, importTariff);
    }

    public boolean hasBalancedAmounts() {
        return (long) sellerPayout + salesTax + importTariff == totalPrice;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("OrderId", orderId);
        tag.putString("SellerUuid", sellerUuid);
        tag.putString("SellerName", sellerName);
        tag.putInt("TotalPrice", totalPrice);
        tag.putInt("SellerPayout", sellerPayout);
        tag.putString("SalesTaxNationId", salesTaxNationId);
        tag.putInt("SalesTax", salesTax);
        tag.putString("ImportTariffNationId", importTariffNationId);
        tag.putInt("ImportTariff", importTariff);
        return tag;
    }

    public static MarketOrderEscrow load(CompoundTag tag) {
        return new MarketOrderEscrow(tag.getString("OrderId"), tag.getString("SellerUuid"),
                tag.getString("SellerName"), tag.getInt("TotalPrice"), tag.getInt("SellerPayout"),
                tag.getString("SalesTaxNationId"), tag.getInt("SalesTax"),
                tag.getString("ImportTariffNationId"), tag.getInt("ImportTariff"));
    }

    MarketOrderEscrow portion(String newOrderId, int amount) {
        if (!hasBalancedAmounts() || amount < 0 || amount > totalPrice) {
            throw new IllegalArgumentException("Escrow portion must stay within the held payment");
        }
        int seller = totalPrice == 0 ? 0 : (int) ((long) sellerPayout * amount / totalPrice);
        int sellerAndSalesTax = totalPrice == 0 ? 0
                : (int) (((long) sellerPayout + salesTax) * amount / totalPrice);
        // Cumulative boundaries assign rounding once while conserving every recipient's total.
        return new MarketOrderEscrow(newOrderId, sellerUuid, sellerName, amount, seller,
                salesTaxNationId, sellerAndSalesTax - seller,
                importTariffNationId, amount - sellerAndSalesTax);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
