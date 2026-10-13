package com.monpai.sailboatmod.client.ui.market;

import com.monpai.sailboatmod.client.ui.state.ConfirmGate;
import com.monpai.sailboatmod.client.ui.state.PendingRequests;
import com.monpai.sailboatmod.market.MarketOverviewData;
import com.monpai.sailboatmod.market.TransportTerminalKind;
import com.monpai.sailboatmod.network.packet.CancelBuyOrderPacket;
import com.monpai.sailboatmod.network.packet.CancelMarketListingPacket;
import com.monpai.sailboatmod.network.packet.CancelPurchaseOrderPacket;
import com.monpai.sailboatmod.network.packet.ClaimMarketCreditsPacket;
import com.monpai.sailboatmod.network.packet.CreateBuyOrderPacket;
import com.monpai.sailboatmod.network.packet.CreateMarketListingPacket;
import com.monpai.sailboatmod.network.packet.DispatchMarketOrderPacket;
import com.monpai.sailboatmod.network.packet.MarketGuiActionPacket;
import com.monpai.sailboatmod.network.packet.MarketWalletActionPacket;
import com.monpai.sailboatmod.network.packet.ProbeFulfillmentModesPacket;
import com.monpai.sailboatmod.network.packet.PurchaseMarketListingPacket;
import com.monpai.sailboatmod.network.packet.RenameMarketPacket;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Turns market screen intents into the existing server packets.
 *
 * <p>Packet types, fields and order are unchanged from the previous screen; the server keeps validating identity,
 * ownership, funds and stock. On top of that the controller adds presentation rules the old screen lacked: an
 * action already waiting for a reply is not sent twice, destructive actions need a second press, and invalid input
 * is reported before anything is sent.</p>
 */
public final class MarketController {
    /** Outcome of an intent, shown to the player by the screen. */
    public enum Result {
        SENT,
        /** First press on a destructive action; the screen shows the confirm state. */
        CONFIRM_REQUIRED,
        /** The same action is still waiting for the server. */
        ALREADY_PENDING,
        NOTHING_SELECTED,
        OWN_LISTING,
        INVALID_QUANTITY,
        INVALID_PRICE,
        PRICE_OUT_OF_RANGE,
        MODE_UNAVAILABLE,
        INVALID_BUY_ORDER,
        INVALID_AMOUNT
    }

    public static final String KEY_BUY = "buy";
    public static final String KEY_LIST = "list";
    public static final String KEY_UNLIST = "unlist";
    public static final String KEY_DISPATCH = "dispatch";
    public static final String KEY_CANCEL_ORDER = "cancel_order";
    public static final String KEY_CLAIM = "claim";
    public static final String KEY_WALLET = "wallet";
    public static final String KEY_BUY_ORDER = "buy_order";
    public static final String KEY_CANCEL_BUY_ORDER = "cancel_buy_order";
    public static final String KEY_RENAME = "rename";

    private final MarketViewModel model;
    private final Consumer<Object> packetSink;
    private final LongSupplier clock;
    private final PendingRequests pending;
    private final ConfirmGate confirm;

    public MarketController(MarketViewModel model, Consumer<Object> packetSink, LongSupplier clock) {
        this(model, packetSink, clock, new PendingRequests(), new ConfirmGate());
    }

    MarketController(MarketViewModel model, Consumer<Object> packetSink, LongSupplier clock,
                     PendingRequests pending, ConfirmGate confirm) {
        this.model = model;
        this.packetSink = packetSink;
        this.clock = clock;
        this.pending = pending;
        this.confirm = confirm;
    }

    public MarketViewModel model() {
        return model;
    }

    public boolean isPending(String key) {
        return pending.isPending(key);
    }

    public boolean isArmed(String target) {
        return confirm.isArmed(target, clock.getAsLong());
    }

    /** New authoritative data arrived: every in-flight action has its answer. */
    public void onDataUpdated(MarketOverviewData data) {
        model.update(data);
        pending.resolveAll();
    }

    /** A server notice answers the action that caused it; the notice itself is displayed by the screen. */
    public void onNotice() {
        pending.resolveAll();
    }

    /** Call every client tick; returns the action key that has waited too long for a reply. */
    public Optional<String> pollTimedOut() {
        return pending.pollTimedOut(clock.getAsLong());
    }

    // ---------------------------------------------------------------- read-only requests

    public void refresh() {
        packetSink.accept(new MarketGuiActionPacket(model.marketPos(), MarketGuiActionPacket.Action.REFRESH));
    }

    /** Asks which delivery modes can reach the chosen warehouse for the listing in the open dialog. */
    public void requestProbe() {
        if (!model.isBuyDialogOpen()) {
            return;
        }
        packetSink.accept(new ProbeFulfillmentModesPacket(model.marketPos(), model.buyDialogListingId(),
                model.buyReceivingPosOrZero()));
    }

    // ---------------------------------------------------------------- goods

    /** Opens the purchase dialog and starts the reachability probe. */
    public Result openBuyDialog() {
        MarketOverviewData.ListingEntry listing = model.selectedListing();
        if (listing == null) {
            return Result.NOTHING_SELECTED;
        }
        if (model.isOwnListing(listing)) {
            return Result.OWN_LISTING;
        }
        model.openBuyDialog();
        requestProbe();
        return Result.SENT;
    }

    public Result confirmBuy() {
        if (!model.isBuyDialogOpen()) {
            return Result.NOTHING_SELECTED;
        }
        MarketOverviewData.ListingEntry listing = model.findListing(model.buyDialogListingId());
        if (listing == null) {
            return Result.NOTHING_SELECTED;
        }
        if (model.isOwnListing(listing)) {
            return Result.OWN_LISTING;
        }
        int quantity = MarketViewModel.parseQuantity(model.buyQty());
        if (quantity <= 0) {
            return Result.INVALID_QUANTITY;
        }
        if (!model.canConfirmBuy()) {
            return Result.MODE_UNAVAILABLE;
        }
        if (!pending.begin(KEY_BUY, clock.getAsLong())) {
            return Result.ALREADY_PENDING;
        }
        packetSink.accept(new PurchaseMarketListingPacket(model.marketPos(), listing.listingId(), quantity,
                model.buyFulfillment(), model.buyReceivingPosOrZero()));
        model.closeBuyDialog();
        return Result.SENT;
    }

    /** Removes the viewer's own listing; needs a second press within the confirm window. */
    public Result unlistSelected() {
        MarketOverviewData.ListingEntry listing = model.selectedListing();
        if (listing == null) {
            return Result.NOTHING_SELECTED;
        }
        if (!confirm.press("unlist:" + listing.listingId(), clock.getAsLong())) {
            return Result.CONFIRM_REQUIRED;
        }
        if (!pending.begin(KEY_UNLIST, clock.getAsLong())) {
            return Result.ALREADY_PENDING;
        }
        packetSink.accept(new CancelMarketListingPacket(model.marketPos(), listing.listingId()));
        return Result.SENT;
    }

    // ---------------------------------------------------------------- sell

    public Result createListing() {
        int storageIndex = model.selectedStorageIndex();
        if (storageIndex < 0 || model.selectedStorage().stockIdentity().isBlank()) {
            return Result.NOTHING_SELECTED;
        }
        int quantity = MarketViewModel.parseQuantity(model.listingQty());
        if (quantity <= 0) {
            return Result.INVALID_QUANTITY;
        }
        switch (model.checkListingPrice()) {
            case NO_STORAGE -> {
                return Result.NOTHING_SELECTED;
            }
            case INVALID -> {
                return Result.INVALID_PRICE;
            }
            case OUT_OF_RANGE -> {
                return Result.PRICE_OUT_OF_RANGE;
            }
            case OK -> {
            }
        }
        if (!pending.begin(KEY_LIST, clock.getAsLong())) {
            return Result.ALREADY_PENDING;
        }
        packetSink.accept(new CreateMarketListingPacket(model.marketPos(), storageIndex, quantity,
                model.requestedUnitPrice(), "", model.selectedStorage().stockIdentity()));
        return Result.SENT;
    }

    // ---------------------------------------------------------------- dispatch & orders

    public Result dispatchSelectedOrder() {
        MarketOverviewData.OrderEntry order = model.selectedOrder();
        if (order == null) {
            return Result.NOTHING_SELECTED;
        }
        if (!pending.begin(KEY_DISPATCH, clock.getAsLong())) {
            return Result.ALREADY_PENDING;
        }
        MarketOverviewData.DispatchOption option = model.selectedDispatchOption();
        packetSink.accept(new DispatchMarketOrderPacket(model.marketPos(), order.orderId(),
                option == null ? TransportTerminalKind.AUTO : TransportTerminalKind.fromName(option.terminalKind())));
        return Result.SENT;
    }

    /** Cancels a purchase order (seller's dispatch queue or the viewer's own order); confirm required. */
    public Result cancelPurchaseOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return Result.NOTHING_SELECTED;
        }
        if (!confirm.press("order:" + orderId, clock.getAsLong())) {
            return Result.CONFIRM_REQUIRED;
        }
        if (!pending.begin(KEY_CANCEL_ORDER, clock.getAsLong())) {
            return Result.ALREADY_PENDING;
        }
        packetSink.accept(new CancelPurchaseOrderPacket(model.marketPos(), orderId));
        return Result.SENT;
    }

    // ---------------------------------------------------------------- finance

    public Result claimCredits() {
        if (!pending.begin(KEY_CLAIM, clock.getAsLong())) {
            return Result.ALREADY_PENDING;
        }
        packetSink.accept(new ClaimMarketCreditsPacket(model.marketPos()));
        return Result.SENT;
    }

    public Result walletAction(MarketWalletActionPacket.Action action) {
        long amount;
        if (action == MarketWalletActionPacket.Action.CLAIM_CREDITS_TO_WALLET) {
            amount = 0L;
        } else {
            int parsed = MarketViewModel.parseQuantity(model.walletAmount());
            if (parsed <= 0) {
                return Result.INVALID_AMOUNT;
            }
            amount = parsed;
        }
        if (!pending.begin(KEY_WALLET, clock.getAsLong())) {
            return Result.ALREADY_PENDING;
        }
        packetSink.accept(new MarketWalletActionPacket(model.marketPos(), action, amount));
        return Result.SENT;
    }

    public Result renameMarket() {
        if (!pending.begin(KEY_RENAME, clock.getAsLong())) {
            return Result.ALREADY_PENDING;
        }
        packetSink.accept(new RenameMarketPacket(model.marketPos(), model.takeRenameValue()));
        return Result.SENT;
    }

    // ---------------------------------------------------------------- buy orders

    public Result createBuyOrder() {
        if (!model.isBuyOrderValid()) {
            return Result.INVALID_BUY_ORDER;
        }
        if (!pending.begin(KEY_BUY_ORDER, clock.getAsLong())) {
            return Result.ALREADY_PENDING;
        }
        packetSink.accept(new CreateBuyOrderPacket(model.marketPos(), model.resolvedBuyOrderCommodity(),
                MarketViewModel.parseQuantity(model.buyOrderQty()),
                MarketViewModel.parseBp(model.buyOrderMinBp()),
                MarketViewModel.parseBp(model.buyOrderMaxBp())));
        return Result.SENT;
    }

    public Result cancelSelectedBuyOrder() {
        MarketOverviewData.BuyOrderEntry entry = model.selectedBuyOrder();
        if (entry == null) {
            return Result.NOTHING_SELECTED;
        }
        if (!confirm.press("buy_order:" + entry.orderId(), clock.getAsLong())) {
            return Result.CONFIRM_REQUIRED;
        }
        if (!pending.begin(KEY_CANCEL_BUY_ORDER, clock.getAsLong())) {
            return Result.ALREADY_PENDING;
        }
        packetSink.accept(new CancelBuyOrderPacket(model.marketPos(), entry.orderId()));
        return Result.SENT;
    }
}
