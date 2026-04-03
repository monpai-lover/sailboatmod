package com.monpai.sailboatmod.client.gui;

import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.ScrollingList;
import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.nation.model.PlacedStructureRecord;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.BuildingUpgradePacket;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Building management window (MineColonies style)
 */
public class BuildingListWindow extends AbstractSailboatWindow {
    private static final ResourceLocation LAYOUT = new ResourceLocation(SailboatMod.MODID, "gui/building/buildinglist.xml");

    private static final String LIST_BUILDINGS = "buildings";
    private static final String LABEL_TYPE = "type";
    private static final String LABEL_LEVEL = "level";
    private static final String LABEL_STATUS = "status";
    private static final String BUTTON_UPGRADE = "upgrade";
    private static final String BUTTON_DEMOLISH = "demolish";
    private static final String LABEL_TITLE = "title";

    private final String townId;
    private List<PlacedStructureRecord> buildings = new ArrayList<>();

    public BuildingListWindow(String townId, List<PlacedStructureRecord> buildings) {
        super(LAYOUT);
        this.townId = townId;
        this.buildings = buildings;

        registerButton(BUTTON_UPGRADE, this::onUpgradeClicked);
        registerButton(BUTTON_DEMOLISH, this::onDemolishClicked);
        registerButton("close", this::close);
        registerButton("buildNew", this::onBuildNew);
    }

    @Override
    public void onOpened() {
        super.onOpened();

        findPaneOfTypeByID(LABEL_TITLE, Text.class)
            .setText(Component.translatable("sailboatmod.gui.building.title"));

        updateBuildingList();
    }

    private void updateBuildingList() {
        ScrollingList list = findPaneOfTypeByID(LIST_BUILDINGS, ScrollingList.class);
        if (list == null) return;

        list.setDataProvider(new ScrollingList.DataProvider() {
            @Override
            public int getElementCount() {
                return buildings.size();
            }

            @Override
            public void updateElement(int index, Pane rowPane) {
                PlacedStructureRecord b = buildings.get(index);

                rowPane.findPaneOfTypeByID(LABEL_TYPE, Text.class)
                    .setText(Component.literal(b.structureType()));
                rowPane.findPaneOfTypeByID(LABEL_LEVEL, Text.class)
                    .setText(Component.literal("Lv." + b.buildingLevel() + "/" + b.getMaxLevel()));
                rowPane.findPaneOfTypeByID(LABEL_STATUS, Text.class)
                    .setText(Component.translatable(b.isBuilt()
                            ? "screen.sailboatmod.building.status.built"
                            : "screen.sailboatmod.building.status.building"));

                Button upgradeBtn = rowPane.findPaneOfTypeByID(BUTTON_UPGRADE, Button.class);
                if (upgradeBtn != null) {
                    upgradeBtn.setEnabled(b.canUpgrade());
                }
            }
        });
    }

    private void onUpgradeClicked(Button button) {
        ScrollingList list = findPaneOfTypeByID(LIST_BUILDINGS, ScrollingList.class);
        int index = list.getListElementIndexByPane(button);
        if (index >= 0 && index < buildings.size()) {
            ModNetwork.CHANNEL.sendToServer(new BuildingUpgradePacket(buildings.get(index).structureId()));
        }
    }

    private void onDemolishClicked(Button button) {
        ScrollingList list = findPaneOfTypeByID(LIST_BUILDINGS, ScrollingList.class);
        int index = list.getListElementIndexByPane(button);
        if (index >= 0 && index < buildings.size()) {
            // TODO: confirm dialog before demolish
        }
    }

    private void onBuildNew() {
        // TODO: open structure selection screen
    }
}
