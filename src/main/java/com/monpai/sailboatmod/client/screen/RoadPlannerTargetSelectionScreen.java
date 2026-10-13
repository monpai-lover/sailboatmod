package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.client.ui.kit.ThemedScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.SelectRoadPlannerTargetPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** Chooses the destination town for a planned road; sends {@link SelectRoadPlannerTargetPacket} as before. */
public class RoadPlannerTargetSelectionScreen extends ThemedScreen {
    private final boolean offhand;
    private final String sourceTownName;
    private final List<RoadPlannerClientHooks.TargetEntry> targets;
    private final List<UIElement> rows = new ArrayList<>();
    private int selectedIndex = -1;
    private Button selectButton;

    public RoadPlannerTargetSelectionScreen(boolean offhand,
                                            String sourceTownName,
                                            List<RoadPlannerClientHooks.TargetEntry> targets,
                                            String selectedTownId) {
        super(Component.translatable("screen.sailboatmod.road_planner.target.title"));
        this.offhand = offhand;
        this.sourceTownName = sourceTownName == null ? "" : sourceTownName;
        this.targets = targets == null ? List.of() : targets;
        for (int i = 0; i < this.targets.size(); i++) {
            if (this.targets.get(i).townId().equalsIgnoreCase(selectedTownId == null ? "" : selectedTownId)) {
                this.selectedIndex = i;
                break;
            }
        }
    }

    @Override
    protected float windowWidth() {
        return 280;
    }

    @Override
    protected UIElement buildBody() {
        UIElement body = Ui.column();
        body.addChildren(Ui.text(Component.translatable("screen.sailboatmod.road_planner.target.source", safeTownName(sourceTownName))),
                Ui.paragraph(Component.translatable("screen.sailboatmod.road_planner.target.hint"), "sm-muted"));
        ScrollerView list = Ui.scroller();
        list.layout(l -> l.height(160).flexGrow(0));
        if (targets.isEmpty()) {
            list.addScrollViewChild(Ui.emptyState(Component.translatable("screen.sailboatmod.road_planner.target.empty")));
        }
        for (int i = 0; i < targets.size(); i++) {
            int index = i;
            RoadPlannerClientHooks.TargetEntry entry = targets.get(i);
            UIElement row = Ui.itemRow(new ItemStack(Items.BELL), safeTownName(entry.townName()),
                    Component.literal(entry.distanceBlocks() + "m"), "sm-price");
            Ui.clickable(row, () -> {
                selectedIndex = index;
                refresh();
            });
            rows.add(row);
            list.addScrollViewChild(row);
        }
        selectButton = Ui.button(Component.translatable("screen.sailboatmod.road_planner.target.select"), Ui.Tone.PRIMARY, this::choose);
        UIElement actions = Ui.row();
        actions.layout(l -> l.gapAll(Ui.GAP_S));
        actions.addChildren(Ui.spacer(), Ui.button(Component.translatable("screen.sailboatmod.route_name.cancel"), Ui.Tone.SECONDARY, this::onClose), selectButton);
        body.addChildren(list, actions);
        refresh();
        return body;
    }

    private void refresh() {
        for (int i = 0; i < rows.size(); i++) {
            Ui.setClass(rows.get(i), "selected", i == selectedIndex);
        }
        selectButton.setActive(selectedIndex >= 0 && selectedIndex < targets.size());
    }

    private void choose() {
        if (selectedIndex < 0 || selectedIndex >= targets.size()) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new SelectRoadPlannerTargetPacket(offhand, targets.get(selectedIndex).townId()));
        onClose();
    }

    private Component safeTownName(String name) {
        if (name == null || name.isBlank()) {
            return Component.translatable("screen.sailboatmod.road_planner.target.unknown");
        }
        return Component.literal(name);
    }
}
