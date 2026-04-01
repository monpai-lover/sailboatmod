package com.monpai.sailboatmod.client.gui;

import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.ScrollingList;
import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.resident.data.ResidentSavedData;
import com.monpai.sailboatmod.resident.model.FamilyData;
import com.monpai.sailboatmod.resident.model.ResidentRecord;
import com.monpai.sailboatmod.resident.service.FamilyService;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Family tree visualization window
 */
public class FamilyTreeWindow extends AbstractSailboatWindow {
    private static final ResourceLocation LAYOUT = new ResourceLocation(SailboatMod.MODID, "gui/resident/familytree.xml");

    private final ResidentRecord resident;
    private final FamilyData family;

    public FamilyTreeWindow(ResidentRecord resident) {
        super(LAYOUT);
        this.resident = resident;
        this.family = FamilyService.getFamily(resident.residentId());
        registerButton("back", this::close);
    }

    @Override
    public void onOpened() {
        super.onOpened();

        setText("title", "§l" + resident.name() + "'s Family");

        // Parents
        String parentA = family.getParentA();
        String parentB = family.getParentB();
        setText("parentAName", parentA.isEmpty() ? "Unknown" : parentA);
        setText("parentBName", parentB.isEmpty() ? "Unknown" : parentB);

        // Self
        setText("selfName", "§l§n" + resident.name());

        // Partner
        String partnerId = family.getPartnerId();
        setText("partnerName", partnerId.isEmpty() ? "None" : partnerId);

        // Children
        List<String> children = family.getChildren();
        ScrollingList childList = findPaneOfTypeByID("childrenList", ScrollingList.class);
        if (childList != null) {
            childList.setDataProvider(new ScrollingList.DataProvider() {
                @Override
                public int getElementCount() { return children.size(); }

                @Override
                public void updateElement(int index, Pane rowPane) {
                    rowPane.findPaneOfTypeByID("childName", Text.class)
                        .setText(Component.literal(children.get(index)));
                }
            });
        }

        // Siblings
        List<String> siblings = family.getSiblings();
        setText("siblingsText", siblings.isEmpty() ? "None" : String.join(", ", siblings));
    }

    private void setText(String id, String value) {
        Text text = findPaneOfTypeByID(id, Text.class);
        if (text != null) text.setText(Component.literal(value));
    }
}
