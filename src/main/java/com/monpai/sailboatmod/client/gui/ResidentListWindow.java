package com.monpai.sailboatmod.client.gui;

import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.PaneBuilders;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.ScrollingList;
import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.resident.model.ResidentRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Main resident management window (MineColonies style)
 */
public class ResidentListWindow extends AbstractSailboatWindow {
    private static final ResourceLocation LAYOUT = new ResourceLocation(SailboatMod.MODID, "gui/resident/residentlist.xml");

    private static final String LIST_RESIDENTS = "residents";
    private static final String LABEL_NAME = "name";
    private static final String LABEL_JOB = "job";
    private static final String LABEL_HAPPINESS = "happiness";
    private static final String BUTTON_DETAIL = "detail";
    private static final String LABEL_TITLE = "title";
    private static final String LABEL_COUNT = "count";

    private final String townId;
    private List<ResidentRecord> residents = new ArrayList<>();

    public ResidentListWindow(String townId, List<ResidentRecord> residents) {
        super(LAYOUT);
        this.townId = townId;
        this.residents = residents;

        registerButton(BUTTON_DETAIL, this::onDetailClicked);
        registerButton("close", this::close);
    }

    @Override
    public void onOpened() {
        super.onOpened();

        findPaneOfTypeByID(LABEL_TITLE, Text.class)
            .setText(Component.translatable("sailboatmod.gui.resident.title"));
        findPaneOfTypeByID(LABEL_COUNT, Text.class)
            .setText(Component.literal(String.valueOf(residents.size())));

        updateResidentList();
    }

    private void updateResidentList() {
        ScrollingList list = findPaneOfTypeByID(LIST_RESIDENTS, ScrollingList.class);
        if (list == null) return;

        list.setDataProvider(new ScrollingList.DataProvider() {
            @Override
            public int getElementCount() {
                return residents.size();
            }

            @Override
            public void updateElement(int index, Pane rowPane) {
                ResidentRecord r = residents.get(index);

                rowPane.findPaneOfTypeByID(LABEL_NAME, Text.class)
                    .setText(Component.literal(r.name()));
                rowPane.findPaneOfTypeByID(LABEL_JOB, Text.class)
                    .setText(Component.literal(r.profession().displayName()));
                rowPane.findPaneOfTypeByID(LABEL_HAPPINESS, Text.class)
                    .setText(Component.literal(r.happiness() + "%"));
            }
        });
    }

    private void onDetailClicked(Button button) {
        ScrollingList list = findPaneOfTypeByID(LIST_RESIDENTS, ScrollingList.class);
        int index = list.getListElementIndexByPane(button);
        if (index >= 0 && index < residents.size()) {
            new ResidentDetailWindow(residents.get(index)).open();
        }
    }
}
