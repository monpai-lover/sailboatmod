package com.monpai.sailboatmod.map.route;

import java.util.List;
import java.util.Objects;

public record MapRouteEntry(String id, MapRouteKind kind, String name, String fromName,
                            String toName, double lengthBlocks, List<List<MapRoutePoint>> paths) {
    public MapRouteEntry {
        id = checkedText(id);
        kind = Objects.requireNonNull(kind, "kind");
        name = checkedText(name);
        fromName = checkedText(fromName);
        toName = checkedText(toName);
        if (!Double.isFinite(lengthBlocks) || lengthBlocks < 0) {
            throw new IllegalArgumentException("Route length must be finite and non-negative");
        }
        paths = paths.stream().map(List::copyOf).toList();
    }

    private static String checkedText(String text) {
        Objects.requireNonNull(text, "route text");
        if (text.length() > MapRouteSnapshot.MAX_STRING_LENGTH) {
            throw new IllegalArgumentException("Route text exceeds the display budget");
        }
        return text;
    }
}
