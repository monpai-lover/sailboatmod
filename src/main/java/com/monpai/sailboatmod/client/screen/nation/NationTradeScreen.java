package com.monpai.sailboatmod.client.screen.nation;

import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.monpai.sailboatmod.client.TradeClientHooks;
import com.monpai.sailboatmod.client.gui.TradeWindowStatePolicy;
import com.monpai.sailboatmod.client.ui.kit.ActionButton;
import com.monpai.sailboatmod.client.ui.kit.ThemedScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.client.ui.state.ConfirmGate;
import com.monpai.sailboatmod.client.ui.state.PendingRequests;
import com.monpai.sailboatmod.nation.menu.TradeScreenData;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.TradeScreenActionPacket;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexWrap;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Trade proposal between two nations: our offer on the left, what we ask for on the right, each above its treasury.
 *
 * <p>Click a treasury item to add it to the trade, click a trade slot to take it out. The draft (items and currency)
 * is kept across the 40-tick refresh and only replaced when {@link TradeWindowStatePolicy#shouldReplaceDraft} says the
 * proposal changed. Accepting a proposal and withdrawing ours need a second press; the server checks permissions,
 * treasuries and expiry as before. The other nation's balance is shown only as a tier.</p>
 */
public class NationTradeScreen extends ThemedScreen {
    private static final int MAX_TRADE_ITEMS = TradeScreenData.MAX_TRADE_ITEMS;
    private static final int AUTO_REFRESH_TICKS = 40;
    private static final String KEY_ACTION = "trade";
    private static final String CONFIRM_ACCEPT = "accept";
    private static final String CONFIRM_CANCEL = "cancel";

    private TradeScreenData data;
    private final List<ItemStack> offerSlots = new ArrayList<>(MAX_TRADE_ITEMS);
    private final List<ItemStack> requestSlots = new ArrayList<>(MAX_TRADE_ITEMS);
    private final PendingRequests pending = new PendingRequests();
    private final ConfirmGate confirm = new ConfirmGate();
    private int refreshTicks;

    private Side ours;
    private Side theirs;
    private final Label status = Ui.text("", "sm-muted");
    private ActionButton proposeButton;
    private ActionButton acceptButton;
    private ActionButton rejectButton;
    private ActionButton counterButton;
    private ActionButton cancelButton;

    /** One half of the window. */
    private final class Side {
        final boolean offer;
        final UIElement swatch = new UIElement();
        int shownColor = -1;
        final Label name = Ui.text("", "sm-title");
        final Label balance = Ui.text("", "sm-muted");
        final UIElement tradeGrid = Ui.wrapRow(Ui.GAP_XS);
        final TextField currency;
        final Label treasuryTitle = Ui.text("", "sm-muted");
        final ScrollerView treasury = Ui.scroller();
        final UIElement treasuryGrid = Ui.wrapRow(1);
        final UIElement element;

        Side(boolean offer) {
            this.offer = offer;
            element = Ui.column("sm-pane");
            element.layout(l -> l.flexGrow(1).flexBasis(0).minWidth(0).paddingAll(Ui.GAP_M).gapAll(Ui.GAP_S));
            swatch.layout(l -> l.width(10).height(10).flexShrink(0));
            UIElement head = Ui.row();
            head.layout(l -> l.gapAll(Ui.GAP_S));
            head.addChildren(swatch, name);
            tradeGrid.layout(l -> l.width(5 * (Ui.SLOT + Ui.GAP_XS)));
            currency = Ui.field("", Component.translatable("screen.sailboatmod.trade.currency"), value -> { });
            currency.setCharValidator(TradeWindowStatePolicy::isCurrencyCharacterAllowed);
            currency.setTextValidator(text -> text.length() <= 15 && text.equals(TradeWindowStatePolicy.filterCurrencyText(text)));
            currency.layout(l -> l.widthPercent(100));
            treasury.layout(l -> l.height(3 * (Ui.SLOT + 1) + 2).flexGrow(0));
            treasuryGrid.layout(l -> l.widthPercent(100));
            treasury.addScrollViewChild(treasuryGrid);
            element.addChildren(head, balance,
                    Ui.text(Component.translatable(offer ? "screen.sailboatmod.trade.our_offer" : "screen.sailboatmod.trade.we_request")),
                    tradeGrid, Ui.text(Component.translatable("screen.sailboatmod.trade.currency"), "sm-muted"), currency,
                    Ui.divider(), treasuryTitle, treasury);
        }

        List<ItemStack> slots() {
            return offer ? offerSlots : requestSlots;
        }
    }

    public NationTradeScreen(TradeScreenData data) {
        super(Component.translatable("screen.sailboatmod.trade.title"));
        this.data = data == null ? TradeScreenData.empty() : data;
        loadDraftFromData();
    }

    public void updateData(TradeScreenData newData) {
        TradeScreenData next = newData == null ? TradeScreenData.empty() : newData;
        boolean replaceDraft = TradeWindowStatePolicy.shouldReplaceDraft(
                data.targetNationId(), data.hasExistingProposal(), data.proposalId(),
                next.targetNationId(), next.hasExistingProposal(), next.proposalId()
        );
        this.data = next;
        if (replaceDraft) {
            confirm.disarm();
            loadDraftFromData();
            loadCurrencyInputsFromData();
        }
        pending.resolveAll();
        refresh();
    }

    @Override
    protected float windowWidth() {
        return 380;
    }

    @Override
    protected UIElement buildBody() {
        UIElement body = Ui.column();
        ours = new Side(true);
        theirs = new Side(false);
        UIElement sides = Ui.row();
        sides.layout(l -> l.gapAll(Ui.GAP_M).alignItems(AlignItems.STRETCH));
        sides.addChildren(ours.element, theirs.element);

        proposeButton = new ActionButton(Component.translatable("screen.sailboatmod.trade.propose"), Ui.Tone.PRIMARY,
                () -> sendAction(TradeScreenActionPacket.Action.PROPOSE));
        acceptButton = new ActionButton(Component.translatable("screen.sailboatmod.trade.accept"), Ui.Tone.PRIMARY, () -> {
            if (confirm.press(CONFIRM_ACCEPT, ticks())) {
                sendAction(TradeScreenActionPacket.Action.ACCEPT);
            } else {
                refresh();
            }
        });
        rejectButton = new ActionButton(Component.translatable("screen.sailboatmod.trade.reject"), Ui.Tone.DANGER,
                () -> sendAction(TradeScreenActionPacket.Action.REJECT));
        counterButton = new ActionButton(Component.translatable("screen.sailboatmod.trade.counter"), Ui.Tone.SECONDARY,
                () -> sendAction(TradeScreenActionPacket.Action.COUNTER_OFFER));
        cancelButton = new ActionButton(Component.translatable("screen.sailboatmod.trade.cancel"), Ui.Tone.DANGER, () -> {
            if (confirm.press(CONFIRM_CANCEL, ticks())) {
                sendAction(TradeScreenActionPacket.Action.CANCEL);
            } else {
                refresh();
            }
        });
        for (ActionButton button : List.of(proposeButton, acceptButton, rejectButton, counterButton, cancelButton)) {
            button.inRow();
        }
        UIElement footer = Ui.row();
        footer.layout(l -> l.widthPercent(100).minWidth(0).flexWrap(FlexWrap.WRAP).gapAll(Ui.GAP_S));
        status.layout(l -> l.flexGrow(1).flexBasis(0).minWidth(0));
        footer.addChildren(status, rejectButton.element(), counterButton.element(), cancelButton.element(),
                acceptButton.element(), proposeButton.element());
        body.addChildren(sides, footer);
        loadCurrencyInputsFromData();
        refresh();
        return body;
    }

    @Override
    protected void onUiTick() {
        refreshTicks++;
        if (refreshTicks >= AUTO_REFRESH_TICKS) {
            refreshTicks = 0;
            ModNetwork.CHANNEL.sendToServer(
                    new TradeScreenActionPacket(TradeScreenActionPacket.Action.REFRESH, data.targetNationId()));
        }
        pending.pollTimedOut(ticks()).ifPresent(key -> {
            pending.resolveAll();
            toast.show(Component.translatable("screen.sailboatmod.ui.no_reply_server"), false, ticks());
        });
        refresh();
    }

    // ================================================================= refresh

    private void refresh() {
        if (ours == null) {
            return;
        }
        boolean readOnly = readOnly();
        fillHeader(ours, data.ourNationName(), data.ourPrimaryColor(),
                Component.translatable("screen.sailboatmod.trade.balance", data.ourTreasuryBalance()));
        // The other nation's treasury is shown only as a tier.
        fillHeader(theirs, data.targetNationName(), data.targetPrimaryColor(),
                Component.translatable("screen.sailboatmod.trade.balance.tier." + tierKey(data.targetTreasuryTier())));
        fillTradeGrid(ours, readOnly);
        fillTradeGrid(theirs, readOnly);
        fillTreasury(ours, data.ourTreasuryItems(), Component.translatable("screen.sailboatmod.trade.treasury_preview"), readOnly);
        fillTreasury(theirs, data.targetTreasuryItems(), data.targetTreasuryItems().isEmpty()
                ? Component.translatable("screen.sailboatmod.trade.treasury_hidden")
                : Component.translatable("screen.sailboatmod.trade.target_treasury"), readOnly);
        ours.currency.setActive(!readOnly);
        theirs.currency.setActive(!readOnly);

        status.setValue(statusText());
        boolean hasProposal = data.hasExistingProposal();
        boolean weProposed = hasProposal && data.weAreProposer();
        boolean theyProposed = hasProposal && !data.weAreProposer();
        boolean busy = pending.isPending(KEY_ACTION);
        proposeButton.element().setDisplay(!hasProposal);
        acceptButton.element().setDisplay(theyProposed);
        rejectButton.element().setDisplay(theyProposed);
        counterButton.element().setDisplay(theyProposed);
        cancelButton.element().setDisplay(weProposed);
        proposeButton.sync(!hasProposal && data.canManageTreasury(), busy, false);
        acceptButton.sync(theyProposed && data.canManageTreasury(), busy, confirm.isArmed(CONFIRM_ACCEPT, ticks()));
        rejectButton.sync(theyProposed, busy, false);
        counterButton.sync(theyProposed && data.canManageTreasury(), busy, false);
        cancelButton.sync(weProposed, busy, confirm.isArmed(CONFIRM_CANCEL, ticks()));
    }

    private void fillHeader(Side side, String name, int color, Component balance) {
        int argb = 0xFF000000 | (color & 0xFFFFFF);
        if (side.shownColor != argb) {
            side.shownColor = argb;
            side.swatch.style(s -> s.background(new ColorRectTexture(argb)));
        }
        side.name.setValue(Component.literal(name));
        side.balance.setValue(balance);
    }

    private String shownTradeSignature(Side side) {
        StringBuilder signature = new StringBuilder();
        for (ItemStack stack : side.slots()) {
            signature.append(stack.isEmpty() ? "-" : stack.getItem() + "x" + stack.getCount()).append(';');
        }
        return signature.toString();
    }

    private final java.util.Map<Side, String> shownTrade = new java.util.HashMap<>();
    private final java.util.Map<Side, List<ItemStack>> shownTreasury = new java.util.HashMap<>();
    private final java.util.Map<Side, Boolean> shownReadOnly = new java.util.HashMap<>();

    private void fillTradeGrid(Side side, boolean readOnly) {
        String signature = shownTradeSignature(side) + readOnly;
        if (signature.equals(shownTrade.get(side))) {
            return;
        }
        shownTrade.put(side, signature);
        side.tradeGrid.clearAllChildren();
        List<ItemStack> slots = side.slots();
        for (int i = 0; i < MAX_TRADE_ITEMS; i++) {
            int index = i;
            ItemStack stack = slots.get(i);
            UIElement slot = Ui.slot(stack, Ui.SLOT);
            if (!stack.isEmpty()) {
                slot.style(s -> s.tooltips(stack.getHoverName(), Component.translatable("screen.sailboatmod.ui.trade.click_remove")));
                if (!readOnly) {
                    Ui.clickable(slot, () -> {
                        slots.set(index, ItemStack.EMPTY);
                        refresh();
                    });
                }
            }
            side.tradeGrid.addChild(slot);
        }
    }

    private void fillTreasury(Side side, List<ItemStack> items, Component title, boolean readOnly) {
        side.treasuryTitle.setValue(title);
        if (items == shownTreasury.get(side) && Boolean.valueOf(readOnly).equals(shownReadOnly.get(side))) {
            return;
        }
        shownTreasury.put(side, items);
        shownReadOnly.put(side, readOnly);
        side.treasuryGrid.clearAllChildren();
        if (items == null) {
            return;
        }
        for (ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            UIElement slot = Ui.slot(stack, Ui.SLOT);
            slot.style(s -> s.tooltips(stack.getHoverName(), Component.translatable("screen.sailboatmod.ui.trade.click_add")));
            if (!readOnly) {
                Ui.clickable(slot, () -> {
                    if (!addToFirstEmpty(side.slots(), stack.copy())) {
                        toast.show(Component.translatable("screen.sailboatmod.ui.trade.full"), false, ticks());
                    }
                    refresh();
                });
            }
            side.treasuryGrid.addChild(slot);
        }
    }

    private Component statusText() {
        if (!data.hasExistingProposal()) {
            return Component.translatable("screen.sailboatmod.trade.status.none");
        }
        int min = data.proposalRemainingSeconds() / 60;
        int sec = data.proposalRemainingSeconds() % 60;
        String time = String.format("%d:%02d", min, sec);
        return data.weAreProposer()
                ? Component.translatable("screen.sailboatmod.trade.status.waiting", time)
                : Component.translatable("screen.sailboatmod.trade.status.incoming", time);
    }

    /** The other nation's treasury tier key, or "unknown" when the relation does not allow seeing it. */
    private static String tierKey(String tier) {
        return switch (tier == null ? "" : tier) {
            case "minimal", "modest", "substantial", "wealthy", "prosperous" -> tier;
            default -> "unknown";
        };
    }

    // ================================================================= draft

    private static boolean addToFirstEmpty(List<ItemStack> slots, ItemStack item) {
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).isEmpty()) {
                slots.set(i, item);
                return true;
            }
        }
        return false;
    }

    private void loadDraftFromData() {
        offerSlots.clear();
        requestSlots.clear();
        for (int i = 0; i < MAX_TRADE_ITEMS; i++) {
            offerSlots.add(ItemStack.EMPTY);
            requestSlots.add(ItemStack.EMPTY);
        }
        if (data.hasExistingProposal()) {
            copyItemsIntoSlots(data.offerItems(), offerSlots);
            copyItemsIntoSlots(data.requestItems(), requestSlots);
        }
    }

    private void loadCurrencyInputsFromData() {
        if (ours == null) {
            return;
        }
        if (data.hasExistingProposal()) {
            ours.currency.setText(String.valueOf(data.offerCurrency()), false);
            theirs.currency.setText(String.valueOf(data.requestCurrency()), false);
        } else {
            ours.currency.setText("", false);
            theirs.currency.setText("", false);
        }
    }

    private static void copyItemsIntoSlots(List<ItemStack> source, List<ItemStack> target) {
        for (int i = 0; i < Math.min(source.size(), MAX_TRADE_ITEMS); i++) {
            ItemStack item = source.get(i);
            target.set(i, item == null ? ItemStack.EMPTY : item.copy());
        }
    }

    private void sendAction(TradeScreenActionPacket.Action action) {
        if (!pending.begin(KEY_ACTION, ticks())) {
            return;
        }
        confirm.disarm();
        ModNetwork.CHANNEL.sendToServer(
                new TradeScreenActionPacket(
                        action,
                        data.targetNationId(),
                        TradeWindowStatePolicy.parseCurrency(ours == null ? "" : ours.currency.getValue()),
                        TradeWindowStatePolicy.parseCurrency(theirs == null ? "" : theirs.currency.getValue()),
                        compactItems(offerSlots),
                        compactItems(requestSlots)
                ));
        refresh();
    }

    private static List<ItemStack> compactItems(List<ItemStack> source) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack item : source) {
            if (item != null && !item.isEmpty()) {
                result.add(item.copy());
            }
        }
        return result;
    }

    private boolean readOnly() {
        return data.hasExistingProposal() && !data.weAreProposer();
    }

    @Override
    public void onClose() {
        TradeClientHooks.onScreenClosed();
        super.onClose();
    }
}
