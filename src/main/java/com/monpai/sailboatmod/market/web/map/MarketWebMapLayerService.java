package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.market.terminal.MarketTerminalSavedData;
import com.monpai.sailboatmod.market.logistics.ShippingTraceService;
import com.monpai.sailboatmod.market.web.MarketPlayerIdentity;
import com.monpai.sailboatmod.market.web.MarketWebService;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class MarketWebMapLayerService {
    private static final int FALLBACK_FILL = 0x5B6573;
    private static final int FALLBACK_BORDER = 0xD7DEE8;

    public MarketWebMapDtos.Snapshot snapshot(MinecraftServer server, MarketPlayerIdentity identity, String focusedMarketId) {
        List<MarketWebMapDtos.MarketMarker> markers = markets(server);
        return new MarketWebMapDtos.Snapshot(
                MarketWebMapConstants.OVERWORLD,
                MarketWebMapConstants.UNKNOWN_COLOR,
                MarketWebMapConstants.TILE_SIZE,
                MarketWebMapConstants.LOD_BLOCKS_PER_PIXEL,
                defaultFocus(server, identity, focusedMarketId, markers),
                territories(server).size(),
                markers.size()
        );
    }

    public List<MarketWebMapDtos.MarketMarker> markets(MinecraftServer server) {
        if (server == null) {
            return List.of();
        }
        NationSavedData nationData = NationSavedData.get(server.overworld());
        List<MarketWebMapDtos.MarketMarker> out = new ArrayList<>();
        for (MarketTerminalSavedData.MarketTerminalEntry entry : MarketTerminalSavedData.get(server.overworld()).entries()) {
            if (!MarketWebMapConstants.OVERWORLD.equals(entry.dimensionId())) {
                continue;
            }
            out.add(toMarketMarker(entry, nationData));
        }
        out.sort(Comparator
                .comparing(MarketWebMapDtos.MarketMarker::marketName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(MarketWebMapDtos.MarketMarker::marketId));
        return out;
    }

    public List<MarketWebMapDtos.Territory> territories(MinecraftServer server) {
        if (server == null) {
            return List.of();
        }
        NationSavedData data = NationSavedData.get(server.overworld());
        List<MarketWebMapDtos.Territory> out = new ArrayList<>();
        for (NationClaimRecord claim : data.getAllClaims()) {
            if (!MarketWebMapConstants.OVERWORLD.equals(claim.dimensionId())) {
                continue;
            }
            out.add(toTerritory(claim, data.getNation(claim.nationId()), data.getTown(claim.townId())));
        }
        out.sort(Comparator
                .comparingInt(MarketWebMapDtos.Territory::chunkZ)
                .thenComparingInt(MarketWebMapDtos.Territory::chunkX)
                .thenComparing(MarketWebMapDtos.Territory::nationId));
        return out;
    }

    public List<MarketWebMapDtos.ShipmentTrace> shipments(MinecraftServer server, MarketPlayerIdentity identity) {
        return ShippingTraceService.toDtos(server, ShippingTraceService.visibleFor(server, identity));
    }

    public static MarketWebMapDtos.Territory toTerritoryForTest(NationClaimRecord claim, NationRecord nation, TownRecord town) {
        return toTerritory(claim, nation, town);
    }

    public static MarketWebMapDtos.MarketMarker toMarketMarkerForTest(MarketTerminalSavedData.MarketTerminalEntry entry) {
        return toMarketMarker(entry, null);
    }

    public static MarketWebMapDtos.Point defaultFocusForTest(List<MarketWebMapDtos.MarketMarker> markets,
                                                             MarketWebMapDtos.MarketMarker selected,
                                                             BlockPos corePos) {
        if (selected != null) {
            return new MarketWebMapDtos.Point(selected.x(), selected.z());
        }
        if (corePos != null) {
            return new MarketWebMapDtos.Point(corePos.getX(), corePos.getZ());
        }
        return averageMarketFocus(markets);
    }

    private MarketWebMapDtos.Point defaultFocus(MinecraftServer server,
                                                MarketPlayerIdentity identity,
                                                String focusedMarketId,
                                                List<MarketWebMapDtos.MarketMarker> markets) {
        if (focusedMarketId != null && !focusedMarketId.isBlank()) {
            for (MarketWebMapDtos.MarketMarker marker : markets) {
                if (focusedMarketId.equals(marker.marketId())) {
                    return new MarketWebMapDtos.Point(marker.x(), marker.z());
                }
            }
        }
        BlockPos core = playerCore(server, identity);
        if (core != null) {
            return new MarketWebMapDtos.Point(core.getX(), core.getZ());
        }
        return averageMarketFocus(markets);
    }

    private static MarketWebMapDtos.Point averageMarketFocus(List<MarketWebMapDtos.MarketMarker> markets) {
        if (markets == null || markets.isEmpty()) {
            return new MarketWebMapDtos.Point(0.0D, 0.0D);
        }
        double x = 0.0D;
        double z = 0.0D;
        for (MarketWebMapDtos.MarketMarker marker : markets) {
            x += marker.x();
            z += marker.z();
        }
        return new MarketWebMapDtos.Point(x / markets.size(), z / markets.size());
    }

    private static BlockPos playerCore(MinecraftServer server, MarketPlayerIdentity identity) {
        if (server == null || identity == null || identity.playerUuid() == null) {
            return null;
        }
        NationSavedData data = NationSavedData.get(server.overworld());
        NationMemberRecord member = data.getMember(identity.playerUuid());
        if (member != null) {
            TownRecord town = firstTownForNation(data, member.nationId());
            if (town != null && town.hasCore() && MarketWebMapConstants.OVERWORLD.equals(town.coreDimension())) {
                return BlockPos.of(town.corePos());
            }
            NationRecord nation = data.getNation(member.nationId());
            if (nation != null && nation.hasCore() && MarketWebMapConstants.OVERWORLD.equals(nation.coreDimension())) {
                return BlockPos.of(nation.corePos());
            }
        }
        return null;
    }

    private static TownRecord firstTownForNation(NationSavedData data, String nationId) {
        List<TownRecord> towns = data.getTownsForNation(nationId);
        return towns.isEmpty() ? null : towns.get(0);
    }

    private static MarketWebMapDtos.MarketMarker toMarketMarker(MarketTerminalSavedData.MarketTerminalEntry entry,
                                                                NationSavedData data) {
        BlockPos pos = entry.marketPos();
        String townId = "";
        String townName = "";
        if (data != null) {
            NationClaimRecord claim = data.getClaim(entry.dimensionId(), pos.getX() >> 4, pos.getZ() >> 4);
            TownRecord town = claim == null ? null : data.getTown(claim.townId());
            if (town != null) {
                townId = town.townId();
                townName = town.name();
            }
        }
        return new MarketWebMapDtos.MarketMarker(
                MarketWebService.encodeMarketId(entry.dimensionId(), pos),
                entry.marketName().isBlank() ? "Market" : entry.marketName(),
                entry.ownerName(),
                entry.ownerUuid(),
                entry.dimensionId(),
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                townId,
                townName
        );
    }

    private static MarketWebMapDtos.Territory toTerritory(NationClaimRecord claim, NationRecord nation, TownRecord town) {
        String nationId = nation == null ? claim.nationId() : nation.nationId();
        String nationName = nation == null ? claim.nationId() : nation.name();
        String townId = town == null ? claim.townId() : town.townId();
        String townName = town == null ? claim.townId() : town.name();
        String flagId = nation == null ? "" : nation.flagId();
        if (flagId.isBlank() && town != null) {
            flagId = town.flagId();
        }
        int fillRgb = nation == null ? FALLBACK_FILL : nation.primaryColorRgb();
        int borderRgb = nation == null ? FALLBACK_BORDER : nation.secondaryColorRgb();
        if (nation != null && borderRgb == 0) {
            borderRgb = fillRgb;
        }
        return new MarketWebMapDtos.Territory(
                nationId,
                nationName,
                townId,
                townName,
                flagId,
                claim.chunkX(),
                claim.chunkZ(),
                fillRgb,
                borderRgb
        );
    }
}
