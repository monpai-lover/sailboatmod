package com.monpai.sailboatmod.client.modernui;

import com.monpai.sailboatmod.client.screen.StructureSelectionScreen;
import com.monpai.sailboatmod.item.BankConstructorItem;
import com.monpai.sailboatmod.nation.service.StructureConstructionManager.StructureType;
import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.mc.ScreenCallback;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public class StructureSelectionModernFragment extends Fragment implements ScreenCallback {
    private final ItemStack constructorStack;
    private int selectedIndex;
    private TextView bodyView;

    public StructureSelectionModernFragment(ItemStack constructorStack) {
        this.constructorStack = constructorStack;
        this.selectedIndex = BankConstructorItem.getSelectedIndex(constructorStack);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable DataSet savedInstanceState) {
        ScrollView scrollView = new ScrollView(requireContext());
        LinearLayout root = ModernUiScreenHelper.createRoot(new LinearLayout(requireContext()));
        scrollView.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(ModernUiScreenHelper.createHeader(new TextView(requireContext()), "Blueprint Catalog", 20), ModernUiScreenHelper.matchWidthWrap());
        TextView subtitle = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        subtitle.setText("Preview the building choice before placement. The constructor item stores the selected blueprint.");
        root.addView(subtitle, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout card = ModernUiScreenHelper.createCard(new LinearLayout(requireContext()));
        bodyView = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        card.addView(bodyView, ModernUiScreenHelper.matchWidthWrap());
        LinearLayout navRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        Button prevButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "< Structure");
        prevButton.setOnClickListener(v -> {
            selectedIndex = Math.floorMod(selectedIndex - 1, StructureType.ALL.size());
            refreshViews();
        });
        Button nextButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Structure >");
        nextButton.setOnClickListener(v -> {
            selectedIndex = Math.floorMod(selectedIndex + 1, StructureType.ALL.size());
            refreshViews();
        });
        navRow.addView(prevButton, ModernUiScreenHelper.wrap());
        navRow.addView(nextButton, ModernUiScreenHelper.wrap());
        card.addView(navRow, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout actionRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        Button confirmButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Use Blueprint");
        confirmButton.setOnClickListener(v -> saveAndClose());
        Button classicButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Classic Controls");
        classicButton.setOnClickListener(v -> Minecraft.getInstance().setScreen(new StructureSelectionScreen(constructorStack)));
        actionRow.addView(confirmButton, ModernUiScreenHelper.wrap());
        actionRow.addView(classicButton, ModernUiScreenHelper.wrap());
        card.addView(actionRow, ModernUiScreenHelper.matchWidthWrap());
        root.addView(card, ModernUiScreenHelper.matchWidthWrap());

        refreshViews();
        return scrollView;
    }

    private void refreshViews() {
        StructureType selected = StructureType.ALL.get(Math.max(0, Math.min(selectedIndex, StructureType.ALL.size() - 1)));
        StringBuilder builder = new StringBuilder();
        builder.append("Selected: ").append(Component.translatable(selected.translationKey()).getString())
                .append("\nFootprint: ").append(selected.w()).append(" x ").append(selected.d())
                .append("\nHeight: ").append(selected.h())
                .append("\nRotation support: yes")
                .append("\nPreview placement before confirming.")
                .append("\n\nCatalog:");
        for (int i = 0; i < StructureType.ALL.size(); i++) {
            StructureType type = StructureType.ALL.get(i);
            builder.append('\n')
                    .append(i == selectedIndex ? "> " : "- ")
                    .append(Component.translatable(type.translationKey()).getString())
                    .append(" | ")
                    .append(type.w()).append("x").append(type.h()).append("x").append(type.d());
        }
        bodyView.setText(builder.toString());
    }

    private void saveAndClose() {
        BankConstructorItem.setSelectedIndex(constructorStack, selectedIndex);
        Minecraft.getInstance().setScreen(null);
    }
}
