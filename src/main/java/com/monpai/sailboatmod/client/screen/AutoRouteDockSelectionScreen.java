package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.monpai.sailboatmod.client.ui.kit.TabBar;
import com.monpai.sailboatmod.client.ui.kit.ThemedScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.dock.AvailableDockEntry;
import com.monpai.sailboatmod.market.TransportTerminalKind;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.CreateAutoRoutePacket;
import com.monpai.sailboatmod.route.water.WaterMidMode;
import dev.vfyjxf.taffy.style.AlignItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks the destination for an automatic route. Ports also choose the mid-route pathfinding mode (default hybrid).
 * "Create" stays disabled until a destination is selected and sends the same {@link CreateAutoRoutePacket}.
 */
public class AutoRouteDockSelectionScreen extends ThemedScreen {
    private final BlockPos sourceDockPos;
    private final TransportTerminalKind terminalKind;
    private final List<AvailableDockEntry> docks;
    private final List<UIElement> rows = new ArrayList<>();
    private int selectedIndex = -1;
    private WaterMidMode selectedMode = WaterMidMode.HYBRID;
    private TabBar<WaterMidMode> modeTabs;
    private Label modeDescription;
    private Button createButton;

    public AutoRouteDockSelectionScreen(BlockPos sourceDockPos, TransportTerminalKind terminalKind, List<AvailableDockEntry> docks) {
        super(Component.translatable(baseKey(terminalKind) + ".title"));
        this.sourceDockPos = sourceDockPos;
        this.terminalKind = terminalKind == null ? TransportTerminalKind.PORT : terminalKind;
        this.docks = docks == null ? List.of() : docks;
    }

    @Override
    protected float windowWidth() {
        return 320;
    }

    @Override
    protected UIElement buildBody() {
        UIElement body = Ui.column();
        body.addChild(Ui.paragraph(autoRouteText("subtitle"), "sm-muted"));

        ScrollerView list = Ui.scroller();
        list.layout(l -> l.height(150).flexGrow(0));
        if (docks.isEmpty()) {
            list.addScrollViewChild(Ui.emptyState(autoRouteText("empty")));
        }
        for (int i = 0; i < docks.size(); i++) {
            int index = i;
            UIElement row = dockRow(docks.get(i));
            Ui.clickable(row, () -> select(index));
            rows.add(row);
            list.addScrollViewChild(row);
        }
        body.addChild(list);

        if (terminalKind == TransportTerminalKind.PORT) {
            modeTabs = TabBar.horizontal(mode -> {
                selectedMode = mode;
                refresh();
            });
            modeTabs.add(WaterMidMode.NOISE, new ItemStack(Items.FEATHER), Component.translatable("screen.sailboatmod.ui.route_mode.noise"))
                    .add(WaterMidMode.NBT, new ItemStack(Items.SPYGLASS), Component.translatable("screen.sailboatmod.ui.route_mode.nbt"))
                    .add(WaterMidMode.HYBRID, new ItemStack(Items.COMPASS), Component.translatable("screen.sailboatmod.ui.route_mode.hybrid"));
            modeDescription = Ui.text("", "sm-muted");
            UIElement modeRow = Ui.row();
            modeRow.layout(l -> l.widthPercent(100).minWidth(0).gapAll(Ui.GAP_S)
                    .flexWrap(dev.vfyjxf.taffy.style.FlexWrap.WRAP));
            modeRow.addChildren(Ui.muted(Component.translatable("screen.sailboatmod.ui.route_mode")), modeTabs.element());
            body.addChildren(modeRow, modeDescription);
        }

        createButton = Ui.button(autoRouteText("create"), Ui.Tone.PRIMARY, this::createRoute);
        UIElement actions = Ui.row();
        actions.layout(l -> l.widthPercent(100).minWidth(0).gapAll(Ui.GAP_S)
                .flexWrap(dev.vfyjxf.taffy.style.FlexWrap.WRAP).flexShrink(0));
        actions.addChildren(Ui.clippedText(autoRouteText("hint"), "sm-muted"), Ui.spacer(),
                Ui.button(Component.translatable("screen.sailboatmod.route_name.cancel"), Ui.Tone.SECONDARY, this::onClose), createButton);
        body.addChild(actions);
        refresh();
        return body;
    }

    private UIElement dockRow(AvailableDockEntry dock) {
        UIElement row = Ui.row("sm-row");
        row.layout(l -> l.widthPercent(100).height(22).paddingHorizontal(Ui.GAP_S).gapAll(Ui.GAP_S).flexShrink(0));
        UIElement names = Ui.column();
        names.layout(l -> l.width(0).flexGrow(1).minWidth(0));
        names.addChildren(Ui.clippedText(dockName(dock.dockName())),
                Ui.clippedText(Component.translatable("screen.sailboatmod.ui.owner_nation",
                        fallback(dock.ownerName(), "screen.sailboatmod.auto_route.unknown_owner"),
                        fallback(dock.nationName(), "screen.sailboatmod.auto_route.no_nation")), "sm-muted"));
        row.addChildren(Ui.slot(new ItemStack(terminalKind == TransportTerminalKind.POST_STATION ? Items.MINECART : Items.OAK_BOAT), Ui.SLOT),
                names, Ui.alignRight(Ui.text(dock.distance() + "m", "sm-price")));
        row.style(s -> s.tooltips(rowText(dock)));
        return row;
    }

    private void select(int index) {
        selectedIndex = index;
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < rows.size(); i++) {
            Ui.setClass(rows.get(i), "selected", i == selectedIndex);
        }
        if (modeTabs != null) {
            modeTabs.select(selectedMode);
            modeDescription.setValue(Component.literal(selectedMode.label()));
        }
        if (createButton != null) {
            createButton.setActive(selectedIndex >= 0 && selectedIndex < docks.size());
        }
    }

    private void createRoute() {
        if (selectedIndex >= 0 && selectedIndex < docks.size()) {
            ModNetwork.CHANNEL.sendToServer(new CreateAutoRoutePacket(sourceDockPos, docks.get(selectedIndex).pos(), selectedMode));
            onClose();
            return;
        }
        toast.show(autoRouteText("select_target"), false, ticks());
    }

    @Override
    protected void afterInit() {
        refresh();
    }

    private Component rowText(AvailableDockEntry dock) {
        return Component.translatable(
                baseKey(terminalKind) + ".entry",
                dockName(dock.dockName()),
                fallback(dock.ownerName(), "screen.sailboatmod.auto_route.unknown_owner"),
                fallback(dock.nationName(), "screen.sailboatmod.auto_route.no_nation"),
                dock.distance()
        );
    }

    private Component dockName(String name) {
        if (name == null || name.isBlank()) {
            return Component.translatable(terminalKind == TransportTerminalKind.POST_STATION
                    ? "block.sailboatmod.post_station"
                    : "block.sailboatmod.dock");
        }
        return Component.literal(name);
    }

    private Component autoRouteText(String suffix, Object... args) {
        return Component.translatable(baseKey(terminalKind) + "." + suffix, args);
    }

    private static String baseKey(TransportTerminalKind terminalKind) {
        return terminalKind == TransportTerminalKind.POST_STATION
                ? "screen.sailboatmod.auto_route.post_station"
                : "screen.sailboatmod.auto_route.port";
    }

    private static Component fallback(String value, String key) {
        if (value == null || value.isBlank()) {
            return Component.translatable(key);
        }
        return Component.literal(value);
    }
}
