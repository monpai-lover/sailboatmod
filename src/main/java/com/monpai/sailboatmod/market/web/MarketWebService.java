package com.monpai.sailboatmod.market.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.monpai.sailboatmod.block.entity.TownWarehouseBlockEntity;
import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.economy.GoldStandardEconomy;
import com.monpai.sailboatmod.market.MarketOverviewData;
import com.monpai.sailboatmod.market.MarketSavedData;
import com.monpai.sailboatmod.market.MarketListing;
import com.monpai.sailboatmod.market.PurchaseOrder;
import com.monpai.sailboatmod.market.FulfillmentMode;
import com.monpai.sailboatmod.market.SellerShipQueue;
import com.monpai.sailboatmod.market.ShippingOrder;
import com.monpai.sailboatmod.market.TransportTerminalKind;
import com.monpai.sailboatmod.market.analytics.CommodityCandlePoint;
import com.monpai.sailboatmod.market.analytics.CommodityCandleSeries;
import com.monpai.sailboatmod.market.analytics.CommodityImpactSnapshot;
import com.monpai.sailboatmod.market.analytics.MarketAnalyticsPoint;
import com.monpai.sailboatmod.market.analytics.MarketAnalyticsSeries;
import com.monpai.sailboatmod.market.commodity.BuyOrder;
import com.monpai.sailboatmod.market.commodity.CommodityConfigLoader;
import com.monpai.sailboatmod.market.commodity.CommodityDefinition;
import com.monpai.sailboatmod.market.commodity.CommodityInitializer;
import com.monpai.sailboatmod.market.commodity.CommodityKeyResolver;
import com.monpai.sailboatmod.market.commodity.CommodityMarketService;
import com.monpai.sailboatmod.market.commodity.CommodityQuote;
import com.monpai.sailboatmod.market.terminal.MarketTerminalSavedData;
import com.monpai.sailboatmod.market.web.map.MarketWebFlagService;
import com.monpai.sailboatmod.market.web.map.MarketWebMapConstants;
import com.monpai.sailboatmod.market.web.map.MarketWebMapJson;
import com.monpai.sailboatmod.market.web.map.MarketWebMapLayerService;
import com.monpai.sailboatmod.market.web.map.MarketWebMapPyramidWriter;
import com.monpai.sailboatmod.market.web.map.MarketWebMapRenderService;
import com.monpai.sailboatmod.market.web.map.MarketWebMapTileCache;
import com.monpai.sailboatmod.market.wallet.MarketWalletGoldSource;
import com.monpai.sailboatmod.market.wallet.MarketWalletService;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationPermission;
import com.monpai.sailboatmod.nation.model.NationTreasuryRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.service.NationService;
import com.monpai.sailboatmod.nation.service.TownService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class MarketWebService {
    private static final CommodityMarketService COMMODITY_MARKET = new CommodityMarketService();
    private final MarketWebMapLayerService mapLayers = new MarketWebMapLayerService();
    private final MarketWebFlagService mapFlags = new MarketWebFlagService();

    public record ItemPreview(String commodityKey, String itemId, String displayName, String category,
                              int suggestedUnitPrice) {
    }

    public record ActionResult(boolean ok, String errorCode, String message) {
        public static ActionResult success() {
            return new ActionResult(true, "", "");
        }

        public static ActionResult failure(String errorCode, String message) {
            return new ActionResult(false, errorCode == null ? "action_failed" : errorCode, message == null ? "Action failed" : message);
        }
    }

    public static ItemPreview resolveItemPreview(String itemId) {
        ItemStack stack = resolveItemStack(itemId);
        if (stack.isEmpty()) {
            return null;
        }
        String commodityKey = CommodityKeyResolver.resolve(stack);
        String displayName = stack.getHoverName().getString();
        CommodityDefinition definition = CommodityConfigLoader.apply(CommodityInitializer.createDefault(
                commodityKey,
                commodityKey,
                displayName
        ));
        return new ItemPreview(
                definition.commodityKey(),
                definition.itemId(),
                definition.displayName(),
                definition.category(),
                CommodityMarketService.estimateBaseUnitPrice(stack)
        );
    }

    public JsonArray listMarkets(MinecraftServer server, MarketPlayerIdentity identity) {
        JsonArray out = new JsonArray();
        if (server == null || identity == null) {
            return out;
        }
        List<MarketTerminalSavedData.MarketTerminalEntry> entries = new ArrayList<>(MarketTerminalSavedData.get(server.overworld()).entries());
        entries.sort(Comparator.comparing(MarketTerminalSavedData.MarketTerminalEntry::marketName, String.CASE_INSENSITIVE_ORDER));
        for (MarketTerminalSavedData.MarketTerminalEntry entry : entries) {
            JsonObject json = new JsonObject();
            json.addProperty("marketId", encodeMarketId(entry.dimensionId(), entry.marketPos()));
            json.addProperty("marketName", entry.marketName().isBlank() ? "Market" : entry.marketName());
            json.addProperty("ownerName", entry.ownerName());
            json.addProperty("ownerUuid", entry.ownerUuid());
            json.addProperty("dimensionId", entry.dimensionId());
            json.addProperty("position", entry.marketPos().toShortString());
            json.addProperty("canManage", entry.ownerUuid().equals(identity.playerUuidString()));
            json.addProperty("loaded", resolveMarket(server, entry.dimensionId(), entry.marketPos()) != null);
            out.add(json);
        }
        return out;
    }

    public JsonObject mapSnapshot(MinecraftServer server, MarketPlayerIdentity identity, String focusedMarketId) {
        return MarketWebMapJson.snapshot(mapLayers.snapshot(server, identity, focusedMarketId));
    }

    public JsonArray mapMarkets(MinecraftServer server, MarketPlayerIdentity identity) {
        return MarketWebMapJson.markets(mapLayers.markets(server));
    }

    public JsonArray mapTerritories(MinecraftServer server, MarketPlayerIdentity identity) {
        return MarketWebMapJson.territories(mapLayers.territories(server));
    }

    public JsonArray mapShipments(MinecraftServer server, MarketPlayerIdentity identity) {
        return MarketWebMapJson.shipments(mapLayers.shipments(server, identity));
    }

    public byte[] mapTile(MinecraftServer server, String lod, int tileX, int tileZ) {
        if (server == null || !"lod_1".equals(lod)) {
            return null;
        }
        MarketWebMapRenderService.global().enqueueTileRequest(server, lod, tileX, tileZ);
        return MarketWebMapTileCache.forServer(server)
                .readPng(MarketWebMapConstants.OVERWORLD, tileX, tileZ)
                .orElse(null);
    }

    public byte[] squareMapTile(MinecraftServer server, String dimension, int zoom, int tileX, int tileZ) {
        if (server == null || zoom < 0 || zoom > MarketWebMapPyramidWriter.MAX_ZOOM) {
            return null;
        }
        String dimensionId = "overworld".equals(dimension) ? MarketWebMapConstants.OVERWORLD : dimension;
        if (!MarketWebMapConstants.OVERWORLD.equals(dimensionId)) {
            return null;
        }
        MarketWebMapRenderService.global().enqueueSquareTileRequest(server, dimensionId, zoom, tileX, tileZ);
        return MarketWebMapTileCache.forServer(server)
                .readSquareTile(dimensionId, zoom, tileX, tileZ)
                .orElse(null);
    }

    public JsonObject mapRenderStatus(MinecraftServer server) {
        JsonObject json = MarketWebMapRenderService.global().renderStatus().toJson();
        json.addProperty("rendererVersion", MarketWebMapTileCache.RENDER_VERSION);
        json.addProperty("serverOnline", server != null);
        return json;
    }

    public byte[] mapFlag(MinecraftServer server, String flagId) {
        if (server == null || server.overworld() == null) {
            return null;
        }
        return mapFlags.readFlag(server.overworld(), flagId).orElse(null);
    }

    public JsonObject marketDetail(MinecraftServer server, MarketPlayerIdentity identity, String marketId) {
        ResolvedMarket resolved = resolveMarket(server, marketId);
        if (resolved == null || identity == null) {
            return null;
        }
        MarketOverviewData overview = resolved.market().buildOverviewForIdentity(
                identity.playerUuidString(),
                identity.playerName(),
                identity.onlinePlayer()
        );
        MarketSavedData marketData = MarketSavedData.get(resolved.level());

        // 未登录访客身份为 (null,"",null)；个人字段（钱包/现金/我的订单）只对已认证身份返回，
        // 防止越权暴露。判定标准：已认证 = playerUuid 非 null。
        boolean authenticated = identity.playerUuid() != null;

        JsonObject root = new JsonObject();
        root.addProperty("marketId", marketId);
        root.addProperty("marketName", overview.marketName());
        root.addProperty("ownerName", overview.ownerName());
        root.addProperty("ownerUuid", overview.ownerUuid());
        root.addProperty("linkedDock", overview.linkedDock());
        root.addProperty("linkedDockName", overview.linkedDockName());
        root.addProperty("linkedDockPos", overview.linkedDockPosText());
        root.addProperty("linkedWarehouse", overview.linkedDock());
        root.addProperty("linkedWarehouseName", overview.linkedDockName());
        root.addProperty("linkedWarehousePos", overview.linkedDockPosText());
        root.addProperty("canManage", overview.canManage());
        if (authenticated) {
            root.addProperty("walletBalance", overview.walletAvailableBalance());
            root.addProperty("walletAvailableBalance", overview.walletAvailableBalance());
            root.addProperty("walletReservedBalance", overview.walletReservedBalance());
            root.addProperty("walletTotalBalance", overview.walletTotalBalance());
        } else {
            root.addProperty("walletBalance", 0L);
            root.addProperty("walletAvailableBalance", 0L);
            root.addProperty("walletReservedBalance", 0L);
            root.addProperty("walletTotalBalance", 0L);
        }
        root.addProperty("treasuryBalance", authenticated ? overview.treasuryBalance() : 0L);
        root.addProperty("canTransferTreasury", authenticated && overview.canTransferTreasury());
        root.addProperty("cashBalance", authenticated ? cashBalance(identity) : 0L);
        root.addProperty("walletOnline", identity.onlinePlayer() != null);
        root.addProperty("walletCurrency", GoldStandardEconomy.LEDGER_CURRENCY);
        root.addProperty("pendingCredits", authenticated ? overview.pendingCredits() : 0L);
        root.addProperty("townName", overview.townName());
        root.addProperty("townId", overview.townId());
        root.addProperty("canChooseReceiving", overview.canChooseReceiving());
        root.add("receivingWarehouseOptions", receivingWarehouseOptions(overview));
        root.addProperty("stockpileCommodityTypes", overview.stockpileCommodityTypes());
        root.addProperty("stockpileTotalUnits", overview.stockpileTotalUnits());
        root.addProperty("openDemandCount", overview.openDemandCount());
        root.addProperty("openDemandUnits", overview.openDemandUnits());
        root.addProperty("activeProcurementCount", overview.activeProcurementCount());
        root.addProperty("totalIncome", overview.totalIncome());
        root.addProperty("totalExpense", overview.totalExpense());
        root.addProperty("netBalance", overview.netBalance());
        root.addProperty("employmentRate", overview.employmentRate());
        root.add("storageEntries", storageEntries(overview));
        root.add("listings", listingEntries(resolved.level(), marketData, overview));
        root.add("buyOrderEntries", buyOrderEntries(overview));
        root.add("priceCharts", priceCharts(overview));
        root.add("commodityBuyBooks", commodityBuyBooks(overview));
        root.add("candleSeries", candleSeries(overview));
        root.add("impactSnapshots", impactSnapshots(overview));
        root.add("analyticsSeries", analyticsSeries(overview));
        root.add("chartCapabilities", chartCapabilities(overview));
        if (authenticated) {
            root.add("myOrders", myOrders(marketData, identity.playerUuidString()));
        } else {
            root.add("myOrders", new com.google.gson.JsonArray());
        }
        root.add("sourceOrders", sourceOrders(overview));
        root.add("shippingEntries", shippingEntries(overview));
        root.add("stockpilePreviewLines", strings(overview.stockpilePreviewLines()));
        root.add("demandPreviewLines", strings(overview.demandPreviewLines()));
        root.add("procurementPreviewLines", strings(overview.procurementPreviewLines()));
        root.add("financePreviewLines", strings(overview.financePreviewLines()));
        return root;
    }

    /**
     * 聚合视图:列出<b>全服</b>所有活跃挂单(脱离区块——getListings 是 overworld 根级全服表),每条标注来源城镇,
     * 并对当前查看者算 purchasable(连通性,委托 {@link com.monpai.sailboatmod.market.terminal.TerminalVisibility})+ 不可购买原因。
     */
    public JsonObject aggregatedListings(MinecraftServer server, MarketPlayerIdentity identity) {
        JsonObject root = new JsonObject();
        JsonArray out = new JsonArray();
        if (server == null) {
            root.add("listings", out);
            return root;
        }
        ServerLevel overworld = server.overworld();
        MarketSavedData market = MarketSavedData.get(overworld);
        com.monpai.sailboatmod.market.terminal.TerminalNetworkSavedData net =
                com.monpai.sailboatmod.market.terminal.TerminalNetworkSavedData.get(overworld);
        com.monpai.sailboatmod.nation.data.NationSavedData nations =
                com.monpai.sailboatmod.nation.data.NationSavedData.get(overworld);

        boolean authenticated = identity != null && identity.playerUuid() != null;
        String viewerTownId = authenticated ? resolveViewerTownId(server, identity) : "";
        root.addProperty("viewerTownId", viewerTownId);
        root.addProperty("authenticated", authenticated);

        List<MarketListing> listings = new ArrayList<>(market.getListings());
        listings.sort(Comparator.comparing(l -> l.itemStack() == null ? "" : l.itemStack().getHoverName().getString(),
                String.CASE_INSENSITIVE_ORDER));
        for (MarketListing listing : listings) {
            if (listing == null || listing.itemStack() == null || listing.itemStack().isEmpty()) {
                continue;
            }
            String sourceTownId = listing.townId() == null ? "" : listing.townId();
            String sourceTownName = townNameOf(nations, sourceTownId);

            // 可购买性 + 原因(纯读内存连通网络,不碰区块)。
            boolean purchasable;
            String reason;
            if (!authenticated) {
                purchasable = false;
                reason = "not_logged_in";
            } else if (viewerTownId == null || viewerTownId.isBlank()) {
                purchasable = false;
                reason = "no_town";
            } else if (identity.playerUuidString().equals(listing.sellerUuid())) {
                purchasable = false;
                reason = "own_listing";
            } else if (com.monpai.sailboatmod.market.terminal.TerminalVisibility.isVisible(net, sourceTownId, viewerTownId)) {
                purchasable = true;
                reason = "";
            } else {
                purchasable = false;
                reason = "no_route";
            }

            JsonObject json = new JsonObject();
            json.addProperty("listingId", listing.listingId());
            json.addProperty("commodityKey", com.monpai.sailboatmod.market.commodity.CommodityKeyResolver.resolve(listing.itemStack()));
            json.addProperty("itemName", listing.itemStack().getHoverName().getString());
            json.addProperty("availableCount", listing.availableCount());
            json.addProperty("unitPrice", listing.unitPrice());
            json.addProperty("sellerName", listing.sellerName());
            json.addProperty("sourceDockName", listing.sourceDockName());
            json.addProperty("sourceTownId", sourceTownId);
            json.addProperty("sourceTownName", sourceTownName);
            json.addProperty("nationId", listing.nationId());
            json.addProperty("sellerNote", listing.sellerNote());
            json.addProperty("purchasable", purchasable);
            json.addProperty("purchaseReason", reason);
            out.add(json);
        }
        root.add("listings", out);
        return root;
    }

    /**
     * 聚合视图购买:按全局 listingId 购买,可见性按买家所属城镇判定,货送买家城镇仓库。
     * executorMarketId = 买家当前打开的市场(作为已加载的购买管线载体;扣款/收货均按买家解析,与该市场无关)。
     */
    public boolean purchaseAggregated(MinecraftServer server, MarketPlayerIdentity identity, String executorMarketId,
                                      String listingId, int quantity, String fulfillment,
                                      net.minecraft.core.BlockPos targetWarehousePos) {
        if (server == null || identity == null || identity.playerUuid() == null
                || listingId == null || listingId.isBlank()) {
            return false;
        }
        String viewerTownId = resolveViewerTownId(server, identity);
        if (viewerTownId == null || viewerTownId.isBlank()) {
            return false;
        }
        ResolvedMarket executor = resolveMarket(server, executorMarketId);
        if (executor == null) {
            return false;
        }
        return executor.market().purchaseAggregatedById(
                identity.playerUuidString(),
                identity.playerName(),
                identity.onlinePlayer(),
                listingId,
                quantity,
                viewerTownId,
                fulfillment,
                targetWarehousePos
        );
    }

    /** 查看者所属城镇:在线优先按其当前坐标所在城镇,否则按成员表。 */
    private String resolveViewerTownId(MinecraftServer server, MarketPlayerIdentity identity) {
        if (identity == null || identity.playerUuid() == null) {
            return "";
        }
        if (identity.onlinePlayer() != null) {
            TownRecord town = TownService.getTownAt(identity.onlinePlayer().level(), identity.onlinePlayer().blockPosition());
            if (town != null && town.townId() != null && !town.townId().isBlank()) {
                return town.townId();
            }
        }
        List<String> towns = NationSavedData.get(server.overworld()).getTownsForPlayer(identity.playerUuid());
        return towns.isEmpty() ? "" : towns.get(0);
    }

    private static String townNameOf(NationSavedData nations, String townId) {
        if (nations == null || townId == null || townId.isBlank()) {
            return "";
        }
        TownRecord town = nations.getTown(townId);
        return town == null ? "" : town.name();
    }

    private static long cashBalance(MarketPlayerIdentity identity) {
        if (identity == null) {
            return 0L;
        }
        if (identity.onlinePlayer() != null) {
            return GoldStandardEconomy.getBalance(identity.onlinePlayer());
        }
        return GoldStandardEconomy.getBalanceByIdentity(identity.playerUuid(), identity.playerName());
    }

    public ActionResult createListing(MinecraftServer server, MarketPlayerIdentity identity, String marketId, int storageIndex, int quantity, int unitPrice, String sellerNote) {
        ResolvedMarket resolved = resolveMarket(server, marketId);
        if (resolved == null || identity == null) {
            return ActionResult.failure("market_not_found", "Market not found");
        }
        com.monpai.sailboatmod.block.entity.MarketBlockEntity.CreateListingResult result =
                resolved.market().createListingFromDockStorage(
                        identity.playerUuidString(),
                        identity.playerName(),
                        storageIndex,
                        quantity,
                        unitPrice,
                        sellerNote
                );
        return result.success()
                ? ActionResult.success()
                : ActionResult.failure(result.messageKey(), result.message().getString());
    }

    /**
     * 列出市场关联殖民地(MineColonies)仓库内可上架物品。仅对已认证且能管理本市场的身份返回非空数组,否则空数组。
     * 外层不再单独返回 colonyId(每项内嵌 colonyId),前端上架时按物品携带的 colonyId 回传。
     */
    public JsonArray mineColoniesWarehouseItems(MinecraftServer server, MarketPlayerIdentity identity, String marketId) {
        JsonArray out = new JsonArray();
        ResolvedMarket resolved = resolveMarket(server, marketId);
        if (resolved == null || identity == null || identity.playerUuid() == null) {
            return out;
        }
        MarketBlockEntity market = resolved.market();
        // 仅市场管理者可见(与 marketDetail 的 canManage 同源)。
        MarketOverviewData overview = market.buildOverviewForIdentity(
                identity.playerUuidString(),
                identity.playerName(),
                identity.onlinePlayer()
        );
        if (!overview.canManage()) {
            return out;
        }
        int colonyId = market.resolveLinkedColonyId();
        for (com.monpai.sailboatmod.integration.minecolonies.MineColoniesWarehouseIntegration.WarehouseItem item
                : market.listMineColoniesWarehouseItems()) {
            if (item == null || item.stack() == null || item.stack().isEmpty() || item.count() <= 0) {
                continue;
            }
            ItemStack stack = item.stack();
            JsonObject json = new JsonObject();
            ResourceLocation regId = ForgeRegistries.ITEMS.getKey(stack.getItem());
            json.addProperty("itemId", regId != null ? regId.toString() : CommodityKeyResolver.resolve(stack));
            json.addProperty("commodityKey", CommodityKeyResolver.resolve(stack));
            json.addProperty("itemName", stack.getHoverName().getString());
            json.addProperty("count", item.count());
            json.addProperty("suggestedUnitPrice", CommodityMarketService.estimateBaseUnitPrice(stack));
            json.addProperty("colonyId", colonyId);
            out.add(json);
        }
        return out;
    }

    /**
     * 从殖民地仓库上架:把 itemId 解析成 ItemStack,委托 {@link MarketBlockEntity#createListingFromMineColoniesWarehouse}
     * (权限/抽货/入库/建 listing 全在后端原子完成)。返回 ActionResult 供路由层拼成功/失败 JSON。
     */
    public ActionResult createMineColoniesListing(MinecraftServer server, MarketPlayerIdentity identity, String marketId,
                                                  int colonyId, String itemId, int quantity, int unitPrice, String sellerNote) {
        ResolvedMarket resolved = resolveMarket(server, marketId);
        if (resolved == null || identity == null) {
            return ActionResult.failure("market_not_found", "Market not found");
        }
        ItemStack stack = resolveItemStack(itemId);
        if (stack.isEmpty()) {
            return ActionResult.failure("invalid_item", "Invalid item");
        }
        com.monpai.sailboatmod.block.entity.MarketBlockEntity.CreateListingResult result =
                resolved.market().createListingFromMineColoniesWarehouse(
                        identity.playerUuidString(),
                        identity.playerName(),
                        identity.onlinePlayer(),
                        colonyId,
                        stack,
                        quantity,
                        unitPrice,
                        sellerNote
                );
        return result.success()
                ? ActionResult.success()
                : ActionResult.failure(result.messageKey(), result.message().getString());
    }

    public boolean purchaseListing(MinecraftServer server, MarketPlayerIdentity identity, String marketId, int listingIndex, int quantity) {
        return purchaseListing(server, identity, marketId, listingIndex, quantity, "SELLER_SHIP", null);
    }

    public boolean purchaseListing(MinecraftServer server, MarketPlayerIdentity identity, String marketId, int listingIndex, int quantity,
                                   String fulfillment, net.minecraft.core.BlockPos targetWarehousePos) {
        ResolvedMarket resolved = resolveMarket(server, marketId);
        return resolved != null && identity != null
                && resolved.market().purchaseListing(
                identity.playerUuidString(),
                identity.playerName(),
                identity.onlinePlayer(),
                listingIndex,
                quantity,
                fulfillment,
                targetWarehousePos
        );
    }

    public JsonObject probeFulfillmentModes(MinecraftServer server, MarketPlayerIdentity identity, String marketId,
                                            int listingIndex, net.minecraft.core.BlockPos targetWarehousePos) {
        JsonObject json = new JsonObject();
        ResolvedMarket resolved = resolveMarket(server, marketId);
        boolean sellerShip = false;
        boolean autoPickup = false;
        boolean realPickup = true;
        if (resolved != null && identity != null) {
            MarketBlockEntity.ModeReachability r = resolved.market().probeFulfillmentModes(
                    identity.playerUuidString(), targetWarehousePos, identity.onlinePlayer());
            sellerShip = r.sellerShip();
            autoPickup = r.autoPickup();
            realPickup = r.realPickup();
        }
        json.addProperty("sellerShip", sellerShip);
        json.addProperty("autoPickup", autoPickup);
        json.addProperty("realPickup", realPickup);
        return json;
    }

    public boolean cancelListing(MinecraftServer server, MarketPlayerIdentity identity, String marketId, String listingId) {
        ResolvedMarket resolved = resolveMarket(server, marketId);
        return resolved != null && identity != null
                && resolved.market().cancelListingById(identity.playerUuidString(), listingId);
    }

    public boolean cancelPurchaseOrder(MinecraftServer server, MarketPlayerIdentity identity, String marketId, String orderId) {
        ResolvedMarket resolved = resolveMarket(server, marketId);
        return resolved != null && identity != null
                && resolved.market().cancelPurchaseOrderById(identity.playerUuidString(), orderId).success();
    }

    public boolean claimCredits(MinecraftServer server, MarketPlayerIdentity identity, String marketId) {
        ResolvedMarket resolved = resolveMarket(server, marketId);
        return resolved != null && identity != null
                && resolved.market().claimPendingCredits(identity.playerUuidString(), identity.playerName(), identity.onlinePlayer());
    }

    public ActionResult transferWallet(MinecraftServer server, MarketPlayerIdentity identity, String marketId, String action, long amount) {
        ResolvedMarket resolved = resolveMarket(server, marketId);
        if (resolved == null || identity == null || identity.playerUuid() == null) {
            return ActionResult.failure("market_not_found", "Market not found");
        }
        String normalizedAction = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        long safeAmount = Math.max(0L, amount);
        String playerUuid = identity.playerUuidString();
        String playerName = identity.playerName();
        return switch (normalizedAction) {
            case "CASH_TO_WALLET" -> cashToWallet(resolved, identity, playerUuid, playerName, safeAmount);
            case "WALLET_TO_CASH" -> walletToCash(resolved, identity, playerUuid, playerName, safeAmount);
            case "WALLET_TO_TREASURY" -> walletToTreasury(resolved, identity, playerUuid, playerName, safeAmount);
            case "TREASURY_TO_WALLET" -> treasuryToWallet(resolved, identity, playerUuid, playerName, safeAmount);
            case "CLAIM_CREDITS_TO_WALLET" -> claimCreditsToWallet(resolved, playerUuid, playerName);
            default -> ActionResult.failure("invalid_wallet_action", "Invalid wallet action");
        };
    }

    private ActionResult cashToWallet(ResolvedMarket resolved, MarketPlayerIdentity identity, String playerUuid, String playerName, long amount) {
        if (amount <= 0L) {
            return ActionResult.failure("invalid_amount", "Invalid amount");
        }
        // 三态：null=无 Vault，true=Vault 已扣，false=Vault 余额不足
        Boolean vaultWithdrawn = identity.onlinePlayer() != null
                ? GoldStandardEconomy.tryWithdraw(identity.onlinePlayer(), amount)
                : GoldStandardEconomy.tryWithdrawByIdentity(identity.playerUuid(), playerName, amount);
        boolean withdrew = Boolean.TRUE.equals(vaultWithdrawn);
        if (!withdrew) {
            // Vault 不可用或不足 → 必须从绑定仓库真扣到，才算扣源成功
            withdrew = MarketWalletGoldSource.withdrawFromLinkedWarehouse(resolved.market(), identity.playerUuid(), amount);
        }
        if (!withdrew) {
            return ActionResult.failure("insufficient_cash", "Insufficient cash");
        }
        // 仅在确凿扣源成功后加钱包
        MarketWalletService.deposit(resolved.level(), playerUuid, playerName, amount);
        return ActionResult.success();
    }

    private ActionResult walletToCash(ResolvedMarket resolved, MarketPlayerIdentity identity, String playerUuid, String playerName, long amount) {
        if (amount <= 0L) {
            return ActionResult.failure("invalid_amount", "Invalid amount");
        }
        MarketWalletService.AccountResult withdrawn = MarketWalletService.withdraw(resolved.level(), playerUuid, playerName, amount);
        if (!withdrawn.success()) {
            return ActionResult.failure("insufficient_wallet", "Insufficient market wallet balance");
        }
        Boolean deposited = GoldStandardEconomy.tryDepositByIdentity(identity.playerUuid(), playerName, amount);
        if (Boolean.TRUE.equals(deposited)) {
            return ActionResult.success();
        }
        if (deposited == null && MarketWalletGoldSource.depositToLinkedWarehouse(resolved.market(), identity.playerUuid(), amount)) {
            return ActionResult.success();
        }
        // rollback wallet: 给付未成功，把已扣的钱包额精确退回，避免黑洞
        MarketWalletService.deposit(resolved.level(), playerUuid, playerName, amount);
        return ActionResult.failure("cash_deposit_unavailable", "Cash deposit is unavailable");
    }

    private ActionResult walletToTreasury(ResolvedMarket resolved, MarketPlayerIdentity identity, String playerUuid, String playerName, long amount) {
        if (amount <= 0L) {
            return ActionResult.failure("invalid_amount", "Invalid amount");
        }
        String nationId = resolveNationId(resolved);
        if (nationId.isBlank()) {
            return ActionResult.failure("treasury_not_found", "Treasury not found");
        }
        MarketWalletService.AccountResult withdrawn = MarketWalletService.withdraw(resolved.level(), playerUuid, playerName, amount);
        if (!withdrawn.success()) {
            return ActionResult.failure("insufficient_wallet", "Insufficient market wallet balance");
        }
        NationSavedData data = NationSavedData.get(resolved.level());
        NationTreasuryRecord treasury = data.getOrCreateTreasury(nationId);
        data.putTreasury(treasury.withBalance(saturatedAdd(treasury.currencyBalance(), amount)));
        return ActionResult.success();
    }

    private ActionResult treasuryToWallet(ResolvedMarket resolved, MarketPlayerIdentity identity, String playerUuid, String playerName, long amount) {
        if (amount <= 0L) {
            return ActionResult.failure("invalid_amount", "Invalid amount");
        }
        String nationId = resolveNationId(resolved);
        if (nationId.isBlank()) {
            return ActionResult.failure("treasury_not_found", "Treasury not found");
        }
        UUID uuid = identity.playerUuid();
        if (!NationService.hasPermission(resolved.level(), uuid, NationPermission.MANAGE_TREASURY)) {
            return ActionResult.failure("no_treasury_permission", "No treasury permission");
        }
        NationSavedData data = NationSavedData.get(resolved.level());
        NationTreasuryRecord treasury = data.getOrCreateTreasury(nationId);
        if (treasury.currencyBalance() < amount) {
            return ActionResult.failure("insufficient_treasury", "Insufficient treasury balance");
        }
        data.putTreasury(treasury.withBalance(treasury.currencyBalance() - amount));
        MarketWalletService.deposit(resolved.level(), playerUuid, playerName, amount);
        return ActionResult.success();
    }

    private ActionResult claimCreditsToWallet(ResolvedMarket resolved, String playerUuid, String playerName) {
        MarketSavedData marketData = MarketSavedData.get(resolved.level());
        int pending = marketData.clearPendingCredits(playerUuid);
        if (pending <= 0) {
            return ActionResult.failure("no_pending_credits", "No pending credits");
        }
        MarketWalletService.deposit(resolved.level(), playerUuid, playerName, pending);
        return ActionResult.success();
    }

    private static String resolveNationId(ResolvedMarket resolved) {
        if (resolved == null) {
            return "";
        }
        TownWarehouseBlockEntity warehouse = resolved.market().getLinkedWarehouse();
        TownRecord town = null;
        if (warehouse != null) {
            String townId = warehouse.getTownId();
            if (townId != null && !townId.isBlank()) {
                town = NationSavedData.get(resolved.level()).getTown(townId);
            }
            if (town == null) {
                town = TownService.getTownAt(resolved.level(), warehouse.getBlockPos());
            }
        }
        if (town == null) {
            town = TownService.getTownAt(resolved.level(), resolved.market().getBlockPos());
        }
        return town == null ? "" : town.nationId();
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    public boolean retryDispatch(MinecraftServer server, MarketPlayerIdentity identity, String marketId, int orderIndex, String terminalType) {
        ResolvedMarket resolved = resolveMarket(server, marketId);
        return resolved != null && identity != null
                && resolved.market().dispatchOrder(
                identity.playerUuidString(),
                identity.playerName(),
                identity.onlinePlayer(),
                orderIndex,
                0,
                TransportTerminalKind.fromName(terminalType)
        );
    }

    public boolean createBuyOrder(MinecraftServer server, MarketPlayerIdentity identity, String marketId, String commodityKey, int quantity, int minPriceBp, int maxPriceBp) {
        ResolvedMarket resolved = resolveMarket(server, marketId);
        if (resolved == null || identity == null || commodityKey == null || commodityKey.isBlank()) {
            return false;
        }
        try {
            ItemStack stack = resolveItemStack(commodityKey);
            if (stack.isEmpty()) {
                return false;
            }
            int safeQuantity = Math.max(1, quantity);
            int reserveBp = Math.max(minPriceBp, maxPriceBp);
            // pricing: buy-order unit price uses market reference price (trade-avg -> lowest ask -> basePrice)
            int lowestAsk = MarketSavedData.get(resolved.level())
                    .lowestActiveAsk(com.monpai.sailboatmod.market.commodity.CommodityKeyResolver.resolve(stack));
            int referenceUnitPrice = COMMODITY_MARKET.referencePrice(stack, lowestAsk);
            long reservedBalance = identity.onlinePlayer() != null && identity.onlinePlayer().getAbilities().instabuild
                    ? 0L
                    : CommodityMarketService.reservedBalanceForBuyOrder(referenceUnitPrice, safeQuantity, reserveBp);
            if (reservedBalance > 0L
                    && !MarketWalletService.reserve(resolved.level(), identity.playerUuidString(), identity.playerName(), reservedBalance).success()) {
                return false;
            }
            try {
                BuyOrder created = COMMODITY_MARKET.createReservedBuyOrder(
                        stack,
                        safeQuantity,
                        minPriceBp,
                        maxPriceBp,
                        identity.playerUuidString(),
                        identity.playerName(),
                        reservedBalance
                );
                return created != null;
            } catch (Exception createException) {
                if (reservedBalance > 0L) {
                    MarketWalletService.releaseReserved(resolved.level(), identity.playerUuidString(), identity.playerName(), reservedBalance);
                }
                return false;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean cancelBuyOrder(MinecraftServer server, MarketPlayerIdentity identity, String marketId, String orderId) {
        ResolvedMarket resolved = resolveMarket(server, marketId);
        if (resolved == null || identity == null || orderId == null || orderId.isBlank()) {
            return false;
        }
        try {
            BuyOrder cancelled = COMMODITY_MARKET.cancelBuyOrderForBuyerReturningOrder(
                    orderId,
                    identity.playerUuidString()
            );
            if (cancelled == null) {
                return false;
            }
            return cancelled.reservedBalance() <= 0L
                    || MarketWalletService.releaseReserved(
                    resolved.level(),
                    identity.playerUuidString(),
                    identity.playerName(),
                    cancelled.reservedBalance()
            ).success();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String encodeMarketId(String dimensionId, BlockPos pos) {
        String raw = (dimensionId == null ? "" : dimensionId.trim()) + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public ResolvedMarket resolveMarket(MinecraftServer server, String marketId) {
        if (server == null || marketId == null || marketId.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(marketId), java.nio.charset.StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|");
            if (parts.length != 4) {
                return null;
            }
            return resolveMarket(server, parts[0], new BlockPos(
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3])
            ));
        } catch (Exception ignored) {
            return null;
        }
    }

    public ResolvedMarket resolveMarket(MinecraftServer server, String dimensionId, BlockPos pos) {
        if (server == null || dimensionId == null || dimensionId.isBlank() || pos == null) {
            return null;
        }
        ResourceLocation resourceLocation = ResourceLocation.tryParse(dimensionId);
        if (resourceLocation == null) {
            return null;
        }
        ResourceKey<Level> key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, resourceLocation);
        ServerLevel level = server.getLevel(key);
        if (level == null) {
            return null;
        }
        level.getChunkAt(pos);
        if (!(level.getBlockEntity(pos) instanceof MarketBlockEntity market)) {
            return null;
        }
        return new ResolvedMarket(level, market);
    }

    private JsonArray receivingWarehouseOptions(MarketOverviewData overview) {
        JsonArray out = new JsonArray();
        for (MarketOverviewData.WarehouseOption opt : overview.receivingWarehouseOptions()) {
            JsonObject json = new JsonObject();
            BlockPos pos = opt.pos();
            json.addProperty("pos", pos.getX() + "," + pos.getY() + "," + pos.getZ());
            json.addProperty("displayName", opt.displayName());
            json.addProperty("townName", opt.townName());
            out.add(json);
        }
        return out;
    }

    private JsonArray storageEntries(MarketOverviewData overview) {
        JsonArray out = new JsonArray();
        for (int i = 0; i < overview.dockStorageEntries().size(); i++) {
            MarketOverviewData.StorageEntry entry = overview.dockStorageEntries().get(i);
            JsonObject json = new JsonObject();
            json.addProperty("index", i);
            json.addProperty("commodityKey", entry.commodityKey());
            json.addProperty("itemName", entry.itemName());
            json.addProperty("quantity", entry.quantity());
            json.addProperty("suggestedUnitPrice", entry.suggestedUnitPrice());
            json.addProperty("minAllowedUnitPrice", entry.minAllowedUnitPrice());
            json.addProperty("maxAllowedUnitPrice", entry.maxAllowedUnitPrice());
            json.addProperty("priceConstrained", entry.priceConstrained());
            json.addProperty("detail", entry.detail());
            json.addProperty("category", entry.category());
            json.addProperty("rarity", entry.rarity());
            out.add(json);
        }
        return out;
    }

    private JsonArray listingEntries(ServerLevel level, MarketSavedData marketData, MarketOverviewData overview) {
        JsonArray out = new JsonArray();
        for (int i = 0; i < overview.listingEntries().size(); i++) {
            MarketOverviewData.ListingEntry entry = overview.listingEntries().get(i);
            JsonObject json = new JsonObject();
            json.addProperty("index", i);
            json.addProperty("listingId", listingIdFor(level, marketData, i));
            json.addProperty("commodityKey", entry.commodityKey());
            json.addProperty("itemName", entry.itemName());
            json.addProperty("availableCount", entry.availableCount());
            json.addProperty("reservedCount", entry.reservedCount());
            json.addProperty("unitPrice", entry.unitPrice());
            json.addProperty("sellerName", entry.sellerName());
            json.addProperty("sourceDockName", entry.sourceDockName());
            json.addProperty("nationId", entry.nationId());
            json.addProperty("sellerNote", entry.sellerNote());
            json.addProperty("category", entry.category());
            json.addProperty("rarity", entry.rarity());
            out.add(json);
        }
        return out;
    }

    private JsonArray buyOrderEntries(MarketOverviewData overview) {
        JsonArray out = new JsonArray();
        for (MarketOverviewData.BuyOrderEntry entry : overview.buyOrderEntries()) {
            JsonObject json = new JsonObject();
            json.addProperty("orderId", entry.orderId());
            json.addProperty("commodityKey", entry.commodityKey());
            json.addProperty("quantity", entry.quantity());
            json.addProperty("minPriceBp", entry.minPriceBp());
            json.addProperty("maxPriceBp", entry.maxPriceBp());
            json.addProperty("buyerName", entry.buyerName());
            json.addProperty("status", entry.status());
            json.addProperty("createdAt", entry.createdAt());
            out.add(json);
        }
        return out;
    }

    private JsonArray priceCharts(MarketOverviewData overview) {
        JsonArray out = new JsonArray();
        for (MarketOverviewData.PriceChartSeries series : overview.priceChartSeries()) {
            JsonObject json = new JsonObject();
            json.addProperty("commodityKey", series.commodityKey());
            json.addProperty("displayName", series.displayName());
            JsonArray points = new JsonArray();
            for (MarketOverviewData.PriceChartPoint point : series.points()) {
                JsonObject p = new JsonObject();
                p.addProperty("bucketAt", point.bucketAt());
                p.addProperty("averageUnitPrice", point.averageUnitPrice());
                p.addProperty("minUnitPrice", point.minUnitPrice());
                p.addProperty("maxUnitPrice", point.maxUnitPrice());
                p.addProperty("volume", point.volume());
                p.addProperty("tradeCount", point.tradeCount());
                points.add(p);
            }
            json.add("points", points);
            out.add(json);
        }
        return out;
    }

    private JsonArray commodityBuyBooks(MarketOverviewData overview) {
        JsonArray out = new JsonArray();
        for (MarketOverviewData.CommodityBuyBook book : overview.commodityBuyBooks()) {
            JsonObject json = new JsonObject();
            json.addProperty("commodityKey", book.commodityKey());
            json.addProperty("displayName", book.displayName());
            JsonArray entries = new JsonArray();
            for (MarketOverviewData.CommodityBuyEntry entry : book.entries()) {
                JsonObject e = new JsonObject();
                e.addProperty("orderId", entry.orderId());
                e.addProperty("buyerName", entry.buyerName());
                e.addProperty("quantity", entry.quantity());
                e.addProperty("minPriceBp", entry.minPriceBp());
                e.addProperty("maxPriceBp", entry.maxPriceBp());
                e.addProperty("createdAt", entry.createdAt());
                e.addProperty("status", entry.status());
                entries.add(e);
            }
            json.add("entries", entries);
            out.add(json);
        }
        return out;
    }

    private JsonArray candleSeries(MarketOverviewData overview) {
        JsonArray out = new JsonArray();
        for (CommodityCandleSeries series : overview.candleSeries()) {
            JsonObject json = new JsonObject();
            json.addProperty("commodityKey", series.commodityKey());
            json.addProperty("displayName", series.displayName());
            json.addProperty("timeframe", series.timeframe());
            JsonArray points = new JsonArray();
            for (CommodityCandlePoint point : series.points()) {
                JsonObject p = new JsonObject();
                p.addProperty("bucketAt", point.bucketAt());
                p.addProperty("openUnitPrice", point.openUnitPrice());
                p.addProperty("highUnitPrice", point.highUnitPrice());
                p.addProperty("lowUnitPrice", point.lowUnitPrice());
                p.addProperty("closeUnitPrice", point.closeUnitPrice());
                p.addProperty("volume", point.volume());
                p.addProperty("tradeCount", point.tradeCount());
                points.add(p);
            }
            json.add("points", points);
            out.add(json);
        }
        return out;
    }

    private JsonArray impactSnapshots(MarketOverviewData overview) {
        JsonArray out = new JsonArray();
        for (CommodityImpactSnapshot snapshot : overview.commodityImpactSnapshots()) {
            JsonObject json = new JsonObject();
            json.addProperty("commodityKey", snapshot.commodityKey());
            json.addProperty("referenceUnitPrice", snapshot.referenceUnitPrice());
            json.addProperty("currentClosePrice", snapshot.currentClosePrice());
            json.addProperty("liquidityScore", snapshot.liquidityScore());
            json.addProperty("inventoryPressureBp", snapshot.inventoryPressureBp());
            json.addProperty("buyPressureBp", snapshot.buyPressureBp());
            json.addProperty("volatilityBp", snapshot.volatilityBp());
            out.add(json);
        }
        return out;
    }

    private JsonArray analyticsSeries(MarketOverviewData overview) {
        JsonArray out = new JsonArray();
        for (MarketAnalyticsSeries series : overview.analyticsSeries()) {
            JsonObject json = new JsonObject();
            json.addProperty("scopeType", series.scopeType());
            json.addProperty("scopeKey", series.scopeKey());
            json.addProperty("displayName", series.displayName());
            JsonArray points = new JsonArray();
            for (MarketAnalyticsPoint point : series.points()) {
                JsonObject p = new JsonObject();
                p.addProperty("bucketAt", point.bucketAt());
                p.addProperty("value", point.value());
                p.addProperty("volume", point.volume());
                p.addProperty("tradeCount", point.tradeCount());
                points.add(p);
            }
            json.add("points", points);
            out.add(json);
        }
        return out;
    }

    private JsonArray myOrders(MarketSavedData marketData, String buyerUuid) {
        JsonArray out = new JsonArray();
        List<PurchaseOrder> orders = marketData.getOrdersForBuyer(buyerUuid);
        orders.sort(Comparator.comparing(PurchaseOrder::orderId));
        for (PurchaseOrder order : orders) {
            JsonObject json = new JsonObject();
            json.addProperty("orderId", order.orderId());
            json.addProperty("listingId", order.listingId());
            json.addProperty("buyerName", order.buyerName());
            json.addProperty("quantity", order.quantity());
            json.addProperty("totalPrice", order.totalPrice());
            json.addProperty("sourceDockName", dockLabel(order.sourceDockName(), order.sourceDockPos()));
            json.addProperty("targetDockName", dockLabel(order.targetDockName(), order.targetDockPos()));
            json.addProperty("status", order.status());
            // 卖家发货排队：按该订单源仓的 SELLER_SHIP 待发队列稳定排序求位次（与客户端 overview 同源，见 SellerShipQueue）。
            int queuePosition = 0;
            int queueEtaSeconds = 0;
            if (FulfillmentMode.fromString(order.fulfillment()) == FulfillmentMode.SELLER_SHIP
                    && "WAITING_SHIPMENT".equals(order.status())) {
                List<String> queueIds = new java.util.ArrayList<>();
                for (PurchaseOrder o : marketData.getOpenOrdersForSourceDock(order.sourceDockPos())) {
                    if (FulfillmentMode.fromString(o.fulfillment()) == FulfillmentMode.SELLER_SHIP
                            && "WAITING_SHIPMENT".equals(o.status())) {
                        queueIds.add(o.orderId());
                    }
                }
                queueIds.sort(Comparator.naturalOrder());
                queuePosition = SellerShipQueue.positionOf(queueIds, order.orderId());
                queueEtaSeconds = SellerShipQueue.etaSeconds(queuePosition);
            }
            json.addProperty("queuePosition", queuePosition);
            json.addProperty("queueEtaSeconds", queueEtaSeconds);
            ShippingOrder shipping = marketData.getShippingOrderForPurchaseOrder(order.orderId());
            if (shipping != null) {
                JsonObject ship = new JsonObject();
                ship.addProperty("shippingOrderId", shipping.shippingOrderId());
                ship.addProperty("boatName", shipping.boatName());
                ship.addProperty("routeName", shipping.routeName());
                ship.addProperty("status", shipping.status());
                json.add("shipping", ship);
            }
            out.add(json);
        }
        return out;
    }

    private JsonArray sourceOrders(MarketOverviewData overview) {
        JsonArray out = new JsonArray();
        for (int i = 0; i < overview.orderEntries().size(); i++) {
            MarketOverviewData.OrderEntry order = overview.orderEntries().get(i);
            JsonObject json = new JsonObject();
            json.addProperty("index", i);
            json.addProperty("label", order.label());
            json.addProperty("sourceDockName", order.sourceDockName());
            json.addProperty("targetDockName", order.targetDockName());
            json.addProperty("quantity", order.quantity());
            json.addProperty("status", order.status());
            json.addProperty("queuePosition", order.queuePosition());
            json.addProperty("queueEtaSeconds", order.queueEtaSeconds());
            json.addProperty("orderId", order.orderId());
            json.addProperty("buyerUuid", order.buyerUuid());
            json.addProperty("cancellable", order.cancellable());
            JsonArray options = new JsonArray();
            for (MarketOverviewData.DispatchOption option : order.dispatchOptions()) {
                JsonObject dispatch = new JsonObject();
                dispatch.addProperty("terminalKind", option.terminalKind());
                dispatch.addProperty("terminalLabel", option.terminalLabel());
                dispatch.addProperty("carrierName", option.carrierName());
                dispatch.addProperty("routeName", option.routeName());
                dispatch.addProperty("sourceTerminalName", option.sourceTerminalName());
                dispatch.addProperty("targetTerminalName", option.targetTerminalName());
                dispatch.addProperty("distanceMeters", option.distanceMeters());
                dispatch.addProperty("etaSeconds", option.etaSeconds());
                dispatch.addProperty("available", option.available());
                dispatch.addProperty("availability", option.availability());
                dispatch.addProperty("detail", option.detail());
                options.add(dispatch);
            }
            json.add("dispatchOptions", options);
            out.add(json);
        }
        return out;
    }

    private JsonArray shippingEntries(MarketOverviewData overview) {
        JsonArray out = new JsonArray();
        for (MarketOverviewData.ShippingEntry entry : overview.shippingEntries()) {
            JsonObject json = new JsonObject();
            json.addProperty("label", entry.label());
            json.addProperty("boatName", entry.boatName());
            json.addProperty("routeName", entry.routeName());
            json.addProperty("mode", entry.mode());
            out.add(json);
        }
        return out;
    }

    private JsonArray strings(List<String> values) {
        JsonArray out = new JsonArray();
        for (String value : values) {
            out.add(value == null ? "" : value);
        }
        return out;
    }

    private JsonObject chartCapabilities(MarketOverviewData overview) {
        JsonObject json = new JsonObject();
        json.addProperty("candles", !overview.candleSeries().isEmpty());
        json.addProperty("macroCharts", !overview.analyticsSeries().isEmpty());
        json.addProperty("logScale", true);
        json.addProperty("inflation", overview.analyticsSeriesFor("MACRO_INDEX", "cpi") != null
                && !overview.analyticsSeriesFor("MACRO_INDEX", "cpi").points().isEmpty());
        return json;
    }

    private String listingIdFor(ServerLevel level, MarketSavedData marketData, int index) {
        MarketListing listing = marketData.getListingByVisibleIndex(index);
        return listing == null ? "" : listing.listingId();
    }

    private static ItemStack resolveItemStack(String commodityKey) {
        ResourceLocation itemId = ResourceLocation.tryParse(commodityKey);
        if (itemId == null) {
            return ItemStack.EMPTY;
        }
        Item item = ForgeRegistries.ITEMS.getValue(itemId);
        if (item == null) {
            item = BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
        }
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static String dockLabel(String name, BlockPos pos) {
        return name == null || name.isBlank() ? pos.toShortString() : name;
    }

    public record ResolvedMarket(ServerLevel level, MarketBlockEntity market) {
    }
}
