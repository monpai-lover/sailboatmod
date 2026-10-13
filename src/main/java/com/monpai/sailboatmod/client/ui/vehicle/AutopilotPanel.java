package com.monpai.sailboatmod.client.ui.vehicle;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.entity.SailboatEntity;
import com.monpai.sailboatmod.entity.TransportEntity;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.ControlAutopilotPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.IntSupplier;

/**
 * Autopilot controls shared by the sailboat and carriage panels: state, route picker and start / pause / resume /
 * stop. Each button sends the same {@link ControlAutopilotPacket} as before; buttons that cannot apply to the current
 * state (pausing a stopped vehicle) are disabled, and the server still decides.
 */
public final class AutopilotPanel {
    private final TransportEntity vehicle;
    private final IntSupplier routeCount;
    private final IntSupplier selectedRoute;
    private final UIElement element;
    private final Label state;
    private final Label route;
    private final Button prev;
    private final Button next;
    private final Button start;
    private final Button pause;
    private final Button resume;
    private final Button stop;

    public AutopilotPanel(TransportEntity vehicle, IntSupplier routeCount, IntSupplier selectedRoute) {
        this.vehicle = vehicle;
        this.routeCount = routeCount;
        this.selectedRoute = selectedRoute;

        element = Ui.column("sm-pane");
        element.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_S));
        UIElement head = Ui.row();
        head.layout(l -> l.gapAll(Ui.GAP_S));
        state = Ui.text("");
        head.addChildren(Ui.icon(new ItemStack(Items.COMPASS), 12), Ui.text(Component.translatable("screen.sailboatmod.ui.autopilot"), "sm-title"),
                Ui.spacer(), state);

        UIElement routeRow = Ui.row();
        routeRow.layout(l -> l.gapAll(Ui.GAP_S));
        prev = Ui.button(Component.literal("<"), Ui.Tone.SECONDARY, () -> send(SailboatEntity.AutopilotControlAction.PREV_ROUTE));
        next = Ui.button(Component.literal(">"), Ui.Tone.SECONDARY, () -> send(SailboatEntity.AutopilotControlAction.NEXT_ROUTE));
        route = Ui.text("");
        route.layout(l -> l.flexGrow(1));
        routeRow.addChildren(prev, route, next);

        UIElement controls = Ui.row();
        controls.layout(l -> l.gapAll(Ui.GAP_S));
        start = Ui.button(Component.translatable("screen.sailboatmod.route_start"), Ui.Tone.PRIMARY, () -> send(SailboatEntity.AutopilotControlAction.START));
        pause = Ui.button(Component.translatable("screen.sailboatmod.route_pause"), Ui.Tone.SECONDARY, () -> send(SailboatEntity.AutopilotControlAction.PAUSE));
        resume = Ui.button(Component.translatable("screen.sailboatmod.route_resume"), Ui.Tone.SECONDARY, () -> send(SailboatEntity.AutopilotControlAction.RESUME));
        stop = Ui.button(Component.translatable("screen.sailboatmod.route_stop"), Ui.Tone.DANGER, () -> send(SailboatEntity.AutopilotControlAction.STOP));
        for (Button button : new Button[] {start, pause, resume, stop}) {
            button.layout(l -> l.flexGrow(1).flexBasis(0));
        }
        controls.addChildren(start, pause, resume, stop);
        element.addChildren(head, routeRow, controls);
        refresh();
    }

    public UIElement element() {
        return element;
    }

    /** Re-reads the entity's synced state; called every tick by the owning screen. */
    public void refresh() {
        boolean active = vehicle.isAutopilotActive();
        boolean paused = active && vehicle.isAutopilotPaused();
        String stateKey = !active ? "stopped" : paused ? "paused" : "running";
        state.setValue(Component.translatable("screen.sailboatmod.route_status." + stateKey));
        Ui.setClass(state, "sm-up", active && !paused);
        Ui.setClass(state, "sm-warn", paused);
        Ui.setClass(state, "sm-muted", !active);

        int count = routeCount.getAsInt();
        route.setValue(Component.translatable("screen.sailboatmod.route_current",
                selectedRoute.getAsInt() + 1, Math.max(count, 1), vehicle.getSelectedRouteName()));
        prev.setActive(count > 0);
        next.setActive(count > 0);
        start.setActive(!active);
        pause.setActive(active && !paused);
        resume.setActive(paused);
        stop.setActive(active);
    }

    private void send(SailboatEntity.AutopilotControlAction action) {
        ModNetwork.CHANNEL.sendToServer(new ControlAutopilotPacket(vehicle.getTransportId(), action));
    }
}
