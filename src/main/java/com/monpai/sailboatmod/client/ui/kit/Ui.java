package com.monpai.sailboatmod.client.ui.kit;

import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;

/**
 * Shared building blocks for SailboatMod's LDLib2 screens.
 *
 * <p>Layout is set here in Java (typed and compile-checked); colours and textures come from the active theme
 * stylesheet through the {@code sm-*} classes, so every screen looks consistent and switches theme together.
 * Spacing follows a 2/4/6/8 GUI-pixel scale; text uses the game font at 9px and 18px only, the two sizes that
 * render crisply at every GUI scale.</p>
 */
public final class Ui {
    public static final float GAP_XS = 2;
    public static final float GAP_S = 4;
    public static final float GAP_M = 6;
    public static final float GAP_L = 8;
    public static final float LINE = 10;
    public static final float CONTROL_HEIGHT = 16;
    public static final float SLOT = 18;

    private Ui() {
    }

    // ---------------------------------------------------------------- containers

    public static UIElement column(String... classes) {
        return new UIElement().addClasses(classes)
                .layout(l -> l.flexDirection(FlexDirection.COLUMN));
    }

    public static UIElement row(String... classes) {
        return new UIElement().addClasses(classes)
                .layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));
    }

    public static UIElement wrapRow(float gap, String... classes) {
        return new UIElement().addClasses(classes)
                .layout(l -> l.flexDirection(FlexDirection.ROW).flexWrap(FlexWrap.WRAP).gapAll(gap));
    }

    /** Takes the remaining space in its row or column. */
    public static UIElement spacer() {
        return new UIElement().layout(l -> l.flexGrow(1));
    }

    public static UIElement divider() {
        return new UIElement().addClass("sm-divider").layout(l -> l.height(1).flexShrink(0));
    }

    /** Vertical scrolling list; add children with {@link ScrollerView#addScrollViewChild}. */
    public static ScrollerView scroller(String... classes) {
        ScrollerView view = new ScrollerView();
        view.addClasses(classes);
        view.layout(l -> l.height(0).flexGrow(1).flexShrink(1).minWidth(0).minHeight(0));
        // Content must wrap to the viewport rather than expand the screen to its intrinsic width.
        view.scrollerStyle(s -> s.adaptiveWidth(false).adaptiveHeight(false));
        view.viewPort.layout(l -> l.paddingAll(1).minWidth(0).minHeight(0));
        view.viewContainer.layout(l -> l.widthPercent(100).minWidth(0));
        Scrollbars.install(view);
        return view;
    }

    // ---------------------------------------------------------------- text

    /** Single-line label that rolls on hover when it does not fit, so long names and translations stay readable. */
    public static Label text(Component value, String... classes) {
        Label label = new Label();
        label.setValue(value);
        label.addClasses(classes);
        label.textStyle(t -> t.textWrap(TextWrap.HOVER_ROLL).adaptiveHeight(true));
        label.layout(l -> l.flexShrink(1).minWidth(0));
        return label;
    }

    public static Label text(String value, String... classes) {
        return text(Component.literal(value == null ? "" : value), classes);
    }

    public static Label muted(Component value) {
        return text(value, "sm-muted");
    }

    /** Multi-line paragraph for hints and empty states. */
    public static Label paragraph(Component value, String... classes) {
        Label label = text(value, classes);
        label.textStyle(t -> t.textWrap(TextWrap.WRAP).adaptiveHeight(true));
        label.layout(l -> l.widthPercent(100));
        return label;
    }

    /** 18px figure (price, balance); double size keeps the pixel font crisp. */
    public static Label figure(Component value, String... classes) {
        Label label = text(value, classes);
        label.textStyle(t -> t.fontSize(18).textWrap(TextWrap.NONE));
        return label;
    }

    public static Label alignRight(Label label) {
        label.textStyle(t -> t.textAlignHorizontal(Horizontal.RIGHT));
        return label;
    }

    // ---------------------------------------------------------------- buttons

    public enum Tone {
        PRIMARY("sm-primary"), SECONDARY(""), DANGER("sm-danger");

        final String cssClass;

        Tone(String cssClass) {
            this.cssClass = cssClass;
        }
    }

    public static Button button(Component label, Tone tone, Runnable onClick) {
        Button button = new Button();
        button.setText(label);
        button.text.setOverflowVisible(false);
        if (!tone.cssClass.isEmpty()) {
            button.addClass(tone.cssClass);
        }
        button.layout(l -> l.height(CONTROL_HEIGHT).paddingHorizontal(GAP_M).flexShrink(0));
        button.setOnClick(event -> onClick.run());
        return button;
    }

    /** Square icon button (refresh, close, theme) showing an item as its icon. */
    public static Button iconButton(ItemStack icon, Component tooltip, Runnable onClick) {
        Button button = new Button();
        button.noText();
        button.addPreIcon(new ItemStackTexture(icon));
        button.layout(l -> l.width(CONTROL_HEIGHT + 2).height(CONTROL_HEIGHT).paddingAll(1).flexShrink(0));
        button.style(s -> s.tooltips(tooltip));
        button.setOnClick(event -> onClick.run());
        return button;
    }

    // ---------------------------------------------------------------- slots & icons

    /**
     * An inventory slot showing an item. The slot frame comes from the theme; the item is drawn by the game's
     * item renderer, so modded items and enchantment glints look exactly as they do in inventories.
     */
    public static UIElement slot(ItemStack stack, float size) {
        UIElement slot = new UIElement().addClass("sm-slot")
                .layout(l -> l.width(size).height(size).paddingAll(Math.max(1, size / 9f)).flexShrink(0)
                        .alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
        UIElement item = new UIElement()
                .layout(l -> l.width(size - Math.max(2, size / 4.5f)).height(size - Math.max(2, size / 4.5f)));
        item.style(s -> s.background(new ItemStackTexture(stack)));
        slot.addChild(item);
        return slot;
    }

    /** Small item icon used inline in text rows (the emerald before prices, page icons). */
    public static UIElement icon(ItemStack stack, float size) {
        UIElement icon = new UIElement().layout(l -> l.width(size).height(size).flexShrink(0));
        icon.style(s -> s.background(new ItemStackTexture(stack)));
        return icon;
    }

    // ---------------------------------------------------------------- inputs

    public static TextField field(String value, Component placeholder, Consumer<String> onChange) {
        TextField field = new TextField();
        field.setText(value == null ? "" : value, false);
        field.textFieldStyle(s -> s.placeholder(placeholder));
        field.setTextResponder(onChange);
        field.layout(l -> l.height(CONTROL_HEIGHT).flexShrink(0));
        return field;
    }

    /** Value handed to the model when a number field holds something invalid; every model reads it as invalid. */
    public static final String INVALID_NUMBER = "-1";

    /**
     * Field for positive whole numbers (quantities, prices, amounts) up to {@code max}.
     *
     * <p>Every edit reaches the model, including clearing the field: LDLib2's own number mode only reports text that
     * passes its validator, so a cleared or out-of-range box kept submitting the last valid number. Here the text
     * validator accepts everything, typing is limited to digits, and anything that is not a number within
     * {@code max} (pasted letters, too many digits) reaches the model as {@link #INVALID_NUMBER} while the field is
     * outlined in red. An empty field reaches the model as empty, which each model already handles.</p>
     */
    public static TextField numberField(String value, Component placeholder, long max, Consumer<String> onChange) {
        TextField field = field(value, placeholder, text -> { });
        field.setCharValidator(Character::isDigit);
        field.setTextValidator(text -> true);
        field.setTextResponder(text -> {
            boolean valid = text.isEmpty() || isNumberWithin(text, 0, max);
            setClass(field, "sm-invalid", !valid);
            onChange.accept(valid ? text : INVALID_NUMBER);
        });
        return field;
    }

    /**
     * Field for whole numbers that may be negative (price bands, rent where -1 means "not for rent"). The model
     * always receives the raw text and decides; the field is outlined in red outside {@code [min, max]}.
     */
    public static TextField signedField(String value, Component placeholder, long min, long max, Consumer<String> onChange) {
        TextField field = field(value, placeholder, text -> { });
        field.setCharValidator(chr -> Character.isDigit(chr) || chr == '-');
        field.setTextValidator(text -> true);
        field.setTextResponder(text -> {
            setClass(field, "sm-invalid", !(text.isEmpty() || isNumberWithin(text, min, max)));
            onChange.accept(text);
        });
        return field;
    }

    /** True when the text is a whole number within {@code [min, max]}. */
    public static boolean isNumberWithin(String text, long min, long max) {
        if (text == null || text.isEmpty() || text.length() > 19) {
            return false;
        }
        try {
            long value = Long.parseLong(text.trim());
            return value >= min && value <= max;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- state helpers

    /** Adds or removes a theme class (selected, active, sm-invalid ...). */
    public static void setClass(UIElement element, String cssClass, boolean on) {
        if (on) {
            if (!element.hasClass(cssClass)) {
                element.addClass(cssClass);
            }
        } else if (element.hasClass(cssClass)) {
            element.removeClass(cssClass);
        }
    }

    /** Centred hint shown where a list or detail pane has nothing to show yet. */
    public static UIElement emptyState(Component message) {
        UIElement box = column();
        box.layout(l -> l.widthPercent(100).paddingAll(GAP_L).gapAll(GAP_S).alignItems(AlignItems.CENTER));
        box.addChild(paragraph(message, "sm-muted"));
        return box;
    }

    /** "Label ........ value" line used for figures in side panes; returns the value label for updates. */
    public static Label keyValue(UIElement parent, Component key, String... valueClasses) {
        UIElement line = row();
        line.layout(l -> l.widthPercent(100).gapAll(GAP_S));
        Label value = alignRight(text("", valueClasses));
        value.layout(l -> l.flexGrow(1));
        line.addChildren(muted(key), value);
        parent.addChild(line);
        return value;
    }

    /** Selectable list row with an item slot, a name and a right-aligned trailing text. */
    public static UIElement itemRow(ItemStack stack, Component name, Component trailing, String... trailingClasses) {
        UIElement row = row("sm-row");
        row.layout(l -> l.widthPercent(100).height(SLOT + 4).paddingHorizontal(GAP_S).gapAll(GAP_S).flexShrink(0));
        Label nameLabel = text(name);
        nameLabel.layout(l -> l.flexGrow(1));
        row.addChildren(slot(stack, SLOT), nameLabel, alignRight(text(trailing, trailingClasses)));
        return row;
    }

    /**
     * Makes a non-button element clickable with hover feedback from the theme's {@code :hover} rules.
     * Used for cards and list rows.
     */
    public static <T extends UIElement> T clickable(T element, Runnable onClick) {
        element.addEventListener(UIEvents.CLICK, event -> onClick.run());
        return element;
    }
}
