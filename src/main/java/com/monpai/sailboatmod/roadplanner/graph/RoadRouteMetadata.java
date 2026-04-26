package com.monpai.sailboatmod.roadplanner.graph;

import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;

import java.util.UUID;

public record RoadRouteMetadata(String roadName,
                                String fromTownName,
                                String toTownName,
                                UUID creatorId,
                                long createdAtGameTime,
                                int width,
                                CompiledRoadSectionType type,
                                Status status) {
    public RoadRouteMetadata {
        roadName = normalize(roadName, "未命名道路");
        fromTownName = normalize(fromTownName, "未知起点");
        toTownName = normalize(toTownName, "未知终点");
        if (creatorId == null) {
            throw new IllegalArgumentException("creatorId cannot be null");
        }
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive");
        }
        if (type == null) {
            throw new IllegalArgumentException("type cannot be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status cannot be null");
        }
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    public enum Status {
        PLANNED,
        BUILDING,
        BUILT,
        REMOVING,
        REMOVED,
        CANCELLED
    }
}
