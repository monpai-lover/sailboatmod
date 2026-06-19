package com.monpai.sailboatmod.market.web.map;

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
                                boolean manual) {
        public ShipmentTrace {
            points = points == null ? List.of() : List.copyOf(points);
            cargo = cargo == null ? List.of() : List.copyOf(cargo);
        }
    }

    public record CargoItem(String name, int quantity, String recipient) {
    }

    private MarketWebMapDtos() {
    }
}
