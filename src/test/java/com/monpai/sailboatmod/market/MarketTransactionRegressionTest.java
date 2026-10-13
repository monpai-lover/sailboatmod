package com.monpai.sailboatmod.market;

import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.block.entity.TownWarehouseBlockEntity;
import com.monpai.sailboatmod.market.wallet.MarketWalletService;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationTreasuryRecord;
import com.monpai.sailboatmod.nation.service.TaxService;
import com.monpai.sailboatmod.nation.service.TownStockpileService;
import com.monpai.sailboatmod.market.commodity.CommodityKeyResolver;
import com.mojang.authlib.GameProfile;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketTransactionRegressionTest {
    private static final String SELLER = "00000000-0000-0000-0000-000000000001";
    private static final String BUYER = "00000000-0000-0000-0000-000000000002";
    private static final BlockPos SOURCE = new BlockPos(1, 64, 1);
    private static final BlockPos TARGET = new BlockPos(20, 64, 20);
    private static final BlockPos ARRIVAL_DOCK = new BlockPos(40, 64, 40);

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staleComponentVariantListingIsRejectedWithoutRemovingEitherBook(boolean infiniteStock) throws Exception {
        StockListingFixture fixture = stockListingFixture();
        setField(MarketBlockEntity.class, fixture.market(), "infiniteStock", infiniteStock);
        String expectedB = MarketStockIdentity.fingerprint(fixture.level().registryAccess(), fixture.bookB());
        // Snapshot B was at index 1. Removing two A books reorders the actual warehouse to [B9, A8].
        fixture.bookA().setCount(8);

        MarketBlockEntity.CreateListingResult result = fixture.market().createListingFromDockStorage(
                SELLER, "Seller", 1, 3, 40, "", expectedB);

        assertFalse(result.success(), "the server must reject index 1 when it no longer identifies B");
        assertEquals("screen.sailboatmod.ui.storage_selection_lost", result.messageKey());
        assertEquals(8, fixture.level().warehouse.countMatchingStock(UUID.fromString(SELLER), fixture.bookA()));
        assertEquals(9, fixture.level().warehouse.countMatchingStock(UUID.fromString(SELLER), fixture.bookB()));
        assertTrue(MarketSavedData.get(fixture.level()).getListings().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "wrong-identity"})
    void missingOrMismatchedListingIdentityCannotRemoveStock(String expectedIdentity) throws Exception {
        StockListingFixture fixture = stockListingFixture();

        MarketBlockEntity.CreateListingResult result = fixture.market().createListingFromDockStorage(
                SELLER, "Seller", 1, 3, 40, "", expectedIdentity);

        assertFalse(result.success());
        assertEquals(10, fixture.bookA().getCount());
        assertEquals(9, fixture.bookB().getCount());
        assertTrue(MarketSavedData.get(fixture.level()).getListings().isEmpty());
    }

    @Test
    void matchingIdentityAtTheRefreshedIndexListsOnlyTheSelectedVariant() throws Exception {
        StockListingFixture fixture = stockListingFixture();
        String expectedB = MarketStockIdentity.fingerprint(fixture.level().registryAccess(), fixture.bookB());
        fixture.bookA().setCount(8);

        MarketBlockEntity.CreateListingResult result = fixture.market().createListingFromDockStorage(
                SELLER, "Seller", 0, 3, 40, "seller note", expectedB);

        assertTrue(result.success());
        assertEquals(8, fixture.bookA().getCount());
        assertEquals(6, fixture.bookB().getCount());
        MarketListing listing = MarketSavedData.get(fixture.level()).getListings().getFirst();
        assertTrue(ItemStack.isSameItemSameComponents(fixture.bookB(), listing.itemStack()));
        assertEquals(3, listing.availableCount());
        assertEquals(40, listing.unitPrice());
        assertEquals("seller note", listing.sellerNote());
        assertEquals(0, listing.reservedCount());
    }

    @Test
    void trustedSynchronousServerListingOverloadRemainsAvailable() throws Exception {
        StockListingFixture fixture = stockListingFixture();

        assertTrue(fixture.market().createListingFromDockStorage(SELLER, "Seller", 1, 3, 40, "").success());
        assertEquals(10, fixture.bookA().getCount());
        assertEquals(6, fixture.bookB().getCount());
    }

    @Test
    void validIdentityDoesNotBypassMarketOwnership() throws Exception {
        StockListingFixture fixture = stockListingFixture();
        String expectedB = MarketStockIdentity.fingerprint(fixture.level().registryAccess(), fixture.bookB());

        assertFalse(fixture.market().createListingFromDockStorage(BUYER, "Buyer", 1, 3, 40, "", expectedB).success());
        assertFalse(fixture.market().createListingFromDockStorage(BUYER, "Buyer", 1, 3, 40, "").success());
        assertEquals(10, fixture.bookA().getCount());
        assertEquals(9, fixture.bookB().getCount());
        assertEquals(1, ((Map<?, ?>) fieldValue(TownWarehouseBlockEntity.class, fixture.level().warehouse,
                "playerStorage")).size(), "an unauthorized synchronous call must not create personal storage");
        assertTrue(MarketSavedData.get(fixture.level()).getListings().isEmpty());
    }

    private StockListingFixture stockListingFixture() throws Exception {
        TestServerLevel level = newLevel();
        UUID seller = UUID.fromString(SELLER);
        ItemStack bookA = variantBook("A", 10);
        ItemStack bookB = variantBook("B", 9);
        NonNullList<ItemStack> stock = NonNullList.withSize(TownWarehouseBlockEntity.STORAGE_SIZE, ItemStack.EMPTY);
        stock.set(0, bookA);
        stock.set(1, bookB);
        setField(TownWarehouseBlockEntity.class, level.warehouse, "playerStorage", new LinkedHashMap<>(Map.of(seller, stock)));
        setField(BlockEntity.class, level.warehouse, "worldPosition", SOURCE);
        setField(TownWarehouseBlockEntity.class, level.warehouse, "townId", "");
        setField(TownWarehouseBlockEntity.class, level.warehouse, "townName", "");
        level.warehouse.setLevel(level);
        MarketBlockEntity block = marketBlock(level);
        setField(BlockEntity.class, block, "worldPosition", TARGET);
        setField(MarketBlockEntity.class, block, "ownerUuid", SELLER);
        setField(MarketBlockEntity.class, block, "ownerName", "Seller");
        setField(MarketBlockEntity.class, block, "marketName", "Market");
        setField(MarketBlockEntity.class, block, "linkedDockPos", SOURCE);
        return new StockListingFixture(level, block, bookA, bookB);
    }

    private static ItemStack variantBook(String variant, int count) {
        ItemStack stack = new ItemStack(Items.ENCHANTED_BOOK, count);
        CompoundTag components = new CompoundTag();
        components.putString("variant", variant);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(components));
        return stack;
    }

    private record StockListingFixture(TestServerLevel level, MarketBlockEntity market, ItemStack bookA, ItemStack bookB) {
    }

    @ParameterizedTest
    @ValueSource(classes = {MarketBlockEntity.class, DockBlockEntity.class})
    void partialShipmentPreservesPriceWhenIntermediateProductExceedsIntRange(Class<?> blockType) throws Exception {
        Object block = allocate(blockType);
        PurchaseOrder order = order(100, 100_000_000);
        Method split = blockType.getDeclaredMethod(
                "splitOrderForShipment", MarketSavedData.class, PurchaseOrder.class, int.class);
        split.setAccessible(true);

        Object result = split.invoke(block, new MarketSavedData(), order, 50);
        PurchaseOrder shipped = splitOrder(result, "shipped");
        PurchaseOrder remainder = splitOrder(result, "remainder");

        assertEquals(50_000_000, shipped.totalPrice());
        assertEquals(50_000_000, remainder.totalPrice());
        assertEquals(order.totalPrice(), (long) shipped.totalPrice() + remainder.totalPrice());
    }

    @Test
    void sellerUnlistingReturnsAvailableAndCancelledReservedCargo() throws Exception {
        TestServerLevel level = newLevel();
        MarketSavedData data = MarketSavedData.get(level);
        data.putListing(listing(6, 4));
        data.putPurchaseOrder(order(4, 100));
        MarketWalletService.deposit(level, SELLER, "Seller", 100L);
        TestWarehouse warehouse = level.warehouse;

        MarketBlockEntity.CancelListingResult result = marketBlock(level).cancelListingResultById(SELLER, "listing");

        assertTrue(result.success());
        assertEquals(10, warehouse.insertedQuantity, "all ten original items must return to the seller");
        assertEquals(UUID.fromString(SELLER), warehouse.recipient);
        assertEquals(PurchaseOrder.STATUS_CANCELLED, data.getPurchaseOrder("order").status());
        assertNull(data.getListing("listing"), "cancelled reservations must not remain stranded on the listing");
        assertEquals(100L, MarketWalletService.availableBalance(level, BUYER, "Buyer"));
    }

    @Test
    void sellerCanCancelSoldOutListingWithUnshippedReservations() throws Exception {
        TestServerLevel level = newLevel();
        MarketSavedData data = MarketSavedData.get(level);
        data.putListing(listing(0, 4));
        data.putPurchaseOrder(order(4, 100));
        MarketWalletService.deposit(level, SELLER, "Seller", 100L);

        assertTrue(marketBlock(level).cancelListingResultById(SELLER, "listing").success());

        assertEquals(4, level.warehouse.insertedQuantity);
        assertNull(data.getListing("listing"));
        assertEquals(PurchaseOrder.STATUS_CANCELLED, data.getPurchaseOrder("order").status());
    }

    @Test
    void repeatedRefundWithOriginalOrderSnapshotDoesNotDuplicateMoneyOrStock() throws Exception {
        TestServerLevel level = newLevel();
        MarketSavedData data = MarketSavedData.get(level);
        data.putListing(listing(6, 4));
        PurchaseOrder original = order(4, 100);
        data.putPurchaseOrder(original);
        MarketWalletService.deposit(level, SELLER, "Seller", 100L);

        MarketRefundService.refundAndReleaseOrder(level, data, original, "delivery_failed", true);
        MarketRefundService.refundAndReleaseOrder(level, data, original, "delivery_failed", true);

        assertEquals(100L, MarketWalletService.availableBalance(level, BUYER, "Buyer"));
        assertEquals(10, data.getListing("listing").availableCount());
        assertEquals(0, data.getListing("listing").reservedCount());
        assertEquals(0L, MarketWalletService.availableBalance(level, SELLER, "Seller"));
    }

    @Test
    void legacyBuyerCancellationRejectsUnrecoverablePaymentWithoutChangingStock() throws Exception {
        TestServerLevel level = newLevel();
        MarketSavedData data = MarketSavedData.get(level);
        data.putListing(listing(6, 4));
        data.putPurchaseOrder(order(4, 100));
        MarketWalletService.deposit(level, SELLER, "Seller", 99L);

        assertFalse(marketBlock(level).cancelPurchaseOrderById(BUYER, "order").success());

        assertEquals("WAITING_SHIPMENT", data.getPurchaseOrder("order").status());
        assertEquals(6, data.getListing("listing").availableCount());
        assertEquals(4, data.getListing("listing").reservedCount());
        assertEquals(0L, MarketWalletService.availableBalance(level, BUYER, "Buyer"));
        assertEquals(99L, MarketWalletService.availableBalance(level, SELLER, "Seller"));
    }

    @Test
    void legacySellerCancellationChecksAggregateRefundFundingBeforeReturningCargo() throws Exception {
        TestServerLevel level = newLevel();
        MarketSavedData data = MarketSavedData.get(level);
        data.putListing(listing(2, 8));
        PurchaseOrder first = order(4, 100);
        data.putPurchaseOrder(first);
        data.putPurchaseOrder(new PurchaseOrder("order-2", first.listingId(), BUYER, "Buyer", 4, 100,
                SOURCE, "Source", TARGET, "Target", first.status(), first.fulfillment(), TARGET));
        MarketWalletService.deposit(level, SELLER, "Seller", 100L);

        assertFalse(marketBlock(level).cancelListingResultById(SELLER, "listing").success());

        assertEquals(0, level.warehouse.insertedQuantity);
        assertEquals(2, data.getListing("listing").availableCount());
        assertEquals(8, data.getListing("listing").reservedCount());
        assertEquals("WAITING_SHIPMENT", data.getPurchaseOrder("order").status());
        assertEquals("WAITING_SHIPMENT", data.getPurchaseOrder("order-2").status());
        assertEquals(100L, MarketWalletService.availableBalance(level, SELLER, "Seller"));
        assertEquals(0L, MarketWalletService.availableBalance(level, BUYER, "Buyer"));
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void failedPhysicalDeliveryRefundsOnceWithoutRestockingActualCargo(boolean targetWarehouseFull, boolean escrowed) throws Exception {
        TestServerLevel level = newLevel();
        if (targetWarehouseFull) {
            level.targetWarehouse = allocate(TestWarehouse.class);
            level.targetWarehouse.rejectCargo = true;
        }
        MarketSavedData data = escrowed ? seedEscrow(level, 100) : MarketSavedData.get(level);
        data.putListing(listing(6, 4));
        data.putPurchaseOrder(withStatus(order(4, 100), "IN_TRANSIT"));
        data.putPurchaseOrder(new PurchaseOrder("other-order", "listing", BUYER, "Buyer", 4, 100,
                SOURCE, "Source", TARGET, "Target", "WAITING_SHIPMENT", "SELLER_SHIP", TARGET));
        if (!escrowed) {
            MarketWalletService.deposit(level, SELLER, "Seller", 100L);
        }
        DockBlockEntity dock = allocate(DockBlockEntity.class);
        dock.setLevel(level);
        Method deliver = DockBlockEntity.class.getDeclaredMethod(
                "tryDeliverManifestEntryToWarehouse", List.class, ShipmentManifestEntry.class);
        deliver.setAccessible(true);
        ShipmentManifestEntry entry = new ShipmentManifestEntry("listing", new ItemStack(Items.OAK_LOG),
                "order", "", BUYER, "Buyer", 4);
        List<ItemStack> physicalCargo = List.of(new ItemStack(Items.OAK_LOG, 4));

        assertFalse((boolean) deliver.invoke(dock, physicalCargo, entry));
        assertFalse((boolean) deliver.invoke(dock, physicalCargo, entry));

        assertEquals(PurchaseOrder.STATUS_CANCELLED, data.getPurchaseOrder("order").status());
        assertEquals(100L, MarketWalletService.availableBalance(level, BUYER, "Buyer"));
        assertEquals(0L, MarketWalletService.availableBalance(level, SELLER, "Seller"));
        assertEquals(6, data.getListing("listing").availableCount(),
                "actual cargo retained for the town fallback must not also reappear on the listing");
        assertEquals(4, data.getListing("listing").reservedCount(),
                "the dispatched order no longer reserves stock, so the other pending order's reservation must remain");
        assertEquals("WAITING_SHIPMENT", data.getPurchaseOrder("other-order").status());
        assertEquals(4, physicalCargo.getFirst().getCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ARRIVED", "CLAIMED"})
    void deliveryCallbacksKeepRefundedOrderCancelled(String callbackStatus) throws Exception {
        TestServerLevel level = newLevel();
        MarketSavedData data = MarketSavedData.get(level);
        data.putPurchaseOrder(withStatus(order(4, 100), PurchaseOrder.STATUS_CANCELLED));
        DockBlockEntity dock = allocate(DockBlockEntity.class);
        dock.setLevel(level);
        if ("ARRIVED".equals(callbackStatus)) {
            Method callback = DockBlockEntity.class.getDeclaredMethod("markArrivedOrders", String.class, String.class);
            callback.setAccessible(true);
            callback.invoke(dock, "order", "");
        } else {
            Class<?> waybillType = Class.forName(DockBlockEntity.class.getName() + "$WaybillEntry");
            Object waybill = allocate(waybillType);
            setField(waybillType, waybill, "purchaseOrderId", "order");
            setField(waybillType, waybill, "shippingOrderId", "");
            Method callback = DockBlockEntity.class.getDeclaredMethod("markClaimedOrders", waybillType);
            callback.setAccessible(true);
            callback.invoke(dock, waybill);
        }

        assertEquals(PurchaseOrder.STATUS_CANCELLED, data.getPurchaseOrder("order").status());
        assertEquals(0L, MarketWalletService.availableBalance(level, SELLER, "Seller"));
    }

    @Test
    void escrowCancellationReturnsHeldFundsWithoutChargingSellerAndConsumesReservation() throws Exception {
        TestServerLevel level = newLevel();
        MarketSavedData data = seedEscrow(level, 100);
        PurchaseOrder snapshot = data.getPurchaseOrder("order");

        MarketRefundService.refundAndReleaseOrder(level, data, snapshot, "buyer_cancel", true);
        MarketRefundService.refundAndReleaseOrder(level, data, snapshot, "buyer_cancel", true);

        assertEquals(100L, MarketWalletService.availableBalance(level, BUYER, "Buyer"));
        assertEquals(0L, MarketWalletService.availableBalance(level, SELLER, "Seller"));
        assertEquals(0, data.save(new CompoundTag()).getList("OrderEscrows", Tag.TAG_COMPOUND).size());
        assertEquals(PurchaseOrder.STATUS_CANCELLED, data.getPurchaseOrder("order").status());
        assertEquals(10, data.getListing("listing").availableCount());
    }

    @Test
    void refundKeepsEscrowAndOrderWhenBuyerWalletCannotHoldFullAmount() throws Exception {
        TestServerLevel level = newLevel();
        MarketSavedData data = seedEscrow(level, 100);
        MarketWalletService.deposit(level, BUYER, "Buyer", Long.MAX_VALUE - 99L);

        MarketRefundService.refundAndReleaseOrder(level, data, data.getPurchaseOrder("order"), "buyer_cancel", true);

        assertEquals(Long.MAX_VALUE - 99L, MarketWalletService.availableBalance(level, BUYER, "Buyer"));
        assertEquals(1, data.save(new CompoundTag()).getList("OrderEscrows", Tag.TAG_COMPOUND).size());
        assertEquals("WAITING_SHIPMENT", data.getPurchaseOrder("order").status());
        assertEquals(6, data.getListing("listing").availableCount());
        assertEquals(4, data.getListing("listing").reservedCount());
    }

    @Test
    void sellerUnlistingKeepsInTransitOrderAndItsRemainingReservation() throws Exception {
        TestServerLevel level = newLevel();
        MarketSavedData data = MarketSavedData.get(level);
        data.putListing(listing(2, 8));
        PurchaseOrder pending = order(4, 100);
        data.putPurchaseOrder(pending);
        data.putPurchaseOrder(new PurchaseOrder("in-transit", "listing", BUYER, "Buyer", 4, 100,
                SOURCE, "Source", TARGET, "Target", "IN_TRANSIT", "SELLER_SHIP", TARGET));
        MarketWalletService.deposit(level, SELLER, "Seller", 100L);

        assertTrue(marketBlock(level).cancelListingResultById(SELLER, "listing").success());

        assertEquals(6, level.warehouse.insertedQuantity);
        assertEquals(0, data.getListing("listing").availableCount());
        assertEquals(4, data.getListing("listing").reservedCount());
        assertEquals("IN_TRANSIT", data.getPurchaseOrder("in-transit").status());
        assertEquals(PurchaseOrder.STATUS_CANCELLED, data.getPurchaseOrder("order").status());
    }

    @Test
    void quotingHeldOrderTaxesDoesNotDepositCurrencyIntoEitherTreasury() throws Exception {
        TestServerLevel level = newLevel();
        NationSavedData nations = NationSavedData.get(level);
        nations.putClaim(new NationClaimRecord("minecraft:overworld", 0, 0, "export", "",
                "member", "member", "member", "member", "member", "member", "member", 1L));
        nations.putClaim(new NationClaimRecord("minecraft:overworld", 1, 1, "import", "",
                "member", "member", "member", "member", "member", "member", "member", 1L));
        nations.putTreasury(NationTreasuryRecord.empty("export").withBalance(100L));
        nations.putTreasury(NationTreasuryRecord.empty("import").withBalance(200L));

        TaxService.TaxResult sales = TaxService.quoteSalesTax(level, 1_000, SOURCE);
        TaxService.TaxResult tariff = TaxService.quoteImportTariff(level, sales.sellerReceives(), SOURCE, TARGET);

        assertTrue(sales.taxAmount() > 0);
        assertTrue(tariff.taxAmount() > 0);
        assertEquals(1_000, (long) tariff.sellerReceives() + sales.taxAmount() + tariff.taxAmount());
        assertEquals(100L, nations.getTreasury("export").currencyBalance());
        assertEquals(200L, nations.getTreasury("import").currencyBalance());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refundedPhysicalFallbackReturnsClaimableCargoToSeller(boolean escrowed) throws Exception {
        TestServerLevel level = newLevel();
        MarketSavedData data = escrowed ? seedEscrow(level, 100) : MarketSavedData.get(level);
        data.putListing(listing(6, 0));
        data.putPurchaseOrder(withStatus(order(4, 100), "IN_TRANSIT"));
        if (escrowed) {
            data.removeListing("listing"); // Dispatch can exhaust the listing; escrow retains the seller identity.
        } else {
            MarketWalletService.deposit(level, SELLER, "Seller", 100L);
        }
        TestDock dock = arrivalDock(level);
        setField(DockBlockEntity.class, dock, "townId", "buyer-town");
        setField(DockBlockEntity.class, dock, "ownerUuid", BUYER);
        ShipmentManifestEntry entry = new ShipmentManifestEntry("listing", new ItemStack(Items.OAK_LOG),
                "order", "", BUYER, "Buyer", 4);

        dock.receiveShipment(null, "Route", "Shipper", "Source", "Destination", 1L, 1L, 10D,
                List.of(new ItemStack(Items.OAK_LOG, 4)), List.of(entry));

        assertEquals(PurchaseOrder.STATUS_CANCELLED, data.getPurchaseOrder("order").status());
        assertEquals(100L, MarketWalletService.availableBalance(level, BUYER, "Buyer"));
        assertTrue(dock.copyAllStorageCargo().isEmpty(), "refunded cargo must stay out of shared dock storage");
        assertTrue(dock.hasPrivateRecoveryCargo());
        String commodity = CommodityKeyResolver.resolve(new ItemStack(Items.OAK_LOG));
        assertEquals(0, TownStockpileService.getAvailable(level, "buyer-town", commodity),
                "refunded cargo must stay out of the public town stockpile");
        Object waybill = waybills(dock).getFirst();
        assertEquals(SELLER, fieldValue(waybill.getClass(), waybill, "recipientUuid"));
        TestPlayer buyer = player(level, BUYER, "Buyer");
        assertFalse(dock.extractVisibleStorage(buyer, 0, 4));
        assertFalse(dock.takeSelectedStorage(buyer), "dock ownership cannot bypass private recovery custody");
        assertFalse(dock.claimSelectedWaybill(buyer), "refunded buyers cannot also claim the physical goods");
        TownStockpileService.add(level, "buyer-town", commodity, 2);
        TestPlayer seller = player(level, SELLER, "Seller");
        assertTrue(dock.claimSelectedWaybill(seller), "the seller must be able to recover the returned cargo");
        assertFalse(dock.hasPrivateRecoveryCargo());
        assertEquals(4, seller.getInventory().countItem(Items.OAK_LOG));
        assertEquals(0, buyer.getInventory().countItem(Items.OAK_LOG));
        assertEquals(2, TownStockpileService.getAvailable(level, "buyer-town", commodity),
                "private claims must neither require nor remove public stockpile cargo");
        assertEquals(PurchaseOrder.STATUS_CANCELLED, data.getPurchaseOrder("order").status());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void privateRecoveryCustodySurvivesDockNbtReload(boolean knownSeller) throws Exception {
        TestServerLevel level = newLevel();
        MarketSavedData data = seedEscrow(level, 100);
        if (!knownSeller) {
            data.removeEscrow("order");
            data.removeListing("listing");
        }
        data.putPurchaseOrder(withStatus(order(4, 100), knownSeller ? "IN_TRANSIT" : PurchaseOrder.STATUS_CANCELLED));
        TestDock dock = arrivalDock(level);
        setField(DockBlockEntity.class, dock, "ownerUuid", BUYER);
        ShipmentManifestEntry entry = new ShipmentManifestEntry("listing", new ItemStack(Items.OAK_LOG),
                "order", "", BUYER, "Buyer", 4);
        dock.receiveShipment(null, "Route", "Shipper", "Source", "Destination", 1L, 1L, 10D,
                List.of(new ItemStack(Items.OAK_LOG, 4)), List.of(entry));

        CompoundTag saved = dock.saveTestData(level.registryAccess());
        assertTrue(saved.getList("Waybills", Tag.TAG_COMPOUND).getCompound(0).getBoolean("PrivateRecoveryCargo"));
        TestDock reloaded = arrivalDock(level);
        reloaded.loadAdditional(saved, level.registryAccess());
        assertTrue(reloaded.hasPrivateRecoveryCargo());
        assertTrue(reloaded.copyAllStorageCargo().isEmpty());
        TestPlayer buyer = player(level, BUYER, "Buyer");
        buyer.recoveryPermission = true;
        assertFalse(reloaded.takeSelectedStorage(buyer));
        assertFalse(reloaded.claimSelectedWaybill(buyer), "refund denial must survive reload and permission level 2");
        TestPlayer administrator = player(level, "00000000-0000-0000-0000-000000000003", "Recovery");
        assertFalse(reloaded.claimSelectedWaybill(administrator));
        administrator.recoveryPermission = true;
        if (knownSeller) {
            assertFalse(reloaded.claimSelectedWaybill(administrator), "only orphaned recovery markers permit admin claims");
            TestPlayer seller = player(level, SELLER, "Seller");
            assertTrue(reloaded.claimSelectedWaybill(seller));
            assertEquals(4, seller.getInventory().countItem(Items.OAK_LOG));
        } else {
            assertTrue(reloaded.claimSelectedWaybill(administrator));
            assertEquals(4, administrator.getInventory().countItem(Items.OAK_LOG));
        }
        assertFalse(reloaded.hasPrivateRecoveryCargo());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waybillLimitNeverEvictsPrivateRecoveryCargo(boolean allPrivate) throws Exception {
        TestServerLevel level = newLevel();
        MarketSavedData data = MarketSavedData.get(level);
        data.putListing(listing(6, 0));
        TestDock dock = arrivalDock(level);
        for (int i = 0; i < 65; i++) {
            boolean privateCargo = allPrivate || i == 0;
            String orderId = "cancelled-" + i;
            PurchaseOrder cancelled = new PurchaseOrder(orderId, "listing", BUYER, "Buyer", 4, 100,
                    SOURCE, "Source", TARGET, "Target", PurchaseOrder.STATUS_CANCELLED, "SHIP", TARGET);
            data.putPurchaseOrder(cancelled);
            List<ShipmentManifestEntry> manifest = privateCargo
                    ? List.of(new ShipmentManifestEntry("listing", new ItemStack(Items.OAK_LOG),
                    orderId, "", BUYER, "Buyer", 4)) : List.of();
            dock.receiveShipment(null, "Route", "Shipper", "Source", "Destination", 1L, 1L, 10D,
                    List.of(new ItemStack(privateCargo ? Items.OAK_LOG : Items.BIRCH_LOG, 4)), manifest);
        }

        assertEquals(allPrivate ? 65 : 64, waybills(dock).size());
        assertEquals(SELLER, fieldValue(waybills(dock).getFirst().getClass(), waybills(dock).getFirst(), "recipientUuid"));
        TestPlayer seller = player(level, SELLER, "Seller");
        for (int i = 0; i < (allPrivate ? 65 : 1); i++) {
            dock.selectWaybillIndex(0);
            assertTrue(dock.claimSelectedWaybill(seller));
        }
        assertEquals(allPrivate ? 260 : 4, seller.getInventory().countItem(Items.OAK_LOG));
    }

    @Test
    void ordinaryArrivalKeepsSharedDockStorageAndLegacyCustodyDefault() throws Exception {
        TestServerLevel level = newLevel();
        TestDock dock = arrivalDock(level);
        setField(DockBlockEntity.class, dock, "ownerUuid", BUYER);
        dock.receiveShipment(null, "Route", "Shipper", "Source", "Destination", 1L, 1L, 10D,
                List.of(new ItemStack(Items.OAK_LOG, 4)));
        CompoundTag saved = dock.saveTestData(level.registryAccess());
        CompoundTag waybill = saved.getList("Waybills", Tag.TAG_COMPOUND).getCompound(0);
        assertFalse(waybill.getBoolean("PrivateRecoveryCargo"));
        waybill.remove("PrivateRecoveryCargo");
        TestDock reloaded = arrivalDock(level);
        reloaded.loadAdditional(saved, level.registryAccess());
        assertFalse(reloaded.hasPrivateRecoveryCargo(), "legacy NBT must default to shared custody");
        TestPlayer buyer = player(level, BUYER, "Buyer");

        assertTrue(reloaded.takeSelectedStorage(buyer));
        assertEquals(4, buyer.getInventory().countItem(Items.OAK_LOG));
    }

    @Test
    void unsuccessfulLegacyRefundKeepsFallbackCargoOwnedByBuyer() throws Exception {
        TestServerLevel level = newLevel();
        MarketSavedData data = MarketSavedData.get(level);
        data.putListing(listing(6, 0));
        data.putPurchaseOrder(withStatus(order(4, 100), "IN_TRANSIT"));
        TestDock dock = arrivalDock(level);
        ShipmentManifestEntry entry = new ShipmentManifestEntry("listing", new ItemStack(Items.OAK_LOG),
                "order", "", BUYER, "Buyer", 4);

        dock.receiveShipment(null, "Route", "Shipper", "Source", "Destination", 1L, 1L, 10D,
                List.of(new ItemStack(Items.OAK_LOG, 4)), List.of(entry));

        assertEquals(0L, MarketWalletService.availableBalance(level, BUYER, "Buyer"));
        Object waybill = waybills(dock).getFirst();
        assertEquals(BUYER, fieldValue(waybill.getClass(), waybill, "recipientUuid"));
        TestPlayer buyer = player(level, BUYER, "Buyer");
        assertTrue(dock.claimSelectedWaybill(buyer));
        assertEquals(4, buyer.getInventory().countItem(Items.OAK_LOG));
    }

    @Test
    void cancelledBuyerWaybillCannotHandCargoToRefundedBuyer() throws Exception {
        TestServerLevel level = newLevel();
        MarketSavedData data = MarketSavedData.get(level);
        data.putPurchaseOrder(withStatus(order(4, 100), PurchaseOrder.STATUS_CANCELLED));
        TestDock dock = arrivalDock(level);
        Class<?> waybillType = Class.forName(DockBlockEntity.class.getName() + "$WaybillEntry");
        Object waybill = allocate(waybillType);
        setField(waybillType, waybill, "purchaseOrderId", "order");
        setField(waybillType, waybill, "shippingOrderId", "");
        setField(waybillType, waybill, "recipientUuid", BUYER);
        setField(waybillType, waybill, "recipientName", "Buyer");
        setField(waybillType, waybill, "cargo", List.of(new ItemStack(Items.OAK_LOG, 4)));
        waybills(dock).add(waybill);
        TestPlayer buyer = player(level, BUYER, "Buyer");

        assertFalse(dock.claimSelectedWaybill(buyer));
        assertFalse(dock.hasPrivateRecoveryCargo(), "legacy cancelled waybills must not become private custody implicitly");

        assertEquals(0, buyer.getInventory().countItem(Items.OAK_LOG));
        assertEquals(1, waybills(dock).size(), "rejected claims must preserve the recoverable cargo");
        assertEquals(PurchaseOrder.STATUS_CANCELLED, data.getPurchaseOrder("order").status());
        TestPlayer recoveryPlayer = player(level, "00000000-0000-0000-0000-000000000003", "Recovery");
        assertFalse(dock.claimSelectedWaybill(recoveryPlayer), "unknown owners must not make recovery cargo publicly claimable");
        recoveryPlayer.recoveryPermission = true;
        assertTrue(dock.claimSelectedWaybill(recoveryPlayer), "an administrator must be able to recover orphaned legacy cargo");
        assertEquals(4, recoveryPlayer.getInventory().countItem(Items.OAK_LOG));
    }

    @Test
    void partialPickupReleasesOnlyLoadedReservationAndConservesEscrow() throws Exception {
        TestServerLevel level = newLevel();
        MarketSavedData data = seedEscrow(level, 250);
        MarketListing listing = listing(0, 10);
        data.putListing(listing);
        PurchaseOrder original = new PurchaseOrder("order", "listing", BUYER, "Buyer", 10, 250,
                SOURCE, "Source", TARGET, "Target", PickupLock.STATUS_LOCKED, "AUTO_PICKUP", TARGET);
        data.putPurchaseOrder(original);
        PurchaseOrder shipped = new PurchaseOrder("order", "listing", BUYER, "Buyer", 5, 125,
                SOURCE, "Source", TARGET, "Target", PickupLock.STATUS_LOCKED, "AUTO_PICKUP", TARGET);
        PurchaseOrder remainder = new PurchaseOrder("remainder", "listing", BUYER, "Buyer", 5, 125,
                SOURCE, "Source", TARGET, "Target", PickupLock.STATUS_LOCKED, "AUTO_PICKUP", TARGET);
        Class<?> selectionType = Class.forName(MarketBlockEntity.class.getName() + "$ShipmentOrderSelection");
        Constructor<?> selectionConstructor = selectionType.getDeclaredConstructor(
                PurchaseOrder.class, PurchaseOrder.class, MarketListing.class);
        selectionConstructor.setAccessible(true);
        Object selection = selectionConstructor.newInstance(shipped, remainder, listing);
        Method applyPickup = MarketBlockEntity.class.getDeclaredMethod("applyPickupSelections", MarketSavedData.class, List.class);
        applyPickup.setAccessible(true);

        applyPickup.invoke(marketBlock(level), data, List.of(selection));

        assertEquals(5, data.getListing("listing").reservedCount());
        assertEquals(0, data.getListing("listing").availableCount());
        assertEquals("IN_TRANSIT", data.getPurchaseOrder("order").status());
        assertEquals(PickupLock.STATUS_LOCKED, data.getPurchaseOrder("remainder").status());
        assertEquals(250, data.getEscrow("order").totalPrice() + data.getEscrow("remainder").totalPrice());
    }

    private static TestDock arrivalDock(TestServerLevel level) throws Exception {
        TestDock dock = allocate(TestDock.class);
        setField(BlockEntity.class, dock, "worldPosition", ARRIVAL_DOCK);
        setField(DockBlockEntity.class, dock, "townId", "");
        setField(DockBlockEntity.class, dock, "nationId", "");
        setField(DockBlockEntity.class, dock, "waybills", new ArrayList<>());
        setField(DockBlockEntity.class, dock, "storage", NonNullList.withSize(DockBlockEntity.STORAGE_SIZE, ItemStack.EMPTY));
        setField(DockBlockEntity.class, dock, "routes", new ArrayList<>());
        setField(DockBlockEntity.class, dock, "assignments", new LinkedHashMap<>());
        setField(DockBlockEntity.class, dock, "routeBook", ItemStack.EMPTY);
        dock.setLevel(level);
        level.arrivalDock = dock;
        return dock;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> waybills(DockBlockEntity dock) throws Exception {
        return (List<Object>) fieldValue(DockBlockEntity.class, dock, "waybills");
    }

    private static Object fieldValue(Class<?> owner, Object target, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static TestPlayer player(TestServerLevel level, String uuid, String name) throws Exception {
        TestPlayer player = allocate(TestPlayer.class);
        player.testLevel = level;
        player.profile = new GameProfile(UUID.fromString(uuid), name);
        player.inventory = new Inventory(player);
        player.containerMenu = new AbstractContainerMenu(null, 0) {
            @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
            @Override public boolean stillValid(Player player) { return true; }
            @Override public void broadcastChanges() { }
        };
        return player;
    }

    private static MarketSavedData seedEscrow(TestServerLevel level, int amount) {
        CompoundTag save = new CompoundTag();
        CompoundTag escrow = new CompoundTag();
        escrow.putString("OrderId", "order");
        escrow.putString("SellerUuid", SELLER);
        escrow.putString("SellerName", "Seller");
        escrow.putInt("TotalPrice", amount);
        escrow.putInt("SellerPayout", amount);
        escrow.putString("SalesTaxNationId", "");
        escrow.putInt("SalesTax", 0);
        escrow.putString("ImportTariffNationId", "");
        escrow.putInt("ImportTariff", 0);
        ListTag escrows = new ListTag();
        escrows.add(escrow);
        save.put("OrderEscrows", escrows);
        MarketSavedData data = MarketSavedData.load(save);
        level.dataStorage.set("sailboatmod_market", data);
        data.putListing(listing(6, 4));
        data.putPurchaseOrder(order(4, amount));
        return data;
    }

    private static PurchaseOrder withStatus(PurchaseOrder order, String status) {
        return new PurchaseOrder(order.orderId(), order.listingId(), order.buyerUuid(), order.buyerName(),
                order.quantity(), order.totalPrice(), order.sourceDockPos(), order.sourceDockName(),
                order.targetDockPos(), order.targetDockName(), status, order.fulfillment(), order.targetWarehousePos());
    }

    private static PurchaseOrder order(int quantity, int totalPrice) {
        return new PurchaseOrder("order", "listing", BUYER, "Buyer", quantity, totalPrice,
                SOURCE, "Source", TARGET, "Target", "WAITING_SHIPMENT", "SELLER_SHIP", TARGET);
    }

    private static MarketListing listing(int available, int reserved) {
        return new MarketListing("listing", SELLER, "Seller", new ItemStack(Items.OAK_LOG),
                25, available, reserved, SOURCE, "Source", "", "", 0, "");
    }

    private static PurchaseOrder splitOrder(Object split, String accessorName) throws Exception {
        Method accessor = split.getClass().getDeclaredMethod(accessorName);
        accessor.setAccessible(true);
        return (PurchaseOrder) accessor.invoke(split);
    }

    private static MarketBlockEntity marketBlock(TestServerLevel level) throws Exception {
        MarketBlockEntity block = allocate(MarketBlockEntity.class);
        block.setLevel(level);
        return block;
    }

    private TestServerLevel newLevel() throws Exception {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.dataStorage = new DimensionDataStorage(temporaryDirectory.toFile(), null, level.registryAccess());
        level.warehouse = allocate(TestWarehouse.class);
        TestMinecraftServer server = allocate(TestMinecraftServer.class);
        setField(MinecraftServer.class, server, "levels", new LinkedHashMap<>(Map.of(Level.OVERWORLD, level)));
        level.server = server;
        return level;
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (T) ((Unsafe) field.get(null)).allocateInstance(type);
    }

    private static final class TestWarehouse extends TownWarehouseBlockEntity {
        private int insertedQuantity;
        private UUID recipient;
        private boolean rejectCargo;

        private TestWarehouse() {
            super(null, null);
        }

        @Override
        public void setChanged() { }

        @Override
        public boolean insertCargo(UUID ownerId, List<ItemStack> cargo) {
            if (rejectCargo) {
                return false;
            }
            recipient = ownerId;
            insertedQuantity += cargo.stream().mapToInt(ItemStack::getCount).sum();
            return true;
        }
    }

    private static final class TestServerLevel extends ServerLevel {
        private MinecraftServer server;
        private DimensionDataStorage dataStorage;
        private TestWarehouse warehouse;
        private TestWarehouse targetWarehouse;
        private TestDock arrivalDock;

        private TestServerLevel() {
            super(null, command -> { }, null, null, null, null, null, false, 0L, List.of(), false, null);
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return SOURCE.equals(pos) ? warehouse : TARGET.equals(pos) ? targetWarehouse
                    : ARRIVAL_DOCK.equals(pos) ? arrivalDock : null;
        }

        @Override
        public MinecraftServer getServer() {
            return server;
        }

        @Override
        public DimensionDataStorage getDataStorage() {
            return dataStorage;
        }

        @Override
        public RegistryAccess registryAccess() {
            return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        }

        @Override
        public ResourceKey<Level> dimension() {
            return Level.OVERWORLD;
        }
    }

    private static final class TestDock extends DockBlockEntity {
        private TestDock() {
            super(null, null);
        }

        @Override public void setChanged() { }
        private CompoundTag saveTestData(net.minecraft.core.HolderLookup.Provider registries) {
            CompoundTag tag = new CompoundTag();
            saveAdditional(tag, registries);
            return tag;
        }
    }

    private static final class TestPlayer extends ServerPlayer {
        private TestServerLevel testLevel;
        private GameProfile profile;
        private Inventory inventory;
        private boolean recoveryPermission;

        private TestPlayer() { super(null, null, null, null); }

        @Override public Level level() { return testLevel; }
        @Override public UUID getUUID() { return profile.getId(); }
        @Override public GameProfile getGameProfile() { return profile; }
        @Override public Component getName() { return Component.literal(profile.getName()); }
        @Override public Inventory getInventory() { return inventory; }
        @Override public boolean hasPermissions(int permissionLevel) { return recoveryPermission && permissionLevel <= 2; }
        @Override public void displayClientMessage(Component message, boolean actionBar) { }
    }

    private static final class TestMinecraftServer extends MinecraftServer {
        private TestMinecraftServer() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        protected net.minecraft.util.debugchart.SampleLogger getTickTimeLogger() {
            return new net.minecraft.util.debugchart.LocalSampleLogger(net.minecraft.util.debugchart.TpsDebugDimensions.values().length);
        }

        @Override public boolean isTickTimeLoggingEnabled() { return false; }
        @Override protected boolean initServer() { return false; }
        @Override public int getOperatorUserPermissionLevel() { return 0; }
        @Override public int getFunctionCompilationLevel() { return 0; }
        @Override public boolean shouldRconBroadcast() { return false; }
        @Override public net.minecraft.SystemReport fillServerSystemReport(net.minecraft.SystemReport report) { return report; }
        @Override public boolean isDedicatedServer() { return false; }
        @Override public int getRateLimitPacketsPerSecond() { return 0; }
        @Override public boolean isEpollEnabled() { return false; }
        @Override public boolean isCommandBlockEnabled() { return false; }
        @Override public boolean isPublished() { return false; }
        @Override public boolean shouldInformAdmins() { return false; }
        @Override public boolean isSingleplayerOwner(com.mojang.authlib.GameProfile profile) { return false; }
    }
}
