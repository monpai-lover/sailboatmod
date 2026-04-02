package com.monpai.sailboatmod.client.modernui;

import com.monpai.sailboatmod.client.TradeOverviewConsumer;
import com.monpai.sailboatmod.client.screen.nation.TradeScreen;
import com.monpai.sailboatmod.nation.menu.TradeScreenData;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.TradeScreenActionPacket;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.mc.ScreenCallback;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class TradeModernFragment extends Fragment implements ScreenCallback, TradeOverviewConsumer {
    private TradeScreenData data;
    private final List<ItemStack> offerSlots = new ArrayList<>();
    private final List<ItemStack> requestSlots = new ArrayList<>();

    private int selectedOurTreasuryIndex;
    private int selectedTargetTreasuryIndex;
    private int selectedOfferIndex;
    private int selectedRequestIndex;

    private TextView titleView;
    private TextView statusView;
    private TextView ourTreasuryBody;
    private TextView offerBody;
    private TextView targetTreasuryBody;
    private TextView requestBody;
    private EditText offerCurrencyInput;
    private EditText requestCurrencyInput;
    private Button proposeButton;
    private Button acceptButton;
    private Button rejectButton;
    private Button counterButton;
    private Button cancelButton;

    public TradeModernFragment(TradeScreenData data) {
        this.data = data == null ? TradeScreenData.empty() : data;
        initSlots();
    }

    @Override
    public boolean isForTrade(String targetNationId) {
        String current = data.targetNationId();
        if (targetNationId == null || targetNationId.isBlank()) {
            return current == null || current.isBlank();
        }
        return targetNationId.equals(current);
    }

    @Override
    public void updateData(TradeScreenData data) {
        this.data = data == null ? TradeScreenData.empty() : data;
        initSlots();
        clampSelections();
        if (titleView != null) {
            titleView.post(this::refreshViews);
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable DataSet savedInstanceState) {
        ScrollView scrollView = new ScrollView(requireContext());
        LinearLayout root = ModernUiScreenHelper.createRoot(new LinearLayout(requireContext()));
        scrollView.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        titleView = ModernUiScreenHelper.createHeader(new TextView(requireContext()), "Trade", 20);
        statusView = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        root.addView(titleView, ModernUiScreenHelper.matchWidthWrap());
        root.addView(statusView, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout topRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        Button refreshButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Refresh");
        refreshButton.setOnClickListener(v -> sendAction(TradeScreenActionPacket.Action.REFRESH));
        Button classicButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Classic Controls");
        classicButton.setOnClickListener(v -> Minecraft.getInstance().setScreen(new TradeScreen(data)));
        topRow.addView(refreshButton, ModernUiScreenHelper.wrap());
        topRow.addView(classicButton, ModernUiScreenHelper.wrap());
        root.addView(topRow, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout currencyCard = ModernUiScreenHelper.createCard(new LinearLayout(requireContext()));
        currencyCard.addView(ModernUiScreenHelper.createHeader(new TextView(requireContext()), "Currency Terms", 14), ModernUiScreenHelper.matchWidthWrap());
        currencyCard.addView(label("Offer Currency"), ModernUiScreenHelper.matchWidthWrap());
        offerCurrencyInput = ModernUiScreenHelper.createInput(new EditText(requireContext()), "");
        currencyCard.addView(offerCurrencyInput, ModernUiScreenHelper.matchWidthWrap());
        currencyCard.addView(label("Request Currency"), ModernUiScreenHelper.matchWidthWrap());
        requestCurrencyInput = ModernUiScreenHelper.createInput(new EditText(requireContext()), "");
        currencyCard.addView(requestCurrencyInput, ModernUiScreenHelper.matchWidthWrap());
        root.addView(currencyCard, ModernUiScreenHelper.matchWidthWrap());

        root.addView(buildTreasuryCard(true), ModernUiScreenHelper.matchWidthWrap());
        root.addView(buildTreasuryCard(false), ModernUiScreenHelper.matchWidthWrap());

        LinearLayout actionCard = ModernUiScreenHelper.createCard(new LinearLayout(requireContext()));
        actionCard.addView(ModernUiScreenHelper.createHeader(new TextView(requireContext()), "Proposal Actions", 14), ModernUiScreenHelper.matchWidthWrap());
        LinearLayout rowA = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        proposeButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Propose");
        proposeButton.setOnClickListener(v -> sendAction(TradeScreenActionPacket.Action.PROPOSE));
        acceptButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Accept");
        acceptButton.setOnClickListener(v -> sendAction(TradeScreenActionPacket.Action.ACCEPT));
        rejectButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Reject");
        rejectButton.setOnClickListener(v -> sendAction(TradeScreenActionPacket.Action.REJECT));
        rowA.addView(proposeButton, ModernUiScreenHelper.wrap());
        rowA.addView(acceptButton, ModernUiScreenHelper.wrap());
        rowA.addView(rejectButton, ModernUiScreenHelper.wrap());
        actionCard.addView(rowA, ModernUiScreenHelper.matchWidthWrap());
        LinearLayout rowB = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        counterButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Counter");
        counterButton.setOnClickListener(v -> sendAction(TradeScreenActionPacket.Action.COUNTER_OFFER));
        cancelButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Cancel Proposal");
        cancelButton.setOnClickListener(v -> sendAction(TradeScreenActionPacket.Action.CANCEL));
        rowB.addView(counterButton, ModernUiScreenHelper.wrap());
        rowB.addView(cancelButton, ModernUiScreenHelper.wrap());
        actionCard.addView(rowB, ModernUiScreenHelper.matchWidthWrap());
        root.addView(actionCard, ModernUiScreenHelper.matchWidthWrap());

        refreshViews();
        return scrollView;
    }

    private LinearLayout buildTreasuryCard(boolean ours) {
        LinearLayout card = ModernUiScreenHelper.createCard(new LinearLayout(requireContext()));
        card.addView(ModernUiScreenHelper.createHeader(new TextView(requireContext()), ours ? "Our Side" : "Target Side", 14), ModernUiScreenHelper.matchWidthWrap());
        TextView treasuryBody = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        TextView selectionBody = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        if (ours) {
            ourTreasuryBody = treasuryBody;
            offerBody = selectionBody;
        } else {
            targetTreasuryBody = treasuryBody;
            requestBody = selectionBody;
        }
        card.addView(treasuryBody, ModernUiScreenHelper.matchWidthWrap());
        card.addView(selectionBody, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout navRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        Button prevButton = ModernUiScreenHelper.createButton(new Button(requireContext()), ours ? "< Treasury" : "< Target");
        prevButton.setOnClickListener(v -> {
            if (ours) {
                selectedOurTreasuryIndex = moveSelection(selectedOurTreasuryIndex, data.ourTreasuryItems().size(), -1);
            } else {
                selectedTargetTreasuryIndex = moveSelection(selectedTargetTreasuryIndex, data.targetTreasuryItems().size(), -1);
            }
            refreshViews();
        });
        Button nextButton = ModernUiScreenHelper.createButton(new Button(requireContext()), ours ? "Treasury >" : "Target >");
        nextButton.setOnClickListener(v -> {
            if (ours) {
                selectedOurTreasuryIndex = moveSelection(selectedOurTreasuryIndex, data.ourTreasuryItems().size(), 1);
            } else {
                selectedTargetTreasuryIndex = moveSelection(selectedTargetTreasuryIndex, data.targetTreasuryItems().size(), 1);
            }
            refreshViews();
        });
        navRow.addView(prevButton, ModernUiScreenHelper.wrap());
        navRow.addView(nextButton, ModernUiScreenHelper.wrap());
        card.addView(navRow, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout actionRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        Button addButton = ModernUiScreenHelper.createButton(new Button(requireContext()), ours ? "Add To Offer" : "Add To Request");
        addButton.setOnClickListener(v -> {
            if (ours) {
                addSelectedTreasuryItemTo(offerSlots, true);
            } else {
                addSelectedTreasuryItemTo(requestSlots, false);
            }
            refreshViews();
        });
        Button removeButton = ModernUiScreenHelper.createButton(new Button(requireContext()), ours ? "Remove Offer Item" : "Remove Request Item");
        removeButton.setOnClickListener(v -> {
            if (ours) {
                removeSelectedTradeItem(offerSlots, true);
            } else {
                removeSelectedTradeItem(requestSlots, false);
            }
            refreshViews();
        });
        actionRow.addView(addButton, ModernUiScreenHelper.wrap());
        actionRow.addView(removeButton, ModernUiScreenHelper.wrap());
        card.addView(actionRow, ModernUiScreenHelper.matchWidthWrap());
        return card;
    }

    private TextView label(String text) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextSize(11);
        return view;
    }

    private void refreshViews() {
        clampSelections();
        syncCurrencyInputs();
        titleView.setText(safe(data.ourNationName()) + " <-> " + safe(data.targetNationName()));
        statusView.setText(buildStatusText());
        ourTreasuryBody.setText(buildTreasuryText(true));
        offerBody.setText(buildTradeSlotsText(true));
        targetTreasuryBody.setText(buildTreasuryText(false));
        requestBody.setText(buildTradeSlotsText(false));

        boolean hasProposal = data.hasExistingProposal();
        boolean weProposed = hasProposal && data.weAreProposer();
        boolean theyProposed = hasProposal && !data.weAreProposer();
        boolean canManage = data.canManageTreasury();
        boolean readOnly = theyProposed;

        offerCurrencyInput.setEnabled(!readOnly);
        requestCurrencyInput.setEnabled(!readOnly);
        proposeButton.setEnabled(!hasProposal && canManage);
        acceptButton.setEnabled(theyProposed && canManage);
        rejectButton.setEnabled(theyProposed);
        counterButton.setEnabled(theyProposed && canManage);
        cancelButton.setEnabled(weProposed);
    }

    private void initSlots() {
        offerSlots.clear();
        requestSlots.clear();
        for (int i = 0; i < TradeScreenData.MAX_TRADE_ITEMS; i++) {
            offerSlots.add(ItemStack.EMPTY);
            requestSlots.add(ItemStack.EMPTY);
        }
        if (data.hasExistingProposal()) {
            for (int i = 0; i < Math.min(data.offerItems().size(), offerSlots.size()); i++) {
                offerSlots.set(i, data.offerItems().get(i).copy());
            }
            for (int i = 0; i < Math.min(data.requestItems().size(), requestSlots.size()); i++) {
                requestSlots.set(i, data.requestItems().get(i).copy());
            }
        }
    }

    private void syncCurrencyInputs() {
        if (offerCurrencyInput != null && !offerCurrencyInput.isFocused()) {
            String next = data.hasExistingProposal() ? String.valueOf(data.offerCurrency()) : offerCurrencyInput.getText().toString();
            if (!next.equals(offerCurrencyInput.getText().toString())) {
                offerCurrencyInput.setText(next.equals("0") && !data.hasExistingProposal() ? "" : next);
            }
        }
        if (requestCurrencyInput != null && !requestCurrencyInput.isFocused()) {
            String next = data.hasExistingProposal() ? String.valueOf(data.requestCurrency()) : requestCurrencyInput.getText().toString();
            if (!next.equals(requestCurrencyInput.getText().toString())) {
                requestCurrencyInput.setText(next.equals("0") && !data.hasExistingProposal() ? "" : next);
            }
        }
    }

    private String buildStatusText() {
        StringBuilder builder = new StringBuilder();
        builder.append("Relation: ").append(safe(data.diplomacyStatus()))
                .append("\nOur balance: ").append(data.ourTreasuryBalance())
                .append(" | Target balance: ").append(data.targetTreasuryBalance());
        if (data.hasExistingProposal()) {
            builder.append("\nProposal: ")
                    .append(data.weAreProposer() ? "Waiting for target" : "Incoming proposal")
                    .append(" | Remaining: ").append(data.proposalRemainingSeconds()).append("s");
        } else {
            builder.append("\nNo active proposal.");
        }
        return builder.toString();
    }

    private String buildTreasuryText(boolean ours) {
        List<ItemStack> items = ours ? data.ourTreasuryItems() : data.targetTreasuryItems();
        int selectedIndex = ours ? selectedOurTreasuryIndex : selectedTargetTreasuryIndex;
        if (items.isEmpty()) {
            return ours ? "Our treasury is empty." : "Target treasury preview is unavailable.";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(ours ? "Treasury Preview" : "Target Preview");
        appendWindow(builder, items, selectedIndex, 6, stack -> stack.getHoverName().getString() + " x" + stack.getCount());
        return builder.toString();
    }

    private String buildTradeSlotsText(boolean offer) {
        List<ItemStack> slots = offer ? offerSlots : requestSlots;
        int selectedIndex = offer ? selectedOfferIndex : selectedRequestIndex;
        StringBuilder builder = new StringBuilder();
        builder.append(offer ? "Offer Package" : "Request Package")
                .append('\n')
                .append("Currency: ")
                .append(offer ? parseLong(offerCurrencyInput == null ? "" : offerCurrencyInput.getText().toString()) : parseLong(requestCurrencyInput == null ? "" : requestCurrencyInput.getText().toString()));
        appendWindow(builder, slots, selectedIndex, 5, stack -> stack.isEmpty() ? "[Empty]" : stack.getHoverName().getString() + " x" + stack.getCount());
        return builder.toString();
    }

    private void addSelectedTreasuryItemTo(List<ItemStack> slots, boolean ours) {
        if (data.hasExistingProposal() && !data.weAreProposer()) {
            return;
        }
        List<ItemStack> source = ours ? data.ourTreasuryItems() : data.targetTreasuryItems();
        int sourceIndex = ours ? selectedOurTreasuryIndex : selectedTargetTreasuryIndex;
        if (source.isEmpty() || sourceIndex < 0 || sourceIndex >= source.size()) {
            return;
        }
        ItemStack candidate = source.get(sourceIndex);
        if (candidate.isEmpty()) {
            return;
        }
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).isEmpty()) {
                slots.set(i, candidate.copy());
                if (ours) {
                    selectedOfferIndex = i;
                } else {
                    selectedRequestIndex = i;
                }
                return;
            }
        }
    }

    private void removeSelectedTradeItem(List<ItemStack> slots, boolean offer) {
        int selectedIndex = offer ? selectedOfferIndex : selectedRequestIndex;
        if (selectedIndex < 0 || selectedIndex >= slots.size()) {
            return;
        }
        slots.set(selectedIndex, ItemStack.EMPTY);
    }

    private void sendAction(TradeScreenActionPacket.Action action) {
        ModNetwork.CHANNEL.sendToServer(new TradeScreenActionPacket(
                action,
                data.targetNationId(),
                parseLong(offerCurrencyInput == null ? "" : offerCurrencyInput.getText().toString()),
                parseLong(requestCurrencyInput == null ? "" : requestCurrencyInput.getText().toString()),
                compactStacks(offerSlots),
                compactStacks(requestSlots)
        ));
    }

    private List<ItemStack> compactStacks(List<ItemStack> slots) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack stack : slots) {
            if (!stack.isEmpty()) {
                out.add(stack.copy());
            }
        }
        return out;
    }

    private void appendWindow(StringBuilder builder, List<ItemStack> values, int selectedIndex, int windowSize,
                              java.util.function.Function<ItemStack, String> formatter) {
        if (values.isEmpty()) {
            builder.append("\n- None");
            return;
        }
        int safeIndex = clampSelection(selectedIndex, values.size());
        int start = Math.max(0, safeIndex - Math.max(1, windowSize / 2));
        int end = Math.min(values.size(), start + windowSize);
        if (end - start < windowSize) {
            start = Math.max(0, end - windowSize);
        }
        for (int i = start; i < end; i++) {
            builder.append('\n')
                    .append(i == safeIndex ? "> " : "- ")
                    .append(formatter.apply(values.get(i)));
        }
    }

    private void clampSelections() {
        selectedOurTreasuryIndex = clampSelection(selectedOurTreasuryIndex, data.ourTreasuryItems().size());
        selectedTargetTreasuryIndex = clampSelection(selectedTargetTreasuryIndex, data.targetTreasuryItems().size());
        selectedOfferIndex = clampSelection(selectedOfferIndex, offerSlots.size());
        selectedRequestIndex = clampSelection(selectedRequestIndex, requestSlots.size());
    }

    private int clampSelection(int index, int size) {
        if (size <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(index, size - 1));
    }

    private int moveSelection(int current, int size, int delta) {
        if (size <= 0) {
            return 0;
        }
        return Math.floorMod(current + delta, size);
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
