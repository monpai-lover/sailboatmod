package com.monpai.sailboatmod.market.web.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class MarketWebMapJson {
    public static JsonObject snapshot(MarketWebMapDtos.Snapshot snapshot) {
        JsonObject json = new JsonObject();
        json.addProperty("dimensionId", snapshot.dimensionId());
        json.addProperty("unknownColor", snapshot.unknownColor());
        json.addProperty("tileSize", snapshot.tileSize());
        json.addProperty("lod", snapshot.lod());
        json.addProperty("renderVersion", MarketWebMapTileCache.RENDER_VERSION);
        // 运行时瓦片写入计数:每渲染一张瓦片 +1。前端 tileRefreshKey 纳入它,使 ?v= 缓存破坏参数随渲染变化,
        // 渲染完网页能拉到新瓦片(renderVersion 是编译期常量,单靠它网页永远显示缓存旧图)。
        json.addProperty("tileEpoch", MarketWebMapTileCache.tileWriteEpoch());
        json.add("defaultFocus", point(snapshot.defaultFocus()));
        json.addProperty("territoryRevision", snapshot.territoryRevision());
        json.addProperty("marketRevision", snapshot.marketRevision());
        return json;
    }

    public static JsonObject point(MarketWebMapDtos.Point point) {
        JsonObject json = new JsonObject();
        json.addProperty("x", point == null ? 0.0D : point.x());
        json.addProperty("z", point == null ? 0.0D : point.z());
        // debugroute 调试航点附加真实方块快照 + 出身标记;普通航点无 debug 数据,不输出这些字段。
        if (point != null && point.hasDebug()) {
            json.addProperty("origin", point.origin());
            json.addProperty("segment", point.segment());
            if (point.block() != null) {
                json.addProperty("block", point.block());
            }
            if (point.water() != null) {
                json.addProperty("water", point.water());
            }
        }
        return json;
    }

    public static JsonArray points(List<MarketWebMapDtos.Point> points) {
        JsonArray out = new JsonArray();
        for (MarketWebMapDtos.Point point : points == null ? List.<MarketWebMapDtos.Point>of() : points) {
            out.add(point(point));
        }
        return out;
    }

    public static JsonObject market(MarketWebMapDtos.MarketMarker marker) {
        JsonObject json = new JsonObject();
        json.addProperty("marketId", marker.marketId());
        json.addProperty("marketName", marker.marketName());
        json.addProperty("ownerName", marker.ownerName());
        json.addProperty("ownerUuid", marker.ownerUuid());
        json.addProperty("dimensionId", marker.dimensionId());
        json.addProperty("x", marker.x());
        json.addProperty("y", marker.y());
        json.addProperty("z", marker.z());
        json.addProperty("townId", marker.townId());
        json.addProperty("townName", marker.townName());
        return json;
    }

    public static JsonArray markets(List<MarketWebMapDtos.MarketMarker> markers) {
        JsonArray out = new JsonArray();
        for (MarketWebMapDtos.MarketMarker marker : markers == null ? List.<MarketWebMapDtos.MarketMarker>of() : markers) {
            out.add(market(marker));
        }
        return out;
    }

    public static JsonObject territory(MarketWebMapDtos.Territory territory) {
        JsonObject json = new JsonObject();
        json.addProperty("nationId", territory.nationId());
        json.addProperty("nationName", territory.nationName());
        json.addProperty("townId", territory.townId());
        json.addProperty("townName", territory.townName());
        json.addProperty("flagId", territory.flagId());
        json.addProperty("chunkX", territory.chunkX());
        json.addProperty("chunkZ", territory.chunkZ());
        json.addProperty("fillRgb", territory.fillRgb());
        json.addProperty("borderRgb", territory.borderRgb());
        json.addProperty("flagUrl", territory.flagId().isBlank() ? "" : "/api/map/flags/" + pathSegment(territory.flagId()) + ".png");
        return json;
    }

    public static JsonArray territories(List<MarketWebMapDtos.Territory> territories) {
        JsonArray out = new JsonArray();
        for (MarketWebMapDtos.Territory territory : territories == null ? List.<MarketWebMapDtos.Territory>of() : territories) {
            out.add(territory(territory));
        }
        return out;
    }

    public static JsonObject shipment(MarketWebMapDtos.ShipmentTrace shipment) {
        JsonObject json = new JsonObject();
        json.addProperty("shippingOrderId", shipment.shippingOrderId());
        json.addProperty("label", shipment.label());
        json.addProperty("transportMode", shipment.transportMode());
        json.addProperty("status", shipment.status());
        json.addProperty("sourceName", shipment.sourceName());
        json.addProperty("targetName", shipment.targetName());
        json.addProperty("sourceTownName", shipment.sourceTownName());
        json.addProperty("targetTownName", shipment.targetTownName());
        json.addProperty("vehicleName", shipment.vehicleName());
        json.addProperty("ownerName", shipment.ownerName());
        json.addProperty("etaSeconds", shipment.etaSeconds());
        json.addProperty("currentSpeed", shipment.currentSpeed());
        JsonArray cargo = new JsonArray();
        for (MarketWebMapDtos.CargoItem item : shipment.cargo()) {
            JsonObject c = new JsonObject();
            c.addProperty("name", item.name());
            c.addProperty("quantity", item.quantity());
            c.addProperty("recipient", item.recipient());
            cargo.add(c);
        }
        json.add("cargo", cargo);
        json.add("points", points(shipment.points()));
        json.addProperty("completedPointCount", shipment.completedPointCount());
        json.addProperty("progressRatio", shipment.progressRatio());
        json.addProperty("manual", shipment.manual());
        if (shipment.current() != null) {
            JsonObject current = new JsonObject();
            current.addProperty("x", shipment.current().x());
            current.addProperty("z", shipment.current().z());
            json.add("current", current);
        }
        return json;
    }

    public static JsonArray shipments(List<MarketWebMapDtos.ShipmentTrace> shipments) {
        JsonArray out = new JsonArray();
        for (MarketWebMapDtos.ShipmentTrace shipment : shipments == null ? List.<MarketWebMapDtos.ShipmentTrace>of() : shipments) {
            out.add(shipment(shipment));
        }
        return out;
    }

    private MarketWebMapJson() {
    }

    private static String pathSegment(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
