package com.monpai.sailboatmod.market;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class MarketDispatchPlanner {
    private static final Comparator<DispatchChoice> BEST_CHOICE_ORDER =
            Comparator.comparing((DispatchChoice choice) -> !choice.available())
                    .thenComparingInt(DispatchChoice::etaSeconds)
                    .thenComparingInt(DispatchChoice::distanceMeters)
                    .thenComparingDouble(DispatchChoice::terminalDistanceScore)
                    .thenComparingInt(DispatchChoice::sequence);

    private MarketDispatchPlanner() {
    }

    public static Optional<DispatchChoice> bestChoice(Collection<DispatchChoice> choices) {
        if (choices == null || choices.isEmpty()) {
            return Optional.empty();
        }
        return choices.stream()
                .filter(Objects::nonNull)
                .min(BEST_CHOICE_ORDER);
    }

    public static List<DispatchChoice> rankedChoices(Collection<DispatchChoice> choices) {
        if (choices == null || choices.isEmpty()) {
            return List.of();
        }
        return choices.stream()
                .filter(Objects::nonNull)
                .sorted(BEST_CHOICE_ORDER)
                .toList();
    }

    public record DispatchChoice(String id,
                                 TransportTerminalKind terminalKind,
                                 boolean available,
                                 int etaSeconds,
                                 int distanceMeters,
                                 double terminalDistanceScore,
                                 int sequence) {
        public DispatchChoice {
            id = id == null ? "" : id.trim();
            terminalKind = terminalKind == null ? TransportTerminalKind.AUTO : terminalKind;
            etaSeconds = Math.max(0, etaSeconds);
            distanceMeters = Math.max(0, distanceMeters);
            terminalDistanceScore = Math.max(0.0D, terminalDistanceScore);
            sequence = Math.max(0, sequence);
        }
    }
}
