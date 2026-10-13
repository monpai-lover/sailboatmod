package com.monpai.sailboatmod.client.ui.market;

import com.monpai.sailboatmod.market.MarketOverviewData;
import com.monpai.sailboatmod.market.TransportTerminalKind;
import com.monpai.sailboatmod.network.packet.CancelBuyOrderPacket;
import com.monpai.sailboatmod.network.packet.CancelMarketListingPacket;
import com.monpai.sailboatmod.network.packet.CancelPurchaseOrderPacket;
import com.monpai.sailboatmod.network.packet.CreateBuyOrderPacket;
import com.monpai.sailboatmod.network.packet.CreateMarketListingPacket;
import com.monpai.sailboatmod.network.packet.DispatchMarketOrderPacket;
import com.monpai.sailboatmod.network.packet.MarketWalletActionPacket;
import com.monpai.sailboatmod.network.packet.ProbeFulfillmentModesPacket;
import com.monpai.sailboatmod.network.packet.PurchaseMarketListingPacket;
import com.monpai.sailboatmod.network.packet.RenameMarketPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static com.monpai.sailboatmod.client.ui.market.MarketFixtures.MARKET;
import static com.monpai.sailboatmod.client.ui.market.MarketFixtures.listing;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The controller must send exactly the packets the previous screen sent. Each check encodes the captured packet and
 * the packet the old code would have built, and compares the wire bytes.
 */
class MarketControllerTest {
    private final List<Object> sent = new ArrayList<>();
    private long tick;

    @BeforeEach
    void reset() {
        sent.clear();
        tick = 0;
    }

    private MarketController controller(MarketFixtures fixtures) {
        return new MarketController(new MarketViewModel(fixtures.build()), sent::add, () -> tick);
    }

    @Test
    void purchaseSendsChosenModeQuantityAndWarehouse() {
        MarketFixtures f = new MarketFixtures();
        f.listings.add(listing("L1", "minecraft:wheat", "Wheat", 9, 20, "seller", "crop", 0));
        BlockPos depot = new BlockPos(1, 2, 3);
        f.warehouses.add(new MarketOverviewData.WarehouseOption(depot, "Depot", "Town"));
        MarketController controller = controller(f);

        assertEquals(MarketController.Result.SENT, controller.openBuyDialog());
        assertSameWire(new ProbeFulfillmentModesPacket(MARKET, "L1", depot), sent.get(0));
        controller.model().applyProbeResult("L1", true, false, true);
        controller.model().setBuyQty("3");

        assertEquals(MarketController.Result.SENT, controller.confirmBuy());
        assertSameWire(new PurchaseMarketListingPacket(MARKET, "L1", 3, "SELLER_SHIP", depot), sent.get(1));
    }

    @Test
    void secondPurchaseWhileWaitingIsNotSent() {
        MarketFixtures f = new MarketFixtures();
        f.listings.add(listing("L1", "minecraft:wheat", "Wheat", 9, 20, "seller", "crop", 0));
        MarketController controller = controller(f);
        controller.openBuyDialog();
        controller.model().applyProbeResult("L1", false, false, true);
        assertEquals(MarketController.Result.SENT, controller.confirmBuy());

        controller.openBuyDialog();
        controller.model().applyProbeResult("L1", false, false, true);
        assertEquals(MarketController.Result.ALREADY_PENDING, controller.confirmBuy());
        assertEquals(3, sent.size(), "probe, purchase, second probe; no second purchase");

        tick = 100;
        assertEquals(Optional.of(MarketController.KEY_BUY), controller.pollTimedOut());
        assertEquals(MarketController.Result.SENT, controller.confirmBuy(), "after a timeout the player may retry");
    }

    @Test
    void freshDataReleasesPendingActions() {
        MarketFixtures f = new MarketFixtures();
        f.listings.add(listing("L1", "minecraft:wheat", "Wheat", 9, 20, "seller", "crop", 0));
        MarketController controller = controller(f);
        controller.openBuyDialog();
        controller.model().applyProbeResult("L1", false, false, true);
        controller.confirmBuy();
        assertTrue(controller.isPending(MarketController.KEY_BUY));
        controller.onDataUpdated(f.build());
        assertTrue(!controller.isPending(MarketController.KEY_BUY));
    }

    @Test
    void ownListingIsRefusedBeforeSending() {
        MarketFixtures f = new MarketFixtures();
        f.listings.add(listing("MINE", "minecraft:wheat", "Wheat", 9, 20, MarketFixtures.VIEWER, "crop", 0));
        MarketController controller = controller(f);
        assertEquals(MarketController.Result.OWN_LISTING, controller.openBuyDialog());
        assertTrue(sent.isEmpty());
    }

    @Test
    void unlistNeedsSecondPress() {
        MarketFixtures f = new MarketFixtures();
        f.listings.add(listing("MINE", "minecraft:wheat", "Wheat", 9, 20, MarketFixtures.VIEWER, "crop", 0));
        MarketController controller = controller(f);
        assertEquals(MarketController.Result.CONFIRM_REQUIRED, controller.unlistSelected());
        assertTrue(sent.isEmpty());
        tick = 10;
        assertEquals(MarketController.Result.SENT, controller.unlistSelected());
        assertSameWire(new CancelMarketListingPacket(MARKET, "MINE"), sent.get(0));
    }

    @Test
    void createListingSendsCurrentStorageIndexAndPrice() {
        MarketFixtures f = new MarketFixtures();
        MarketOverviewData.StorageEntry stick = MarketFixtures.storage("minecraft:stick", "Stick", 8, 2, 1, 3, true);
        MarketOverviewData.StorageEntry wheat = MarketFixtures.storage("minecraft:wheat", "Wheat", 64, 10, 9, 11, true);
        f.storage.add(stick);
        f.storage.add(wheat);
        MarketController controller = controller(f);
        controller.model().selectStorage(MarketViewModel.storageKey(wheat));
        controller.model().setListingQty("16");
        controller.model().setListingUnitPrice("12");
        assertEquals(MarketController.Result.PRICE_OUT_OF_RANGE, controller.createListing());
        controller.model().setListingQty("0");
        controller.model().setListingUnitPrice("10");
        assertEquals(MarketController.Result.INVALID_QUANTITY, controller.createListing());
        assertTrue(sent.isEmpty());

        controller.model().setListingQty("16");
        assertEquals(MarketController.Result.SENT, controller.createListing());
        assertSameWire(new CreateMarketListingPacket(MARKET, 1, 16, 10, "", wheat.stockIdentity()), sent.get(0));
    }

    @Test
    void dispatchUsesSelectedCarrierKind() {
        MarketFixtures f = new MarketFixtures();
        MarketOverviewData.DispatchOption cart = MarketFixtures.carrier("POST_STATION", "Cart");
        f.orders.add(MarketFixtures.order("O1", List.of(cart)));
        MarketController controller = controller(f);
        assertEquals(MarketController.Result.SENT, controller.dispatchSelectedOrder());
        assertSameWire(new DispatchMarketOrderPacket(MARKET, "O1", TransportTerminalKind.fromName("POST_STATION")), sent.get(0));
    }

    @Test
    void dispatchWithoutCarrierFallsBackToAuto() {
        MarketFixtures f = new MarketFixtures();
        f.orders.add(MarketFixtures.order("O1", List.of()));
        MarketController controller = controller(f);
        controller.dispatchSelectedOrder();
        assertSameWire(new DispatchMarketOrderPacket(MARKET, "O1", TransportTerminalKind.AUTO), sent.get(0));
    }

    @Test
    void cancellingAPurchaseOrderIsKeyedByOrderId() {
        MarketController controller = controller(new MarketFixtures());
        assertEquals(MarketController.Result.CONFIRM_REQUIRED, controller.cancelPurchaseOrder("order-1"));
        assertEquals(MarketController.Result.CONFIRM_REQUIRED, controller.cancelPurchaseOrder("order-2"),
                "a different order re-arms instead of confirming");
        assertEquals(MarketController.Result.SENT, controller.cancelPurchaseOrder("order-2"));
        assertSameWire(new CancelPurchaseOrderPacket(MARKET, "order-2"), sent.get(0));
    }

    @Test
    void walletActionsValidateAmountExceptClaim() {
        MarketController controller = controller(new MarketFixtures());
        controller.model().setWalletAmount("0");
        assertEquals(MarketController.Result.INVALID_AMOUNT,
                controller.walletAction(MarketWalletActionPacket.Action.CASH_TO_WALLET));
        assertEquals(MarketController.Result.SENT,
                controller.walletAction(MarketWalletActionPacket.Action.CLAIM_CREDITS_TO_WALLET));
        assertSameWire(new MarketWalletActionPacket(MARKET, MarketWalletActionPacket.Action.CLAIM_CREDITS_TO_WALLET, 0L), sent.get(0));
        controller.onNotice();
        controller.model().setWalletAmount("250");
        controller.walletAction(MarketWalletActionPacket.Action.WALLET_TO_CASH);
        assertSameWire(new MarketWalletActionPacket(MARKET, MarketWalletActionPacket.Action.WALLET_TO_CASH, 250L), sent.get(1));
    }

    @Test
    void buyOrdersAndRenameKeepPacketShape() {
        MarketFixtures f = new MarketFixtures();
        f.buyOrders.add(MarketFixtures.buyOrder("B1", "minecraft:wheat"));
        MarketController controller = controller(f);
        controller.model().setBuyOrderCommodity("minecraft:wheat");
        controller.model().setBuyOrderQty("8");
        controller.model().setBuyOrderMinBp("-200");
        controller.model().setBuyOrderMaxBp("4000");
        assertEquals(MarketController.Result.INVALID_BUY_ORDER, controller.createBuyOrder(),
                "out-of-band input is refused instead of being clamped and sent");
        assertTrue(sent.isEmpty());
        controller.model().setBuyOrderMaxBp("1000");
        assertEquals(MarketController.Result.SENT, controller.createBuyOrder());
        assertSameWire(new CreateBuyOrderPacket(MARKET, "minecraft:wheat", 8, -200, 1000), sent.get(0));

        controller.cancelSelectedBuyOrder();
        tick = 1;
        controller.cancelSelectedBuyOrder();
        assertSameWire(new CancelBuyOrderPacket(MARKET, "B1"), sent.get(1));

        controller.model().setRenameDraft(" Pier ");
        controller.renameMarket();
        assertSameWire(new RenameMarketPacket(MARKET, "Pier"), sent.get(2));
    }

    private static void assertSameWire(Object expected, Object actual) {
        assertEquals(expected.getClass(), actual.getClass());
        assertArrayEquals(encode(expected), encode(actual));
    }

    private static byte[] encode(Object packet) {
        try {
            Method encode = Arrays.stream(packet.getClass().getMethods())
                    .filter(m -> m.getName().equals("encode") && m.getParameterCount() == 2
                            && java.lang.reflect.Modifier.isStatic(m.getModifiers()))
                    .findFirst().orElseThrow();
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            encode.invoke(null, packet, buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
