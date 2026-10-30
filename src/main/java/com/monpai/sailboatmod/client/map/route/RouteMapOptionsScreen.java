package com.monpai.sailboatmod.client.map.route;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.monpai.sailboatmod.client.ui.kit.ThemedScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.map.route.MapRouteKind;
import com.monpai.sailboatmod.map.route.MapRouteEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumMap;
import java.util.Objects;

/** Compact retained-parent options dialog shared by all three maps. */
public final class RouteMapOptionsScreen extends ThemedScreen {
    private final Screen parent;
    private final SharedMapRouteLayer layer;
    private final EnumMap<MapRouteKind, Button> categories = new EnumMap<>(MapRouteKind.class);
    private Button inspection;
    private Label coverage;
    private UIElement details;
    private MapRouteEntry shownSelection;
    private boolean shownTruncated;

    public RouteMapOptionsScreen(Screen parent, SharedMapRouteLayer layer) {
        super(SharedMapRouteLayer.text("title"));
        this.parent = parent;
        this.layer = layer;
    }

    public static Button settingsButton(Screen parent, SharedMapRouteLayer layer) {
        return Ui.iconButton(new ItemStack(Items.FILLED_MAP), SharedMapRouteLayer.text("settings"), () -> open(parent, layer));
    }

    public static void open(Screen parent, SharedMapRouteLayer layer) {
        Minecraft.getInstance().setScreen(new RouteMapOptionsScreen(parent, layer));
    }

    @Override protected float windowWidth() { return 290; }

    @Override
    protected UIElement buildBody() {
        UIElement body = Ui.column();
        ScrollerView scroll = Ui.scroller();
        scroll.layout(l -> l.height(184).flexGrow(1).flexShrink(1));
        UIElement options = Ui.column();
        options.layout(l -> l.widthPercent(100).gapAll(Ui.GAP_S).minWidth(0));
        for (MapRouteKind kind : MapRouteKind.values()) {
            Button button = Ui.button(Component.empty(), Ui.Tone.SECONDARY, () -> {
                layer.setVisible(kind, !layer.visible(kind));
                refresh();
            });
            button.layout(l -> l.widthPercent(100));
            categories.put(kind, button);
            options.addChild(button);
        }
        inspection = Ui.button(Component.empty(), Ui.Tone.SECONDARY, () -> {
            layer.setInspectionEnabled(!layer.inspectionEnabled());
            refresh();
        });
        inspection.layout(l -> l.widthPercent(100));
        options.addChildren(Ui.divider(), inspection,
                Ui.paragraph(SharedMapRouteLayer.text("inspect_hint"), "sm-muted"), Ui.divider());
        coverage = Ui.paragraph(Component.empty(), "sm-muted");
        details = Ui.column();
        details.layout(l -> l.widthPercent(100).gapAll(Ui.GAP_XS).minWidth(0));
        options.addChildren(coverage, Ui.text(SharedMapRouteLayer.text("selected"), "sm-title"), details);
        scroll.addScrollViewChild(options);
        UIElement actions = Ui.row();
        actions.addChildren(Ui.spacer(), Ui.button(Component.translatable("gui.done"), Ui.Tone.PRIMARY, this::onClose));
        body.addChildren(scroll, actions);
        refresh();
        return body;
    }

    private void refresh() {
        categories.forEach((kind, button) -> {
            button.setText(Component.empty().append(Component.literal("━ ").withColor(SharedMapRouteLayer.color(kind) & 0xFFFFFF))
                    .append(SharedMapRouteLayer.kindText(kind)).append(": ")
                    .append(SharedMapRouteLayer.text(layer.visible(kind) ? "shown" : "hidden")));
            Ui.setClass(button, "sm-primary", layer.visible(kind));
        });
        inspection.setText(SharedMapRouteLayer.text("inspection").copy().append(": ")
                .append(SharedMapRouteLayer.text(layer.inspectionEnabled() ? "on" : "off")));
        Ui.setClass(inspection, "sm-primary", layer.inspectionEnabled());
        coverage.setValue(SharedMapRouteLayer.text("truncated"));
        coverage.setDisplay(layer.truncated());
        details.clearAllChildren();
        layer.selected().ifPresentOrElse(route -> SharedMapRouteLayer.detailLines(route).forEach(line ->
                        details.addChild(Ui.paragraph(line))),
                () -> details.addChild(Ui.paragraph(SharedMapRouteLayer.text("none"), "sm-muted")));
        shownSelection = layer.selected().orElse(null);
        shownTruncated = layer.truncated();
    }

    @Override
    protected void onUiTick() {
        layer.refreshWorldContext();
        if (!Objects.equals(shownSelection, layer.selected().orElse(null)) || shownTruncated != layer.truncated()) refresh();
    }

    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }
}
