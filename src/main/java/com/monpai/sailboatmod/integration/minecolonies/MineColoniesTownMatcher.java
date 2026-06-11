package com.monpai.sailboatmod.integration.minecolonies;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class MineColoniesTownMatcher {
    public static Optional<ExternalColonyRef> select(Optional<ExternalColonyRef> coreMatch,
                                                     List<ExternalColonyRef> claimMatches) {
        if (coreMatch != null && coreMatch.isPresent()) {
            return coreMatch;
        }
        if (claimMatches == null || claimMatches.isEmpty()) {
            return Optional.empty();
        }

        Map<ExternalColonyRef, Integer> counts = new HashMap<>();
        for (ExternalColonyRef ref : claimMatches) {
            if (ref != null && !ref.dimensionId().isBlank() && ref.colonyId() > 0) {
                counts.merge(ref, 1, Integer::sum);
            }
        }
        ExternalColonyRef best = null;
        int bestCount = 0;
        boolean tied = false;
        for (Map.Entry<ExternalColonyRef, Integer> entry : counts.entrySet()) {
            int count = entry.getValue();
            if (count > bestCount) {
                best = entry.getKey();
                bestCount = count;
                tied = false;
            } else if (count == bestCount) {
                tied = true;
            }
        }
        return best == null || tied ? Optional.empty() : Optional.of(best);
    }

    private MineColoniesTownMatcher() {
    }
}
