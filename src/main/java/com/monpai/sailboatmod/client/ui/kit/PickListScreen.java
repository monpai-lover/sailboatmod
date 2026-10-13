package com.monpai.sailboatmod.client.ui.kit;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * A themed dialog listing entries the player picks one of (built roads to edit or demolish, towns to target). Each
 * row shows an icon, a title, a detail line and a trailing figure; clicking a row picks it.
 */
public abstract class PickListScreen<T> extends ThemedScreen {
    /** How one entry is shown. */
    public record Row(ItemStack icon, Component title, Component detail, Component trailing) {
    }

    private final Component subtitle;
    private final Component emptyText;
    protected final List<T> entries;

    protected PickListScreen(Component title, Component subtitle, Component emptyText, List<T> entries) {
        super(title);
        this.subtitle = subtitle;
        this.emptyText = emptyText;
        this.entries = entries == null ? List.of() : List.copyOf(entries);
    }

    protected abstract Row describe(T entry);

    protected abstract void pick(T entry);

    @Override
    protected float windowWidth() {
        return 300;
    }

    @Override
    protected UIElement buildBody() {
        UIElement body = Ui.column();
        if (subtitle != null) {
            body.addChild(Ui.paragraph(subtitle, "sm-muted"));
        }
        ScrollerView list = Ui.scroller();
        list.layout(l -> l.height(180).flexGrow(0));
        if (entries.isEmpty()) {
            list.addScrollViewChild(Ui.emptyState(emptyText));
        }
        for (T entry : entries) {
            Row row = describe(entry);
            UIElement element = Ui.row("sm-row");
            element.layout(l -> l.widthPercent(100).height(24).paddingHorizontal(Ui.GAP_S).gapAll(Ui.GAP_S).flexShrink(0));
            UIElement names = Ui.column();
            names.layout(l -> l.flexGrow(1).minWidth(0));
            names.addChild(Ui.text(row.title()));
            if (row.detail() != null) {
                names.addChild(Ui.text(row.detail(), "sm-muted"));
            }
            element.addChildren(Ui.slot(row.icon(), Ui.SLOT), names);
            if (row.trailing() != null) {
                element.addChild(Ui.alignRight(Ui.text(row.trailing(), "sm-price")));
            }
            Ui.clickable(element, () -> pick(entry));
            list.addScrollViewChild(element);
        }
        UIElement actions = Ui.row();
        actions.addChildren(Ui.spacer(), Ui.button(Component.translatable("screen.sailboatmod.ui.close"), Ui.Tone.SECONDARY, this::onClose));
        body.addChildren(list, actions);
        return body;
    }
}
