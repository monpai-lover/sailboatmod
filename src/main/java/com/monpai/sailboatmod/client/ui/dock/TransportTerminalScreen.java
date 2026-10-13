package com.monpai.sailboatmod.client.ui.dock;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.monpai.sailboatmod.client.ui.kit.ActionButton;
import com.monpai.sailboatmod.client.ui.kit.ThemedContainerScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.client.ui.state.ConfirmGate;
import com.monpai.sailboatmod.client.ui.state.PendingRequests;
import com.monpai.sailboatmod.menu.DockMenu;
import dev.vfyjxf.taffy.style.AlignItems;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * Shared frame of the dock and post-station screens: the cargo bay (route-book slot, deposit slot, player inventory)
 * on the left, terminal pages on the right, the terrain minimap, and "Sending…" / confirmation state.
 *
 * <p>Both terminals keep their selection on the server (routes, boats, storage lines, waybills are selected by index
 * and the server answers with fresh screen data), so lists here are rebuilt from each update and a click sends the
 * index of the row as shown, exactly as before.</p>
 */
public abstract class TransportTerminalScreen<M extends DockMenu> extends ThemedContainerScreen<M> {
    /** Menu slot indexes fixed by {@link DockMenu}. */
    protected static final int ROUTE_BOOK_SLOT = 0;
    protected static final int DEPOSIT_SLOT = 1;
    protected static final int PLAYER_INVENTORY_START = 2;

    /** Widths of the two halves of the window. */
    protected static final int CARGO_BAY_WIDTH = 174;
    protected static final int PANEL_GAP = 6;
    protected static final int TERMINAL_WIDTH = 320;

    protected final PendingRequests pending = new PendingRequests();
    protected final ConfirmGate confirm = new ConfirmGate();
    protected final TerrainMiniMap miniMap = new TerrainMiniMap();
    protected Label bookSlotLabel;
    protected Label depositSlotLabel;

    protected TransportTerminalScreen(M menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    /** Left half: the two terminal slots with their labels above the player's inventory. */
    protected UIElement cargoBay(Component bookLabel, Component depositLabel, Component hint) {
        UIElement bay = Ui.column("sm-pane");
        bay.layout(l -> l.width(CARGO_BAY_WIDTH).flexShrink(0).paddingAll(Ui.GAP_M).gapAll(Ui.GAP_S));
        UIElement slots = Ui.row();
        slots.layout(l -> l.gapAll(Ui.GAP_L));
        bookSlotLabel = Ui.clippedText(bookLabel, "sm-muted");
        depositSlotLabel = Ui.clippedText(depositLabel, "sm-muted");
        slots.addChildren(labelledSlot(ROUTE_BOOK_SLOT, bookSlotLabel), labelledSlot(DEPOSIT_SLOT, depositSlotLabel));
        bay.addChildren(slots, Ui.paragraph(hint, "sm-muted"), Ui.divider(), playerInventory(PLAYER_INVENTORY_START));
        return bay;
    }

    private UIElement labelledSlot(int index, Label label) {
        UIElement column = Ui.row();
        column.layout(l -> l.width(0).gapAll(Ui.GAP_S).flexGrow(1).minWidth(0));
        label.layout(l -> l.width(0).flexGrow(1).minWidth(0));
        column.addChildren(slotGrid(menu.slots.subList(index, index + 1), 1), label);
        return column;
    }

    /** Right half: terminal content with a fixed width. */
    protected UIElement terminalPanel() {
        UIElement panel = Ui.column();
        panel.layout(l -> l.width(TERMINAL_WIDTH).flexGrow(1).flexShrink(1).minWidth(0).gapAll(Ui.GAP_S).minHeight(0));
        return panel;
    }

    /** A column heading must roll inside its allocated width instead of painting over its neighbour. */
    protected static Label panelHeading(Component title) {
        Label label = Ui.text(title, "sm-muted");
        label.textStyle(style -> style.adaptiveWidth(false).adaptiveHeight(false));
        label.layout(layout -> layout.widthPercent(100).height(9).flexShrink(0).minWidth(0));
        label.setOverflowVisible(false);
        return label;
    }

    protected UIElement twoHalves(UIElement cargoBay, UIElement terminal) {
        UIElement body = Ui.row();
        body.layout(l -> l.gapAll(PANEL_GAP).alignItems(AlignItems.STRETCH));
        body.addChildren(cargoBay, terminal);
        return body;
    }

    /**
     * Refills a server-selected list: one row per entry, the server's selected index highlighted, and a click sends
     * the clicked row's index.
     */
    protected static void fillList(ScrollerView list, int size, int selected, IntFunction<UIElement> row,
                                   IntConsumer onClick, Component emptyText) {
        list.clearAllScrollViewChildren();
        if (size <= 0) {
            list.addScrollViewChild(Ui.emptyState(emptyText));
            return;
        }
        int shownSelected = Math.max(0, Math.min(selected, size - 1));
        for (int i = 0; i < size; i++) {
            int index = i;
            UIElement element = row.apply(i);
            Ui.setClass(element, "selected", i == shownSelected);
            Ui.clickable(element, () -> onClick.accept(index));
            list.addScrollViewChild(element);
        }
    }

    /** A text row with an optional second line. */
    protected static UIElement textRow(String main, String sub) {
        UIElement row = Ui.column("sm-row");
        row.layout(l -> l.widthPercent(100).paddingHorizontal(Ui.GAP_S).paddingVertical(Ui.GAP_XS).flexShrink(0));
        row.addChild(Ui.clippedText(main == null ? "" : main));
        if (sub != null && !sub.isBlank()) {
            row.addChild(Ui.clippedText(sub, "sm-muted"));
        }
        return row;
    }

    /** A text row with a right-aligned figure (distance, state). */
    protected static UIElement textRow(String main, String trailing, String trailingClass) {
        UIElement row = Ui.row("sm-row");
        row.layout(l -> l.widthPercent(100).height(14).paddingHorizontal(Ui.GAP_S).gapAll(Ui.GAP_S).flexShrink(0));
        com.lowdragmc.lowdraglib2.gui.ui.elements.Label name = Ui.clippedText(main == null ? "" : main);
        name.layout(l -> l.width(0).flexGrow(1));
        row.addChildren(name, Ui.alignRight(Ui.text(trailing == null ? "" : trailing, trailingClass)));
        return row;
    }

    protected static List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }

    /** Called by subclasses when new screen data arrives: every outstanding request has been answered. */
    protected void onServerData() {
        pending.resolveAll();
    }

    @Override
    protected void onUiTick() {
        pending.pollTimedOut(ticks()).ifPresent(key -> {
            pending.resolveAll();
            toast.show(Component.translatable("screen.sailboatmod.ui.no_reply_server"), false, ticks());
            refresh();
        });
    }

    /** Re-reads the current screen data into the UI. */
    protected abstract void refresh();

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && miniMap.dragTo(mouseX, mouseY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && miniMap.release(mouseX, mouseY)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        String boat = miniMap.hoveredBoat();
        if (boat != null && !boat.isBlank()) {
            graphics.renderTooltip(font, Component.literal(boat), mouseX, mouseY);
        }
    }

    /** Syncs an action button with the pending state of its request key and an optional confirmation target. */
    protected void syncAction(ActionButton button, boolean enabled, String requestKey, String confirmTarget) {
        boolean armed = confirmTarget != null && confirm.isArmed(confirmTarget, ticks());
        button.sync(enabled, pending.isPending(requestKey), armed);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
