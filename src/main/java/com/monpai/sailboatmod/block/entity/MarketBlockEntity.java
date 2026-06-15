package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.dock.DockRegistry;
import com.monpai.sailboatmod.dock.PostStationRegistry;
import com.monpai.sailboatmod.dock.TownWarehouseRegistry;
import com.monpai.sailboatmod.economy.GoldStandardEconomy;
import com.monpai.sailboatmod.entity.CarriageEntity;
import com.monpai.sailboatmod.entity.TransportEntity;
import com.monpai.sailboatmod.market.MarketDispatchPlanner;
import com.monpai.sailboatmod.market.MarketListing;
import com.monpai.sailboatmod.market.FulfillmentMode;
import com.monpai.sailboatmod.market.SellerShipQueue;
import com.monpai.sailboatmod.market.PickupLock;
import com.monpai.sailboatmod.market.MarketOverviewData;
import com.monpai.sailboatmod.market.MarketPricePolicy;
import com.monpai.sailboatmod.market.MarketPricePolicy.ListingPriceWindow;
import com.monpai.sailboatmod.market.MarketSavedData;
import com.monpai.sailboatmod.market.ProcurementRecord;
import com.monpai.sailboatmod.market.ProcurementService;
import com.monpai.sailboatmod.market.PurchaseOrder;
import com.monpai.sailboatmod.market.ShipmentManifestEntry;
import com.monpai.sailboatmod.market.ShippingOrder;
import com.monpai.sailboatmod.market.TransportTerminalKind;
import com.monpai.sailboatmod.market.commodity.CommodityKeyResolver;
import com.monpai.sailboatmod.market.commodity.CommodityConfigLoader;
import com.monpai.sailboatmod.market.commodity.CommodityDefinition;
import com.monpai.sailboatmod.market.commodity.CommodityMarketService;
import com.monpai.sailboatmod.market.commodity.CommodityPriceChartPoint;
import com.monpai.sailboatmod.market.commodity.CommodityQuote;
import com.monpai.sailboatmod.market.commodity.MarketTradeSide;
import com.monpai.sailboatmod.market.logistics.ShippingTraceService;
import com.monpai.sailboatmod.market.analytics.CommodityCandleSeries;
import com.monpai.sailboatmod.market.analytics.CommodityImpactSnapshot;
import com.monpai.sailboatmod.market.analytics.MarketAnalyticsSeries;
import com.monpai.sailboatmod.market.analytics.MarketAnalyticsService;
import com.monpai.sailboatmod.market.terminal.MarketTerminalSavedData;
import com.monpai.sailboatmod.market.wallet.MarketWalletService;
import com.monpai.sailboatmod.menu.MarketMenu;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.DockTownBindingRecord;
import com.monpai.sailboatmod.nation.model.NationPermission;
import com.monpai.sailboatmod.nation.model.NationTreasuryRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.service.DockTownResolver;
import com.monpai.sailboatmod.nation.service.NationService;
import com.monpai.sailboatmod.nation.service.TownEconomySnapshotService;
import com.monpai.sailboatmod.nation.service.TownFinanceLedgerService;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.route.LandTransportNetworkService;
import com.monpai.sailboatmod.route.RouteDefinition;
import com.monpai.sailboatmod.registry.ModBlockEntities;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MarketBlockEntity extends BlockEntity implements MenuProvider {
    private static final Logger MARKET_LOGGER = LogUtils.getLogger();
    private static final double LINK_DOCK_RADIUS = 24.0D;
    private static final double PORT_PREVIEW_SPEED_MPS = 8.0D;
    private static final double POST_STATION_PREVIEW_SPEED_MPS = 5.0D;
    private static final boolean ALLOW_TERRAIN_FALLBACK_FOR_POST_STATION_DISPATCH = false;
    private static final CommodityMarketService COMMODITY_MARKET = new CommodityMarketService();
    private static final MarketAnalyticsService MARKET_ANALYTICS = new MarketAnalyticsService();
    private String marketName = "";
    private String ownerName = "";
    private String ownerUuid = "";
    @Nullable
    private BlockPos linkedDockPos;

    public record CreateListingResult(boolean success, String messageKey, Object[] messageArgs) {
        public static CreateListingResult created() {
            return new CreateListingResult(true, "screen.sailboatmod.market.status.list_created", new Object[0]);
        }

        public static CreateListingResult failure(String messageKey, Object... messageArgs) {
            return new CreateListingResult(false, messageKey, messageArgs == null ? new Object[0] : messageArgs);
        }

        public Component message() {
            return Component.translatable(messageKey, messageArgs);
        }
    }

    public MarketBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MARKET_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            bindNearestDockIfAbsent();
            syncTerminalRegistry();
            com.monpai.sailboatmod.market.MarketRegistry.register(level, worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            com.monpai.sailboatmod.market.MarketRegistry.unregister(level, worldPosition);
        }
        super.setRemoved();
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
        long walletAvailableBalance = 0L;
        long walletReservedBalance = 0L;
        long walletTotalBalance = 0L;
        long treasuryBalance = 0L;
        boolean canTransferTreasury = false;
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
                if (!town.nationId().isBlank()) {
                    NationSavedData nationData = NationSavedData.get(level);
                    NationTreasuryRecord treasury = nationData.getOrCreateTreasury(town.nationId());
                    treasuryBalance = treasury.currencyBalance();
                    UUID viewerUuidForPermission = parseUuid(safePlayerUuid);
                    canTransferTreasury = viewerUuidForPermission != null
                            && NationService.hasPermission(level, viewerUuidForPermission, NationPermission.MANAGE_TREASURY);
                }
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
            UUID viewerUuid = parseUuid(safePlayerUuid);
            dockStorageAccessible = viewerUuid != null && canManageMarket(safePlayerUuid);
            dockStorageLines = dockStorageAccessible ? warehouse.getVisibleStorageLines(viewerUuid) : List.of();
            if (dockStorageAccessible) {
                int storageCount = warehouse.getVisibleStorageCount(viewerUuid);
                for (int i = 0; i < storageCount; i++) {
                    ItemStack stack = warehouse.getStorageItemForVisibleIndex(viewerUuid, i);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    String itemName = stack.getHoverName().getString();
                    String label = itemName + " x" + stack.getCount();
                    String itemKey = ForgeRegistries.ITEMS.getKey(stack.getItem()) != null
                            ? ForgeRegistries.ITEMS.getKey(stack.getItem()).toString()
                            : "";
                    int suggestedUnitPrice = COMMODITY_MARKET.referencePrice(stack);
                    ListingPriceWindow priceWindow = listingPriceWindow(stack, 1, suggestedUnitPrice);
                    storageEntries.add(new MarketOverviewData.StorageEntry(
                            label,
                            itemKey,
                            itemName,
                            stack.getCount(),
                            suggestedUnitPrice,
                            priceWindow.minAllowedUnitPrice(),
                            priceWindow.maxAllowedUnitPrice(),
                            priceWindow.constrained(),
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
        List<MarketOverviewData.DispatchOption> availableDispatchOptions = new ArrayList<>();
        if (level != null && !level.isClientSide) {
            if (!safePlayerUuid.isBlank()) {
                var walletAccount = MarketWalletService.getAccount(level, safePlayerUuid, playerName);
                walletAvailableBalance = walletAccount.availableBalance();
                walletReservedBalance = walletAccount.reservedBalance();
                walletTotalBalance = walletAccount.totalBalance();
            }
            MarketSavedData market = MarketSavedData.get(level);
            for (MarketListing listing : market.getListings()) {
                if (!isListingVisibleToTown(townId, listing)) {
                    continue;
                }
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
                        listing.listingId(),
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
            availableDispatchOptions.addAll(buildAvailableDispatchOptions(linkedWarehouse, onlinePlayer));
            // 卖家发货待发单按 orderId 稳定排序求位次（与 web myOrders 同源逻辑，见 SellerShipQueue）。
            List<String> sellerShipQueueIds = new ArrayList<>();
            for (PurchaseOrder o : openOrders) {
                if (FulfillmentMode.fromString(o.fulfillment()) == FulfillmentMode.SELLER_SHIP
                        && "WAITING_SHIPMENT".equals(o.status())) {
                    sellerShipQueueIds.add(o.orderId());
                }
            }
            sellerShipQueueIds.sort(java.util.Comparator.naturalOrder());
            for (PurchaseOrder order : openOrders) {
                String line = order.toSummaryLine();
                orderLines.add(line);
                int queuePos = SellerShipQueue.positionOf(sellerShipQueueIds, order.orderId());
                int queueEta = SellerShipQueue.etaSeconds(queuePos);
                orderEntries.add(new MarketOverviewData.OrderEntry(
                        order.orderId(),
                        line,
                        order.sourceDockName().isBlank() ? order.sourceDockPos().toShortString() : order.sourceDockName(),
                        order.targetDockName().isBlank() ? order.targetDockPos().toShortString() : order.targetDockName(),
                        order.quantity(),
                        order.status(),
                        queuePos,
                        queueEta,
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
            try {
                addBuyOrderCommodityDisplayNames(chartDisplayNames, COMMODITY_MARKET.listActiveBuyOrderCommodityDefinitions());
            } catch (SQLException exception) {
                MARKET_LOGGER.debug("Failed to load active buy order commodity definitions for market overview", exception);
            }
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

        java.util.List<MarketOverviewData.WarehouseOption> receivingOptions = receivingWarehouseOptionsForViewer(safePlayerUuid);

        return new MarketOverviewData(
                worldPosition,
                getMarketName(),
                getOwnerName(),
                getOwnerUuid(),
                safePlayerUuid,
                safePlayerUuid.isBlank() || level == null || level.isClientSide ? 0 : MarketSavedData.get(level).getPendingCredits(safePlayerUuid),
                walletAvailableBalance,
                walletReservedBalance,
                walletTotalBalance,
                treasuryBalance,
                canTransferTreasury,
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
                availableDispatchOptions,
                buyOrderEntries,
                priceChartSeries,
                commodityBuyBooks,
                candleSeries,
                impactSnapshots,
                analyticsSeries,
                receivingOptions,
                !receivingOptions.isEmpty()
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

    public CreateListingResult createListingFromDockStorage(Player player, int visibleStorageIndex, int quantity, int requestedUnitPrice, String sellerNote) {
        if (player == null) {
            return CreateListingResult.failure("screen.sailboatmod.market.error.listing_unavailable");
        }
        return createListingFromDockStorage(
                player.getUUID().toString(),
                player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName(),
                visibleStorageIndex,
                quantity,
                requestedUnitPrice,
                sellerNote
        );
    }

    public CreateListingResult createListingFromDockStorage(String playerUuid, String playerName, int visibleStorageIndex, int quantity, int requestedUnitPrice, String sellerNote) {
        TownWarehouseBlockEntity warehouse = getLinkedWarehouse();
        String safePlayerUuid = playerUuid == null ? "" : playerUuid.trim();
        String safePlayerName = playerName == null ? "" : playerName.trim();
        if (level == null || level.isClientSide || warehouse == null || safePlayerUuid.isBlank() || !canManageMarket(safePlayerUuid)) {
            return CreateListingResult.failure("screen.sailboatmod.market.error.listing_unavailable");
        }
        UUID sellerId = parseUuid(safePlayerUuid);
        if (sellerId == null) {
            return CreateListingResult.failure("screen.sailboatmod.market.error.listing_unavailable");
        }
        ItemStack selected = warehouse.getStorageItemForVisibleIndex(sellerId, visibleStorageIndex);
        if (selected.isEmpty()) {
            return CreateListingResult.failure("screen.sailboatmod.market.storage_empty");
        }
        int stocked = selected.getCount();
        int amount = Math.max(1, Math.min(quantity, stocked));
        ItemStack listed = selected.copy();
        listed.setCount(1);
        if (requestedUnitPrice <= 0) {
            return CreateListingResult.failure("screen.sailboatmod.market.error.listing_price_invalid");
        }
        ListingPriceWindow priceWindow = listingPriceWindow(listed, amount, requestedUnitPrice);
        if (!priceWindow.valid()) {
            return CreateListingResult.failure(
                    "screen.sailboatmod.market.error.listing_price_out_of_range",
                    priceWindow.minAllowedUnitPrice(),
                    priceWindow.maxAllowedUnitPrice(),
                    priceWindow.referenceUnitPrice()
            );
        }
        if (!warehouse.extractVisibleStorage(sellerId, visibleStorageIndex, amount)) {
            return CreateListingResult.failure("screen.sailboatmod.market.error.listing_unavailable");
        }
        // pricing: listing no longer bumps stock (stock no longer drives price)
        MarketSavedData market = MarketSavedData.get(level);
        String listingTownId = warehouse.getTownId();
        String listingNationId = "";
        TownRecord town = TownService.getTownAt(level, warehouse.getBlockPos());
        if (town != null) {
            listingTownId = town.townId();
            listingNationId = town.nationId();
        }
        market.putListing(new MarketListing(
                market.nextId(),
                safePlayerUuid,
                safePlayerName,
                listed,
                Math.max(1, priceWindow.requestedUnitPrice()),
                amount,
                0,
                linkedDockPos,
                warehouse.getDisplayName().getString(),
                listingTownId,
                listingNationId,
                priceWindow.constrained() ? priceWindow.derivedPriceAdjustmentBp() : 0,
                sellerNote
        ));
        syncTerminalRegistry();
        return CreateListingResult.created();
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
        return purchaseListing(playerUuid, playerName, onlinePlayer, listingIndex, quantity, "SELLER_SHIP", null);
    }

    public boolean purchaseListing(String playerUuid, String playerName, @Nullable Player onlinePlayer, int listingIndex, int quantity,
                                   String fulfillment, @Nullable BlockPos targetWarehousePos) {
        if (level == null || level.isClientSide) {
            return false;
        }
        MarketSavedData market = MarketSavedData.get(level);
        MarketListing listing = market.getListingByVisibleIndex(listingIndex);
        if (listing == null) {
            return false;
        }
        return purchaseListingResolved(playerUuid, playerName, onlinePlayer, listing, quantity, fulfillment, targetWarehousePos);
    }

    public boolean purchaseListingById(String playerUuid, String playerName, @Nullable Player onlinePlayer, String listingId, int quantity) {
        return purchaseListingById(playerUuid, playerName, onlinePlayer, listingId, quantity, "SELLER_SHIP", null);
    }

    public boolean purchaseListingById(String playerUuid, String playerName, @Nullable Player onlinePlayer, String listingId, int quantity,
                                       String fulfillment, @Nullable BlockPos targetWarehousePos) {
        if (level == null || level.isClientSide) {
            return false;
        }
        MarketSavedData market = MarketSavedData.get(level);
        MarketListing listing = market.getListing(listingId);
        if (listing == null) {
            return false;
        }
        return purchaseListingResolved(playerUuid, playerName, onlinePlayer, listing, quantity, fulfillment, targetWarehousePos);
    }

    /** 买家当前所属 town 内、其可写入的收货仓坐标列表（默认仓首位）。供下单回退与下拉同源。 */
    public java.util.List<BlockPos> receivingWarehouseCandidatesFor(String buyerUuid) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        if (level == null || level.isClientSide || buyerUuid == null || buyerUuid.isBlank()) {
            return out;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(buyerUuid.trim());
        } catch (IllegalArgumentException ex) {
            return out;
        }
        NationSavedData nations = NationSavedData.get(level);
        // 镇民体系：玩家先加入 town，收货候选收窄为其所属 town 的仓库（而非整个 nation 的所有 town）
        for (String townId : nations.getTownsForPlayer(uuid)) {
            BlockPos warehousePos = TownWarehouseRegistry.get(level, townId);
            if (warehousePos != null && !out.contains(warehousePos)) {
                out.add(warehousePos);
            }
        }
        return out;
    }

    /** 收货下拉同源数据：买家当前 town 可写入仓库，每项含显示名 + townName，默认仓首位。 */
    public java.util.List<MarketOverviewData.WarehouseOption> receivingWarehouseOptionsForViewer(String buyerUuid) {
        java.util.List<MarketOverviewData.WarehouseOption> out = new java.util.ArrayList<>();
        if (level == null || level.isClientSide) {
            return out;
        }
        NationSavedData nations = NationSavedData.get(level);
        TownWarehouseBlockEntity linked = getLinkedWarehouse();
        for (BlockPos pos : receivingWarehouseCandidatesFor(buyerUuid)) {
            String display = linked != null ? warehouseDisplayNameFor(pos, linked) : posLabel(pos);
            String townName = townNameForWarehouse(nations, pos);
            out.add(new MarketOverviewData.WarehouseOption(pos, display, townName));
        }
        return out;
    }

    private String posLabel(BlockPos pos) {
        return pos == null ? "" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private String townNameForWarehouse(NationSavedData nations, BlockPos pos) {
        if (level != null && level.getBlockEntity(pos) instanceof TownWarehouseBlockEntity w) {
            String townId = w.getTownId();
            TownRecord town = townId == null ? null : nations.getTown(townId);
            return town == null ? "" : town.name();
        }
        return "";
    }

    /** 买家默认收货仓：候选列表第一个；无则返回 null（下单回退由调用方处理）。 */
    @Nullable
    public BlockPos defaultReceivingWarehouseFor(String buyerUuid) {
        java.util.List<BlockPos> candidates = receivingWarehouseCandidatesFor(buyerUuid);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /** 下单目的地解析：买家选的收货仓 → 买家默认收货仓 → null（不再回退卖家源仓）。 */
    private BlockPos resolveBuyerTargetWarehouse(String buyerUuid, @Nullable BlockPos chosen) {
        if (chosen != null && !chosen.equals(BlockPos.ZERO)) {
            return chosen;
        }
        return defaultReceivingWarehouseFor(buyerUuid);
    }

    /** 取某仓库坐标的显示名：是 town 仓则用其名，否则回退 linked 仓名。 */
    private String warehouseDisplayNameFor(BlockPos warehousePos, TownWarehouseBlockEntity fallback) {
        if (level != null && level.getBlockEntity(warehousePos) instanceof TownWarehouseBlockEntity w) {
            return w.getDisplayName().getString();
        }
        return fallback.getDisplayName().getString();
    }

    private boolean purchaseListingResolved(String playerUuid, String playerName, @Nullable Player onlinePlayer,
                                            MarketListing listing, int quantity,
                                            String fulfillment, @Nullable BlockPos targetWarehousePos) {
        TownWarehouseBlockEntity warehouse = getLinkedWarehouse();
        String safePlayerUuid = playerUuid == null ? "" : playerUuid.trim();
        String safePlayerName = playerName == null ? "" : playerName.trim();
        if (level == null || level.isClientSide || warehouse == null || safePlayerUuid.isBlank() || listing == null) {
            return false;
        }
        if (safePlayerUuid.equals(listing.sellerUuid())) {
            return false;
        }
        if (!canViewerReachListing(safePlayerUuid, listing)) {
            return false;
        }
        int amount = Math.max(1, Math.min(quantity, listing.availableCount()));
        int total = currentListingTotalPrice(listing, amount);
        // 先解析收货仓与模式：②③（非真人自提）无可用收货仓时，必须在扣款前拒单，避免钱货已动才发现 null。
        FulfillmentMode resolvedMode = FulfillmentMode.fromString(fulfillment);
        BlockPos receivingWarehouse = resolveBuyerTargetWarehouse(safePlayerUuid, targetWarehousePos);
        if (resolvedMode != FulfillmentMode.REAL_PICKUP && receivingWarehouse == null) {
            return false;
        }
        // 真人自提无收货仓时，货物原地等买家自取：收货仓回退为源仓，避免下游显示名查询 NPE。
        if (receivingWarehouse == null) {
            receivingWarehouse = listing.sourceDockPos();
        }
        if (!chargePlayer(safePlayerUuid, safePlayerName, onlinePlayer, total)) {
            return false;
        }
        MarketSavedData market = MarketSavedData.get(level);
        applyCommodityDemand(listing, amount, total, safePlayerUuid, safePlayerName);
        int sellerPayout = total;
        com.monpai.sailboatmod.nation.service.TaxService.TaxResult salesTaxResult =
                com.monpai.sailboatmod.nation.service.TaxService.applySalesTax(level, total, this.worldPosition);
        sellerPayout = salesTaxResult.sellerReceives();
        com.monpai.sailboatmod.nation.service.TaxService.TaxResult tariffResult =
                com.monpai.sailboatmod.nation.service.TaxService.applyImportTariff(level, sellerPayout, listing.sourceDockPos(), linkedDockPos);
        sellerPayout = tariffResult.sellerReceives();
        com.monpai.sailboatmod.nation.service.TaxService.recordTrade(level, this.worldPosition);
        paySeller(market, listing.sellerUuid(), listing.sellerName(), sellerPayout);
        // pricing: keep the seller-fixed unit price on resale
        market.putListing(new MarketListing(
                listing.listingId(),
                listing.sellerUuid(),
                listing.sellerName(),
                listing.itemStack(),
                listing.unitPrice(),
                Math.max(0, listing.availableCount() - amount),
                listing.reservedCount() + amount,
                listing.sourceDockPos(),
                listing.sourceDockName(),
                listing.townId(),
                listing.nationId(),
                listing.priceAdjustmentBp(),
                listing.sellerNote()
        ));
        String fulfillmentMode = resolvedMode.name();
        // 自提（真人/自动）下单即把货为买家锁定（PICKUP_LOCKED，不被后台发货）；卖家发货走待发。
        String orderStatus = (resolvedMode == FulfillmentMode.REAL_PICKUP || resolvedMode == FulfillmentMode.AUTO_PICKUP)
                ? PickupLock.STATUS_LOCKED
                : "WAITING_SHIPMENT";
        PurchaseOrder createdOrder = new PurchaseOrder(
                market.nextId(),
                listing.listingId(),
                safePlayerUuid,
                safePlayerName,
                amount,
                total,
                listing.sourceDockPos(),
                listing.sourceDockName(),
                receivingWarehouse,
                warehouseDisplayNameFor(receivingWarehouse, warehouse),
                orderStatus,
                fulfillmentMode,
                receivingWarehouse
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
        UUID sellerId = parseUuid(safePlayerUuid);
        if (sellerId == null) {
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
        if (!targetWarehouseSelection.warehouse().insertCargo(sellerId, cargo)) {
            return CancelListingResult.failure("screen.sailboatmod.market.unlist.failed_storage_full");
        }
        adjustCommoditySupply(listing.itemStack(), -listing.availableCount());
        // 场景③：卖家撤单时，挂单下未发货的买家订单已无法履约——退款给买家（货物随撤单已回卖家仓，不再归还）。
        List<PurchaseOrder> strandedOrders = market.getActiveOrdersForListing(listing.listingId());
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
        for (PurchaseOrder stranded : strandedOrders) {
            refundAndReleaseOrder(market, stranded, "seller_cancel", false);
        }
        return CancelListingResult.success(targetWarehouseSelection.usedLinkedWarehouseFallback()
                ? "screen.sailboatmod.market.unlist.success_linked_dock"
                : "screen.sailboatmod.market.unlist.success");
    }

    /**
     * 退款并（可选）归还一个订单的货物。幂等：已 CANCELLED 的订单直接跳过，不重复退款。
     * 退款全额 totalPrice 给买家；订单标记 CANCELLED 保留审计。
     * {@code returnCargo=true}（买家取消/送达失败）时把本单 reservedCount 退回挂单 availableCount；
     * {@code false}（卖家撤单）时不碰挂单——货物随撤单已回卖家仓，只退钱。
     */
    private void refundAndReleaseOrder(MarketSavedData market, PurchaseOrder order, String reason, boolean returnCargo) {
        if (market == null || order == null) {
            return;
        }
        if (PurchaseOrder.STATUS_CANCELLED.equals(order.status())) {
            return; // 幂等守卫：已取消并退过款，不再重复
        }
        if (level != null && !level.isClientSide && order.totalPrice() > 0
                && order.buyerUuid() != null && !order.buyerUuid().isBlank()) {
            MarketWalletService.deposit(level, order.buyerUuid(), order.buyerName(), order.totalPrice());
        }
        // 货物归还：仅 returnCargo 且挂单仍在时，把本单预留量退回可售量。
        MarketListing listing = returnCargo ? market.getListing(order.listingId()) : null;
        if (listing != null) {
            market.putListing(new MarketListing(
                    listing.listingId(),
                    listing.sellerUuid(),
                    listing.sellerName(),
                    listing.itemStack(),
                    listing.unitPrice(),
                    listing.availableCount() + order.quantity(),
                    Math.max(0, listing.reservedCount() - order.quantity()),
                    listing.sourceDockPos(),
                    listing.sourceDockName(),
                    listing.townId(),
                    listing.nationId(),
                    listing.priceAdjustmentBp(),
                    listing.sellerNote()
            ));
        }
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
                PurchaseOrder.STATUS_CANCELLED,
                order.fulfillment(),
                order.targetWarehousePos()
        ));
        MARKET_LOGGER.info("Refunded purchase order {} ({}), returned {} to buyer {}",
                order.orderId(), reason, order.totalPrice(), order.buyerUuid());
    }

    /** 买家主动取消未发货订单（场景①）：仅本人、仅未发货态（PAID/WAITING_SHIPMENT/PICKUP_LOCKED）可取消。 */
    public CancelPurchaseResult cancelPurchaseOrderById(String requesterUuid, String orderId) {
        if (level == null || level.isClientSide) {
            return CancelPurchaseResult.failure("screen.sailboatmod.market.order.cancel.failed_missing");
        }
        MarketSavedData market = MarketSavedData.get(level);
        PurchaseOrder order = market.getPurchaseOrder(orderId);
        if (order == null) {
            return CancelPurchaseResult.failure("screen.sailboatmod.market.order.cancel.failed_missing");
        }
        String safeRequester = requesterUuid == null ? "" : requesterUuid.trim();
        if (!safeRequester.equals(order.buyerUuid())) {
            return CancelPurchaseResult.failure("screen.sailboatmod.market.order.cancel.failed_not_owner");
        }
        String status = order.status();
        boolean cancellable = "PAID".equals(status)
                || "WAITING_SHIPMENT".equals(status)
                || PickupLock.STATUS_LOCKED.equals(status);
        if (!cancellable) {
            return CancelPurchaseResult.failure("screen.sailboatmod.market.order.cancel.failed_in_transit");
        }
        refundAndReleaseOrder(market, order, "buyer_cancel", true);
        return CancelPurchaseResult.success("screen.sailboatmod.market.order.cancel.success");
    }

    /** 送达失败时自动退款（场景②）：收货仓满/不存在导致入仓失败时由运输层调用。 */
    public void refundFailedDelivery(PurchaseOrder order, String reason) {
        if (level == null || level.isClientSide || order == null) {
            return;
        }
        refundAndReleaseOrder(MarketSavedData.get(level), order, reason, true);
    }

    public record CancelPurchaseResult(boolean success, String messageKey) {
        public static CancelPurchaseResult success(String messageKey) {
            return new CancelPurchaseResult(true, messageKey);
        }

        public static CancelPurchaseResult failure(String messageKey) {
            return new CancelPurchaseResult(false, messageKey);
        }
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
                return dispatchBestSelectedOrder(playerUuid, playerName, onlinePlayer, sourceWarehouse, market, selectedOrder);
            }
            return dispatchSelectedOrder(playerUuid, playerName, onlinePlayer, sourceWarehouse, market, selectedOrder, effectiveKind);
        }
        return tryAutoDispatchOrders(playerUuid, playerName, onlinePlayer, linkedDockPos, effectiveKind);
    }

    public boolean dispatchOrderById(String playerUuid, String playerName, @Nullable Player onlinePlayer,
                                     String orderId, TransportTerminalKind terminalKind) {
        if (linkedDockPos == null || !canManageMarket(playerUuid) || level == null || level.isClientSide) {
            return false;
        }
        TownWarehouseBlockEntity sourceWarehouse = getLinkedWarehouse();
        if (sourceWarehouse == null) {
            return false;
        }
        MarketSavedData market = MarketSavedData.get(level);
        PurchaseOrder order = market.getPurchaseOrder(orderId);
        if (order == null || !linkedDockPos.equals(order.sourceDockPos())) {
            return false;
        }
        return dispatchResolvedOrder(playerUuid, playerName, onlinePlayer, market, sourceWarehouse, order, terminalKind);
    }

    private boolean dispatchResolvedOrder(String playerUuid, String playerName, @Nullable Player onlinePlayer,
                                          MarketSavedData market, TownWarehouseBlockEntity sourceWarehouse,
                                          PurchaseOrder order, TransportTerminalKind terminalKind) {
        if (market == null || sourceWarehouse == null || order == null) {
            return false;
        }
        if (order.sourceDockPos().equals(order.targetDockPos())) {
            return processLocalOrders(sourceWarehouse, market);
        }
        TransportTerminalKind effectiveKind = terminalKind == null ? TransportTerminalKind.AUTO : terminalKind;
        if (effectiveKind == TransportTerminalKind.AUTO) {
            return dispatchBestSelectedOrder(playerUuid, playerName, onlinePlayer, sourceWarehouse, market, order);
        }
        return dispatchSelectedOrder(playerUuid, playerName, onlinePlayer, sourceWarehouse, market, order, effectiveKind);
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

    /** 后台自动发货入口（由 TransportDispatchService 定时调用）：发本市场所有需车的待发订单。 */
    public void runBackgroundAutoDispatch() {
        if (level == null || level.isClientSide || linkedDockPos == null) {
            return;
        }
        if (getLinkedWarehouse() == null) {
            return;
        }
        MarketSavedData market = MarketSavedData.get(level);
        // 自动自提（AUTO_PICKUP，PICKUP_LOCKED）：把买家空车空驶到产地（不在产地时），到产地后 Task4 装货、Task5 续发。
        // 独立于下方 SELLER_SHIP 链路——自提单非 WAITING_SHIPMENT，isBackgroundDispatchable 不认它。
        dispatchAutoPickup(market);
        boolean hasDispatchable = false;
        for (PurchaseOrder order : market.getOpenOrdersForSourceDock(linkedDockPos)) {
            if (com.monpai.sailboatmod.market.logistics.TransportDispatchService
                    .isBackgroundDispatchable(order.status(), order.fulfillment())) {
                hasDispatchable = true;
                break;
            }
        }
        if (!hasDispatchable) {
            return;
        }
        // 复用现成自动发货链路（AUTO 模式自动选港口/驿站、船/马车统一）；无车则内部 return false、订单留队列。
        tryAutoDispatchOrders("", "", null, linkedDockPos, TransportTerminalKind.AUTO);
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
                shipped = tryDispatchWaitingOrdersAuto(shipperUuid, shipperName, player, sourceWarehouse, market);
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
            if (!FulfillmentMode.fromString(order.fulfillment()).needsVehicleDispatch()) {
                continue; // 真人自提不进调度发车（系统/手动均不代发）
            }
            byTargetWarehouse.computeIfAbsent(order.targetDockPos(), ignored -> new ArrayList<>()).add(order);
        }
        for (Map.Entry<BlockPos, List<PurchaseOrder>> entry : byTargetWarehouse.entrySet()) {
            DispatchTerminalPlan terminalPlan = resolveDispatchTerminalPlan(sourceWarehouse, entry.getKey(), terminalKind, player);
            if (terminalPlan == null) {
                continue;
            }
            for (TransportEntity boat : availableDispatchBoats(terminalPlan.sourceTerminal(), player)) {
                ShipmentPlan shipmentPlan = buildShipmentPlanForWarehouseTarget(
                        market,
                        boat,
                        terminalPlan.routeIndex(),
                        terminalPlan.generatedRoute(),
                        terminalPlan.landPlan(),
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

    private boolean tryDispatchWaitingOrdersAuto(String shipperUuid, String shipperName, @Nullable Player player,
                                                 TownWarehouseBlockEntity sourceWarehouse, MarketSavedData market) {
        if (sourceWarehouse == null || market == null) {
            return false;
        }
        LinkedHashMap<BlockPos, List<PurchaseOrder>> byTargetWarehouse = new LinkedHashMap<>();
        for (PurchaseOrder order : market.getOpenOrdersForSourceDock(sourceWarehouse.getBlockPos())) {
            if (order.sourceDockPos().equals(order.targetDockPos())) {
                continue;
            }
            if (!FulfillmentMode.fromString(order.fulfillment()).needsVehicleDispatch()) {
                continue; // 真人自提不进调度发车（系统/手动均不代发）
            }
            byTargetWarehouse.computeIfAbsent(order.targetDockPos(), ignored -> new ArrayList<>()).add(order);
        }
        List<DispatchCandidate> candidates = new ArrayList<>();
        for (Map.Entry<BlockPos, List<PurchaseOrder>> entry : byTargetWarehouse.entrySet()) {
            collectDispatchCandidates(market, sourceWarehouse, entry.getKey(), entry.getValue(), player, candidates);
        }
        for (DispatchCandidate candidate : rankedDispatchCandidates(candidates)) {
            if (dispatchShipmentPlan(
                    shipperUuid,
                    shipperName,
                    player,
                    candidate.carrier(),
                    candidate.sourceTerminal(),
                    market,
                    candidate.shipmentPlan(),
                    candidate.terminalKind()
            )) {
                return true;
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
        UUID buyerId = parseUuid(order.buyerUuid());
        if (buyerId == null || !warehouse.insertCargo(buyerId, cargo)) {
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
                "CLAIMED",
                order.fulfillment(),
                order.targetWarehousePos()
        ));
        ProcurementService.markDeliveredByOrder(level, order.orderId(), "", warehouse.getTownId(),
                CommodityKeyResolver.resolve(listing.itemStack()), order.quantity());
        return true;
    }

    @Nullable
    private DispatchTerminalPlan resolveDispatchTerminalPlan(TownWarehouseBlockEntity sourceWarehouse, BlockPos targetWarehousePos,
                                                             TransportTerminalKind terminalKind, @Nullable Player player) {
        return resolveDispatchTerminalPlan(sourceWarehouse, targetWarehousePos, terminalKind, player, true);
    }

    /**
     * 解析一对终端的派发计划。{@code requireAvailableBoat=true}（派发用）要求源终端当前有空闲船/车；
     * {@code false}（仅探测路网/航线连通性用）跳过有车检查——卖家暂无空闲车不代表线路不通，订单可进队列等车。
     */
    private DispatchTerminalPlan resolveDispatchTerminalPlan(TownWarehouseBlockEntity sourceWarehouse, BlockPos targetWarehousePos,
                                                             TransportTerminalKind terminalKind, @Nullable Player player,
                                                             boolean requireAvailableBoat) {
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
            if (requireAvailableBoat && availableDispatchBoats(sourceTerminal, player).isEmpty()) {
                continue;
            }
            for (DockBlockEntity targetTerminal : targetTerminals) {
                if (terminalKind == TransportTerminalKind.POST_STATION) {
                    if (!(sourceTerminal instanceof PostStationBlockEntity sourceStation)
                            || !(targetTerminal instanceof PostStationBlockEntity targetStation)
                            || !(level instanceof ServerLevel serverLevel)) {
                        continue;
                    }
                    LandTransportNetworkService service = new LandTransportNetworkService();
                    LandTransportNetworkService.RouteAvailability availability = service.planRouteToTown(
                            serverLevel,
                            service.stationRef(level, sourceStation),
                            targetWarehouse.getTownId(),
                            ALLOW_TERRAIN_FALLBACK_FOR_POST_STATION_DISPATCH
                    );
                    if (!availability.reachable() || availability.plan() == null) {
                        continue;
                    }
                    LandTransportNetworkService.LandRoutePlan landPlan = availability.plan();
                    if (!landPlan.targetStationPos().equals(targetStation.getBlockPos())) {
                        continue;
                    }
                    double pairScore = Vec3.atCenterOf(sourceWarehouse.getBlockPos()).distanceToSqr(Vec3.atCenterOf(sourceTerminal.getBlockPos()))
                            + Vec3.atCenterOf(targetWarehouse.getBlockPos()).distanceToSqr(Vec3.atCenterOf(targetTerminal.getBlockPos()))
                            + landPlan.distanceMeters();
                    if (best == null || pairScore < best.pairScore()) {
                        best = new DispatchTerminalPlan(sourceTerminal, targetTerminal, -1, landPlan.route(), landPlan, pairScore);
                    }
                    continue;
                }
                int routeIndex = sourceTerminal.findRouteIndexByDestinationDock(targetTerminal.getBlockPos(), targetTerminal.getDockName());
                if (routeIndex < 0) {
                    continue;
                }
                double pairScore = Vec3.atCenterOf(sourceWarehouse.getBlockPos()).distanceToSqr(Vec3.atCenterOf(sourceTerminal.getBlockPos()))
                        + Vec3.atCenterOf(targetWarehouse.getBlockPos()).distanceToSqr(Vec3.atCenterOf(targetTerminal.getBlockPos()));
                if (best == null || pairScore < best.pairScore()) {
                    best = new DispatchTerminalPlan(sourceTerminal, targetTerminal, routeIndex, null, null, pairScore);
                }
            }
        }
        return best;
    }

    /** 三模式可达性：③卖家发货、②自动自提、①真人自提（恒可达）。供两端弹窗置灰。 */
    public ModeReachability probeFulfillmentModes(String buyerUuid, @Nullable BlockPos targetWarehousePos, @Nullable Player player) {
        boolean realPickup = true; // 玩家自己驾车去取，恒可达
        if (level == null || level.isClientSide || targetWarehousePos == null || targetWarehousePos.equals(BlockPos.ZERO)) {
            return new ModeReachability(false, false, realPickup);
        }
        TownWarehouseBlockEntity sellerWarehouse = getLinkedWarehouse();
        // 只探路网/航线连通性（requireAvailableBoat=false）：卖家暂无空闲车不代表线路不通，订单可进队列等车。
        boolean sellerShip = sellerWarehouse != null && (
                resolveDispatchTerminalPlan(sellerWarehouse, targetWarehousePos, TransportTerminalKind.PORT, player, false) != null
                || resolveDispatchTerminalPlan(sellerWarehouse, targetWarehousePos, TransportTerminalKind.POST_STATION, player, false) != null);
        // ② 自动自提：买家空驶去源仓装货再回收货仓，路网/航线可达性判据同 ③（同一对终端的真路由存在性）。
        boolean autoPickup = sellerShip;
        return new ModeReachability(sellerShip, autoPickup, realPickup);
    }

    public record ModeReachability(boolean sellerShip, boolean autoPickup, boolean realPickup) {
    }

    private boolean dispatchBestSelectedOrder(String shipperUuid, String shipperName, @Nullable Player player,
                                             TownWarehouseBlockEntity sourceWarehouse, MarketSavedData market,
                                             PurchaseOrder order) {
        if (sourceWarehouse == null || market == null || order == null) {
            return false;
        }
        for (DispatchCandidate candidate : dispatchCandidates(market, sourceWarehouse, order.targetDockPos(), List.of(order), player)) {
            if (dispatchShipmentPlan(
                    shipperUuid,
                    shipperName,
                    player,
                    candidate.carrier(),
                    candidate.sourceTerminal(),
                    market,
                    candidate.shipmentPlan(),
                    candidate.terminalKind()
            )) {
                return true;
            }
        }
        return false;
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
        for (TransportEntity boat : availableDispatchBoats(terminalPlan.sourceTerminal(), player)) {
            ShipmentPlan shipmentPlan = buildShipmentPlanForWarehouseTarget(
                    market,
                    boat,
                    terminalPlan.routeIndex(),
                    terminalPlan.generatedRoute(),
                    terminalPlan.landPlan(),
                    terminalPlan.targetTerminal(),
                    List.of(order)
            );
            if (shipmentPlan != null && dispatchShipmentPlan(shipperUuid, shipperName, player, boat, terminalPlan.sourceTerminal(), market, shipmentPlan, terminalKind)) {
                return true;
            }
        }
        return false;
    }

    private List<DispatchCandidate> dispatchCandidates(MarketSavedData market, TownWarehouseBlockEntity sourceWarehouse,
                                                       BlockPos targetWarehousePos, List<PurchaseOrder> orders,
                                                       @Nullable Player player) {
        List<DispatchCandidate> candidates = new ArrayList<>();
        collectDispatchCandidates(market, sourceWarehouse, targetWarehousePos, orders, player, candidates);
        return rankedDispatchCandidates(candidates);
    }

    private void collectDispatchCandidates(MarketSavedData market, TownWarehouseBlockEntity sourceWarehouse,
                                           BlockPos targetWarehousePos, List<PurchaseOrder> orders,
                                           @Nullable Player player, List<DispatchCandidate> candidates) {
        if (market == null || sourceWarehouse == null || targetWarehousePos == null || orders == null || orders.isEmpty() || candidates == null) {
            return;
        }
        collectDispatchCandidatesForKind(market, sourceWarehouse, targetWarehousePos, orders, player, TransportTerminalKind.PORT, candidates);
        collectDispatchCandidatesForKind(market, sourceWarehouse, targetWarehousePos, orders, player, TransportTerminalKind.POST_STATION, candidates);
    }

    private void collectDispatchCandidatesForKind(MarketSavedData market, TownWarehouseBlockEntity sourceWarehouse,
                                                  BlockPos targetWarehousePos, List<PurchaseOrder> orders,
                                                  @Nullable Player player, TransportTerminalKind terminalKind,
                                                  List<DispatchCandidate> candidates) {
        DispatchTerminalPlan terminalPlan = resolveDispatchTerminalPlan(sourceWarehouse, targetWarehousePos, terminalKind, player);
        if (terminalPlan == null) {
            return;
        }
        for (TransportEntity carrier : availableDispatchBoats(terminalPlan.sourceTerminal(), player)) {
            ShipmentPlan shipmentPlan = buildShipmentPlanForWarehouseTarget(
                    market,
                    carrier,
                    terminalPlan.routeIndex(),
                    terminalPlan.generatedRoute(),
                    terminalPlan.landPlan(),
                    terminalPlan.targetTerminal(),
                    orders
            );
            if (shipmentPlan == null) {
                continue;
            }
            int distanceMeters = shipmentDistanceMeters(terminalPlan.sourceTerminal(), shipmentPlan);
            MarketDispatchPlanner.DispatchChoice choice = new MarketDispatchPlanner.DispatchChoice(
                    dispatchChoiceId(terminalKind, targetWarehousePos, carrier),
                    terminalKind,
                    true,
                    shipmentEtaSeconds(terminalKind, shipmentPlan, distanceMeters),
                    distanceMeters,
                    terminalPlan.pairScore(),
                    candidates.size()
            );
            candidates.add(new DispatchCandidate(choice, terminalKind, carrier, terminalPlan.sourceTerminal(), shipmentPlan));
        }
    }

    private List<DispatchCandidate> rankedDispatchCandidates(List<DispatchCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<MarketDispatchPlanner.DispatchChoice> rankedChoices =
                MarketDispatchPlanner.rankedChoices(candidates.stream().map(DispatchCandidate::choice).toList());
        List<DispatchCandidate> rankedCandidates = new ArrayList<>();
        for (MarketDispatchPlanner.DispatchChoice choice : rankedChoices) {
            for (DispatchCandidate candidate : candidates) {
                if (candidate.choice().equals(choice)) {
                    rankedCandidates.add(candidate);
                    break;
                }
            }
        }
        return List.copyOf(rankedCandidates);
    }

    private List<MarketOverviewData.DispatchOption> buildAvailableDispatchOptions(@Nullable TownWarehouseBlockEntity sourceWarehouse,
                                                                                  @Nullable Player player) {
        if (sourceWarehouse == null) {
            return List.of();
        }
        List<MarketOverviewData.DispatchOption> options = new ArrayList<>();
        collectAvailableDispatchOptions(sourceWarehouse, TransportTerminalKind.PORT, player, options);
        collectAvailableDispatchOptions(sourceWarehouse, TransportTerminalKind.POST_STATION, player, options);
        return List.copyOf(options);
    }

    private void collectAvailableDispatchOptions(TownWarehouseBlockEntity sourceWarehouse,
                                                 TransportTerminalKind terminalKind,
                                                 @Nullable Player player,
                                                 List<MarketOverviewData.DispatchOption> options) {
        if (sourceWarehouse == null || terminalKind == null || options == null) {
            return;
        }
        for (DockBlockEntity terminal : terminalsForTown(sourceWarehouse.getTownId(), terminalKind)) {
            List<TransportEntity> carriers = availableDispatchBoats(terminal, player);
            for (TransportEntity carrier : carriers) {
                options.add(new MarketOverviewData.DispatchOption(
                        terminalKind.name(),
                        dispatchTerminalPreviewLabel(terminal, terminalKind),
                        carrier.getTransportName().getString(),
                        "",
                        terminal.getDockName(),
                        "",
                        0,
                        0,
                        true,
                        "Ready",
                        terminal.getDockName()
                ));
            }
        }
    }

    private String dispatchTerminalPreviewLabel(DockBlockEntity terminal, TransportTerminalKind terminalKind) {
        String terminalName = terminal == null ? "" : terminal.getDockName();
        if (terminalName != null && !terminalName.isBlank()) {
            return terminalName;
        }
        return terminalKind == TransportTerminalKind.POST_STATION ? "Post Station" : "Port";
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
            List<TransportEntity> boats = availableDispatchBoats(sourceTerminal, player);
            for (DockBlockEntity targetTerminal : targetTerminals) {
                RoutePreview preview = resolveRoutePreview(sourceWarehouse, targetWarehouse, sourceTerminal, targetTerminal, terminalKind);
                if (preview == null) {
                    continue;
                }
                int etaSeconds = preview.landPlan() == null
                        ? estimateEtaSeconds(terminalKind, preview.distanceMeters())
                        : preview.landPlan().etaSeconds();
                String detail = preview.landPlan() == null
                        ? sourceTerminal.getDockName() + " -> " + targetTerminal.getDockName()
                        : landDispatchDetail(preview.landPlan());
                double pairScore = Vec3.atCenterOf(sourceWarehouse.getBlockPos()).distanceToSqr(Vec3.atCenterOf(sourceTerminal.getBlockPos()))
                        + Vec3.atCenterOf(targetWarehouse.getBlockPos()).distanceToSqr(Vec3.atCenterOf(targetTerminal.getBlockPos()))
                        + preview.distanceMeters();
                DispatchPreviewPlan candidate = new DispatchPreviewPlan(
                        sourceTerminal,
                        targetTerminal,
                        boats.isEmpty() ? "-" : boats.get(0).getTransportName().getString(),
                        preview.routeName(),
                        preview.distanceMeters(),
                        etaSeconds,
                        !boats.isEmpty(),
                        boats.isEmpty() ? "No vehicle" : "Ready",
                        detail,
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
        if (terminalKind == TransportTerminalKind.POST_STATION) {
            if (!(sourceTerminal instanceof PostStationBlockEntity sourceStation)
                    || !(targetTerminal instanceof PostStationBlockEntity targetStation)
                    || !(level instanceof ServerLevel serverLevel)) {
                return null;
            }
            LandTransportNetworkService service = new LandTransportNetworkService();
            LandTransportNetworkService.RouteAvailability availability = service.planRouteToTown(
                    serverLevel,
                    service.stationRef(level, sourceStation),
                    targetWarehouse.getTownId(),
                    ALLOW_TERRAIN_FALLBACK_FOR_POST_STATION_DISPATCH
            );
            if (!availability.reachable() || availability.plan() == null) {
                return null;
            }
            LandTransportNetworkService.LandRoutePlan plan = availability.plan();
            if (!plan.targetStationPos().equals(targetStation.getBlockPos())) {
                return null;
            }
            return new RoutePreview(plan.route().name(), plan.distanceMeters(), true, plan);
        }
        int routeIndex = sourceTerminal.findRouteIndexByDestinationDock(targetTerminal.getBlockPos(), targetTerminal.getDockName());
        if (routeIndex >= 0) {
            RouteDefinition route = routeIndex < sourceTerminal.getRoutesForMap().size() ? sourceTerminal.getRoutesForMap().get(routeIndex) : null;
            int distanceMeters = route == null ? 0 : (int) Math.round(estimateRouteLength(route));
            return new RoutePreview(sourceTerminal.getRouteName(routeIndex), Math.max(distanceMeters, 0), false, null);
        }
        return null;
    }

    public static String landDispatchDetail(@Nullable LandTransportNetworkService.LandRoutePlan plan) {
        if (plan == null) {
            return "";
        }
        String source = landTownName(plan.sourceStation());
        String target = landTownName(plan.targetStation());
        if (source.isBlank() || target.isBlank()) {
            return plan.route().name();
        }
        if (plan.passThroughTownNames().isEmpty()) {
            return source + " -> " + target;
        }
        return source + " via " + String.join(" > ", plan.passThroughTownNames()) + " -> " + target;
    }

    public static boolean allowTerrainFallbackForPostStationDispatchForTest() {
        return ALLOW_TERRAIN_FALLBACK_FOR_POST_STATION_DISPATCH;
    }

    private static String landTownName(@Nullable LandTransportNetworkService.StationRef station) {
        if (station == null) {
            return "";
        }
        if (!station.townName().isBlank()) {
            return station.townName();
        }
        if (!station.stationName().isBlank()) {
            return station.stationName();
        }
        return station.townId();
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

    private boolean canViewerReachListing(String playerUuid, MarketListing listing) {
        TownWarehouseBlockEntity warehouse = getLinkedWarehouse();
        if (warehouse == null) {
            return false;
        }
        String viewerTownId = DockTownResolver.resolveTownForArrival(level, warehouse.getBlockPos(), warehouse.getTownId());
        return isListingVisibleToTown(viewerTownId, listing);
    }

    private boolean isListingVisibleToTown(String viewerTownId, MarketListing listing) {
        if (level == null || viewerTownId == null || viewerTownId.isBlank() || listing == null) {
            return false;
        }
        String sourceTownId = DockTownResolver.resolveTownForSource(level, listing.sourceDockPos(), listing.townId());
        if (sourceTownId == null || sourceTownId.isBlank()) {
            return false;
        }
        if (viewerTownId.equals(sourceTownId)) {
            return true;
        }
        return hasPortRoute(sourceTownId, viewerTownId) || hasLandRoute(sourceTownId, viewerTownId);
    }

    private boolean hasPortRoute(String sourceTownId, String targetTownId) {
        List<DockBlockEntity> sourcePorts = terminalsForTown(sourceTownId, TransportTerminalKind.PORT);
        List<DockBlockEntity> targetPorts = terminalsForTown(targetTownId, TransportTerminalKind.PORT);
        for (DockBlockEntity source : sourcePorts) {
            for (DockBlockEntity target : targetPorts) {
                if (source.findRouteIndexByDestinationDock(target.getBlockPos(), target.getDockName()) >= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasLandRoute(String sourceTownId, String targetTownId) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        LandTransportNetworkService service = new LandTransportNetworkService();
        List<DockBlockEntity> sourceStations = terminalsForTown(sourceTownId, TransportTerminalKind.POST_STATION);
        for (DockBlockEntity source : sourceStations) {
            if (!(source instanceof PostStationBlockEntity station)) {
                continue;
            }
            LandTransportNetworkService.RouteAvailability availability = service.planRouteToTown(
                    serverLevel,
                    service.stationRef(level, station),
                    targetTownId,
                    ALLOW_TERRAIN_FALLBACK_FOR_POST_STATION_DISPATCH
            );
            if (availability.reachable()) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private ShipmentPlan buildShipmentPlanForWarehouseTarget(MarketSavedData market, TransportEntity boat, int routeIndex,
                                                             @Nullable RouteDefinition generatedRoute,
                                                             @Nullable LandTransportNetworkService.LandRoutePlan landPlan,
                                                             DockBlockEntity targetTerminal, List<PurchaseOrder> orders) {
        if (market == null || boat == null || targetTerminal == null || orders == null || orders.isEmpty()
                || (routeIndex < 0 && generatedRoute == null)) {
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
        return new ShipmentPlan(routeIndex, generatedRoute, landPlan, targetTerminal.getBlockPos(), targetTerminal.getDockName(), cargo, selections);
    }

    private String dispatchChoiceId(TransportTerminalKind terminalKind, BlockPos targetWarehousePos, TransportEntity carrier) {
        String kind = terminalKind == null ? TransportTerminalKind.AUTO.name() : terminalKind.name();
        String target = targetWarehousePos == null ? "0" : Long.toString(targetWarehousePos.asLong());
        String carrierId = carrier == null || carrier.getTransportUuid() == null ? "" : carrier.getTransportUuid().toString();
        return kind + ":" + target + ":" + carrierId;
    }

    private int shipmentDistanceMeters(DockBlockEntity sourceDock, ShipmentPlan plan) {
        if (plan == null) {
            return 0;
        }
        if (plan.landPlan() != null) {
            return plan.landPlan().distanceMeters();
        }
        return (int) Math.round(estimateRouteLength(routeForShipment(sourceDock, plan)));
    }

    private int shipmentEtaSeconds(TransportTerminalKind terminalKind, ShipmentPlan plan, int distanceMeters) {
        if (plan != null && plan.landPlan() != null) {
            return plan.landPlan().etaSeconds();
        }
        return estimateEtaSeconds(terminalKind == null ? TransportTerminalKind.PORT : terminalKind, distanceMeters);
    }

    private boolean dispatchShipmentPlan(String shipperUuid, String shipperName, @Nullable Player player, TransportEntity boat, DockBlockEntity sourceDock,
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
        RouteDefinition route = routeForShipment(sourceDock, plan);
        int distanceMeters = shipmentDistanceMeters(sourceDock, plan);
        int etaSeconds = shipmentEtaSeconds(terminalKind, plan, distanceMeters);
        String routeName = route == null || route.name().isBlank() ? sourceDock.getRouteName(plan.routeIndex()) : route.name();
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
                    boat.getTransportUuid().toString(),
                    boat.getTransportName().getString(),
                    "OWN",
                    terminalKind == null ? TransportTerminalKind.PORT.name() : terminalKind.name(),
                    routeName,
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
        if (!routeLoadedVehicleToTarget(boat, sourceDock, plan.generatedRoute(), plan.landPlan(), plan.routeIndex(), player)) {
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
                    "IN_TRANSIT",
                    order.fulfillment(),
                    order.targetWarehousePos()
            ));
            if (selection.remainderOrder() != null) {
                market.putPurchaseOrder(selection.remainderOrder());
            }
        }
        applyListingReservationDeltas(market, listingReservationDeltas);
        for (ShippingOrder shippingOrder : shippingOrders) {
            market.putShippingOrder(shippingOrder);
            ShippingTraceService.createOrUpdateTrace(level, shippingOrder, route);
            ProcurementService.markInTransit(level, shippingOrder.purchaseOrderId(), shippingOrder.shippingOrderId());
        }
        return true;
    }

    /**
     * 把"已装好货"的载具从产地终端发往目标。只负责发车（路线编排 + 自动驾驶启动），不再装货，
     * 供 SELLER_SHIP 调度与自动自提续发共用。失败时回滚临时货与待派状态。
     *
     * @param generatedRoute 临时生成路线（陆运/驿站走这条，配 landPlan）；为 null 表示走源终端既有航线索引
     * @param landPlan       陆运任务计划（仅 generatedRoute != null 时使用）
     * @param routeIndex     源终端既有航线索引（仅 generatedRoute == null 时使用，港口）
     * @return 是否成功发车
     */
    private boolean routeLoadedVehicleToTarget(TransportEntity boat, DockBlockEntity sourceDock,
                                               @Nullable RouteDefinition generatedRoute,
                                               @Nullable LandTransportNetworkService.LandRoutePlan landPlan,
                                               int routeIndex, @Nullable Player player) {
        if (boat == null || sourceDock == null) {
            return false;
        }
        if (generatedRoute != null) {
            if (!(boat instanceof CarriageEntity carriage)) {
                clearTemporaryShipmentCargo(boat);
                boat.clearPendingMarketDelivery();
                return false;
            }
            boat.setRouteCatalog(List.of(generatedRoute), 0, sourceDock.getBlockPos());
            carriage.setLandTransportTask(landPlan, true, CarriageEntity.TransportTaskKind.MARKET_ORDER);
            if (!boat.startAutopilotFromRouteStart()) {
                clearTemporaryShipmentCargo(boat);
                boat.clearPendingMarketDelivery();
                return false;
            }
        } else if (!sourceDock.assignLoadedBoatToRouteIndex(boat, routeIndex, true, player)) {
            clearTemporaryShipmentCargo(boat);
            boat.clearPendingMarketDelivery();
            return false;
        }
        return true;
    }

    /**
     * 进-zone 装货核心（真人自提=玩家车触发；自动自提=系统车触发）。
     * 找车主买家在本产地（本市场 linkedDockPos）的 PICKUP_LOCKED 自提订单，按 listing 模板生成货装车
     * （splitCargo + splitOrderForShipment 拆单，与 SELLER_SHIP 同一装货模型——货已在上架时扣出仓库，此处按模板兑现），
     * 装上的订单转 IN_TRANSIT + 设 manifest（装不下的留余量继续锁定）。
     *
     * @param boat 进入本产地终端 zone 的载具
     * @return 是否装了货（供自动自提判断"装完续发收货仓"，见 Task5）
     */
    public boolean tryLoadPickupCargo(TransportEntity boat) {
        if (level == null || level.isClientSide || boat == null || linkedDockPos == null) {
            return false;
        }
        String buyerUuid = boat.getOwnerUuid();
        if (buyerUuid == null || buyerUuid.isBlank()) {
            return false;
        }
        MarketSavedData market = MarketSavedData.get(level);
        List<PurchaseOrder> lockedOrders = pickupOrdersForBuyerAtThisSource(market, buyerUuid);
        if (lockedOrders.isEmpty()) {
            return false;
        }
        List<ItemStack> cargo = new ArrayList<>();
        List<ShipmentOrderSelection> selections = new ArrayList<>();
        for (PurchaseOrder order : lockedOrders) {
            MarketListing listing = findListingById(market, order.listingId());
            if (listing == null) {
                continue;
            }
            int shippable = findMaxLoadableQuantity(boat, cargo, listing.itemStack(), order.quantity());
            if (shippable <= 0) {
                if (!cargo.isEmpty()) {
                    break; // 车满了，停止继续装
                }
                continue;
            }
            PurchaseSplit split = splitOrderForShipment(market, order, shippable);
            if (split == null) {
                continue;
            }
            cargo.addAll(splitCargo(listing.itemStack(), split.shipped().quantity()));
            selections.add(new ShipmentOrderSelection(split.shipped(), split.remainder(), listing));
        }
        if (cargo.isEmpty() || selections.isEmpty()) {
            return false;
        }
        if (!boat.canLoadCargo(cargo) || !boat.loadCargo(cargo)) {
            return false;
        }
        List<ShipmentManifestEntry> manifest = applyPickupSelections(market, selections);
        boat.setPendingShipmentManifest(manifest);
        forwardAutoPickupIfNeeded(boat, selections);
        return true;
    }

    /**
     * 自动自提续发：货已装在买家自己的车上、停在产地终端；若装上的订单含 AUTO_PICKUP，
     * 把车自动发往该订单的买家收货仓（复用 SELLER_SHIP 的终端/路线规划 + 已装货发车逻辑）。
     * 真人自提（REAL_PICKUP）不续发——玩家自己把车开走。续发失败不回滚装货（货仍在车上，
     * 玩家可手动驾驶送达），仅记为未自动发车。
     */
    private void forwardAutoPickupIfNeeded(TransportEntity boat, List<ShipmentOrderSelection> selections) {
        if (level == null || level.isClientSide || boat == null || selections == null || selections.isEmpty()) {
            return;
        }
        PurchaseOrder autoOrder = null;
        for (ShipmentOrderSelection selection : selections) {
            PurchaseOrder order = selection.dispatchOrder();
            if (FulfillmentMode.fromString(order.fulfillment()) == FulfillmentMode.AUTO_PICKUP) {
                autoOrder = order;
                break;
            }
        }
        if (autoOrder == null || autoOrder.targetWarehousePos() == null) {
            return;
        }
        TownWarehouseBlockEntity sourceWarehouse = getLinkedWarehouse();
        if (sourceWarehouse == null) {
            return;
        }
        TransportTerminalKind terminalKind = boat instanceof CarriageEntity
                ? TransportTerminalKind.POST_STATION
                : TransportTerminalKind.PORT;
        DispatchTerminalPlan terminalPlan = resolveDispatchTerminalPlan(
                sourceWarehouse, autoOrder.targetWarehousePos(), terminalKind, null);
        if (terminalPlan == null) {
            return;
        }
        routeLoadedVehicleToTarget(
                boat,
                terminalPlan.sourceTerminal(),
                terminalPlan.generatedRoute(),
                terminalPlan.landPlan(),
                terminalPlan.routeIndex(),
                null);
    }

    /**
     * 自动自提空驶调度（后台 tick 入口）：扫描本产地（linkedDockPos）所有 AUTO_PICKUP + PICKUP_LOCKED 订单，
     * 对每个买家——若其空闲车已在产地终端 zone，则交给 Task4 的进-zone 触发装货，不重复发车；
     * 否则把买家在其他终端的空闲空车空驶到产地终端（到产地后由 Task4 装货、Task5 续发）。无可调度车则留队列。
     * 自提单非 WAITING_SHIPMENT，不会被 SELLER_SHIP 链路（tryDispatchWaitingOrders）认领，故此处独立处理。
     */
    private void dispatchAutoPickup(MarketSavedData market) {
        if (level == null || level.isClientSide || linkedDockPos == null || market == null) {
            return;
        }
        TownWarehouseBlockEntity sourceWarehouse = getLinkedWarehouse();
        if (sourceWarehouse == null) {
            return;
        }
        Set<String> buyers = new LinkedHashSet<>();
        for (PurchaseOrder order : market.getPickupOrdersForSourceDock(linkedDockPos)) {
            if (FulfillmentMode.fromString(order.fulfillment()) != FulfillmentMode.AUTO_PICKUP) {
                continue;
            }
            String buyerUuid = order.buyerUuid();
            if (buyerUuid != null && !buyerUuid.isBlank()) {
                buyers.add(buyerUuid);
            }
        }
        for (String buyerUuid : buyers) {
            deadheadBuyerVehicleToSource(sourceWarehouse, buyerUuid);
        }
    }

    /**
     * 把指定买家的一辆空闲空车空驶到本产地终端。港口、驿站两类终端各试一遍：
     * 若该买家车已停在任一产地终端 zone（availableBuyerVehiclesForPickup 非空），说明 Task4 会就地装货，直接返回不发车；
     * 否则枚举其他终端上属于该买家的空闲空车，规划「他处终端 → 产地终端」的有向路线并空驶过去。
     */
    private void deadheadBuyerVehicleToSource(TownWarehouseBlockEntity sourceWarehouse, String buyerUuid) {
        if (level == null || sourceWarehouse == null || buyerUuid == null || buyerUuid.isBlank()) {
            return;
        }
        for (TransportTerminalKind kind : List.of(TransportTerminalKind.PORT, TransportTerminalKind.POST_STATION)) {
            List<DockBlockEntity> sourceTerminals = terminalsForTown(sourceWarehouse.getTownId(), kind);
            if (sourceTerminals.isEmpty()) {
                continue;
            }
            boolean alreadyAtSource = false;
            for (DockBlockEntity sourceTerminal : sourceTerminals) {
                if (!sourceTerminal.availableBuyerVehiclesForPickup(buyerUuid).isEmpty()) {
                    alreadyAtSource = true;
                    break;
                }
            }
            if (alreadyAtSource) {
                continue; // 车已在产地终端，Task4 进-zone 装货接手，不空驶
            }
            deadheadFromRemoteTerminals(sourceTerminals, buyerUuid, kind);
        }
    }

    /**
     * 枚举该 kind 下所有终端，找属于买家的空闲空车，规划「该终端 → 某个产地终端」的有向路线并空驶一辆。
     * 跳过类型不符的终端、跳过产地终端本身。一次只成功空驶一辆（return），其余下个后台 tick 再处理。
     */
    private void deadheadFromRemoteTerminals(List<DockBlockEntity> sourceTerminals, String buyerUuid, TransportTerminalKind kind) {
        if (level == null || sourceTerminals == null || sourceTerminals.isEmpty() || buyerUuid == null || buyerUuid.isBlank()) {
            return;
        }
        Set<BlockPos> candidates = kind == TransportTerminalKind.POST_STATION ? PostStationRegistry.get(level) : DockRegistry.get(level);
        for (BlockPos pos : candidates) {
            if (!(level.getBlockEntity(pos) instanceof DockBlockEntity remote)) {
                continue;
            }
            if (kind == TransportTerminalKind.PORT && remote instanceof PostStationBlockEntity) {
                continue;
            }
            if (kind == TransportTerminalKind.POST_STATION && !(remote instanceof PostStationBlockEntity)) {
                continue;
            }
            BlockPos remotePos = remote.getBlockPos();
            boolean isSourceTerminal = false;
            for (DockBlockEntity sourceTerminal : sourceTerminals) {
                if (sourceTerminal.getBlockPos().equals(remotePos)) {
                    isSourceTerminal = true;
                    break;
                }
            }
            if (isSourceTerminal) {
                continue; // 产地终端本身，车若在此由 Task4 就地装货，不算空驶来源
            }
            List<TransportEntity> vehicles = remote.availableBuyerVehiclesForPickup(buyerUuid);
            if (vehicles.isEmpty()) {
                continue;
            }
            for (DockBlockEntity sourceTerminal : sourceTerminals) {
                DeadheadRoute route = planDeadheadRoute(remote, sourceTerminal, kind);
                if (route == null) {
                    continue;
                }
                if (routeLoadedVehicleToTarget(vehicles.get(0), remote, route.generatedRoute(), route.landPlan(), route.routeIndex(), null)) {
                    return; // 一次只空驶一辆，其余下个 tick 再处理
                }
            }
        }
    }

    /**
     * 规划「fromTerminal → toTerminal（=产地终端）」的有向空驶路线。返回 null 表示该对不可达。
     * 不复用 resolveDispatchTerminalPlan（它要求源终端有空闲船且自挑终端，这里源终端固定、目标固定）。
     */
    @Nullable
    private DeadheadRoute planDeadheadRoute(DockBlockEntity fromTerminal, DockBlockEntity toTerminal, TransportTerminalKind kind) {
        if (level == null || fromTerminal == null || toTerminal == null || kind == null) {
            return null;
        }
        if (kind == TransportTerminalKind.POST_STATION) {
            if (!(fromTerminal instanceof PostStationBlockEntity)
                    || !(toTerminal instanceof PostStationBlockEntity toStation)
                    || !(level instanceof ServerLevel serverLevel)) {
                return null;
            }
            String targetTownId = DockTownResolver.resolveTownForArrival(level, toStation.getBlockPos(), toStation.getTownId());
            if (targetTownId == null || targetTownId.isBlank()) {
                return null;
            }
            LandTransportNetworkService service = new LandTransportNetworkService();
            LandTransportNetworkService.RouteAvailability availability = service.planRouteToTown(
                    serverLevel,
                    service.stationRef(level, (PostStationBlockEntity) fromTerminal),
                    targetTownId,
                    ALLOW_TERRAIN_FALLBACK_FOR_POST_STATION_DISPATCH
            );
            if (!availability.reachable() || availability.plan() == null) {
                return null;
            }
            LandTransportNetworkService.LandRoutePlan landPlan = availability.plan();
            if (!landPlan.targetStationPos().equals(toStation.getBlockPos())) {
                return null;
            }
            return new DeadheadRoute(-1, landPlan.route(), landPlan);
        }
        int routeIndex = fromTerminal.findRouteIndexByDestinationDock(toTerminal.getBlockPos(), toTerminal.getDockName());
        if (routeIndex < 0) {
            return null;
        }
        return new DeadheadRoute(routeIndex, null, null);
    }

    /** 本市场产地（linkedDockPos）的该买家 PICKUP_LOCKED 自提订单。 */
    private List<PurchaseOrder> pickupOrdersForBuyerAtThisSource(MarketSavedData market, String buyerUuid) {
        List<PurchaseOrder> out = new ArrayList<>();
        for (PurchaseOrder order : market.getOrdersForBuyer(buyerUuid)) {
            if (!PickupLock.isPickupOrder(order.status(), order.fulfillment())) {
                continue;
            }
            if (linkedDockPos.equals(order.sourceDockPos())) {
                out.add(order);
            }
        }
        return out;
    }

    /**
     * 把已装车的自提 selection 落库：dispatchOrder 转 IN_TRANSIT、remainderOrder 留库（继续锁定），返回 manifest。
     * 复用 dispatchShipmentPlan 的 selection→manifest 模型，去掉 SELLER_SHIP 专有的 ShippingOrder/route 部分。
     */
    private List<ShipmentManifestEntry> applyPickupSelections(MarketSavedData market, List<ShipmentOrderSelection> selections) {
        List<ShipmentManifestEntry> manifest = new ArrayList<>();
        for (ShipmentOrderSelection selection : selections) {
            PurchaseOrder order = selection.dispatchOrder();
            manifest.add(new ShipmentManifestEntry(
                    selection.listing().listingId(),
                    selection.listing().itemStack(),
                    order.orderId(),
                    "",
                    order.buyerUuid(),
                    order.buyerName(),
                    order.quantity()
            ));
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
                    "IN_TRANSIT",
                    order.fulfillment(),
                    order.targetWarehousePos()
            ));
            if (selection.remainderOrder() != null) {
                market.putPurchaseOrder(selection.remainderOrder());
            }
        }
        return manifest;
    }

    private void clearTemporaryShipmentCargo(TransportEntity carrier) {
        if (carrier != null) {
            carrier.unloadAllCargo();
        }
    }

    @Nullable
    private RouteDefinition routeForShipment(DockBlockEntity sourceDock, ShipmentPlan plan) {
        if (plan.generatedRoute() != null) {
            return plan.generatedRoute();
        }
        if (plan.routeIndex() >= 0 && plan.routeIndex() < sourceDock.getRoutesForMap().size()) {
            return sourceDock.getRoutesForMap().get(plan.routeIndex());
        }
        return null;
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

    private int findMaxLoadableQuantity(TransportEntity boat, List<ItemStack> currentCargo, ItemStack template, int maxQuantity) {
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
                order.status(),
                order.fulfillment(),
                order.targetWarehousePos()
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
                "WAITING_SHIPMENT",
                order.fulfillment(),
                order.targetWarehousePos()
        );
        return new PurchaseSplit(shipped, remainder);
    }

    private record ShipmentPlan(int routeIndex,
                                @Nullable RouteDefinition generatedRoute,
                                @Nullable LandTransportNetworkService.LandRoutePlan landPlan,
                                BlockPos targetDockPos,
                                String targetDockName,
                                List<ItemStack> cargo,
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
                                        int routeIndex,
                                        @Nullable RouteDefinition generatedRoute,
                                        @Nullable LandTransportNetworkService.LandRoutePlan landPlan,
                                        double pairScore) {
        boolean usesGeneratedRoute() {
            return generatedRoute != null;
        }
    }

    /** 空驶有向路线：从他处终端导航到产地终端。PORT 用 routeIndex；POST_STATION 用 generatedRoute + landPlan。 */
    private record DeadheadRoute(int routeIndex,
                                @Nullable RouteDefinition generatedRoute,
                                @Nullable LandTransportNetworkService.LandRoutePlan landPlan) {
    }

    private record DispatchCandidate(MarketDispatchPlanner.DispatchChoice choice,
                                     TransportTerminalKind terminalKind,
                                     TransportEntity carrier,
                                     DockBlockEntity sourceTerminal,
                                     ShipmentPlan shipmentPlan) {
    }

    private record RoutePreview(String routeName,
                                int distanceMeters,
                                boolean autoCreated,
                                @Nullable LandTransportNetworkService.LandRoutePlan landPlan) {
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

    private boolean chargePlayer(String playerUuid, String playerName, @Nullable Player player, int amount) {
        if (amount <= 0) {
            return true;
        }
        if (player != null) {
            if (player.getAbilities().instabuild) {
                return true;
            }
        }
        return level != null
                && MarketWalletService.withdraw(level, playerUuid, playerName, amount).success();
    }

    private void paySeller(MarketSavedData market, String sellerUuid, String sellerName, int amount) {
        if (amount <= 0 || market == null || sellerUuid == null || sellerUuid.isBlank()) {
            return;
        }
        if (level != null) {
            MarketWalletService.deposit(level, sellerUuid, sellerName, amount);
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

    private ListingPriceWindow listingPriceWindow(ItemStack stack, int quantity, int requestedUnitPrice) {
        // pricing: protection band is reference-price +/-50%; reference falls back trade-avg -> lowest ask -> basePrice
        int lowestAsk = (level == null || level.isClientSide)
                ? 0
                : MarketSavedData.get(level).lowestActiveAsk(CommodityKeyResolver.resolve(stack));
        int referenceUnitPrice = COMMODITY_MARKET.referencePrice(stack, lowestAsk);
        return MarketPricePolicy.referencePriceWindow(referenceUnitPrice, requestedUnitPrice);
    }

    private int currentListingUnitPrice(MarketListing listing, int quantity) {
        // pricing: listing price is fixed by the seller, never recomputed from stock/quote
        if (listing == null) {
            return 0;
        }
        return Math.max(1, listing.unitPrice());
    }

    private int currentListingTotalPrice(MarketListing listing, int quantity) {
        // pricing: total is the seller-fixed unit price times quantity, no dynamic recompute
        if (listing == null) {
            return 0;
        }
        return safeTotalPrice(listing.unitPrice(), quantity);
    }

    private int applyPriceAdjustment(int basePrice, int priceAdjustmentBp) {
        return MarketPricePolicy.applyPriceAdjustment(basePrice, priceAdjustmentBp);
    }

    private int derivePriceAdjustmentBp(int basePrice, int requestedUnitPrice) {
        return MarketPricePolicy.derivePriceAdjustmentBp(basePrice, requestedUnitPrice);
    }

    private int safeTotalPrice(int unitPrice, int quantity) {
        long total = (long) Math.max(1, unitPrice) * Math.max(1, quantity);
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
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

    private void applyCommodityDemand(MarketListing listing, int amount, int totalPrice, String buyerUuid, String buyerName) {
        if (listing == null || amount <= 0) {
            return;
        }
        String buyerNationId = "";
        if (level != null && linkedDockPos != null) {
            TownRecord buyerTown = TownService.getTownAt(level, linkedDockPos);
            buyerNationId = buyerTown == null ? "" : buyerTown.nationId();
        }
        try {
            COMMODITY_MARKET.recordTradeAtPrice(
                    listing.itemStack(),
                    MarketTradeSide.BUY,
                    amount,
                    Math.max(0, listing.unitPrice()),
                    Math.max(0, totalPrice),
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

    private List<TransportEntity> availableDispatchBoats(DockBlockEntity sourceDock, @Nullable Player player) {
        if (sourceDock == null) {
            return List.of();
        }
        if (player != null) {
            return sourceDock.getAvailableSailboatsForDispatch(player);
        }
        return sourceDock.getAvailableSailboatsForDispatch(null);
    }

    static boolean includeCarrierInOfflineDispatchOverviewForTest(UUID dockOwner, UUID carrierOwner) {
        return true;
    }

    static void addBuyOrderCommodityDisplayNamesForTest(Map<String, String> displayNames, List<CommodityDefinition> definitions) {
        addBuyOrderCommodityDisplayNames(displayNames, definitions);
    }

    private static void addBuyOrderCommodityDisplayNames(Map<String, String> displayNames, List<CommodityDefinition> definitions) {
        if (displayNames == null || definitions == null || definitions.isEmpty()) {
            return;
        }
        for (CommodityDefinition definition : definitions) {
            if (definition == null || definition.commodityKey().isBlank()) {
                continue;
            }
            String displayName = !definition.displayName().isBlank()
                    ? definition.displayName()
                    : (!definition.itemId().isBlank() ? definition.itemId() : definition.commodityKey());
            displayNames.putIfAbsent(definition.commodityKey(), displayName);
        }
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
