package com.monpai.sailboatmod.client.screen.nation;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.monpai.sailboatmod.client.texture.NationFlagUploadClient;
import com.monpai.sailboatmod.client.ui.kit.ThemedScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.nation.service.NationFlagStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;

/** Quick nation commands and flag upload. Buttons run the same chat commands as before. */
public class NationMenuScreen extends ThemedScreen {
    private TextField flagPathInput;

    public NationMenuScreen() {
        super(Component.translatable("screen.sailboatmod.nation.title"));
    }

    @Override
    protected float windowWidth() {
        return 240;
    }

    @Override
    protected UIElement buildBody() {
        UIElement body = Ui.column();
        body.addChild(Ui.paragraph(Component.translatable("screen.sailboatmod.nation.desc"), "sm-muted"));
        UIElement commands = Ui.wrapRow(Ui.GAP_S);
        commands.layout(l -> l.widthPercent(100));
        commands.addChildren(
                command(Items.BOOK, "screen.sailboatmod.nation.info", "nation info"),
                command(Items.IRON_SWORD, "screen.sailboatmod.nation.war", "nation war info"),
                command(Items.WHITE_BANNER, "screen.sailboatmod.nation.claim", "nation claim"),
                command(Items.BARRIER, "screen.sailboatmod.nation.unclaim", "nation unclaim"),
                command(Items.PAINTING, "screen.sailboatmod.nation.flag_info", "nation flag info"),
                command(Items.SHIELD, "screen.sailboatmod.nation.claimperm_info", "nation claimperm info"));

        UIElement upload = Ui.column("sm-pane");
        upload.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_S));
        flagPathInput = Ui.field("", Component.translatable("screen.sailboatmod.nation.flag_path"), value -> { });
        flagPathInput.layout(l -> l.widthPercent(100));
        UIElement actions = Ui.row();
        actions.addChildren(Ui.spacer(), Ui.button(Component.translatable("screen.sailboatmod.nation.upload"), Ui.Tone.PRIMARY, this::submitUpload));
        upload.addChildren(Ui.paragraph(Component.translatable("screen.sailboatmod.nation.upload_hint",
                NationFlagStorage.maxWidth(), NationFlagStorage.maxHeight(), NationFlagStorage.maxBytes()), "sm-muted"), flagPathInput, actions);
        body.addChildren(commands, upload);
        return body;
    }

    private Button command(ItemLike icon, String labelKey, String command) {
        Button button = Ui.button(Component.translatable(labelKey), Ui.Tone.SECONDARY, () -> runCommand(command));
        button.addPreIcon(new ItemStackTexture(new ItemStack(icon)));
        button.layout(l -> l.width(108).height(20));
        return button;
    }

    @Override
    protected boolean onTextFieldKey(UIElement field, int keyCode) {
        if (isEnter(keyCode)) {
            submitUpload();
            return true;
        }
        return false;
    }

    private void runCommand(String command) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.connection == null) {
            toast.show(Component.translatable("screen.sailboatmod.nation.command_unavailable"), false, ticks());
            return;
        }
        minecraft.player.connection.sendCommand(command);
        toast.show(Component.translatable("screen.sailboatmod.nation.command_sent", "/" + command), true, ticks());
    }

    private void submitUpload() {
        String path = flagPathInput == null ? "" : flagPathInput.getValue();
        toast.show(NationFlagUploadClient.uploadFromPath(path), true, ticks());
    }
}
