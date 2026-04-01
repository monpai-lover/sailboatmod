package com.monpai.sailboatmod.client.gui;

import com.ldtteam.blockui.controls.Text;
import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.ResidentActionPacket;
import com.monpai.sailboatmod.resident.model.ResidentRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Resident detail window (MineColonies citizen detail style)
 */
public class ResidentDetailWindow extends AbstractSailboatWindow {
    private static final ResourceLocation LAYOUT = new ResourceLocation(SailboatMod.MODID, "gui/resident/residentdetail.xml");

    private static final String LABEL_NAME = "name";
    private static final String LABEL_GENDER = "gender";
    private static final String LABEL_AGE = "age";
    private static final String LABEL_JOB = "job";
    private static final String LABEL_HAPPINESS = "happiness";
    private static final String LABEL_HUNGER = "hunger";
    private static final String LABEL_CULTURE = "culture";
    private static final String LABEL_LEVEL = "level";
    private static final String LABEL_EDUCATED = "educated";
    private static final String BUTTON_ASSIGN_JOB = "assignJob";
    private static final String BUTTON_ASSIGN_HOME = "assignHome";
    private static final String BUTTON_BACK = "back";

    private final ResidentRecord resident;

    public ResidentDetailWindow(ResidentRecord resident) {
        super(LAYOUT);
        this.resident = resident;

        registerButton(BUTTON_BACK, this::close);
        registerButton(BUTTON_ASSIGN_JOB, this::onAssignJob);
        registerButton(BUTTON_ASSIGN_HOME, this::onAssignHome);
        registerButton("dismiss", this::onDismiss);
        registerButton("familyTree", () -> new FamilyTreeWindow(resident).open());
    }

    @Override
    public void onOpened() {
        super.onOpened();

        setText(LABEL_NAME, resident.name());
        setText(LABEL_GENDER, resident.gender().displayName());
        setText(LABEL_AGE, String.valueOf(resident.age()));
        setText(LABEL_JOB, resident.profession().displayName());
        setText(LABEL_HAPPINESS, resident.happiness() + "%");
        setText(LABEL_HUNGER, resident.hunger() + "/" + ResidentRecord.MAX_HUNGER);
        setText(LABEL_CULTURE, resident.culture().displayName());
        setText(LABEL_LEVEL, String.valueOf(resident.level()));
        setText(LABEL_EDUCATED, resident.educated() ? "Yes" : "No");
    }

    private void setText(String id, String value) {
        Text text = findPaneOfTypeByID(id, Text.class);
        if (text != null) {
            text.setText(Component.literal(value));
        }
    }

    private void onAssignJob() {
        // Cycle through professions for now
        ModNetwork.CHANNEL.sendToServer(new ResidentActionPacket(
            resident.residentId(), ResidentActionPacket.Action.ASSIGN_JOB, "builder"));
    }

    private void onAssignHome() {
        // TODO: pick a building from list
    }

    private void onDismiss() {
        ModNetwork.CHANNEL.sendToServer(new ResidentActionPacket(
            resident.residentId(), ResidentActionPacket.Action.DISMISS, ""));
        this.close();
    }
}
