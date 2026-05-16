package com.monpai.sailboatmod.client.gui;

import java.util.Objects;

final class TradeWindowStatePolicy {
    private TradeWindowStatePolicy() {
    }

    static boolean shouldReplaceDraft(String currentTargetNationId,
                                      boolean currentHasProposal,
                                      String currentProposalId,
                                      String nextTargetNationId,
                                      boolean nextHasProposal,
                                      String nextProposalId) {
        return !Objects.equals(normalize(currentTargetNationId), normalize(nextTargetNationId))
                || currentHasProposal != nextHasProposal
                || !Objects.equals(normalize(currentProposalId), normalize(nextProposalId));
    }

    static String filterCurrencyText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder filtered = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isCurrencyCharacterAllowed(c)) {
                filtered.append(c);
            }
        }
        return filtered.toString();
    }

    static boolean isCurrencyCharacterAllowed(char c) {
        return c >= '0' && c <= '9';
    }

    static long parseCurrency(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
