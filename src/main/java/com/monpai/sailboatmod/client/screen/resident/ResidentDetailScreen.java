package com.monpai.sailboatmod.client.screen.resident;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.monpai.sailboatmod.client.ui.kit.ActionButton;
import com.monpai.sailboatmod.client.ui.kit.MeterBar;
import com.monpai.sailboatmod.client.ui.kit.ThemedScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.client.ui.state.ConfirmGate;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.ResidentActionPacket;
import com.monpai.sailboatmod.resident.model.ResidentRecord;
import dev.vfyjxf.taffy.style.AlignItems;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Resident card opened by talking to a resident: identity, needs, and the same actions as the previous window
 * (assign job, family tree, dismiss). Dismissing needs a second press. Assigning a home was never wired to the server
 * and stays unavailable.
 */
public class ResidentDetailScreen extends ThemedScreen {
    private static final String CONFIRM_DISMISS = "dismiss";

    private final ResidentRecord resident;
    private final ConfirmGate confirm = new ConfirmGate();
    private ActionButton dismissButton;

    public ResidentDetailScreen(ResidentRecord resident) {
        super(Component.literal(resident.name()));
        this.resident = resident;
    }

    @Override
    protected float windowWidth() {
        return 250;
    }

    @Override
    protected UIElement buildBody() {
        UIElement body = Ui.column();
        UIElement head = Ui.row();
        head.layout(l -> l.gapAll(Ui.GAP_M).alignItems(AlignItems.CENTER));
        UIElement names = Ui.column();
        names.layout(l -> l.flexGrow(1).minWidth(0));
        names.addChildren(Ui.text(resident.name(), "sm-title"),
                Ui.text(resident.profession().displayName() + " · Lv." + resident.level(), "sm-muted"));
        head.addChildren(Ui.slot(new ItemStack(Items.PLAYER_HEAD), 36), names);

        UIElement facts = Ui.column("sm-pane");
        facts.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_XS));
        fact(facts, "gender", resident.gender().displayName());
        fact(facts, "age", String.valueOf(resident.age()));
        fact(facts, "profession", resident.profession().displayName());
        fact(facts, "culture", resident.culture().displayName());
        fact(facts, "level", String.valueOf(resident.level()));
        Ui.keyValue(facts, Component.translatable("screen.sailboatmod.ui.resident.educated"), resident.educated() ? "sm-up" : "sm-muted")
                .setValue(Component.translatable(resident.educated() ? "screen.sailboatmod.bank.loan_yes" : "screen.sailboatmod.bank.loan_no"));

        UIElement needs = Ui.column("sm-pane");
        needs.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_XS));
        needs.addChildren(
                need("happiness", resident.happiness() / 100f, resident.happiness() + "%", 0xFF3CCF7E),
                need("hunger", resident.hunger() / (float) ResidentRecord.MAX_HUNGER, resident.hunger() + "/" + ResidentRecord.MAX_HUNGER, 0xFFF5B13D));

        Button assignHome = Ui.button(Component.translatable("screen.sailboatmod.ui.resident.assign_home"), Ui.Tone.SECONDARY, () -> { });
        assignHome.setActive(false);
        assignHome.style(s -> s.tooltips(Component.translatable("screen.sailboatmod.ui.resident.not_available")));
        dismissButton = new ActionButton(Component.translatable("screen.sailboatmod.ui.resident.dismiss"), Ui.Tone.DANGER, this::onDismiss);
        UIElement actions = Ui.wrapRow(Ui.GAP_S);
        actions.layout(l -> l.widthPercent(100));
        actions.addChildren(
                Ui.button(Component.translatable("screen.sailboatmod.ui.resident.assign_job"), Ui.Tone.PRIMARY, this::onAssignJob),
                assignHome,
                Ui.button(Component.translatable("screen.sailboatmod.ui.resident.family_tree"), Ui.Tone.SECONDARY,
                        () -> Minecraft.getInstance().setScreen(new FamilyTreeScreen(resident, this))),
                dismissButton.element());
        body.addChildren(head, facts, needs, actions);
        return body;
    }

    private static void fact(UIElement parent, String key, String value) {
        Ui.keyValue(parent, Component.translatable("screen.sailboatmod.ui.resident." + key)).setValue(Component.literal(value));
    }

    private static UIElement need(String key, float ratio, String value, int color) {
        UIElement row = Ui.row();
        row.layout(l -> l.gapAll(Ui.GAP_S).widthPercent(100));
        com.lowdragmc.lowdraglib2.gui.ui.elements.Label label = Ui.text(Component.translatable("screen.sailboatmod.ui.resident." + key), "sm-muted");
        label.layout(l -> l.width(70));
        MeterBar bar = new MeterBar().set(ratio, color);
        bar.layout(l -> l.flexGrow(1));
        row.addChildren(label, bar, Ui.text(value));
        return row;
    }

    @Override
    protected void onUiTick() {
        dismissButton.sync(true, false, confirm.isArmed(CONFIRM_DISMISS, ticks()));
    }

    private void onAssignJob() {
        ModNetwork.CHANNEL.sendToServer(new ResidentActionPacket(resident.residentId(), ResidentActionPacket.Action.ASSIGN_JOB, "builder"));
        toast.show(Component.translatable("screen.sailboatmod.ui.resident.job_requested"), true, ticks());
    }

    private void onDismiss() {
        if (!confirm.press(CONFIRM_DISMISS, ticks())) {
            onUiTick();
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new ResidentActionPacket(resident.residentId(), ResidentActionPacket.Action.DISMISS, ""));
        onClose();
    }
}
