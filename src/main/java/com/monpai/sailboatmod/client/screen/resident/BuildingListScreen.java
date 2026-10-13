package com.monpai.sailboatmod.client.screen.resident;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.monpai.sailboatmod.client.ui.kit.ThemedScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.nation.model.PlacedStructureRecord;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.BuildingUpgradePacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** A town's buildings with their level and state; "Upgrade" sends {@link BuildingUpgradePacket} as before. */
public class BuildingListScreen extends ThemedScreen {
    private final String townId;
    private final List<PlacedStructureRecord> buildings;

    public BuildingListScreen(String townId, List<PlacedStructureRecord> buildings) {
        super(Component.translatable("sailboatmod.gui.building.title"));
        this.townId = townId;
        this.buildings = buildings == null ? List.of() : List.copyOf(buildings);
    }

    public String townId() {
        return townId;
    }

    @Override
    protected float windowWidth() {
        return 300;
    }

    @Override
    protected UIElement buildBody() {
        UIElement body = Ui.column();
        ScrollerView list = Ui.scroller();
        list.layout(l -> l.height(180).flexGrow(0));
        if (buildings.isEmpty()) {
            list.addScrollViewChild(Ui.emptyState(Component.translatable("screen.sailboatmod.ui.resident.none")));
        }
        for (PlacedStructureRecord building : buildings) {
            UIElement row = Ui.row("sm-row");
            row.layout(l -> l.widthPercent(100).height(24).paddingHorizontal(Ui.GAP_S).gapAll(Ui.GAP_S).flexShrink(0));
            UIElement names = Ui.column();
            names.layout(l -> l.width(0).flexGrow(1).minWidth(0));
            names.addChildren(Ui.clippedText(building.structureType()),
                    Ui.clippedText(Component.literal("Lv." + building.buildingLevel() + "/" + building.getMaxLevel() + " · ")
                            .append(Component.translatable(building.isBuilt()
                                    ? "screen.sailboatmod.building.status.built"
                                    : "screen.sailboatmod.building.status.building")), "sm-muted"));
            Button upgrade = Ui.button(Component.translatable("screen.sailboatmod.ui.resident.upgrade"), Ui.Tone.PRIMARY, () -> {
                ModNetwork.CHANNEL.sendToServer(new BuildingUpgradePacket(building.structureId()));
                toast.show(Component.translatable("screen.sailboatmod.ui.resident.upgrade_requested"), true, ticks());
            });
            upgrade.setActive(building.canUpgrade());
            row.addChildren(Ui.slot(new ItemStack(Items.BRICKS), Ui.SLOT), names, upgrade);
            list.addScrollViewChild(row);
        }
        UIElement actions = Ui.row();
        actions.addChildren(Ui.spacer(), Ui.button(Component.translatable("screen.sailboatmod.ui.close"), Ui.Tone.SECONDARY, this::onClose));
        body.addChildren(list, actions);
        return body;
    }
}
