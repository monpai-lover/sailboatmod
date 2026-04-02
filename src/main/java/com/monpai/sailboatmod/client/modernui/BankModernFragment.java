package com.monpai.sailboatmod.client.modernui;

import com.monpai.sailboatmod.client.BankOverviewConsumer;
import com.monpai.sailboatmod.client.screen.BankScreen;
import com.monpai.sailboatmod.menu.BankMenu;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.BankActionPacket;
import com.monpai.sailboatmod.network.packet.SyncTreasuryPacket;
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
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class BankModernFragment extends Fragment implements ScreenCallback, BankOverviewConsumer {
    private final BankMenu menu;

    private TextView titleView;
    private TextView statusView;
    private TextView treasuryBody;
    private TextView selectedItemBody;
    private EditText amountInput;
    private Button depositButton;
    private Button withdrawButton;
    private Button withdrawItemButton;
    private Button itemPrevButton;
    private Button itemNextButton;

    private int selectedSlot = -1;

    public BankModernFragment(BankMenu menu) {
        this.menu = menu;
    }

    @Override
    public void refreshData() {
        clampSelection();
        if (titleView != null) {
            titleView.post(this::refreshViews);
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable DataSet savedInstanceState) {
        ScrollView scrollView = new ScrollView(requireContext());
        LinearLayout root = ModernUiScreenHelper.createRoot(new LinearLayout(requireContext()));
        scrollView.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        titleView = ModernUiScreenHelper.createHeader(new TextView(requireContext()), "Treasury Bank", 20);
        statusView = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        root.addView(titleView, ModernUiScreenHelper.matchWidthWrap());
        root.addView(statusView, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout topRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        Button refreshButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Refresh");
        refreshButton.setOnClickListener(v -> refreshViews());
        Button classicButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Classic Controls");
        classicButton.setOnClickListener(v -> openClassicScreen());
        topRow.addView(refreshButton, ModernUiScreenHelper.wrap());
        topRow.addView(classicButton, ModernUiScreenHelper.wrap());
        root.addView(topRow, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout amountCard = ModernUiScreenHelper.createCard(new LinearLayout(requireContext()));
        amountCard.addView(ModernUiScreenHelper.createHeader(new TextView(requireContext()), "Currency", 14), ModernUiScreenHelper.matchWidthWrap());
        amountInput = ModernUiScreenHelper.createInput(new EditText(requireContext()), "");
        amountCard.addView(amountInput, ModernUiScreenHelper.matchWidthWrap());
        LinearLayout amountRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        depositButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Deposit");
        depositButton.setOnClickListener(v -> sendCurrency(BankActionPacket.Action.DEPOSIT_CURRENCY));
        withdrawButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Withdraw");
        withdrawButton.setOnClickListener(v -> sendCurrency(BankActionPacket.Action.WITHDRAW_CURRENCY));
        amountRow.addView(depositButton, ModernUiScreenHelper.wrap());
        amountRow.addView(withdrawButton, ModernUiScreenHelper.wrap());
        amountCard.addView(amountRow, ModernUiScreenHelper.matchWidthWrap());
        root.addView(amountCard, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout itemCard = ModernUiScreenHelper.createCard(new LinearLayout(requireContext()));
        itemCard.addView(ModernUiScreenHelper.createHeader(new TextView(requireContext()), "Treasury Items", 14), ModernUiScreenHelper.matchWidthWrap());
        treasuryBody = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        selectedItemBody = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        itemCard.addView(treasuryBody, ModernUiScreenHelper.matchWidthWrap());
        itemCard.addView(selectedItemBody, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout navRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        itemPrevButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "< Item");
        itemPrevButton.setOnClickListener(v -> {
            selectedSlot = moveSelectedSlot(-1);
            refreshViews();
        });
        itemNextButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Item >");
        itemNextButton.setOnClickListener(v -> {
            selectedSlot = moveSelectedSlot(1);
            refreshViews();
        });
        navRow.addView(itemPrevButton, ModernUiScreenHelper.wrap());
        navRow.addView(itemNextButton, ModernUiScreenHelper.wrap());
        itemCard.addView(navRow, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout itemRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        Button depositItemButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Deposit Held Item");
        depositItemButton.setOnClickListener(v -> {
            ModNetwork.CHANNEL.sendToServer(new BankActionPacket(BankActionPacket.Action.DEPOSIT_ITEM, menu.getBankPos(), 0, 0));
            refreshViews();
        });
        withdrawItemButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Withdraw Selected");
        withdrawItemButton.setOnClickListener(v -> withdrawSelectedItem());
        itemRow.addView(depositItemButton, ModernUiScreenHelper.wrap());
        itemRow.addView(withdrawItemButton, ModernUiScreenHelper.wrap());
        itemCard.addView(itemRow, ModernUiScreenHelper.matchWidthWrap());
        root.addView(itemCard, ModernUiScreenHelper.matchWidthWrap());

        refreshViews();
        return scrollView;
    }

    private void refreshViews() {
        clampSelection();
        titleView.setText("Treasury Bank");
        statusView.setText("Bank: " + menu.getBankPos().toShortString()
                + "\nBalance: " + SyncTreasuryPacket.ClientTreasuryCache.getBalance());
        treasuryBody.setText(buildTreasuryWindow());
        selectedItemBody.setText(buildSelectedItemText());
        boolean hasItems = !nonEmptySlots().isEmpty();
        itemPrevButton.setEnabled(nonEmptySlots().size() > 1);
        itemNextButton.setEnabled(nonEmptySlots().size() > 1);
        withdrawItemButton.setEnabled(hasItems && selectedSlot >= 0);
        depositButton.setEnabled(parseAmount() > 0);
        withdrawButton.setEnabled(parseAmount() > 0);
    }

    private void sendCurrency(BankActionPacket.Action action) {
        long amount = parseAmount();
        if (amount <= 0) {
            statusView.setText("Enter a valid amount.\nBank: " + menu.getBankPos().toShortString());
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new BankActionPacket(action, menu.getBankPos(), amount, 0));
        refreshViews();
    }

    private void withdrawSelectedItem() {
        if (selectedSlot < 0) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new BankActionPacket(BankActionPacket.Action.WITHDRAW_ITEM, menu.getBankPos(), 0, selectedSlot));
        refreshViews();
    }

    private String buildTreasuryWindow() {
        List<Integer> slots = nonEmptySlots();
        if (slots.isEmpty()) {
            return "No items stored.\nUse Deposit Held Item to move the main-hand stack into the treasury.";
        }
        int currentIndex = Math.max(0, slots.indexOf(selectedSlot));
        if (selectedSlot < 0) {
            currentIndex = 0;
        }
        int start = Math.max(0, currentIndex - 3);
        int end = Math.min(slots.size(), start + 7);
        if (end - start < 7) {
            start = Math.max(0, end - 7);
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Stored stacks ").append(currentIndex + 1).append("/").append(slots.size());
        for (int i = start; i < end; i++) {
            int slot = slots.get(i);
            ItemStack stack = SyncTreasuryPacket.ClientTreasuryCache.getItems().get(slot);
            builder.append('\n')
                    .append(slot == selectedSlot ? "> " : "- ")
                    .append(stack.getHoverName().getString())
                    .append(" x").append(stack.getCount())
                    .append(" | slot ").append(slot);
        }
        return builder.toString();
    }

    private String buildSelectedItemText() {
        if (selectedSlot < 0) {
            return "No item selected.";
        }
        ItemStack stack = SyncTreasuryPacket.ClientTreasuryCache.getItems().get(selectedSlot);
        if (stack.isEmpty()) {
            return "No item selected.";
        }
        String depositor = SyncTreasuryPacket.ClientTreasuryCache.getDepositor(selectedSlot);
        return "Selected: " + stack.getHoverName().getString()
                + "\nCount: " + stack.getCount()
                + "\nDepositor: " + (depositor == null || depositor.isBlank() ? "-" : depositor);
    }

    private List<Integer> nonEmptySlots() {
        List<Integer> slots = new ArrayList<>();
        List<ItemStack> items = SyncTreasuryPacket.ClientTreasuryCache.getItems();
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isEmpty()) {
                slots.add(i);
            }
        }
        return slots;
    }

    private void clampSelection() {
        List<Integer> slots = nonEmptySlots();
        if (slots.isEmpty()) {
            selectedSlot = -1;
            return;
        }
        if (!slots.contains(selectedSlot)) {
            selectedSlot = slots.get(0);
        }
    }

    private int moveSelectedSlot(int delta) {
        List<Integer> slots = nonEmptySlots();
        if (slots.isEmpty()) {
            return -1;
        }
        int currentIndex = Math.max(0, slots.indexOf(selectedSlot));
        int nextIndex = Math.floorMod(currentIndex + delta, slots.size());
        return slots.get(nextIndex);
    }

    private long parseAmount() {
        if (amountInput == null) {
            return 0L;
        }
        try {
            return Long.parseLong(amountInput.getText().toString().trim());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private void openClassicScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        Inventory inventory = minecraft.player.getInventory();
        minecraft.setScreen(new BankScreen(menu, inventory, Component.translatable("screen.sailboatmod.bank.title")));
    }
}
