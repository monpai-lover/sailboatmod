package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.dock.DockRegistry;
import com.monpai.sailboatmod.dock.PostStationRegistry;
import com.monpai.sailboatmod.dock.TownWarehouseRegistry;
import com.monpai.sailboatmod.economy.GoldStandardEconomy;
import com.monpai.sailboatmod.entity.SailboatEntity;
import com.monpai.sailboatmod.market.MarketListing;
import com.monpai.sailboatmod.market.MarketOverviewData;
import com.monpai.sailboatmod.market.MarketSavedData;
import com.monpai.sailboatmod.market.ProcurementRecord;
import com.monpai.sailboatmod.market.ProcurementService;
import com.monpai.sailboatmod.market.PurchaseOrder;
import com.monpai.sailboatmod.market.ShipmentManifestEntry;
import com.monpai.sailboatmod.market.ShippingOrder;
import com.monpai.sailboatmod.market.TransportTerminalKind;
import com.monpai.sailboatmod.market.commodity.CommodityKeyResolver;
import com.monpai.sailboatmod.market.commodity.CommodityMarketService;
import com.monpai.sailboatmod.market.commodity.CommodityPriceChartPoint;
import com.monpai.sailboatmod.market.commodity.CommodityQuote;
import com.monpai.sailboatmod.market.commodity.MarketTradeSide;
import com.monpai.sailboatmod.market.analytics.CommodityCandleSeries;
import com.monpai.sailboatmod.market.analytics.CommodityImpactSnapshot;
import com.monpai.sailboatmod.market.analytics.MarketAnalyticsSeries;
import com.monpai.sailboatmod.market.analytics.MarketAnalyticsService;
import com.monpai.sailboatmod.market.web.MarketTerminalSavedData;
import com.monpai.sailboatmod.menu.MarketMenu;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.DockTownBindingRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.service.DockTownResolver;
import com.monpai.sailboatmod.nation.service.TownEconomySnapshotService;
import com.monpai.sailboatmod.nation.service.TownFinanceLedgerService;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.route.RoadAutoRouteService;
import com.monpai.sailboatmod.route.RouteDefinition;
import com.monpai.sailboatmod.registry.ModBlockEntities;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MarketBlockEntity extends BlockEntity implements MenuProvider {
    private static final Logger MARKET_LOGGER = LogUtils.getLogger();
    private static final double LINK_DOCK_RADIUS = 24.0D;
    private static final double PORT_PREVIEW_SPEED_MPS = 8.0D;
    private static final double POST_STATION_PREVIEW_SPEED_MPS = 5.0D;
    private static final CommodityMarketService COMMODITY_MARKET = new CommodityMarketService();
    private static final MarketAnalyticsService MARKET_ANALYTICS = new MarketAnalyticsService();
    private String marketName = "";
    private String ownerName = "";
    private String ownerUuid = "";
    @Nullable
    private BlockPos linkedDockPos;

    public MarketBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MARKET_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            bindNearestDockIfAbsent();
            syncTerminalRegistry();
        }
    }

    public void initializeOwnerIfAbsent(Player player) {
        if (level == null || level.isClientSide || player == null) {
            return;
        }
        if (ownerUuid != null && !ownerUuid.isBlank()) {
            return;
        }
        ownerUuid = player.getUUID().toString();
        ownerName = player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName();
        setChanged();
        syncTerminalRegistry();
    }

    public boolean bindNearestDockIfAbsent() {
        if (linkedDockPos != null) {
            return true;
        }
        return bindNearestDock();
    }

    public boolean bindNearestDock() {
        if (level == null || level.isClientSide) {
            return false;
        }
        BlockPos nearest = TownWarehouseRegistry.findNearest(level, Vec3.atCenterOf(worldPosition), LINK_DOCK_RADIUS);
        if (nearest == null) {
            return false;
        }
        linkedDockPos = nearest.immutable();
        setChanged();
        syncTerminalRegistry();
        return true;
    }

    public MarketOverviewData buildOverview(Player player) {
        String playerUuid = player == null ? "" : player.getUUID().toString();
        String playerName = player == null
                ? ""
                : player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName();
        return buildOverviewForIdentity(playerUuid, playerName, player);
    }

    public MarketOverviewData buildOverviewForIdentity(String playerUuid, String playerName, @Nullable Player onlinePlayer) {
        String safePlayerUuid = playerUuid == null ? "" : playerUuid.trim();
        String dockName = "-";
        String dockPosText = "-";
        String townId = "";
        String townName = "";
        int stockpileCommodityTypes = 0;
        int stockpileTotalUnits = 0;
        int openDemandCount = 0;
        int openDemandUnits = 0;
        int activeProcurementCount = 0;
        long totalIncome = 0L;
        long totalExpense = 0L;
        long netBalance = 0L;
        float employmentRate = 0.0F;
        List<String> stockpilePreviewLines = List.of();
        List<String> demandPreviewLines = List.of();
        List<String> procurementPreviewLines = List.of();
        List<String> financePreviewLines = List.of();
        boolean linked = false;
        List<MarketOverviewData.ShippingEntry> shippingEntries = new ArrayList<>();
        boolean dockStorageAccessible = false;
        List<String> dockStorageLines = List.of();
        List<MarketOverviewData.StorageEntry> storageEntries = new ArrayList<>();
        if (level != null && linkedDockPos != null && level.getBlockEntity(linkedDockPos) instanceof TownWarehouseBlockEntity warehouse) {
            dockName = warehouse.getDisplayName().getString();
            dockPosText = linkedDockPos.toShortString();
            linked = true;
            townId = warehouse.getTownId();
            TownRecord town = townId.isBlank() ? TownService.getTownAt(level, linkedDockPos) : NationSavedData.get(level).getTown(townId);
            if (town != null) {
                townId = town.townId();
                townName = town.name().isBlank() ? fallbackTownLabel(townId) : town.name();
            } else {
                townName = warehouse.getTownName();
            }
            if (!townId.isBlank()) {
                TownEconomySnapshotService.TownEconomySnapshot economy = TownEconomySnapshotService.build(level, townId);
                stockpileCommodityTypes = economy.stockpileCommodityTypes();
                stockpileTotalUnits = economy.stockpileTotalUnits();
                openDemandCount = economy.openDemandCount();
                openDemandUnits = economy.openDemandUnits();
                activeProcurementCount = economy.activeProcurementCount();
                totalIncome = economy.totalIncome();
                totalExpense = economy.totalExpense();
                netBalance = economy.netBalance();
                employmentRate = economy.employmentRate();
                stockpilePreviewLines = economy.stockpilePreviewLines();
                demandPreviewLines = economy.demandPreviewLines();
                procurementPreviewLines = economy.procurementPreviewLines();
                financePreviewLines = economy.financePreviewLines();
            }
            dockStorageAccessible = canManageMarket(safePlayerUuid);
            dockStorageLines = dockStorageAccessible ? warehouse.getVisibleStorageLines() : List.of();
            if (dockStorageAccessible) {
                int storageCount = warehouse.getVisibleStorageCount();
                for (int i = 0; i < storageCount; i++) {
                    ItemStack stack = warehouse.getStorageItemForVisibleIndex(i);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    String itemName = stack.getHoverName().getString();
                    String label = itemName + " x" + stack.getCount();
                    String itemKey = ForgeRegistries.ITEMS.getKey(stack.getItem()) != null
                            ? ForgeRegistries.ITEMS.getKey(stack.getItem()).toString()
                            : "";
                    storageEntries.add(new MarketOverviewData.StorageEntry(
                            label,
                            itemKey,
                            itemName,
                            stack.getCount(),
                            currentCommodityUnitPrice(stack, 1, CommodityMarketService.estimateBaseUnitPrice(stack)),
                            Component.translatable("screen.sailboatmod.market.storage_at", warehouse.getDisplayName().getString()).getString(),
                            resolveListingCategory(stack),
                            resolveListingRarity(stack)
                    ));
                }
            }
        }

        List<String> listingLines = new ArrayList<>();
        List<MarketOverviewData.ListingEntry> listingEntries = new ArrayList<>();
        Map<String, String> chartDisplayNames = new LinkedHashMap<>();
        List<String> orderLines = new ArrayList<>();
        List<MarketOverviewData.OrderEntry> orderEntries = new ArrayList<>();
        List<String> shippingLines = new ArrayList<>();
        if (level != null && !level.isClientSide) {
            MarketSavedData market = MarketSavedData.get(level);
            for (MarketListing listing : market.getListings()) {
                String line = describeListingLine(listing);
                int listingRarity = 0;
                String category = "";
                try {
                    com.monpai.sailboatmod.market.commodity.CommodityMarketService.CommoditySnapshot snap = COMMODITY_MARKET.ensureCommodity(listing.itemStack());
                    category = snap.definition().category();
                    listingRarity = snap.definition().rarity();
                } catch (Exception ignored) {}
                listingLines.add(line);
                listingEntries.add(new MarketOverviewData.ListingEntry(
                        line,
                        CommodityKeyResolver.resolve(listing.itemStack()),
                        listing.itemStack().isEmpty() ? "-" : listing.itemStack().getHoverName().getString(),
                        listing.availableCount(),
                        listing.reservedCount(),
                        currentListingUnitPrice(listing, 1),
                        listing.sellerName(),
                        listing.sellerUuid(),
                        listing.sourceDockName().isBlank() ? listing.sourceDockPos().toShortString() : listing.sourceDockName(),
                        listing.nationId(),
                        listing.sellerNote(),
                        category,
                        listingRarity
                ));
                chartDisplayNames.putIfAbsent(
                        CommodityKeyResolver.resolve(listing.itemStack()),
                        listing.itemStack().isEmpty() ? "-" : listing.itemStack().getHoverName().getString()
                );
            }
            List<PurchaseOrder> openOrders = linkedDockPos == null ? List.<PurchaseOrder>of() : market.getOpenOrdersForSourceDock(linkedDockPos);
            TownWarehouseBlockEntity linkedWarehouse = getLinkedWarehouse();
            for (PurchaseOrder order : openOrders) {
                String line = order.toSummaryLine();
                orderLines.add(line);
                orderEntries.add(new MarketOverviewData.OrderEntry(
                        line,
                        order.sourceDockName().isBlank() ? order.sourceDockPos().toShortString() : order.sourceDockName(),
                        order.targetDockName().isBlank() ? order.targetDockPos().toShortString() : order.targetDockName(),
                        order.quantity(),
                        order.status(),
                        buildDispatchOptionsForOrder(linkedWarehouse, order, onlinePlayer)
                ));
            }
        }

        List<String> buyOrderLines = new ArrayList<>();
        List<MarketOverviewData.BuyOrderEntry> buyOrderEntries = new ArrayList<>();
        List<MarketOverviewData.PriceChartSeries> priceChartSeries = new ArrayList<>();
        List<MarketOverviewData.CommodityBuyBook> commodityBuyBooks = new ArrayList<>();
        List<CommodityCandleSeries> candleSeries = List.of();
        List<CommodityImpactSnapshot> impactSnapshots = List.of();
        List<MarketAnalyticsSeries> analyticsSeries = List.of();
        if (level != null && !level.isClientSide && !safePlayerUuid.isBlank()) {
            try {
                List<com.monpai.sailboatmod.market.commodity.BuyOrder> buyOrders =
                        COMMODITY_MARKET.listBuyOrdersForBuyer(safePlayerUuid);
                for (com.monpai.sailboatmod.market.commodity.BuyOrder order : buyOrders) {
                    String line = order.commodityKey() + " x" + order.quantity() + " [" + order.minPriceBp() + "-" + order.maxPriceBp() + "bp]";
                    buyOrderLines.add(line);
                    buyOrderEntries.add(new MarketOverviewData.BuyOrderEntry(
                            order.orderId(),
                            line,
                            order.commodityKey(),
                            order.quantity(),
                            order.minPriceBp(),
                            order.maxPriceBp(),
                            order.buyerName(),
                            order.status(),
                            order.createdAt()
                    ));
                }
            } catch (Exception ignored) {
            }
        }

        if (level != null && !level.isClientSide) {
            for (Map.Entry<String, String> entry : chartDisplayNames.entrySet()) {
                try {
                    List<CommodityPriceChartPoint> points = COMMODITY_MARKET.listPriceChart(entry.getKey());
                    List<MarketOverviewData.PriceChartPoint> chartPoints = new ArrayList<>(points.size());
                    for (CommodityPriceChartPoint point : points) {
                        chartPoints.add(new MarketOverviewData.PriceChartPoint(
                                point.bucketAt(),
                                point.averageUnitPrice(),
                                point.minUnitPrice(),
                                point.maxUnitPrice(),
                                point.volume(),
                                point.tradeCount()
                        ));
                    }
                    priceChartSeries.add(new MarketOverviewData.PriceChartSeries(entry.getKey(), entry.getValue(), chartPoints));
                } catch (SQLException exception) {
                    MARKET_LOGGER.debug("Failed to load price chart history for {}", entry.getKey(), exception);
                }
                try {
                    List<com.monpai.sailboatmod.market.commodity.BuyOrder> orders = COMMODITY_MARKET.listBuyOrders(entry.getKey());
                    List<MarketOverviewData.CommodityBuyEntry> buyEntries = new ArrayList<>(orders.size());
                    for (com.monpai.sailboatmod.market.commodity.BuyOrder order : orders) {
                        buyEntries.add(new MarketOverviewData.CommodityBuyEntry(
                                order.orderId(),
                                order.buyerName(),
                                order.quantity(),
                                order.minPriceBp(),
                                order.maxPriceBp(),
                                order.createdAt(),
                                order.status()
                        ));
                    }
                    commodityBuyBooks.add(new MarketOverviewData.CommodityBuyBook(entry.getKey(), entry.getValue(), buyEntries));
                } catch (SQLException exception) {
                    MARKET_LOGGER.debug("Failed to load buy book for {}", entry.getKey(), exception);
                }
            }
            candleSeries = MARKET_ANALYTICS.loadCandleSeries(chartDisplayNames);
            impactSnapshots = MARKET_ANALYTICS.loadImpactSnapshots(chartDisplayNames);
            analyticsSeries = MARKET_ANALYTICS.loadAnalyticsSeries(level instanceof net.minecraft.server.level.ServerLevel serverLevel ? serverLevel : null, collectCategories(listingEntries));
        }

        return new MarketOverviewData(
                worldPosition,
                getMarketName(),
                getOwnerName(),
                getOwnerUuid(),
                safePlayerUuid,
                safePlayerUuid.isBlank() || level == null || level.isClientSide ? 0 : MarketSavedData.get(level).getPendingCredits(safePlayerUuid),
                linked,
                dockName,
                dockPosText,
                dockStorageAccessible,
                canManageMarket(safePlayerUuid),
                townId,
                townName,
                stockpileCommodityTypes,
                stockpileTotalUnits,
                openDemandCount,
                openDemandUnits,
                activeProcurementCount,
                totalIncome,
                totalExpense,
                netBalance,
                employmentRate,
                dockStorageLines,
                listingLines,
                orderLines,
                shippingLines,
                buyOrderLines,
                stockpilePreviewLines,
                demandPreviewLines,
                procurementPreviewLines,
                financePreviewLines,
                storageEntries,
                listingEntries,
                orderEntries,
                shippingEntries,
                buyOrderEntries,
                priceChartSeries,
                commodityBuyBooks,
                candleSeries,
                impactSnapshots,
                analyticsSeries
        );
    }

    private static List<String> collectCategories(List<MarketOverviewData.ListingEntry> listingEntries) {
        List<String> categories = new ArrayList<>();
        for (MarketOverviewData.ListingEntry entry : listingEntries) {
            if (entry.category() != null && !entry.category().isBlank()) {
                categories.add(entry.category());
            }
        }
        return categories;
    }

    private static String fallbackTownLabel(String townId) {
        if (townId == null || townId.isBlank()) {
            return "";
        }
        return townId.length() > 24 ? townId.substring(0, 24) + "..." : townId;
    }

    public boolean createListingFromDockStorage(Player player, int visibleStorageIndex, int quantity, int unitPrice, int priceAdjustmentBp, String sellerNote) {
        if (player == null) {
            return false;
        }
        return createListingFromDockStorage(
                player.getUUID().toString(),
                player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName(),
                visibleStorageIndex,
                quantity,
                unitPrice,
                priceAdjustmentBp,
                sellerNote
        );
    }

    public boolean createListingFromDockStorage(String playerUuid, String playerName, int visibleStorageIndex, int quantity, int unitPrice, int priceAdjustmentBp, String sellerNote) {
        TownWarehouseBlockEntity warehouse = getLinkedWarehouse();
        String safePlayerUuid = playerUuid == null ? "" : playerUuid.trim();
        String safePlayerName = playerName == null ? "" : playerName.trim();
        if (level == null || level.isClientSide || warehouse == null || safePlayerUuid.isBlank() || !canManageMarket(safePlayerUuid)) {
            return false;
        }
        ItemStack selected = warehouse.getStorageItemForVisibleIndex(visibleStorageIndex);
        if (selected.isEmpty()) {
            return false;
        }
        int stocked = selected.getCount();
        int amount = Math.max(1, Math.min(quantity, stocked));
        ItemStack listed = selected.copy();
        listed.setCount(1);
        int fallbackPrice = Math.max(CommodityMarketService.estimateBaseUnitPrice(listed), unitPrice);
        if (!warehouse.extractVisibleStorage(visibleStorageIndex, amount)) {
            return false;
        }
        int price = currentCommodityUnitPrice(listed, 1, fallbackPrice);
        adjustCommoditySupply(listed, amount);
        MarketSavedData market = MarketSavedData.get(level);
        String listingTownId = warehouse.getTownId();
        String listingNationId = "";
        TownRecord town = TownService.getTownAt(level, warehouse.getBlockPos());
        if (town != null) {
            listingTownId = town.townId();
            listingNationId = town.nationId();
        }
        int adjustedPrice = applyPriceAdjustment(price, priceAdjustmentBp);
        market.putListing(new MarketListing(
                market.nextId(),
                safePlayerUuid,
                safePlayerName,
                listed,
                Math.max(1, adjustedPrice),
                amount,
                0,
                linkedDockPos,
                warehouse.getDisplayName().getString(),
                listingTownId,
                listingNationId,
                priceAdjustmentBp,
                sellerNote
        ));
        syncTerminalRegistry();
        return true;
    }

    public boolean purchaseListing(Player player, int listingIndex, int quantity) {
        if (player == null) {
            return false;
        }
        return purchaseListing(
                player.getUUID().toString(),
                player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName(),
                player,
                listingIndex,
                quantity
        );
    }

    public boolean purchaseListing(String playerUuid, String playerName, @Nullable Player onlinePlayer, int listingIndex, int quantity) {
        TownWarehouseBlockEntity warehouse = getLinkedWarehouse();
        String safePlayerUuid = playerUuid == null ? "" : playerUuid.trim();
        String safePlayerName = playerName == null ? "" : playerName.trim();
        if (level == null || level.isClientSide || warehouse == null || safePlayerUuid.isBlank()) {
            return false;
        }
        MarketSavedData market = MarketSavedData.get(level);
        MarketListing listing = market.getListingByVisibleIndex(listingIndex);
        if (listing == null) {
            return false;
        }
        if (safePlayerUuid.equals(listing.sellerUuid())) {
            return false;
        }
        int amount = Math.max(1, Math.min(quantity, listing.availableCount()));
        int total = currentListingTotalPrice(listing, amount);
        if (!chargePlayer(safePlayerUuid, safePlayerName, onlinePlayer, total)) {
            return false;
        }
        applyCommodityDemand(listing, amount, safePlayerUuid, safePlayerName);
        int sellerPayout = total;
        com.monpai.sailboatmod.nation.service.TaxService.TaxResult salesTaxResult =
                com.monpai.sailboatmod.nation.service.TaxService.applySalesTax(level, total, this.worldPosition);
        sellerPayout = salesTaxResult.sellerReceives();
        com.monpai.sailboatmod.nation.service.TaxService.TaxResult tariffResult =
                com.monpai.sailboatmod.nation.service.TaxService.applyImportTariff(level, sellerPayout, listing.sourceDockPos(), linkedDockPos);
        sellerPayout = tariffResult.sellerReceives();
        com.monpai.sailboatmod.nation.service.TaxService.recordTrade(level, this.worldPosition);
        paySeller(market, listing.sellerUuid(), listing.sellerName(), sellerPayout);
        market.putListing(new MarketListing(
                listing.listingId(),
                listing.sellerUuid(),
                listing.sellerName(),
                listing.itemStack(),
                currentListingUnitPrice(listing, 1),
                Math.max(0, listing.availableCount() - amount),
                listing.reservedCount() + amount,
                listing.sourceDockPos(),
                listing.sourceDockName(),
                listing.townId(),
                listing.nationId(),
                listing.priceAdjustmentBp(),
                listing.sellerNote()
        ));
        PurchaseOrder createdOrder = new PurchaseOrder(
                market.nextId(),
                listing.listingId(),
                safePlayerUuid,
                safePlayerName,
                amount,
                total,
                listing.sourceDockPos(),
                listing.sourceDockName(),
                linkedDockPos,
                warehouse.getDisplayName().getString(),
                "WAITING_SHIPMENT"
        );
        market.putPurchaseOrder(createdOrder);
        String buyerTownId = warehouse.getTownId();
        String sourceTownId = DockTownResolver.resolveTownForSource(level, listing.sourceDockPos(), listing.townId());
        ProcurementRecord procurement = ProcurementService.createProcurement(
                level,
                buyerTownId,
                sourceTownId,
                CommodityKeyResolver.resolve(listing.itemStack()),
                amount,
                amount <= 0 ? 0L : total / amount,
                total,
                "STOCK_REPLENISH",
                createdOrder.orderId(),
                createdOrder.orderId(),
                "",
                DockTownResolver.dockId(level, linkedDockPos)
        );
        String sourceRef = procurement == null ? createdOrder.orderId() : procurement.procurementId();
        if (!buyerTownId.isBlank()) {
            TownFinanceLedgerService.recordExpense(
                    level,
                    buyerTownId,
                    "MARKET_PURCHASE",
                    total,
                    GoldStandardEconomy.LEDGER_CURRENCY,
                    CommodityKeyResolver.resolve(listing.itemStack()),
                    amount,
                    sourceRef
            );
        }
        if (!sourceTownId.isBlank()) {
            TownFinanceLedgerService.recordIncome(
                    level,
                    sourceTownId,
                    "MARKET_SALE",
                    sellerPayout,
                    GoldStandardEconomy.LEDGER_CURRENCY,
                    CommodityKeyResolver.resolve(listing.itemStack()),
                    amount,
                    sourceRef
            );
        }
        tryAutoDispatchOrders(safePlayerUuid, safePlayerName, onlinePlayer, createdOrder.sourceDockPos(), TransportTerminalKind.AUTO);
        return true;
    }

    public boolean cancelListing(Player player, int listingIndex) {
        return cancelListingResult(player, listingIndex).success();
    }

    public CancelListingResult cancelListingResult(Player player, int listingIndex) {
        if (player == null) {
            return CancelListingResult.failure("screen.sailboatmod.market.unlist.failed_not_owner");
        }
        String listingId = listingIdByVisibleIndex(listingIndex);
        return cancelListingResultById(player.getUUID().toString(), listingId);
    }

    public boolean cancelListingById(String playerUuid, String listingId) {
        return cancelListingResultById(playerUuid, listingId).success();
    }

    public CancelListingResult cancelListingResultById(String playerUuid, String listingId) {
        if (level == null || level.isClientSide) {
            return CancelListingResult.failure("screen.sailboatmod.market.unlist.failed_missing_dock");
        }
        MarketSavedData market = MarketSavedData.get(level);
        MarketListing listing = market.getListing(listingId);
        if (listing == null) {
            return CancelListingResult.failure("screen.sailboatmod.market.unlist.failed_no_stock");
        }
        String safePlayerUuid = playerUuid == null ? "" : playerUuid.trim();
        if (!safePlayerUuid.equals(listing.sellerUuid())) {
            return CancelListingResult.failure("screen.sailboatmod.market.unlist.failed_not_owner");
        }
        if (listing.availableCount() <= 0) {
            return CancelListingResult.failure("screen.sailboatmod.market.unlist.failed_no_stock");
        }
        WarehouseSelectionResult targetWarehouseSelection = resolveCancelListingWarehouse(safePlayerUuid, listing);
        if (targetWarehouseSelection.result() != null) {
            return targetWarehouseSelection.result();
        }
        List<ItemStack> cargo = splitCargo(listing.itemStack(), listing.availableCount());
        if (!targetWarehouseSelection.warehouse().insertCargo(cargo)) {
            return CancelListingResult.failure("screen.sailboatmod.market.unlist.failed_storage_full");
        }
        adjustCommoditySupply(listing.itemStack(), -listing.availableCount());
        if (listing.reservedCount() <= 0) {
            market.removeListing(listing.listingId());
        } else {
            market.putListing(new MarketListing(
                    listing.listingId(),
                    listing.sellerUuid(),
                    listing.sellerName(),
                    listing.itemStack(),
                    listing.unitPrice(),
                    0,
                    listing.reservedCount(),
                    listing.sourceDockPos(),
                    listing.sourceDockName(),
                    listing.townId(),
                    listing.nationId(),
                    listing.priceAdjustmentBp(),
                    listing.sellerNote()
            ));
        }
        return CancelListingResult.success(targetWarehouseSelection.usedLinkedWarehouseFallback()
                ? "screen.sailboatmod.market.unlist.success_linked_dock"
                : "screen.sailboatmod.market.unlist.success");
    }

    public boolean claimPendingCredits(Player player) {
        if (player == null) {
            return false;
        }
        return claimPendingCredits(
                player.getUUID().toString(),
                player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName(),
                player
        );
    }

    public boolean claimPendingCredits(String playerUuid, String playerName, @Nullable Player onlinePlayer) {
        if (level == null || level.isClientSide) {
            return false;
        }
        MarketSavedData market = MarketSavedData.get(level);
        String safePlayerUuid = playerUuid == null ? "" : playerUuid.trim();
        int pending = market.getPendingCredits(safePlayerUuid);
        if (pending <= 0) {
            return false;
        }
        Boolean depositResult = onlinePlayer != null
                ? GoldStandardEconomy.tryDeposit(onlinePlayer, pending)
                : GoldStandardEconomy.tryDepositByIdentity(parseUuid(safePlayerUuid), playerName, pending);
        if (depositResult != null && depositResult) {
            market.clearPendingCredits(safePlayerUuid);
            return true;
        }
        return false;
    }

    public boolean dispatchOrder(Player player, int orderIndex, int boatIndex) {
        if (player == null) {
            return false;
        }
        return dispatchOrder(
                player.getUUID().toString(),
                player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName(),
                player,
                orderIndex,
                boatIndex,
                TransportTerminalKind.AUTO
        );
    }

    public boolean dispatchOrder(String playerUuid, String playerName, @Nullable Player onlinePlayer, int orderIndex, int boatIndex) {
        return dispatchOrder(playerUuid, playerName, onlinePlayer, orderIndex, boatIndex, TransportTerminalKind.AUTO);
    }

    public boolean dispatchOrder(String playerUuid, String playerName, @Nullable Player onlinePlayer, int orderIndex, int boatIndex,
                                 TransportTerminalKind terminalKind) {
        if (linkedDockPos == null || !canManageMarket(playerUuid) || level == null || level.isClientSide) {
            return false;
        }
        TransportTerminalKind effectiveKind = terminalKind == null ? TransportTerminalKind.AUTO : terminalKind;
        TownWarehouseBlockEntity sourceWarehouse = getLinkedWarehouse();
        if (sourceWarehouse == null) {
            return false;
        }
        MarketSavedData market = MarketSavedData.get(level);
        List<PurchaseOrder> openOrders = market.getOpenOrdersForSourceDock(linkedDockPos);
        if (orderIndex >= 0 && orderIndex < openOrders.size()) {
            PurchaseOrder selectedOrder = openOrders.get(orderIndex);
            if (selectedOrder.sourceDockPos().equals(selectedOrder.targetDockPos())) {
                return processLocalOrders(sourceWarehouse, market);
            }
            if (effectiveKind == TransportTerminalKind.AUTO) {
                return dispatchSelectedOrder(playerUuid, playerName, onlinePlayer, sourceWarehouse, market, selectedOrder, TransportTerminalKind.PORT)
                        || dispatchSelectedOrder(playerUuid, playerName, onlinePlayer, sourceWarehouse, market, selectedOrder, TransportTerminalKind.POST_STATION);
            }
            return dispatchSelectedOrder(playerUuid, playerName, onlinePlayer, sourceWarehouse, market, selectedOrder, effectiveKind);
        }
        return tryAutoDispatchOrders(playerUuid, playerName, onlinePlayer, linkedDockPos, effectiveKind);
    }

    public String getMarketName() {
        if (marketName == null || marketName.isBlank()) {
            return ownerName != null && !ownerName.isBlank() ? ownerName + "'s Market" : "Market";
        }
        return marketName;
    }

    public void setMarketName(String name) {
        this.marketName = name == null ? "" : name.trim();
        setChanged();
        syncTerminalRegistry();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    public String getOwnerName() {
        return ownerName == null || ownerName.isBlank() ? "-" : ownerName;
    }

    public String getOwnerUuid() {
        return ownerUuid == null ? "" : ownerUuid;
    }

    public boolean canManageMarket(Player player) {
        if (player == null || player.getAbilities().instabuild) {
            return player != null;
        }
        return canManageMarket(player.getUUID().toString());
    }

    public boolean canManageMarket(String playerUuid) {
        String safePlayerUuid = playerUuid == null ? "" : playerUuid.trim();
        return ownerUuid != null && ownerUuid.equals(safePlayerUuid);
    }

    @Nullable
    public BlockPos getLinkedDockPos() {
        return linkedDockPos;
    }

    @Nullable
    public DockBlockEntity getLinkedDock() {
        if (level == null || linkedDockPos == null) {
            return null;
        }
        return level.getBlockEntity(linkedDockPos) instanceof DockBlockEntity dock ? dock : null;
    }

    @Nullable
    public TownWarehouseBlockEntity getLinkedWarehouse() {
        if (level == null || linkedDockPos == null) {
            return null;
        }
        return level.getBlockEntity(linkedDockPos) instanceof TownWarehouseBlockEntity warehouse ? warehouse : null;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal(getMarketName());
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new MarketMenu(containerId, playerInventory, worldPosition);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("MarketName", marketName == null ? "" : marketName);
        tag.putString("OwnerName", ownerName == null ? "" : ownerName);
        tag.putString("OwnerUuid", ownerUuid == null ? "" : ownerUuid);
        if (linkedDockPos != null) {
            tag.putLong("LinkedDockPos", linkedDockPos.asLong());
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        marketName = tag.getString("MarketName");
        ownerName = tag.getString("OwnerName");
        ownerUuid = tag.getString("OwnerUuid");
        linkedDockPos = tag.contains("LinkedDockPos") ? BlockPos.of(tag.getLong("LinkedDockPos")) : null;
    }

    private MarketListing findListingById(MarketSavedData market, String listingId) {
        return market.getListing(listingId);
    }

    private boolean tryAutoDispatchOrders(String shipperUuid, String shipperName, @Nullable Player player, BlockPos sourceDockPos,
                                          TransportTerminalKind terminalKind) {
        if (level == null || level.isClientSide || sourceDockPos == null) {
            return false;
        }
        TownWarehouseBlockEntity sourceWarehouse = level.getBlockEntity(sourceDockPos) instanceof TownWarehouseBlockEntity warehouse ? warehouse : null;
        if (sourceWarehouse == null) {
            return false;
        }
        MarketSavedData market = MarketSavedData.get(level);
        boolean progressedAny = false;
        while (true) {
            boolean progressed = processLocalOrders(sourceWarehouse, market);
            boolean shipped = false;
            TransportTerminalKind effectiveKind = terminalKind == null ? TransportTerminalKind.AUTO : terminalKind;
            if (effectiveKind == TransportTerminalKind.AUTO) {
                shipped = tryDispatchWaitingOrders(shipperUuid, shipperName, player, sourceWarehouse, market, TransportTerminalKind.PORT)
                        || tryDispatchWaitingOrders(shipperUuid, shipperName, player, sourceWarehouse, market, TransportTerminalKind.POST_STATION);
            } else {
                shipped = tryDispatchWaitingOrders(shipperUuid, shipperName, player, sourceWarehouse, market, effectiveKind);
            }
            if (!progressed) {
                if (!shipped) {
                    break;
                }
            }
            progressedAny = true;
        }
        return progressedAny;
    }

    private boolean tryDispatchWaitingOrders(String shipperUuid, String shipperName, @Nullable Player player,
                                             TownWarehouseBlockEntity sourceWarehouse, MarketSavedData market,
                                             TransportTerminalKind terminalKind) {
        if (sourceWarehouse == null || market == null || terminalKind == null || terminalKind == TransportTerminalKind.AUTO) {
            return false;
        }
        LinkedHashMap<BlockPos, List<PurchaseOrder>> byTargetWarehouse = new LinkedHashMap<>();
        for (PurchaseOrder order : market.getOpenOrdersForSourceDock(sourceWarehouse.getBlockPos())) {
            if (order.sourceDockPos().equals(order.targetDockPos())) {
                continue;
            }
            byTargetWarehouse.computeIfAbsent(order.targetDockPos(), ignored -> new ArrayList<>()).add(order);
        }
        for (Map.Entry<BlockPos, List<PurchaseOrder>> entry : byTargetWarehouse.entrySet()) {
            DispatchTerminalPlan terminalPlan = resolveDispatchTerminalPlan(sourceWarehouse, entry.getKey(), terminalKind, player);
            if (terminalPlan == null) {
                continue;
            }
            for (SailboatEntity boat : availableDispatchBoats(terminalPlan.sourceTerminal(), player)) {
                ShipmentPlan shipmentPlan = buildShipmentPlanForWarehouseTarget(
                        market,
                        boat,
                        terminalPlan.routeIndex(),
                        terminalPlan.targetTerminal(),
                        entry.getValue()
                );
                if (shipmentPlan == null) {
                    continue;
                }
                if (dispatchShipmentPlan(shipperUuid, shipperName, player, boat, terminalPlan.sourceTerminal(), market, shipmentPlan, terminalKind)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean processLocalOrders(TownWarehouseBlockEntity sourceWarehouse, MarketSavedData market) {
        if (sourceWarehouse == null || market == null) {
            return false;
        }
        boolean processed = false;
        List<PurchaseOrder> waiting = new ArrayList<>(market.getOpenOrdersForSourceDock(sourceWarehouse.getBlockPos()));
        for (PurchaseOrder order : waiting) {
            if (!order.sourceDockPos().equals(order.targetDockPos())) {
                continue;
            }
            MarketListing listing = findListingById(market, order.listingId());
            if (listing == null) {
                continue;
            }
            if (deliverOrderLocally(sourceWarehouse, market, order, listing)) {
                processed = true;
            }
        }
        return processed;
    }

    private boolean deliverOrderLocally(TownWarehouseBlockEntity warehouse, MarketSavedData market, PurchaseOrder order, MarketListing listing) {
        List<ItemStack> cargo = splitCargo(listing.itemStack(), order.quantity());
        if (!warehouse.insertCargo(cargo)) {
            return false;
        }
        applyListingReservationDeltas(market, Map.of(listing.listingId(), order.quantity()));
        market.putPurchaseOrder(new PurchaseOrder(
                order.orderId(),
                order.listingId(),
                order.buyerUuid(),
                order.buyerName(),
                order.quantity(),
                order.totalPrice(),
                order.sourceDockPos(),
                order.sourceDockName(),
                order.targetDockPos(),
                order.targetDockName(),
                "CLAIMED"
        ));
        ProcurementService.markDeliveredByOrder(level, order.orderId(), "", warehouse.getTownId(),
                CommodityKeyResolver.resolve(listing.itemStack()), order.quantity());
        return true;
    }

    @Nullable
    private DispatchTerminalPlan resolveDispatchTerminalPlan(TownWarehouseBlockEntity sourceWarehouse, BlockPos targetWarehousePos,
                                                             TransportTerminalKind terminalKind, @Nullable Player player) {
        if (level == null || sourceWarehouse == null || targetWarehousePos == null || terminalKind == null || terminalKind == TransportTerminalKind.AUTO) {
            return null;
        }
        TownWarehouseBlockEntity targetWarehouse = level.getBlockEntity(targetWarehousePos) instanceof TownWarehouseBlockEntity warehouse ? warehouse : null;
        if (targetWarehouse == null) {
            return null;
        }
        List<DockBlockEntity> sourceTerminals = terminalsForTown(sourceWarehouse.getTownId(), terminalKind);
        List<DockBlockEntity> targetTerminals = terminalsForTown(targetWarehouse.getTownId(), terminalKind);
        DispatchTerminalPlan best = null;
        for (DockBlockEntity sourceTerminal : sourceTerminals) {
            if (availableDispatchBoats(sourceTerminal, player).isEmpty()) {
                continue;
            }
            for (DockBlockEntity targetTerminal : targetTerminals) {
                int routeIndex = sourceTerminal.findRouteIndexByDestinationDock(targetTerminal.getBlockPos(), targetTerminal.getDockName());
                if (routeIndex < 0 && terminalKind == TransportTerminalKind.POST_STATION
                        && sourceTerminal instanceof PostStationBlockEntity sourceStation
                        && targetTerminal instanceof PostStationBlockEntity targetStation
                        && level instanceof net.minecraft.server.level.ServerLevel serverLevel
                        && RoadAutoRouteService.canCreateAutoRoute(level, sourceStation, targetStation)
                        && RoadAutoRouteService.createAndSaveAutoRoute(serverLevel, sourceStation, targetStation)) {
                    routeIndex = sourceTerminal.findRouteIndexByDestinationDock(targetTerminal.getBlockPos(), targetTerminal.getDockName());
                }
                if (routeIndex < 0) {
                    continue;
                }
                double pairScore = Vec3.atCenterOf(sourceWarehouse.getBlockPos()).distanceToSqr(Vec3.atCenterOf(sourceTerminal.getBlockPos()))
                        + Vec3.atCenterOf(targetWarehouse.getBlockPos()).distanceToSqr(Vec3.atCenterOf(targetTerminal.getBlockPos()));
                if (best == null || pairScore < best.pairScore()) {
                    best = new DispatchTerminalPlan(sourceTerminal, targetTerminal, routeIndex, pairScore);
                }
            }
        }
        return best;
    }

    private boolean dispatchSelectedOrder(String shipperUuid, String shipperName, @Nullable Player player,
                                          TownWarehouseBlockEntity sourceWarehouse, MarketSavedData market,
                                          PurchaseOrder order, TransportTerminalKind terminalKind) {
        if (sourceWarehouse == null || market == null || order == null || terminalKind == null || terminalKind == TransportTerminalKind.AUTO) {
            return false;
        }
        DispatchTerminalPlan terminalPlan = resolveDispatchTerminalPlan(sourceWarehouse, order.targetDockPos(), terminalKind, player);
        if (terminalPlan == null) {
            return false;
        }
        for (SailboatEntity boat : availableDispatchBoats(terminalPlan.sourceTerminal(), player)) {
            ShipmentPlan shipmentPlan = buildShipmentPlanForWarehouseTarget(
                    market,
                    boat,
                    terminalPlan.routeIndex(),
                    terminalPlan.targetTerminal(),
                    List.of(order)
            );
            if (shipmentPlan != null && dispatchShipmentPlan(shipperUuid, shipperName, player, boat, terminalPlan.sourceTerminal(), market, shipmentPlan, terminalKind)) {
                return true;
            }
        }
        return false;
    }

    private List<MarketOverviewData.DispatchOption> buildDispatchOptionsForOrder(@Nullable TownWarehouseBlockEntity sourceWarehouse,
                                                                                 @Nullable PurchaseOrder order,
                                                                                 @Nullable Player player) {
        if (sourceWarehouse == null || order == null) {
            return List.of();
        }
        return List.of(
                buildDispatchOption(sourceWarehouse, order.targetDockPos(), TransportTerminalKind.PORT, player),
                buildDispatchOption(sourceWarehouse, order.targetDockPos(), TransportTerminalKind.POST_STATION, player)
        );
    }

    private MarketOverviewData.DispatchOption buildDispatchOption(TownWarehouseBlockEntity sourceWarehouse, BlockPos targetWarehousePos,
                                                                  TransportTerminalKind terminalKind, @Nullable Player player) {
        String terminalLabel = terminalKind == TransportTerminalKind.POST_STATION ? "Post Station" : "Port";
        if (level == null || sourceWarehouse == null || targetWarehousePos == null || terminalKind == null || terminalKind == TransportTerminalKind.AUTO) {
            return new MarketOverviewData.DispatchOption(
                    terminalKind == null ? TransportTerminalKind.AUTO.name() : terminalKind.name(),
                    terminalLabel,
                    "-",
                    "-",
                    "",
                    "",
                    0,
                    0,
                    false,
                    "Unavailable",
                    "Terminal preview unavailable."
            );
        }
        if (sourceWarehouse.getBlockPos().equals(targetWarehousePos)) {
            return new MarketOverviewData.DispatchOption(
                    terminalKind.name(),
                    terminalLabel,
                    "-",
                    "-",
                    "",
                    "",
                    0,
                    0,
                    false,
                    "Local",
                    "Source and destination share the same warehouse."
            );
        }

        TownWarehouseBlockEntity targetWarehouse = level.getBlockEntity(targetWarehousePos) instanceof TownWarehouseBlockEntity warehouse ? warehouse : null;
        if (targetWarehouse == null) {
            return new MarketOverviewData.DispatchOption(terminalKind.name(), terminalLabel, "-", "-", "", "", 0, 0, false, "Unavailable", "Target warehouse is missing.");
        }

        List<DockBlockEntity> sourceTerminals = terminalsForTown(sourceWarehouse.getTownId(), terminalKind);
        List<DockBlockEntity> targetTerminals = terminalsForTown(targetWarehouse.getTownId(), terminalKind);
        if (sourceTerminals.isEmpty() || targetTerminals.isEmpty()) {
            return new MarketOverviewData.DispatchOption(
                    terminalKind.name(),
                    terminalLabel,
                    "-",
                    "-",
                    "",
                    "",
                    0,
                    0,
                    false,
                    "Unavailable",
                    sourceTerminals.isEmpty() ? "No source terminal in the origin town." : "No destination terminal in the target town."
            );
        }

        DispatchPreviewPlan best = null;
        for (DockBlockEntity sourceTerminal : sourceTerminals) {
            List<SailboatEntity> boats = availableDispatchBoats(sourceTerminal, player);
            for (DockBlockEntity targetTerminal : targetTerminals) {
                RoutePreview preview = resolveRoutePreview(sourceWarehouse, targetWarehouse, sourceTerminal, targetTerminal, terminalKind);
                if (preview == null) {
                    continue;
                }
                double pairScore = Vec3.atCenterOf(sourceWarehouse.getBlockPos()).distanceToSqr(Vec3.atCenterOf(sourceTerminal.getBlockPos()))
                        + Vec3.atCenterOf(targetWarehouse.getBlockPos()).distanceToSqr(Vec3.atCenterOf(targetTerminal.getBlockPos()))
                        + preview.distanceMeters();
                DispatchPreviewPlan candidate = new DispatchPreviewPlan(
                        sourceTerminal,
                        targetTerminal,
                        boats.isEmpty() ? "-" : boats.get(0).getName().getString(),
                        preview.routeName(),
                        preview.distanceMeters(),
                        estimateEtaSeconds(terminalKind, preview.distanceMeters()),
                        !boats.isEmpty(),
                        boats.isEmpty() ? "No vehicle" : "Ready",
                        preview.autoCreated() ? "Route will be generated automatically on dispatch." : sourceTerminal.getDockName() + " -> " + targetTerminal.getDockName(),
                        pairScore
                );
                if (best == null || (candidate.available() && !best.available()) || candidate.pairScore() < best.pairScore()) {
                    best = candidate;
                }
            }
        }

        if (best == null) {
            return new MarketOverviewData.DispatchOption(
                    terminalKind.name(),
                    terminalLabel,
                    "-",
                    "-",
                    "",
                    "",
                    0,
                    0,
                    false,
                    "No route",
                    "No valid route was found between the terminals."
            );
        }

        return new MarketOverviewData.DispatchOption(
                terminalKind.name(),
                terminalLabel,
                best.carrierName(),
                best.routeName(),
                best.sourceTerminal().getDockName(),
                best.targetTerminal().getDockName(),
                best.distanceMeters(),
                best.etaSeconds(),
                best.available(),
                best.availability(),
                best.detail()
        );
    }

    @Nullable
    private RoutePreview resolveRoutePreview(TownWarehouseBlockEntity sourceWarehouse, TownWarehouseBlockEntity targetWarehouse,
                                             DockBlockEntity sourceTerminal, DockBlockEntity targetTerminal,
                                             TransportTerminalKind terminalKind) {
        int routeIndex = sourceTerminal.findRouteIndexByDestinationDock(targetTerminal.getBlockPos(), targetTerminal.getDockName());
        if (routeIndex >= 0) {
            RouteDefinition route = routeIndex < sourceTerminal.getRoutesForMap().size() ? sourceTerminal.getRoutesForMap().get(routeIndex) : null;
            int distanceMeters = route == null ? 0 : (int) Math.round(estimateRouteLength(route));
            return new RoutePreview(sourceTerminal.getRouteName(routeIndex), Math.max(distanceMeters, 0), false);
        }
        if (terminalKind != TransportTerminalKind.POST_STATION
                || !(sourceTerminal instanceof PostStationBlockEntity sourceStation)
                || !(targetTerminal instanceof PostStationBlockEntity targetStation)
                || !(level instanceof net.minecraft.server.level.ServerLevel serverLevel)
                || !RoadAutoRouteService.canCreateAutoRoute(level, sourceStation, targetStation)) {
            return null;
        }
        RoadAutoRouteService.RouteResolution resolution = RoadAutoRouteService.resolveAutoRoutePreview(serverLevel, sourceStation.getBlockPos(), targetStation.getBlockPos());
        if (!resolution.found()) {
            return null;
        }
        return new RoutePreview("Road Auto: " + targetTerminal.getDockName(), (int) Math.round(estimatePathLength(resolution.path())), true);
    }

    private int estimateEtaSeconds(TransportTerminalKind terminalKind, int distanceMeters) {
        double speed = terminalKind == TransportTerminalKind.POST_STATION ? POST_STATION_PREVIEW_SPEED_MPS : PORT_PREVIEW_SPEED_MPS;
        if (distanceMeters <= 0 || speed <= 0.0D) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(distanceMeters / speed));
    }

    private double estimateRouteLength(@Nullable RouteDefinition route) {
        if (route == null) {
            return 0.0D;
        }
        if (route.routeLengthMeters() > 0.0D) {
            return route.routeLengthMeters();
        }
        double total = 0.0D;
        for (int i = 1; i < route.waypoints().size(); i++) {
            total += route.waypoints().get(i - 1).distanceTo(route.waypoints().get(i));
        }
        return total;
    }

    private double estimatePathLength(List<BlockPos> path) {
        if (path == null || path.size() < 2) {
            return 0.0D;
        }
        double total = 0.0D;
        for (int i = 1; i < path.size(); i++) {
            total += Vec3.atCenterOf(path.get(i - 1)).distanceTo(Vec3.atCenterOf(path.get(i)));
        }
        return total;
    }

    private List<DockBlockEntity> terminalsForTown(String townId, TransportTerminalKind terminalKind) {
        if (level == null || townId == null || townId.isBlank() || terminalKind == null) {
            return List.of();
        }
        Set<BlockPos> candidates = terminalKind == TransportTerminalKind.POST_STATION ? PostStationRegistry.get(level) : DockRegistry.get(level);
        List<DockBlockEntity> terminals = new ArrayList<>();
        for (BlockPos pos : candidates) {
            if (!(level.getBlockEntity(pos) instanceof DockBlockEntity terminal)) {
                continue;
            }
            if (terminalKind == TransportTerminalKind.PORT && terminal instanceof PostStationBlockEntity) {
                continue;
            }
            if (terminalKind == TransportTerminalKind.POST_STATION && !(terminal instanceof PostStationBlockEntity)) {
                continue;
            }
            String resolvedTownId = DockTownResolver.resolveTownForArrival(level, pos, terminal.getTownId());
            if (townId.equals(resolvedTownId)) {
                terminals.add(terminal);
            }
        }
        return terminals;
    }

    @Nullable
    private ShipmentPlan buildShipmentPlanForWarehouseTarget(MarketSavedData market, SailboatEntity boat, int routeIndex,
                                                             DockBlockEntity targetTerminal, List<PurchaseOrder> orders) {
        if (market == null || boat == null || targetTerminal == null || routeIndex < 0 || orders == null || orders.isEmpty()) {
            return null;
        }
        List<ItemStack> cargo = new ArrayList<>();
        List<ShipmentOrderSelection> selections = new ArrayList<>();
        for (PurchaseOrder order : orders) {
            MarketListing listing = findListingById(market, order.listingId());
            if (listing == null) {
                continue;
            }
            int shippableQuantity = findMaxLoadableQuantity(boat, cargo, listing.itemStack(), order.quantity());
            if (shippableQuantity <= 0) {
                if (!cargo.isEmpty()) {
                    break;
                }
                continue;
            }
            PurchaseSplit split = splitOrderForShipment(market, order, shippableQuantity);
            if (split == null) {
                continue;
            }
            cargo.addAll(splitCargo(listing.itemStack(), split.shipped().quantity()));
            selections.add(new ShipmentOrderSelection(split.shipped(), split.remainder(), listing));
            if (shippableQuantity < order.quantity()) {
                break;
            }
        }
        if (cargo.isEmpty() || selections.isEmpty()) {
            return null;
        }
        return new ShipmentPlan(routeIndex, targetTerminal.getBlockPos(), targetTerminal.getDockName(), cargo, selections);
    }

    private boolean dispatchShipmentPlan(String shipperUuid, String shipperName, @Nullable Player player, SailboatEntity boat, DockBlockEntity sourceDock,
                                         MarketSavedData market, ShipmentPlan plan, TransportTerminalKind terminalKind) {
        if (boat == null || sourceDock == null || market == null || plan == null) {
            return false;
        }
        if (!boat.canLoadCargo(plan.cargo()) || !boat.loadCargo(plan.cargo())) {
            return false;
        }

        List<ShipmentManifestEntry> manifest = new ArrayList<>();
        Map<String, Integer> listingReservationDeltas = new LinkedHashMap<>();
        List<ShippingOrder> shippingOrders = new ArrayList<>();
        RouteDefinition route = plan.routeIndex() >= 0 && plan.routeIndex() < sourceDock.getRoutesForMap().size()
                ? sourceDock.getRoutesForMap().get(plan.routeIndex()) : null;
        int distanceMeters = (int) Math.round(estimateRouteLength(route));
        int etaSeconds = estimateEtaSeconds(terminalKind == null ? TransportTerminalKind.PORT : terminalKind, distanceMeters);
        for (ShipmentOrderSelection selection : plan.selections()) {
            String shippingOrderId = market.nextId();
            PurchaseOrder order = selection.dispatchOrder();
            manifest.add(new ShipmentManifestEntry(
                    selection.listing().listingId(),
                    selection.listing().itemStack(),
                    order.orderId(),
                    shippingOrderId,
                    order.buyerUuid(),
                    order.buyerName(),
                    order.quantity()
            ));
            listingReservationDeltas.merge(selection.listing().listingId(), order.quantity(), Integer::sum);
            shippingOrders.add(new ShippingOrder(
                    shippingOrderId,
                    order.orderId(),
                    shipperUuid == null ? "" : shipperUuid.trim(),
                    shipperName == null ? "" : shipperName.trim(),
                    boat.getUUID().toString(),
                    boat.getName().getString(),
                    "OWN",
                    terminalKind == null ? TransportTerminalKind.PORT.name() : terminalKind.name(),
                    sourceDock.getRouteName(plan.routeIndex()),
                    sourceDock.getBlockPos(),
                    sourceDock.getDockName(),
                    plan.targetDockPos(),
                    plan.targetDockName(),
                    sourceDock.getDockName(),
                    plan.targetDockName(),
                    distanceMeters,
                    etaSeconds,
                    0,
                    "SAILING"
            ));
        }
        boat.setPendingShipmentManifest(manifest);
        if (!sourceDock.assignLoadedBoatToRouteIndex(boat, plan.routeIndex(), true, player)) {
            sourceDock.insertCargo(boat.unloadAllCargo());
            boat.clearPendingMarketDelivery();
            return false;
        }

        for (ShipmentOrderSelection selection : plan.selections()) {
            PurchaseOrder order = selection.dispatchOrder();
            market.putPurchaseOrder(new PurchaseOrder(
                    order.orderId(),
                    order.listingId(),
                    order.buyerUuid(),
                    order.buyerName(),
                    order.quantity(),
                    order.totalPrice(),
                    order.sourceDockPos(),
                    order.sourceDockName(),
                    order.targetDockPos(),
                    order.targetDockName(),
                    "IN_TRANSIT"
            ));
            if (selection.remainderOrder() != null) {
                market.putPurchaseOrder(selection.remainderOrder());
            }
        }
        applyListingReservationDeltas(market, listingReservationDeltas);
        for (ShippingOrder shippingOrder : shippingOrders) {
            market.putShippingOrder(shippingOrder);
            ProcurementService.markInTransit(level, shippingOrder.purchaseOrderId(), shippingOrder.shippingOrderId());
        }
        return true;
    }

    private void applyListingReservationDeltas(MarketSavedData market, Map<String, Integer> listingReservationDeltas) {
        for (Map.Entry<String, Integer> entry : listingReservationDeltas.entrySet()) {
            MarketListing listing = market.getListing(entry.getKey());
            if (listing == null) {
                continue;
            }
            int nextReserved = Math.max(0, listing.reservedCount() - Math.max(0, entry.getValue()));
            if (listing.availableCount() <= 0 && nextReserved <= 0) {
                market.removeListing(listing.listingId());
                continue;
            }
            market.putListing(new MarketListing(
                    listing.listingId(),
                    listing.sellerUuid(),
                    listing.sellerName(),
                    listing.itemStack(),
                    listing.unitPrice(),
                    listing.availableCount(),
                    nextReserved,
                    listing.sourceDockPos(),
                    listing.sourceDockName(),
                    listing.townId(),
                    listing.nationId(),
                    listing.priceAdjustmentBp(),
                    listing.sellerNote()
            ));
        }
    }

    private int findMaxLoadableQuantity(SailboatEntity boat, List<ItemStack> currentCargo, ItemStack template, int maxQuantity) {
        if (boat == null || template == null || template.isEmpty() || maxQuantity <= 0) {
            return 0;
        }
        int low = 0;
        int high = maxQuantity;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            List<ItemStack> candidate = new ArrayList<>(currentCargo);
            candidate.addAll(splitCargo(template, mid));
            if (boat.canLoadCargo(candidate)) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    @Nullable
    private PurchaseSplit splitOrderForShipment(MarketSavedData market, PurchaseOrder order, int shippedQuantity) {
        if (market == null || order == null || shippedQuantity <= 0) {
            return null;
        }
        if (shippedQuantity >= order.quantity()) {
            return new PurchaseSplit(order, null);
        }
        int shippedTotal = order.totalPrice() * shippedQuantity / Math.max(1, order.quantity());
        int remainderQuantity = order.quantity() - shippedQuantity;
        int remainderTotal = Math.max(0, order.totalPrice() - shippedTotal);
        PurchaseOrder shipped = new PurchaseOrder(
                order.orderId(),
                order.listingId(),
                order.buyerUuid(),
                order.buyerName(),
                shippedQuantity,
                shippedTotal,
                order.sourceDockPos(),
                order.sourceDockName(),
                order.targetDockPos(),
                order.targetDockName(),
                order.status()
        );
        PurchaseOrder remainder = new PurchaseOrder(
                market.nextId(),
                order.listingId(),
                order.buyerUuid(),
                order.buyerName(),
                remainderQuantity,
                remainderTotal,
                order.sourceDockPos(),
                order.sourceDockName(),
                order.targetDockPos(),
                order.targetDockName(),
                "WAITING_SHIPMENT"
        );
        return new PurchaseSplit(shipped, remainder);
    }

    private record ShipmentPlan(int routeIndex, BlockPos targetDockPos, String targetDockName, List<ItemStack> cargo,
                                List<ShipmentOrderSelection> selections) {
        private int totalQuantity() {
            int total = 0;
            for (ShipmentOrderSelection selection : selections) {
                total += selection.dispatchOrder().quantity();
            }
            return total;
        }
    }

    private record DispatchTerminalPlan(DockBlockEntity sourceTerminal, DockBlockEntity targetTerminal,
                                        int routeIndex, double pairScore) {
    }

    private record RoutePreview(String routeName, int distanceMeters, boolean autoCreated) {
    }

    private record DispatchPreviewPlan(DockBlockEntity sourceTerminal, DockBlockEntity targetTerminal, String carrierName,
                                       String routeName, int distanceMeters, int etaSeconds, boolean available,
                                       String availability, String detail, double pairScore) {
    }

    private record ShipmentOrderSelection(PurchaseOrder dispatchOrder, @Nullable PurchaseOrder remainderOrder,
                                          MarketListing listing) {
    }

    private record PurchaseSplit(PurchaseOrder shipped, @Nullable PurchaseOrder remainder) {
    }

    private static List<ItemStack> splitCargo(ItemStack template, int quantity) {
        List<ItemStack> cargo = new ArrayList<>();
        if (template == null || template.isEmpty() || quantity <= 0) {
            return cargo;
        }
        int remaining = quantity;
        int stackSize = Math.max(1, template.getMaxStackSize());
        while (remaining > 0) {
            int amount = Math.min(stackSize, remaining);
            ItemStack stack = template.copy();
            stack.setCount(amount);
            cargo.add(stack);
            remaining -= amount;
        }
        return cargo;
    }

    private static boolean chargePlayer(String playerUuid, String playerName, @Nullable Player player, int amount) {
        if (amount <= 0) {
            return true;
        }
        if (player != null) {
            if (player.getAbilities().instabuild) {
                return true;
            }
            return Boolean.TRUE.equals(GoldStandardEconomy.tryWithdraw(player, amount));
        }
        return Boolean.TRUE.equals(GoldStandardEconomy.tryWithdrawByIdentity(parseUuid(playerUuid), playerName, amount));
    }

    private void paySeller(MarketSavedData market, String sellerUuid, String sellerName, int amount) {
        if (amount <= 0 || market == null || sellerUuid == null || sellerUuid.isBlank()) {
            return;
        }
        Boolean deposited = GoldStandardEconomy.tryDepositByIdentity(parseUuid(sellerUuid), sellerName, amount);
        if (deposited != null && deposited) {
            return;
        }
        market.addPendingCredits(sellerUuid, amount);
    }

    private String describeListingLine(MarketListing listing) {
        return listing.toSummaryLine(currentListingUnitPrice(listing, 1));
    }

    private String resolveListingCategory(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        try {
            return COMMODITY_MARKET.ensureCommodity(stack).definition().category();
        } catch (SQLException exception) {
            MARKET_LOGGER.debug("Failed to resolve commodity category for {}", stack.getHoverName().getString(), exception);
            return "";
        }
    }

    private int resolveListingRarity(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        try {
            return COMMODITY_MARKET.ensureCommodity(stack).definition().rarity();
        } catch (SQLException exception) {
            MARKET_LOGGER.debug("Failed to resolve commodity rarity for {}", stack.getHoverName().getString(), exception);
            return 0;
        }
    }

    private int currentCommodityUnitPrice(ItemStack stack, int quantity, int fallbackPrice) {
        CommodityQuote quote = quoteCommodity(stack, quantity);
        if (quote == null) {
            return Math.max(1, fallbackPrice);
        }
        return Math.max(1, quote.buyUnitPrice());
    }

    private int currentListingUnitPrice(MarketListing listing, int quantity) {
        if (listing == null) {
            return 0;
        }
        CommodityQuote quote = quoteCommodity(listing.itemStack(), Math.max(1, quantity));
        int fallbackPrice = Math.max(CommodityMarketService.estimateBaseUnitPrice(listing.itemStack()), listing.unitPrice());
        int basePrice = quote == null ? Math.max(1, fallbackPrice) : Math.max(1, quote.buyUnitPrice());
        return applyPriceAdjustment(basePrice, listing.priceAdjustmentBp());
    }

    private int currentListingTotalPrice(MarketListing listing, int quantity) {
        if (listing == null) {
            return 0;
        }
        CommodityQuote quote = quoteCommodity(listing.itemStack(), Math.max(1, quantity));
        int fallbackUnitPrice = Math.max(CommodityMarketService.estimateBaseUnitPrice(listing.itemStack()), listing.unitPrice());
        int baseTotal = quote == null
                ? Math.max(1, fallbackUnitPrice) * Math.max(1, quantity)
                : Math.max(0, quote.buyPrice());
        return Math.max(0, applyPriceAdjustment(baseTotal, listing.priceAdjustmentBp()));
    }

    private int applyPriceAdjustment(int basePrice, int priceAdjustmentBp) {
        return Math.max(1, (int) Math.round(basePrice * (1 + priceAdjustmentBp / 10000.0D)));
    }

    @Nullable
    private CommodityQuote quoteCommodity(ItemStack stack, int quantity) {
        try {
            return COMMODITY_MARKET.quote(stack, Math.max(1, quantity));
        } catch (SQLException exception) {
            MARKET_LOGGER.debug("Failed to query commodity quote", exception);
            return null;
        }
    }

    private void adjustCommoditySupply(ItemStack stack, int delta) {
        if (stack == null || stack.isEmpty() || delta == 0) {
            return;
        }
        try {
            COMMODITY_MARKET.adjustStock(stack, delta);
        } catch (SQLException exception) {
            MARKET_LOGGER.warn("Failed to adjust commodity supply by {} for {}", delta, stack.getHoverName().getString(), exception);
        }
    }

    private void applyCommodityDemand(MarketListing listing, int amount, String buyerUuid, String buyerName) {
        if (listing == null || amount <= 0) {
            return;
        }
        String buyerNationId = "";
        if (level != null && linkedDockPos != null) {
            TownRecord buyerTown = TownService.getTownAt(level, linkedDockPos);
            buyerNationId = buyerTown == null ? "" : buyerTown.nationId();
        }
        try {
            COMMODITY_MARKET.applyTrade(
                    listing.itemStack(),
                    MarketTradeSide.BUY,
                    amount,
                    worldPosition.toShortString(),
                    listing.nationId(),
                    buyerNationId,
                    buyerUuid == null ? "" : buyerUuid.trim(),
                    buyerName == null ? "" : buyerName.trim()
            );
        } catch (SQLException exception) {
            MARKET_LOGGER.warn("Failed to record commodity purchase for market listing {}", listing.listingId(), exception);
        }
    }

    @Nullable
    public String listingIdByVisibleIndex(int listingIndex) {
        if (level == null || level.isClientSide) {
            return null;
        }
        MarketListing listing = MarketSavedData.get(level).getListingByVisibleIndex(listingIndex);
        return listing == null ? null : listing.listingId();
    }

    private List<SailboatEntity> availableDispatchBoats(DockBlockEntity sourceDock, @Nullable Player player) {
        if (sourceDock == null) {
            return List.of();
        }
        if (player != null) {
            return sourceDock.getAvailableSailboatsForDispatch(player);
        }
        return sourceDock.getAvailableSailboatsForDispatch(null).stream()
                .filter(boat -> sourceDock.getOwnerUuid().equals(boat.getOwnerUuid()))
                .toList();
    }

    private WarehouseSelectionResult resolveCancelListingWarehouse(String playerUuid, MarketListing listing) {
        TownWarehouseBlockEntity originalWarehouse = level.getBlockEntity(listing.sourceDockPos()) instanceof TownWarehouseBlockEntity warehouse ? warehouse : null;
        if (originalWarehouse != null) {
            return WarehouseSelectionResult.success(originalWarehouse, false);
        }
        TownWarehouseBlockEntity linkedWarehouse = getLinkedWarehouse();
        if (linkedWarehouse == null) {
            return WarehouseSelectionResult.failure("screen.sailboatmod.market.unlist.failed_missing_dock");
        }
        if (!canManageMarket(playerUuid)) {
            return WarehouseSelectionResult.failure("screen.sailboatmod.market.unlist.failed_dock_access");
        }
        return WarehouseSelectionResult.success(linkedWarehouse, true);
    }

    private void syncTerminalRegistry() {
        if (level == null || level.isClientSide) {
            return;
        }
        MarketTerminalSavedData.get(level).putEntry(new MarketTerminalSavedData.MarketTerminalEntry(
                level.dimension().location().toString(),
                worldPosition,
                getMarketName(),
                getOwnerUuid(),
                getOwnerName()
        ));
    }

    @Nullable
    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public record CancelListingResult(boolean success, String messageKey) {
        public static CancelListingResult success(String messageKey) {
            return new CancelListingResult(true, messageKey == null ? "" : messageKey);
        }

        public static CancelListingResult failure(String messageKey) {
            return new CancelListingResult(false, messageKey == null ? "" : messageKey);
        }
    }

    private record WarehouseSelectionResult(@Nullable TownWarehouseBlockEntity warehouse,
                                            boolean usedLinkedWarehouseFallback,
                                            @Nullable CancelListingResult result) {
        private static WarehouseSelectionResult success(TownWarehouseBlockEntity warehouse, boolean usedLinkedWarehouseFallback) {
            return new WarehouseSelectionResult(warehouse, usedLinkedWarehouseFallback, null);
        }

        private static WarehouseSelectionResult failure(String messageKey) {
            return new WarehouseSelectionResult(null, false, CancelListingResult.failure(messageKey));
        }
    }

}
