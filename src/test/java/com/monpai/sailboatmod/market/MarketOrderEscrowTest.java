package com.monpai.sailboatmod.market;

import com.monpai.sailboatmod.market.wallet.MarketWalletService;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationRecord;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class MarketOrderEscrowTest {
    private static final String SELLER = "00000000-0000-0000-0000-000000000001";
    private static final String BUYER = "00000000-0000-0000-0000-000000000002";

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void pendingEscrowSurvivesSaveLoadWithAllPaymentRecipients() {
        MarketSavedData loaded = MarketSavedData.load(escrowSave(100, 85, 10, 5));

        ListTag escrows = savedEscrows(loaded);
        assertEquals(1, escrows.size());
        assertEquals("order", escrows.getCompound(0).getString("OrderId"));
        assertEquals(SELLER, escrows.getCompound(0).getString("SellerUuid"));
        assertEquals("Seller", escrows.getCompound(0).getString("SellerName"));
        assertEquals(100, escrows.getCompound(0).getInt("TotalPrice"));
        assertEquals(85, escrows.getCompound(0).getInt("SellerPayout"));
        assertEquals("sales", escrows.getCompound(0).getString("SalesTaxNationId"));
        assertEquals(10, escrows.getCompound(0).getInt("SalesTax"));
        assertEquals("import", escrows.getCompound(0).getString("ImportTariffNationId"));
        assertEquals(5, escrows.getCompound(0).getInt("ImportTariff"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ARRIVED", "CLAIMED"})
    void successfulDeliveryPaysSellerAndTaxesOnlyOnce(String status) throws Exception {
        TestLevel level = newLevel();
        MarketSavedData data = attachEscrow(level, escrowSave(100, 85, 10, 5));

        assertEquals(0L, MarketWalletService.availableBalance(level, SELLER, "Seller"));
        assertEquals(1, savedEscrows(data).size(), "loading must retain the pending escrow without paying it");
        data.putPurchaseOrder(order("order", 4, 100, status));
        data.putPurchaseOrder(order("order", 4, 100, status));

        assertEquals(85L, MarketWalletService.availableBalance(level, SELLER, "Seller"));
        assertEquals(10L, NationSavedData.get(level).getTreasury("sales").currencyBalance());
        assertEquals(5L, NationSavedData.get(level).getTreasury("import").currencyBalance());
        assertTrue(savedEscrows(data).isEmpty());
        MarketSavedData reloaded = MarketSavedData.load(data.save(new CompoundTag()));
        level.storage.set("sailboatmod_market", reloaded);
        MarketSavedData.get(level).putPurchaseOrder(order("order", 4, 100, status));
        assertEquals(85L, MarketWalletService.availableBalance(level, SELLER, "Seller"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAID", "WAITING_SHIPMENT", "IN_TRANSIT", "CANCELLED"})
    void nonDeliveryStatusKeepsFundsHeld(String status) throws Exception {
        TestLevel level = newLevel();
        MarketSavedData data = attachEscrow(level, escrowSave(100, 85, 10, 5));

        data.putPurchaseOrder(order("order", 4, 100, status));

        assertEquals(0L, MarketWalletService.availableBalance(level, SELLER, "Seller"));
        assertEquals(1, savedEscrows(data).size());
    }

    @Test
    void legacyDeliveredOrderWithoutEscrowNeverReceivesAnotherPayment() throws Exception {
        TestLevel level = newLevel();

        MarketSavedData.get(level).putPurchaseOrder(order("legacy", 4, 100, "ARRIVED"));

        assertEquals(0L, MarketWalletService.availableBalance(level, SELLER, "Seller"));
    }

    @Test
    void lateDeliveryCannotReviveAnAuthoritativelyCancelledOrder() throws Exception {
        TestLevel level = newLevel();
        MarketSavedData data = MarketSavedData.get(level);
        data.putPurchaseOrder(order("order", 4, 100, "CANCELLED"));

        data.putPurchaseOrder(order("order", 4, 100, "ARRIVED"));

        assertEquals("CANCELLED", data.getPurchaseOrder("order").status());
        assertEquals(0L, MarketWalletService.availableBalance(level, SELLER, "Seller"));
    }

    @Test
    void fullSellerWalletKeepsAllFundsHeldInsteadOfPartiallyPayingTaxes() throws Exception {
        TestLevel level = newLevel();
        MarketSavedData data = attachEscrow(level, escrowSave(100, 85, 10, 5));
        MarketWalletService.deposit(level, SELLER, "Seller", Long.MAX_VALUE);

        data.putPurchaseOrder(order("order", 4, 100, "ARRIVED"));

        assertEquals(Long.MAX_VALUE, MarketWalletService.availableBalance(level, SELLER, "Seller"));
        assertTrue(NationSavedData.get(level).getTreasury("sales") == null);
        assertTrue(NationSavedData.get(level).getTreasury("import") == null);
        assertEquals(1, savedEscrows(data).size());
    }

    @Test
    void reloadedDeliveredEscrowRetriesWhenSellerCanReceivePayment() throws Exception {
        TestLevel level = newLevel();
        MarketSavedData data = attachEscrow(level, escrowSave(100, 85, 10, 5));
        MarketWalletService.deposit(level, SELLER, "Seller", Long.MAX_VALUE);
        data.putPurchaseOrder(order("order", 4, 100, "ARRIVED"));

        MarketSavedData loaded = MarketSavedData.load(data.save(new CompoundTag()));
        level.storage.set("sailboatmod_market", loaded);
        assertEquals(Long.MAX_VALUE, MarketWalletService.availableBalance(level, SELLER, "Seller"));
        assertEquals(1, savedEscrows(loaded).size(), "static load must keep failed settlement pending");
        assertTrue(MarketWalletService.withdraw(level, SELLER, "Seller", 100L).success());

        MarketSavedData retried = MarketSavedData.get(level);

        assertEquals(Long.MAX_VALUE - 15L, MarketWalletService.availableBalance(level, SELLER, "Seller"));
        assertEquals(10L, NationSavedData.get(level).getTreasury("sales").currencyBalance());
        assertEquals(5L, NationSavedData.get(level).getTreasury("import").currencyBalance());
        assertTrue(savedEscrows(retried).isEmpty());
        MarketSavedData.get(level).putPurchaseOrder(order("order", 4, 100, "ARRIVED"));
        assertEquals(Long.MAX_VALUE - 15L, MarketWalletService.availableBalance(level, SELLER, "Seller"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sales", "import", "both"})
    void disbandedTaxRecipientRefundsBuyerWithoutBlockingSellerPayment(String deletedNation) throws Exception {
        TestLevel level = newLevel();
        MarketSavedData data = attachEscrow(level, escrowSave(100, 85, 10, 5));
        NationSavedData nations = NationSavedData.get(level);
        if (!"import".equals(deletedNation)) {
            nations.removeNation("sales");
        }
        if (!"sales".equals(deletedNation)) {
            nations.removeNation("import");
        }
        int refund = "sales".equals(deletedNation) ? 10 : "import".equals(deletedNation) ? 5 : 15;

        data.putPurchaseOrder(order("order", 4, 100, "ARRIVED"));
        data.putPurchaseOrder(order("order", 4, 100, "ARRIVED"));

        assertEquals(85L, MarketWalletService.availableBalance(level, SELLER, "Seller"));
        assertEquals(refund, MarketWalletService.availableBalance(level, BUYER, "Buyer"));
        long validTaxes = 0L;
        if (nations.getNation("sales") != null) {
            assertEquals(10L, nations.getTreasury("sales").currencyBalance());
            validTaxes += nations.getTreasury("sales").currencyBalance();
        }
        if (nations.getNation("import") != null) {
            assertEquals(5L, nations.getTreasury("import").currencyBalance());
            validTaxes += nations.getTreasury("import").currencyBalance();
        }
        assertEquals(100L, MarketWalletService.availableBalance(level, SELLER, "Seller")
                + MarketWalletService.availableBalance(level, BUYER, "Buyer") + validTaxes);
        assertTrue(savedEscrows(data).isEmpty());
    }

    @Test
    void fullBuyerWalletKeepsMissingTaxRefundAndSellerPaymentHeldTogether() throws Exception {
        TestLevel level = newLevel();
        MarketSavedData data = attachEscrow(level, escrowSave(100, 85, 10, 5));
        NationSavedData.get(level).removeNation("sales");
        MarketWalletService.deposit(level, BUYER, "Buyer", Long.MAX_VALUE);

        data.putPurchaseOrder(order("order", 4, 100, "ARRIVED"));

        assertEquals(0L, MarketWalletService.availableBalance(level, SELLER, "Seller"));
        assertEquals(Long.MAX_VALUE, MarketWalletService.availableBalance(level, BUYER, "Buyer"));
        assertTrue(NationSavedData.get(level).getTreasury("import") == null);
        assertEquals(1, savedEscrows(data).size());
    }

    @Test
    void splittingEscrowConservesSellerAndBothTaxesIncludingRounding() throws Exception {
        MarketSavedData data = MarketSavedData.load(escrowSave(101, 83, 11, 7));
        PurchaseOrder shipped = order("order", 1, 33, "IN_TRANSIT");
        PurchaseOrder remainder = order("remainder", 2, 68, "WAITING_SHIPMENT");
        Method split;
        try {
            split = MarketSavedData.class.getMethod("splitEscrow", String.class, PurchaseOrder.class, PurchaseOrder.class);
        } catch (NoSuchMethodException missing) {
            fail("MarketSavedData must split the held payment when shipment is split", missing);
            return;
        }

        split.invoke(data, "order", shipped, remainder);

        ListTag escrows = savedEscrows(data);
        assertEquals(2, escrows.size());
        CompoundTag first = findEscrow(escrows, "order");
        CompoundTag second = findEscrow(escrows, "remainder");
        assertEquals(33, first.getInt("TotalPrice"));
        assertEquals(68, second.getInt("TotalPrice"));
        assertEquals(83, first.getInt("SellerPayout") + second.getInt("SellerPayout"));
        assertEquals(11, first.getInt("SalesTax") + second.getInt("SalesTax"));
        assertEquals(7, first.getInt("ImportTariff") + second.getInt("ImportTariff"));
        for (Tag raw : escrows) {
            CompoundTag escrow = (CompoundTag) raw;
            assertEquals(escrow.getInt("TotalPrice"), escrow.getInt("SellerPayout")
                    + escrow.getInt("SalesTax") + escrow.getInt("ImportTariff"));
        }
    }

    private static ListTag savedEscrows(MarketSavedData data) {
        return data.save(new CompoundTag()).getList("OrderEscrows", Tag.TAG_COMPOUND);
    }

    private static CompoundTag escrowSave(int total, int sellerPayout, int salesTax, int importTariff) {
        CompoundTag escrow = new CompoundTag();
        escrow.putString("OrderId", "order");
        escrow.putString("SellerUuid", SELLER);
        escrow.putString("SellerName", "Seller");
        escrow.putInt("TotalPrice", total);
        escrow.putInt("SellerPayout", sellerPayout);
        escrow.putString("SalesTaxNationId", "sales");
        escrow.putInt("SalesTax", salesTax);
        escrow.putString("ImportTariffNationId", "import");
        escrow.putInt("ImportTariff", importTariff);
        ListTag escrows = new ListTag();
        escrows.add(escrow);
        CompoundTag save = new CompoundTag();
        save.put("OrderEscrows", escrows);
        return save;
    }

    private static CompoundTag findEscrow(ListTag escrows, String orderId) {
        for (Tag raw : escrows) {
            CompoundTag escrow = (CompoundTag) raw;
            if (orderId.equals(escrow.getString("OrderId"))) {
                return escrow;
            }
        }
        fail("Missing escrow for " + orderId);
        return new CompoundTag();
    }

    private MarketSavedData attachEscrow(TestLevel level, CompoundTag save) {
        level.storage.set("sailboatmod_market", MarketSavedData.load(save));
        return MarketSavedData.get(level);
    }

    private static PurchaseOrder order(String id, int quantity, int total, String status) {
        return new PurchaseOrder(id, "listing", BUYER, "Buyer", quantity, total,
                BlockPos.ZERO, "Source", BlockPos.ZERO, "Target", status, "SELLER_SHIP", BlockPos.ZERO);
    }

    private TestLevel newLevel() throws Exception {
        TestLevel level = allocate(TestLevel.class);
        level.storage = new DimensionDataStorage(temporaryDirectory.toFile(), null, null);
        level.server = allocate(DedicatedServer.class);
        Field levels = MinecraftServer.class.getDeclaredField("levels");
        levels.setAccessible(true);
        levels.set(level.server, Map.of(Level.OVERWORLD, level));
        for (String nationId : List.of("sales", "import")) {
            NationSavedData.get(level).putNation(new NationRecord(nationId, nationId, nationId, 1, 2,
                    UUID.randomUUID(), 1L, "", "", NationRecord.noCorePos(), ""));
        }
        return level;
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (T) ((Unsafe) field.get(null)).allocateInstance(type);
    }

    private static final class TestLevel extends ServerLevel {
        private DedicatedServer server;
        private DimensionDataStorage storage;

        private TestLevel() {
            super(null, command -> { }, null, null, null, null, null, false, 0L, List.of(), false, null);
        }

        @Override public MinecraftServer getServer() { return server; }
        @Override public DimensionDataStorage getDataStorage() { return storage; }
        @Override public net.minecraft.resources.ResourceKey<Level> dimension() { return Level.OVERWORLD; }
    }
}
