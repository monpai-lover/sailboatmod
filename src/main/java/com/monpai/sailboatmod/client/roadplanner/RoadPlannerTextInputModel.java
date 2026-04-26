package com.monpai.sailboatmod.client.roadplanner;

import java.util.Optional;
import java.util.UUID;

public record RoadPlannerTextInputModel(Kind kind,
                                        UUID routeId,
                                        UUID edgeId,
                                        String title,
                                        String initialValue,
                                        String value,
                                        int maxLength) {
    public RoadPlannerTextInputModel {
        if (kind == null) {
            throw new IllegalArgumentException("kind cannot be null");
        }
        routeId = routeId == null ? new UUID(0L, 0L) : routeId;
        edgeId = edgeId == null ? new UUID(0L, 0L) : edgeId;
        title = clean(title);
        initialValue = clean(initialValue);
        value = clean(value);
        maxLength = Math.max(1, maxLength);
        if (initialValue.length() > maxLength) {
            initialValue = initialValue.substring(0, maxLength);
        }
        if (value.length() > maxLength) {
            value = value.substring(0, maxLength);
        }
    }

    public static RoadPlannerTextInputModel renameRoad(UUID routeId, UUID edgeId, String initialValue) {
        String cleanInitial = clean(initialValue);
        return new RoadPlannerTextInputModel(Kind.RENAME_ROAD, routeId, edgeId, "重命名道路", cleanInitial, cleanInitial, 64);
    }

    public RoadPlannerTextInputModel withValue(String value) {
        return new RoadPlannerTextInputModel(kind, routeId, edgeId, title, initialValue, value, maxLength);
    }

    public Optional<SubmitIntent> submit() {
        if (value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new SubmitIntent(routeId, edgeId, value));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public enum Kind {
        RENAME_ROAD
    }

    public record SubmitIntent(UUID routeId, UUID edgeId, String value) {
    }
}
