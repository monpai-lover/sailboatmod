package com.monpai.sailboatmod.market.web.map;

import java.util.List;

public final class MarketWebMapDtos {
    public record Point(double x, double z) {
    }

    public record Snapshot(String dimensionId,
                           String unknownColor,
                           int tileSize,
                           int lod,
                           Point defaultFocus,
                           long territoryRevision,
                           long marketRevision) {
    }

    public record MarketMarker(String marketId,
                               String marketName,
                               String ownerName,
                               String ownerUuid,
                               String dimensionId,
                               int x,
                               int y,
                               int z,
                               String townId,
                               String townName) {
    }

    public record Territory(String nationId,
                            String nationName,
                            String townId,
                            String townName,
                            String flagId,
                            int chunkX,
                            int chunkZ,
                            int fillRgb,
                            int borderRgb) {
    }

    public record FlagMeta(String flagId, String url, int width, int height) {
    }

    public record ShipmentTrace(String shippingOrderId,
                                String label,
                                String transportMode,
                                String status,
                                String sourceName,
                                String targetName,
                                List<Point> points,
                                int completedPointCount,
                                double progressRatio) {
        public ShipmentTrace {
            points = points == null ? List.of() : List.copyOf(points);
        }
    }

    private MarketWebMapDtos() {
    }
}
