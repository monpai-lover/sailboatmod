package com.monpai.sailboatmod.client.ui.kit;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.monpai.sailboatmod.client.ui.theme.UiPreferences;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The standard themed window every SailboatMod screen uses: header with the title, optional extras (chips, refresh),
 * the theme switch and close button, then the body and the toast.
 */
public final class WindowFrame {
    public final UIElement window;
    public final Label title;

    private WindowFrame(UIElement window, Label title) {
        this.window = window;
        this.title = title;
    }

    public static WindowFrame build(Component heading, UIElement body, Toast toast, Runnable onClose, UIElement... headerExtras) {
        UIElement window = Ui.column("sm-window", "sm-controls");
        window.layout(l -> l.paddingAll(0));
        UIElement header = Ui.row("sm-header");
        header.layout(l -> l.height(22).paddingHorizontal(Ui.GAP_M).gapAll(Ui.GAP_S).flexShrink(0));
        Label title = Ui.text(heading, "sm-title");
        title.textStyle(s -> s.adaptiveWidth(false));
        title.setOverflowVisible(false);
        title.layout(l -> l.width(0).flexGrow(1).flexShrink(1).minWidth(0));
        header.addChild(title);
        for (UIElement extra : headerExtras) {
            extra.layout(l -> l.flexShrink(1).minWidth(0));
            header.addChild(extra);
        }
        header.addChild(Ui.iconButton(new ItemStack(Items.MAP), Component.translatable("screen.sailboatmod.ui.theme.toggle"),
                () -> UiPreferences.get().toggleTheme()));
        header.addChild(Ui.iconButton(new ItemStack(Items.BARRIER), Component.translatable("screen.sailboatmod.ui.close"), onClose));
        body.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_M).flexGrow(1).minHeight(0));
        window.addChildren(header, body, toast.element());
        return new WindowFrame(window, title);
    }
}
