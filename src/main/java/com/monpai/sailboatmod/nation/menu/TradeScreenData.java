package com.monpai.sailboatmod.nation.menu;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public record TradeScreenData(
        String ourNationId,
        String ourNationName,
        int ourPrimaryColor,
        long ourTreasuryBalance,
        List<ItemStack> ourTreasuryItems,
        boolean canManageTreasury,
        String targetNationId,
        String targetNationName,
        int targetPrimaryColor,
        long targetTreasuryBalance,
        List<ItemStack> targetTreasuryItems,
        boolean hasExistingProposal,
        String proposalId,
        boolean weAreProposer,
        long offerCurrency,
        List<ItemStack> offerItems,
        long requestCurrency,
        List<ItemStack> requestItems,
        int proposalRemainingSeconds,
        String diplomacyStatus
) {
    public static final int MAX_TRADE_ITEMS = 9;
    public static final int TREASURY_SLOTS = 54;

    public TradeScreenData {
        ourNationId = sanitize(ourNationId, 40);
        ourNationName = sanitize(ourNationName, 64);
        targetNationId = sanitize(targetNationId, 40);
        targetNationName = sanitize(targetNationName, 64);
        proposalId = sanitize(proposalId, 16);
        diplomacyStatus = sanitize(diplomacyStatus, 24);
        if (ourTreasuryItems == null) ourTreasuryItems = List.of();
        if (targetTreasuryItems == null) targetTreasuryItems = List.of();
        if (offerItems == null) offerItems = List.of();
        if (requestItems == null) requestItems = List.of();
        offerCurrency = Math.max(0L, offerCurrency);
        requestCurrency = Math.max(0L, requestCurrency);
        proposalRemainingSeconds = Math.max(0, proposalRemainingSeconds);
    }
    public static TradeScreenData empty() {
        return new TradeScreenData("", "", 0, 0L, List.of(), false,
                "", "", 0, 0L, List.of(),
                false, "", false, 0L, List.of(), 0L, List.of(), 0, "neutral");
    }

    private static String sanitize(String value, int maxLength) {
        if (value == null || value.isBlank()) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
