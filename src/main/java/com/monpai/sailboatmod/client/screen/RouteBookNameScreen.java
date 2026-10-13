package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.monpai.sailboatmod.client.ui.kit.ThemedScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.FinalizeRouteNamePacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Names a freshly recorded route; Enter or "Confirm" sends {@link FinalizeRouteNamePacket} as before. */
public class RouteBookNameScreen extends ThemedScreen {
    private final InteractionHand hand;
    private final String suggestedName;
    private TextField nameBox;

    public RouteBookNameScreen(InteractionHand hand, String suggestedName) {
        super(Component.translatable("screen.sailboatmod.route_name.title"));
        this.hand = hand;
        this.suggestedName = suggestedName == null ? "" : suggestedName;
    }

    @Override
    protected float windowWidth() {
        return 240;
    }

    @Override
    protected UIElement buildBody() {
        UIElement body = Ui.column();
        UIElement intro = Ui.row();
        intro.layout(l -> l.gapAll(Ui.GAP_M));
        intro.addChildren(Ui.slot(new ItemStack(Items.WRITABLE_BOOK), Ui.SLOT),
                Ui.paragraph(Component.translatable("screen.sailboatmod.route_name.desc"), "sm-muted"));
        nameBox = Ui.field(suggestedName, Component.translatable("screen.sailboatmod.route_name.input"), value -> { });
        nameBox.layout(l -> l.widthPercent(100));
        UIElement actions = Ui.row();
        actions.layout(l -> l.gapAll(Ui.GAP_S));
        actions.addChildren(Ui.spacer(),
                Ui.button(Component.translatable("screen.sailboatmod.route_name.cancel"), Ui.Tone.SECONDARY, this::onClose),
                Ui.button(Component.translatable("screen.sailboatmod.route_name.confirm"), Ui.Tone.PRIMARY, this::submit));
        body.addChildren(intro, nameBox, actions);
        return body;
    }

    @Override
    protected void afterInit() {
        modularUI().requestFocus(nameBox);
    }

    @Override
    protected boolean onTextFieldKey(UIElement field, int keyCode) {
        if (isEnter(keyCode)) {
            submit();
            return true;
        }
        return false;
    }

    private void submit() {
        String name = this.nameBox == null ? "" : this.nameBox.getValue();
        if (name.length() > 64) {
            name = name.substring(0, 64); // the old box stopped at 64 characters
        }
        ModNetwork.CHANNEL.sendToServer(new FinalizeRouteNamePacket(hand, name));
        onClose();
    }
}
