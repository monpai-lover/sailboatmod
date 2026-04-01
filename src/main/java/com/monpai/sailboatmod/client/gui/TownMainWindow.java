package com.monpai.sailboatmod.client.gui;

import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.ScrollingList;
import com.ldtteam.blockui.views.SwitchView;
import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.nation.model.PlacedStructureRecord;
import com.monpai.sailboatmod.resident.model.ResidentRecord;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.BuildingUpgradePacket;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Main town management window with tabs (MineColonies TownHall style)
 */
public class TownMainWindow extends AbstractSailboatWindow {
    private static final ResourceLocation LAYOUT = new ResourceLocation(SailboatMod.MODID, "gui/town/townmain.xml");

    private final String townId;
    private final String townName;
    private List<ResidentRecord> residents;
    private List<PlacedStructureRecord> buildings;

    private SwitchView pages;

    public TownMainWindow(String townId, String townName,
                          List<ResidentRecord> residents,
                          List<PlacedStructureRecord> buildings) {
        super(LAYOUT);
        this.townId = townId;
        this.townName = townName;
        this.residents = residents != null ? residents : new ArrayList<>();
        this.buildings = buildings != null ? buildings : new ArrayList<>();

        registerButton("overviewTab", () -> switchPage("pageOverview"));
        registerButton("residentsTab", () -> switchPage("pageResidents"));
        registerButton("buildingsTab", () -> switchPage("pageBuildings"));
        registerButton("economyTab", () -> switchPage("pageEconomy"));
        registerButton("close", this::close);
        registerButton("rDetail", this::onResidentDetail);
        registerButton("bUpgrade", this::onBuildingUpgrade);
        registerButton("bDemolish", this::onBuildingDemolish);
        registerButton("buildNew", () -> { /* TODO */ });
    }

    @Override
    public void onOpened() {
        super.onOpened();
        pages = findPaneOfTypeByID("pages", SwitchView.class);
        switchPage("pageOverview");
    }

    private void switchPage(String pageId) {
        if (pages != null) {
            pages.setView(pageId);
        }

        switch (pageId) {
            case "pageOverview" -> fillOverview();
            case "pageResidents" -> fillResidents();
            case "pageBuildings" -> fillBuildings();
            case "pageEconomy" -> fillEconomy();
        }
    }

    private void fillOverview() {
        setText("townName", "§l" + townName);
        setText("residentCount", String.valueOf(residents.size()));
        setText("buildingCount", String.valueOf(buildings.size()));
    }

    private void fillResidents() {
        ScrollingList list = findPaneOfTypeByID("residentsList", ScrollingList.class);
        if (list == null) return;

        list.setDataProvider(new ScrollingList.DataProvider() {
            @Override
            public int getElementCount() {
                return residents.size();
            }

            @Override
            public void updateElement(int index, Pane rowPane) {
                ResidentRecord r = residents.get(index);
                rowPane.findPaneOfTypeByID("rName", Text.class)
                    .setText(Component.literal(r.name()));
                rowPane.findPaneOfTypeByID("rJob", Text.class)
                    .setText(Component.literal(r.profession().displayName()));
                rowPane.findPaneOfTypeByID("rHappy", Text.class)
                    .setText(Component.literal(r.happiness() + "%"));
            }
        });
    }

    private void fillBuildings() {
        ScrollingList list = findPaneOfTypeByID("buildingsList", ScrollingList.class);
        if (list == null) return;

        list.setDataProvider(new ScrollingList.DataProvider() {
            @Override
            public int getElementCount() {
                return buildings.size();
            }

            @Override
            public void updateElement(int index, Pane rowPane) {
                PlacedStructureRecord b = buildings.get(index);
                rowPane.findPaneOfTypeByID("bType", Text.class)
                    .setText(Component.literal(b.structureType()));
                rowPane.findPaneOfTypeByID("bLevel", Text.class)
                    .setText(Component.literal("Lv." + b.buildingLevel() + "/" + b.getMaxLevel()));
                rowPane.findPaneOfTypeByID("bStatus", Text.class)
                    .setText(Component.literal(b.isBuilt() ? "Built" : "Building..."));

                Button upgradeBtn = rowPane.findPaneOfTypeByID("bUpgrade", Button.class);
                if (upgradeBtn != null) {
                    upgradeBtn.setEnabled(b.canUpgrade());
                }
            }
        });
    }

    private void fillEconomy() {
        setText("eTreasury", "0 Gold");
        setText("eIncome", "+0/day");
        setText("eExpenses", "-0/day");

        // Population health stats
        int totalHappy = 0;
        int sickCount = 0;
        int hungryCount = 0;
        int unemployed = 0;
        for (ResidentRecord r : residents) {
            totalHappy += r.happiness();
            if (r.hunger() < 20) hungryCount++;
            if (r.profession() == com.monpai.sailboatmod.resident.model.Profession.UNEMPLOYED) unemployed++;
        }
        int avgHappy = residents.isEmpty() ? 0 : totalHappy / residents.size();

        setText("eAvgHappiness", avgHappy + "%");
        setText("eSickCount", String.valueOf(sickCount));
        setText("eHungryCount", String.valueOf(hungryCount));
        setText("eUnemployed", String.valueOf(unemployed));
    }

    private void setText(String id, String value) {
        Text text = findPaneOfTypeByID(id, Text.class);
        if (text != null) {
            text.setText(Component.literal(value));
        }
    }

    private void onResidentDetail(Button button) {
        ScrollingList list = findPaneOfTypeByID("residentsList", ScrollingList.class);
        if (list == null) return;
        int index = list.getListElementIndexByPane(button);
        if (index >= 0 && index < residents.size()) {
            new ResidentDetailWindow(residents.get(index)).open();
        }
    }

    private void onBuildingUpgrade(Button button) {
        ScrollingList list = findPaneOfTypeByID("buildingsList", ScrollingList.class);
        if (list == null) return;
        int index = list.getListElementIndexByPane(button);
        if (index >= 0 && index < buildings.size()) {
            ModNetwork.CHANNEL.sendToServer(new BuildingUpgradePacket(buildings.get(index).structureId()));
        }
    }

    private void onBuildingDemolish(Button button) {
        ScrollingList list = findPaneOfTypeByID("buildingsList", ScrollingList.class);
        if (list == null) return;
        int index = list.getListElementIndexByPane(button);
        if (index >= 0 && index < buildings.size()) {
            // TODO: confirm dialog before demolish
        }
    }
}
