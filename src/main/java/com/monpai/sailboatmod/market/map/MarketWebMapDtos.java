package com.monpai.sailboatmod.market.map;

import java.util.List;

public final class MarketWebMapDtos {
    /**
     * 地图坐标点。debug 字段(block/water/origin/segment)仅 /marketweb debugroute 投放的调试航点携带,
     * 普通航点用 2 参构造器,debug 字段保持「无数据」哨兵(origin=segment=-1、block=null、water=null),
     * 序列化时不输出。这样所有现存 new Point(x, z) 调用点零改动。
     */
    public record Point(double x, double z, String block, Boolean water, byte origin, byte segment) {
        public Point(double x, double z) {
            this(x, z, null, null, (byte) -1, (byte) -1);
        }

        /** 是否携带 debug 节点信息(origin/segment 有效)。 */
        public boolean hasDebug() {
            return origin >= 0;
        }
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
                                String sourceTownName,
                                String targetTownName,
                                String vehicleName,
                                String ownerName,
                                int etaSeconds,
                                double currentSpeed,
                                List<CargoItem> cargo,
                                List<Point> points,
                                int completedPointCount,
                                double progressRatio,
                                Point current,
                                boolean manual,
                                FlightTelemetry flight) {
        /** Older transports and saved traces have no flight telemetry. */
        public ShipmentTrace(String shippingOrderId, String label, String transportMode, String status,
                             String sourceName, String targetName, String sourceTownName, String targetTownName,
                             String vehicleName, String ownerName, int etaSeconds, double currentSpeed,
                             List<CargoItem> cargo, List<Point> points, int completedPointCount,
                             double progressRatio, Point current, boolean manual) {
            this(shippingOrderId, label, transportMode, status, sourceName, targetName, sourceTownName, targetTownName,
                    vehicleName, ownerName, etaSeconds, currentSpeed, cargo, points, completedPointCount,
                    progressRatio, current, manual, null);
        }
        public ShipmentTrace {
            points = points == null ? List.of() : List.copyOf(points);
            cargo = cargo == null ? List.of() : List.copyOf(cargo);
        }
    }

    /** World Y, not height above terrain. Unknown/non-finite readings remain absent. */
    public record FlightTelemetry(Double worldY, String flightState, String executionMode) {
        public FlightTelemetry {
            worldY = worldY != null && Double.isFinite(worldY) ? worldY : null;
            flightState = flightState == null ? "" : flightState.trim().toUpperCase(java.util.Locale.ROOT);
            executionMode = executionMode == null ? "" : executionMode.trim().toUpperCase(java.util.Locale.ROOT);
        }
    }

    public record CargoItem(String name, int quantity, String recipient) {
    }

    private MarketWebMapDtos() {
    }
}
