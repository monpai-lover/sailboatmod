package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.monpai.sailboatmod.client.ui.bank.BankLogic;
import com.monpai.sailboatmod.client.ui.kit.ActionButton;
import com.monpai.sailboatmod.client.ui.kit.TabBar;
import com.monpai.sailboatmod.client.ui.kit.ThemedContainerScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.client.ui.state.PendingRequests;
import com.monpai.sailboatmod.economy.GoldStandardEconomy;
import com.monpai.sailboatmod.menu.BankMenu;
import com.monpai.sailboatmod.nation.model.LoanAccountView;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.BankActionPacket;
import com.monpai.sailboatmod.network.packet.SyncTreasuryPacket;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexWrap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Nation bank: currency, item storage and loans.
 *
 * <p>Every button sends the same {@link BankActionPacket} the previous screen sent; the server checks membership,
 * {@code MANAGE_TREASURY} and funds. Items are deposited by shift-clicking the player's inventory, handled by
 * {@link BankMenu#quickMoveStack} exactly as before. The screen redraws whenever {@link SyncTreasuryPacket} refreshes the
 * client cache.</p>
 */
public class BankScreen extends ThemedContainerScreen<BankMenu> {
    private static final String REQUEST = "bank";
    private static final float WIDTH = 340;
    /** Largest amount the old 16-character box could hold. */
    private static final long MAX_AMOUNT = 9_999_999_999_999_999L;

    private final PendingRequests pending = new PendingRequests();
    private final Map<BankLogic.Page, UIElement> pages = new EnumMap<>(BankLogic.Page.class);
    private final TabBar<BankLogic.Page> pageTabs;
    private final TabBar<BankLogic.LoanAccount> loanTabs;
    private BankLogic.Page page = BankLogic.Page.TREASURY;
    private BankLogic.LoanAccount loanAccount = BankLogic.LoanAccount.PERSONAL;

    // header
    private final Label headerBalance = Ui.text("", "sm-price");

    // treasury
    private final Label treasuryBalance = Ui.figure(Component.empty(), "sm-price");
    private TextField amountField;
    private final Map<BankLogic.CurrencyAction, ActionButton> currencyButtons = new EnumMap<>(BankLogic.CurrencyAction.class);

    // storage
    private final ScrollerView itemList = Ui.scroller();
    private final UIElement selectionSlot = Ui.row();
    private final Label selectionName = Ui.text("", "sm-title");
    private final Label selectionCount = Ui.text("", "sm-muted");
    private final Label selectionDepositor = Ui.text("", "sm-muted");
    private ActionButton withdrawItemButton;
    private UIElement inventory;
    private int selectedSlot = -1;
    private ItemStack selectedStack = ItemStack.EMPTY;

    // loans
    private final UIElement loanFigures = Ui.column();
    private final Label loanHint = Ui.paragraph(Component.empty(), "sm-muted");
    private TextField loanAmountField;
    private ActionButton borrowButton;
    private ActionButton repayButton;

    // last cache contents shown; the cache swaps its lists on every sync
    private Object shownItems;
    private long shownBalance = Long.MIN_VALUE;
    private LoanAccountView shownPersonal;
    private LoanAccountView shownNation;

    public BankScreen(BankMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        pageTabs = TabBar.horizontal(this::openPage);
        pageTabs.add(BankLogic.Page.TREASURY, new ItemStack(Items.GOLD_INGOT), Component.translatable("screen.sailboatmod.bank.mode_treasury"))
                .add(BankLogic.Page.STORAGE, new ItemStack(Items.CHEST), Component.translatable("screen.sailboatmod.bank.mode_storage"))
                .add(BankLogic.Page.LOANS, new ItemStack(Items.WRITABLE_BOOK), Component.translatable("screen.sailboatmod.bank.mode_loans"));
        loanTabs = TabBar.horizontal(account -> {
            loanAccount = account;
            refresh();
        });
        loanTabs.add(BankLogic.LoanAccount.PERSONAL, new ItemStack(Items.EMERALD), Component.translatable("screen.sailboatmod.bank.loan_personal"))
                .add(BankLogic.LoanAccount.NATION, new ItemStack(Items.WHITE_BANNER), Component.translatable("screen.sailboatmod.bank.loan_nation"));

        UIElement body = Ui.column();
        body.addChild(pageTabs.element());
        pages.put(BankLogic.Page.TREASURY, buildTreasuryPage());
        pages.put(BankLogic.Page.STORAGE, buildStoragePage());
        pages.put(BankLogic.Page.LOANS, buildLoansPage());
        pages.values().forEach(body::addChild);

        UIElement balanceChip = Ui.row("sm-chip");
        balanceChip.layout(l -> l.width(78).height(Ui.CONTROL_HEIGHT).paddingHorizontal(Ui.GAP_S).gapAll(Ui.GAP_XS));
        headerBalance.textStyle(s -> s.adaptiveWidth(false));
        headerBalance.layout(l -> l.width(0).flexGrow(1).minWidth(0));
        headerBalance.setOverflowVisible(false);
        balanceChip.addChildren(Ui.icon(new ItemStack(Items.GOLD_INGOT), 12), headerBalance);
        balanceChip.style(s -> s.tooltips(Component.translatable("screen.sailboatmod.bank.currency_label")));

        UIElement window = window(title, body, balanceChip);
        window.layout(l -> l.width(WIDTH).height(300));
        body.layout(l -> l.height(0).flexBasis(0).flexGrow(1).flexShrink(1).minHeight(0));
        install(window);
        bindAllSlots();
        refresh();
    }

    // ================================================================= pages

    private UIElement buildTreasuryPage() {
        UIElement page = Ui.column("sm-pane");
        page.layout(l -> l.paddingAll(Ui.GAP_L).gapAll(Ui.GAP_M));
        UIElement balance = Ui.row();
        balance.layout(l -> l.gapAll(Ui.GAP_S));
        balance.addChildren(Ui.slot(new ItemStack(Items.GOLD_BLOCK), Ui.SLOT),
                Ui.muted(Component.translatable("screen.sailboatmod.bank.currency_label")), Ui.spacer(), treasuryBalance);

        amountField = Ui.numberField("", Component.translatable("screen.sailboatmod.bank.amount"), MAX_AMOUNT, value -> refreshValidation());
        amountField.layout(l -> l.flexGrow(1).flexBasis(0).flexShrink(1).minWidth(40));
        UIElement actions = Ui.row();
        actions.layout(l -> l.widthPercent(100).minWidth(0).flexWrap(FlexWrap.WRAP).gapAll(Ui.GAP_S));
        actions.addChild(amountField);
        for (BankLogic.CurrencyAction action : BankLogic.CurrencyAction.values()) {
            String key = switch (action) {
                case DEPOSIT -> "screen.sailboatmod.bank.deposit";
                case WITHDRAW -> "screen.sailboatmod.bank.withdraw";
                case WITHDRAW_AS_GOLD -> "screen.sailboatmod.bank.withdraw_as_gold";
            };
            ActionButton button = new ActionButton(Component.translatable(key),
                    action == BankLogic.CurrencyAction.DEPOSIT ? Ui.Tone.PRIMARY : Ui.Tone.SECONDARY,
                    () -> sendCurrency(action)).inRow();
            currencyButtons.put(action, button);
            actions.addChild(button.element());
        }
        page.addChildren(balance, Ui.divider(), Ui.muted(Component.translatable("screen.sailboatmod.bank.amount_label")), actions,
                Ui.paragraph(Component.translatable("screen.sailboatmod.bank.treasury_help"), "sm-muted"));
        return page;
    }

    private UIElement buildStoragePage() {
        UIElement page = Ui.column();
        page.layout(l -> l.flexGrow(1).flexShrink(1).flexBasis(0).minHeight(0).gapAll(Ui.GAP_S));

        UIElement top = Ui.row();
        top.layout(l -> l.gapAll(Ui.GAP_M).alignItems(AlignItems.STRETCH).flexGrow(1).flexBasis(0).minHeight(40));
        UIElement listColumn = Ui.column();
        listColumn.layout(l -> l.flexGrow(1).minWidth(0).gapAll(Ui.GAP_XS));
        Label storageHeading = Ui.muted(Component.translatable("screen.sailboatmod.bank.items_title"));
        // A fixed single line avoids LDLib2's adaptive-height rounding loop at fractional centred positions.
        storageHeading.textStyle(s -> s.adaptiveHeight(false));
        storageHeading.layout(l -> l.height(9).flexShrink(0));
        listColumn.addChildren(storageHeading, itemList);

        UIElement side = Ui.column("sm-pane");
        side.layout(l -> l.widthPercent(100).paddingAll(Ui.GAP_M).gapAll(Ui.GAP_S));
        UIElement head = Ui.row();
        head.layout(l -> l.gapAll(Ui.GAP_S));
        UIElement names = Ui.column();
        names.layout(l -> l.flexGrow(1).minWidth(0));
        names.addChildren(selectionName, selectionCount);
        head.addChildren(selectionSlot, names);
        withdrawItemButton = new ActionButton(Component.translatable("screen.sailboatmod.bank.withdraw_item"), Ui.Tone.PRIMARY, this::sendWithdrawItem);
        withdrawItemButton.element().layout(l -> l.widthPercent(100));
        side.addChildren(head, selectionDepositor, Ui.spacer(), withdrawItemButton.element());
        ScrollerView sideViewport = Ui.scroller();
        sideViewport.layout(l -> l.width(118).flexGrow(0).flexShrink(0));
        sideViewport.addScrollViewChild(side);
        top.addChildren(listColumn, sideViewport);

        inventory = playerInventory(menu.playerInventoryStart());
        page.addChildren(top, Ui.paragraph(Component.translatable("screen.sailboatmod.bank.storage_shift_hint"), "sm-muted"),
                Ui.divider(), inventory);
        return page;
    }

    private UIElement buildLoansPage() {
        UIElement page = Ui.column("sm-pane");
        page.layout(l -> l.flexGrow(1).flexShrink(1).flexBasis(0).minHeight(0).paddingAll(Ui.GAP_L).gapAll(Ui.GAP_S));
        loanFigures.layout(l -> l.gapAll(Ui.GAP_XS).widthPercent(100));
        ScrollerView figuresViewport = Ui.scroller();
        figuresViewport.addScrollViewChild(loanFigures);
        loanAmountField = Ui.numberField("", Component.translatable("screen.sailboatmod.bank.amount"), MAX_AMOUNT, value -> refreshValidation());
        loanAmountField.layout(l -> l.flexGrow(1).flexBasis(0).flexShrink(1).minWidth(40));
        borrowButton = new ActionButton(Component.translatable("screen.sailboatmod.bank.loan_borrow"), Ui.Tone.PRIMARY, () -> sendLoan(true)).inRow();
        repayButton = new ActionButton(Component.translatable("screen.sailboatmod.bank.loan_repay"), Ui.Tone.SECONDARY, () -> sendLoan(false)).inRow();
        UIElement actions = Ui.row();
        actions.layout(l -> l.widthPercent(100).minWidth(0).flexWrap(FlexWrap.WRAP).gapAll(Ui.GAP_S));
        actions.addChildren(loanAmountField, borrowButton.element(), repayButton.element());
        page.addChildren(loanTabs.element(), figuresViewport, Ui.divider(),
                Ui.muted(Component.translatable("screen.sailboatmod.bank.amount_label")), actions, loanHint);
        return page;
    }

    // ================================================================= actions (same packets as before)

    private void openPage(BankLogic.Page next) {
        page = next;
        if (next != BankLogic.Page.STORAGE) {
            clearSelection();
        }
        refresh();
    }

    private void sendCurrency(BankLogic.CurrencyAction action) {
        long amount = BankLogic.parseAmount(amountField.getValue());
        if (amount < 0) {
            toast.show(Component.translatable("command.sailboatmod.nation.treasury.invalid_amount"), false, ticks());
            return;
        }
        if (!pending.begin(REQUEST, ticks())) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new BankActionPacket(action.packetAction, menu.getBankPos(), amount, 0));
        toast.show(Component.translatable(action == BankLogic.CurrencyAction.DEPOSIT
                ? "screen.sailboatmod.bank.depositing" : "screen.sailboatmod.bank.withdrawing", amount), true, ticks());
        refresh();
    }

    private void sendWithdrawItem() {
        ItemStack current = currentSelection();
        if (current.isEmpty()) {
            toast.show(Component.translatable("screen.sailboatmod.bank.select_item"), false, ticks());
            return;
        }
        if (!pending.begin(REQUEST, ticks())) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new BankActionPacket(BankActionPacket.Action.WITHDRAW_ITEM, menu.getBankPos(),
                current.getCount(), selectedSlot, current.copy()));
        toast.show(Component.translatable("screen.sailboatmod.bank.withdrawing_item"), true, ticks());
        clearSelection();
        refresh();
    }

    private void sendLoan(boolean borrow) {
        long amount = BankLogic.parseAmount(loanAmountField.getValue());
        if (amount < 0) {
            toast.show(Component.translatable("command.sailboatmod.nation.treasury.invalid_amount"), false, ticks());
            return;
        }
        if (!pending.begin(REQUEST, ticks())) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new BankActionPacket(BankLogic.loanAction(loanAccount, borrow), menu.getBankPos(), amount, 0));
        toast.show(Component.translatable(borrow ? "screen.sailboatmod.bank.borrowing" : "screen.sailboatmod.bank.repaying", amount), true, ticks());
        refresh();
    }

    // ================================================================= refresh

    @Override
    protected void onUiTick() {
        if (cacheChanged()) {
            pending.resolve(REQUEST);
            refresh();
        }
        pending.pollTimedOut(ticks()).ifPresent(key -> {
            toast.show(Component.translatable("screen.sailboatmod.ui.no_reply_server"), false, ticks());
            refresh();
        });
    }

    private boolean cacheChanged() {
        return SyncTreasuryPacket.ClientTreasuryCache.getItems() != shownItems
                || SyncTreasuryPacket.ClientTreasuryCache.getBalance() != shownBalance
                || SyncTreasuryPacket.ClientTreasuryCache.getPersonalLoan() != shownPersonal
                || SyncTreasuryPacket.ClientTreasuryCache.getNationLoan() != shownNation;
    }

    private void refresh() {
        shownItems = SyncTreasuryPacket.ClientTreasuryCache.getItems();
        shownBalance = SyncTreasuryPacket.ClientTreasuryCache.getBalance();
        shownPersonal = SyncTreasuryPacket.ClientTreasuryCache.getPersonalLoan();
        shownNation = SyncTreasuryPacket.ClientTreasuryCache.getNationLoan();

        String balance = GoldStandardEconomy.formatBalance(shownBalance);
        headerBalance.setValue(Component.literal(balance));
        treasuryBalance.setValue(Component.literal(balance));

        pageTabs.select(page);
        pages.forEach((p, element) -> element.setDisplay(p == page));
        boolean storage = page == BankLogic.Page.STORAGE;
        inventory.setDisplay(storage);
        menu.setPlayerInventoryVisible(storage);

        refreshItems();
        refreshLoans();
        refreshValidation();
    }

    private void refreshItems() {
        List<ItemStack> items = SyncTreasuryPacket.ClientTreasuryCache.getItems();
        // Keep the selection only while the same item still sits in that treasury slot.
        if (selectedSlot >= 0 && (selectedSlot >= items.size()
                || !ItemStack.isSameItemSameComponents(items.get(selectedSlot), selectedStack))) {
            clearSelection();
        }
        itemList.clearAllScrollViewChildren();
        boolean any = false;
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            any = true;
            int slotIndex = i;
            UIElement row = Ui.itemRow(stack, stack.getHoverName(), Component.literal("x" + stack.getCount()), "sm-price");
            Ui.setClass(row, "selected", slotIndex == selectedSlot);
            String depositor = SyncTreasuryPacket.ClientTreasuryCache.getDepositor(slotIndex);
            if (!depositor.isBlank()) {
                row.style(s -> s.tooltips(Component.translatable("screen.sailboatmod.bank.depositor", depositor).withStyle(ChatFormatting.GRAY)));
            }
            Ui.clickable(row, () -> {
                selectedSlot = slotIndex;
                selectedStack = stack.copy();
                refresh();
            });
            itemList.addScrollViewChild(row);
        }
        if (!any) {
            itemList.addScrollViewChild(Ui.emptyState(Component.translatable("screen.sailboatmod.ui.treasury_empty")));
        }

        ItemStack current = currentSelection();
        selectionSlot.clearAllChildren();
        selectionSlot.addChild(Ui.slot(current, Ui.SLOT));
        if (current.isEmpty()) {
            selectionName.setValue(Component.translatable("screen.sailboatmod.bank.select_item"));
            selectionCount.setValue(Component.empty());
            selectionDepositor.setValue(Component.empty());
        } else {
            selectionName.setValue(current.getHoverName());
            selectionCount.setValue(Component.literal("x" + current.getCount()));
            String depositor = SyncTreasuryPacket.ClientTreasuryCache.getDepositor(selectedSlot);
            selectionDepositor.setValue(depositor.isBlank() ? Component.empty()
                    : Component.translatable("screen.sailboatmod.bank.depositor", depositor));
        }
        withdrawItemButton.sync(!current.isEmpty(), pending.isPending(REQUEST), false);
    }

    private void refreshLoans() {
        loanTabs.select(loanAccount);
        LoanAccountView view = loanAccount == BankLogic.LoanAccount.PERSONAL ? shownPersonal : shownNation;
        loanFigures.clearAllChildren();
        if (!view.enabled()) {
            loanFigures.addChild(Ui.text(Component.translatable("screen.sailboatmod.bank.loan_disabled"), "sm-down"));
        } else {
            figure("screen.sailboatmod.ui.loan.outstanding", GoldStandardEconomy.formatBalance(view.outstanding()), "sm-price");
            figure("screen.sailboatmod.ui.loan.max", GoldStandardEconomy.formatBalance(view.maxBorrowable()));
            figure("screen.sailboatmod.ui.loan.principal", GoldStandardEconomy.formatBalance(view.principal()));
            figure("screen.sailboatmod.ui.loan.accrued", GoldStandardEconomy.formatBalance(view.accruedInterest()));
            figure("screen.sailboatmod.ui.loan.next_interest", GoldStandardEconomy.formatBalance(view.nextInterestCharge()));
            figure("screen.sailboatmod.ui.loan.due", formatWhen(view.nextDueAt()));
            Label delinquent = Ui.keyValue(loanFigures, Component.translatable("screen.sailboatmod.ui.loan.delinquent"),
                    view.delinquent() ? "sm-down" : "sm-up");
            delinquent.setValue(Component.translatable(view.delinquent() ? "screen.sailboatmod.bank.loan_yes" : "screen.sailboatmod.bank.loan_no"));
        }
        loanHint.setValue(Component.translatable(BankLogic.loanHintKey(loanAccount)));
    }

    private void figure(String key, String value, String... classes) {
        Ui.keyValue(loanFigures, Component.translatable(key), classes).setValue(Component.literal(value));
    }

    private void refreshValidation() {
        boolean busy = pending.isPending(REQUEST);
        boolean amountBad = BankLogic.isInvalidInput(amountField.getValue());
        boolean amountOk = BankLogic.parseAmount(amountField.getValue()) > 0;
        Ui.setClass(amountField, "sm-invalid", amountBad);
        currencyButtons.values().forEach(button -> button.sync(amountOk, busy, false));

        LoanAccountView view = loanAccount == BankLogic.LoanAccount.PERSONAL ? shownPersonal : shownNation;
        boolean loanBad = BankLogic.isInvalidInput(loanAmountField.getValue());
        boolean loanOk = BankLogic.parseAmount(loanAmountField.getValue()) > 0 && view != null && view.enabled();
        Ui.setClass(loanAmountField, "sm-invalid", loanBad);
        borrowButton.sync(loanOk, busy, false);
        repayButton.sync(loanOk, busy, false);
    }

    private ItemStack currentSelection() {
        List<ItemStack> items = SyncTreasuryPacket.ClientTreasuryCache.getItems();
        if (selectedSlot < 0 || selectedSlot >= items.size()) {
            return ItemStack.EMPTY;
        }
        return items.get(selectedSlot);
    }

    private void clearSelection() {
        selectedSlot = -1;
        selectedStack = ItemStack.EMPTY;
    }

    private static String formatWhen(long epochMillis) {
        if (epochMillis <= 0L) {
            return "-";
        }
        return new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date(epochMillis));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
