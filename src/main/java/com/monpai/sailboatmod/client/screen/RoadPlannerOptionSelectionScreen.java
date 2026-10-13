package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.client.ui.kit.ThemedScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.SelectRoadPlannerPreviewOptionPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** Chooses one of the planned road variants (bridge or detour), then opens the planner's config mode as before. */
public class RoadPlannerOptionSelectionScreen extends ThemedScreen {
    private final String sourceTownName;
    private final String targetTownName;
    private final List<RoadPlannerClientHooks.PreviewOption> options;
    private final String selectedOptionId;
    private final List<UIElement> rows = new ArrayList<>();
    private int selectedIndex = -1;
    private Button selectButton;

    public RoadPlannerOptionSelectionScreen(String sourceTownName,
                                            String targetTownName,
                                            List<RoadPlannerClientHooks.PreviewOption> options,
                                            String selectedOptionId) {
        super(Component.translatable("screen.sailboatmod.road_planner.option.title"));
        this.sourceTownName = sourceTownName == null ? "-" : sourceTownName;
        this.targetTownName = targetTownName == null ? "-" : targetTownName;
        this.options = options == null ? List.of() : List.copyOf(options);
        this.selectedOptionId = selectedOptionId == null ? "" : selectedOptionId;
        this.selectedIndex = defaultSelectedIndex();
    }

    @Override
    protected float windowWidth() {
        return 250;
    }

    @Override
    protected UIElement buildBody() {
        UIElement body = Ui.column();
        body.addChild(Ui.text(Component.translatable("screen.sailboatmod.road_planner.option.route", sourceTownName, targetTownName)));
        ScrollerView list = Ui.scroller();
        list.layout(l -> l.height(150).maxHeight(150));
        for (int i = 0; i < options.size(); i++) {
            int index = i;
            RoadPlannerClientHooks.PreviewOption option = options.get(i);
            UIElement row = Ui.row("sm-row");
            row.layout(l -> l.widthPercent(100).height(26).paddingHorizontal(Ui.GAP_S).gapAll(Ui.GAP_S).flexShrink(0));
            UIElement names = Ui.column();
            names.layout(l -> l.flexGrow(1).minWidth(0));
            names.addChildren(Ui.text(option.label(), "sm-title"),
                    Ui.text(Component.translatable(option.bridgeBacked() ? "screen.sailboatmod.ui.road.option_bridge" : "screen.sailboatmod.ui.road.option_detour",
                            option.pathNodeCount()), "sm-muted"));
            row.addChildren(Ui.slot(new ItemStack(option.bridgeBacked() ? Items.OAK_FENCE : Items.DIRT_PATH), Ui.SLOT), names);
            Ui.clickable(row, () -> {
                selectedIndex = index;
                refresh();
            });
            rows.add(row);
            list.addScrollViewChild(row);
        }
        if (options.isEmpty()) {
            list.addScrollViewChild(Ui.emptyState(Component.translatable("screen.sailboatmod.ui.road.no_options")));
        }
        selectButton = Ui.button(Component.translatable("screen.sailboatmod.road_planner.option.select"), Ui.Tone.PRIMARY, this::choose);
        UIElement actions = Ui.row();
        actions.layout(l -> l.gapAll(Ui.GAP_S));
        actions.addChildren(Ui.spacer(), Ui.button(Component.translatable("gui.cancel"), Ui.Tone.SECONDARY, this::onClose), selectButton);
        body.addChildren(list, actions);
        refresh();
        return body;
    }

    private void refresh() {
        for (int i = 0; i < rows.size(); i++) {
            Ui.setClass(rows.get(i), "selected", i == selectedIndex);
        }
        selectButton.setActive(selectedIndex >= 0 && selectedIndex < options.size());
    }

    private void choose() {
        if (selectedIndex < 0 || selectedIndex >= options.size()) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new SelectRoadPlannerPreviewOptionPacket(options.get(selectedIndex).optionId()));
        RoadPlannerClientHooks.enterConfigMode();
        net.minecraft.client.Minecraft.getInstance().setScreen(new RoadPlannerConfigScreen());
    }

    @Override
    public void onClose() {
        RoadPlannerClientHooks.clearPlanningResult();
        RoadPlannerClientHooks.clearPreview();
        super.onClose();
    }

    private int defaultSelectedIndex() {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).optionId().equalsIgnoreCase(selectedOptionId)) {
                return i;
            }
        }
        return options.isEmpty() ? -1 : 0;
    }
}
