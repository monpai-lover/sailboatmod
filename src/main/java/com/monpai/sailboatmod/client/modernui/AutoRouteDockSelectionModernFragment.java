package com.monpai.sailboatmod.client.modernui;

import com.monpai.sailboatmod.client.screen.AutoRouteDockSelectionScreen;
import com.monpai.sailboatmod.dock.AvailableDockEntry;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.CreateAutoRoutePacket;
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
import net.minecraft.core.BlockPos;

import java.util.List;

public class AutoRouteDockSelectionModernFragment extends Fragment implements ScreenCallback {
    private final BlockPos sourceDockPos;
    private final List<AvailableDockEntry> docks;
    private int selectedIndex;
    private TextView bodyView;

    public AutoRouteDockSelectionModernFragment(BlockPos sourceDockPos, List<AvailableDockEntry> docks) {
        this.sourceDockPos = sourceDockPos;
        this.docks = docks == null ? List.of() : docks;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable DataSet savedInstanceState) {
        ScrollView scrollView = new ScrollView(requireContext());
        LinearLayout root = ModernUiScreenHelper.createRoot(new LinearLayout(requireContext()));
        scrollView.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(ModernUiScreenHelper.createHeader(new TextView(requireContext()), "Auto Route Target", 20), ModernUiScreenHelper.matchWidthWrap());
        TextView subtitle = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        subtitle.setText("Source dock: " + sourceDockPos.toShortString());
        root.addView(subtitle, ModernUiScreenHelper.matchWidthWrap());

        LinearLayout card = ModernUiScreenHelper.createCard(new LinearLayout(requireContext()));
        bodyView = ModernUiScreenHelper.createBody(new TextView(requireContext()));
        card.addView(bodyView, ModernUiScreenHelper.matchWidthWrap());
        LinearLayout navRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        Button prevButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "< Dock");
        prevButton.setOnClickListener(v -> {
            selectedIndex = moveSelection(-1);
            refreshViews();
        });
        Button nextButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Dock >");
        nextButton.setOnClickListener(v -> {
            selectedIndex = moveSelection(1);
            refreshViews();
        });
        navRow.addView(prevButton, ModernUiScreenHelper.wrap());
        navRow.addView(nextButton, ModernUiScreenHelper.wrap());
        card.addView(navRow, ModernUiScreenHelper.matchWidthWrap());
        LinearLayout actionRow = ModernUiScreenHelper.row(new LinearLayout(requireContext()));
        Button createButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Create Route");
        createButton.setOnClickListener(v -> createRoute());
        Button classicButton = ModernUiScreenHelper.createButton(new Button(requireContext()), "Classic Controls");
        classicButton.setOnClickListener(v -> Minecraft.getInstance().setScreen(new AutoRouteDockSelectionScreen(sourceDockPos, docks)));
        actionRow.addView(createButton, ModernUiScreenHelper.wrap());
        actionRow.addView(classicButton, ModernUiScreenHelper.wrap());
        card.addView(actionRow, ModernUiScreenHelper.matchWidthWrap());
        root.addView(card, ModernUiScreenHelper.matchWidthWrap());

        refreshViews();
        return scrollView;
    }

    private void refreshViews() {
        if (docks.isEmpty()) {
            bodyView.setText("No candidate dock available.");
            return;
        }
        AvailableDockEntry selected = docks.get(Math.max(0, Math.min(selectedIndex, docks.size() - 1)));
        StringBuilder builder = new StringBuilder();
        builder.append("Selected: ").append(displayName(selected))
                .append("\nOwner: ").append(fallback(selected.ownerName()))
                .append("\nNation: ").append(fallback(selected.nationName()))
                .append("\nDistance: ").append(selected.distance()).append("m")
                .append("\n\nCandidates:");
        appendWindow(builder);
        bodyView.setText(builder.toString());
    }

    private void appendWindow(StringBuilder builder) {
        int safeIndex = Math.max(0, Math.min(selectedIndex, docks.size() - 1));
        int start = Math.max(0, safeIndex - 3);
        int end = Math.min(docks.size(), start + 7);
        if (end - start < 7) {
            start = Math.max(0, end - 7);
        }
        for (int i = start; i < end; i++) {
            AvailableDockEntry dock = docks.get(i);
            builder.append('\n')
                    .append(i == safeIndex ? "> " : "- ")
                    .append(displayName(dock))
                    .append(" | ").append(fallback(dock.ownerName()))
                    .append(" | ").append(fallback(dock.nationName()))
                    .append(" | ").append(dock.distance()).append("m");
        }
    }

    private void createRoute() {
        if (docks.isEmpty()) {
            return;
        }
        AvailableDockEntry selected = docks.get(Math.max(0, Math.min(selectedIndex, docks.size() - 1)));
        ModNetwork.CHANNEL.sendToServer(new CreateAutoRoutePacket(sourceDockPos, selected.pos()));
        Minecraft.getInstance().setScreen(null);
    }

    private int moveSelection(int delta) {
        if (docks.isEmpty()) {
            return 0;
        }
        return Math.floorMod(selectedIndex + delta, docks.size());
    }

    private String displayName(AvailableDockEntry dock) {
        return dock.dockName() == null || dock.dockName().isBlank() ? "Dock" : dock.dockName();
    }

    private String fallback(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
