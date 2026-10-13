package com.monpai.sailboatmod.client.ui.kit;

import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Row (or rail) of page tabs. The active tab is marked with the theme's {@code active} class; selecting a tab only
 * calls back, the owner decides which page to show.
 */
public final class TabBar<T> {
    private final UIElement element;
    private final Map<T, Button> tabs = new LinkedHashMap<>();
    private final Consumer<T> onSelect;
    private final boolean vertical;

    private TabBar(boolean vertical, Consumer<T> onSelect) {
        this.vertical = vertical;
        this.onSelect = onSelect;
        element = vertical ? Ui.column("sm-divider-right") : Ui.wrapRow(Ui.GAP_XS);
        element.layout(l -> l.gapAll(Ui.GAP_XS).flexShrink(0));
        if (vertical) {
            element.layout(l -> l.width(56).paddingAll(Ui.GAP_XS));
        }
    }

    public static <T> TabBar<T> horizontal(Consumer<T> onSelect) {
        return new TabBar<>(false, onSelect);
    }

    /** Left-hand navigation rail, like the market's. */
    public static <T> TabBar<T> rail(Consumer<T> onSelect) {
        return new TabBar<>(true, onSelect);
    }

    public TabBar<T> add(T key, ItemStack icon, Component label) {
        Button tab = new Button();
        tab.addClass("sm-nav-tab");
        if (icon != null && !icon.isEmpty()) {
            tab.addPreIcon(new ItemStackTexture(icon));
        }
        tab.setText(label);
        tab.textStyle(t -> t.adaptiveWidth(false).textWrap(TextWrap.HOVER_ROLL));
        tab.text.layout(l -> l.width(0).flexGrow(1).flexShrink(1).minWidth(0));
        tab.text.setOverflowVisible(false);
        tab.style(s -> s.tooltips(label));
        if (vertical) {
            tab.layout(l -> l.widthPercent(100).height(18).paddingHorizontal(Ui.GAP_XS));
        } else {
            tab.layout(l -> l.height(18).paddingHorizontal(Ui.GAP_M).flexBasis(0).flexGrow(1).flexShrink(1).minWidth(54));
        }
        tab.setOnClick(e -> onSelect.accept(key));
        tabs.put(key, tab);
        element.addChild(tab);
        return this;
    }

    public UIElement element() {
        return element;
    }

    public void select(T key) {
        tabs.forEach((k, tab) -> Ui.setClass(tab, "active", k.equals(key)));
    }

    /** Hides a tab the player cannot use (for example management tabs without the permission). */
    public void setAvailable(T key, boolean available) {
        Button tab = tabs.get(key);
        if (tab != null) {
            tab.setDisplay(available);
        }
    }
}
