package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.monpai.sailboatmod.client.ui.kit.*;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.RegisteredVehicleControllerActionPacket;
import com.monpai.sailboatmod.transport.control.*;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** The two controller blocks share the existing responsive LDLib2 theme and controls. */
public final class RegisteredVehicleControllerScreen extends ThemedScreen {
    private ControllerScreenData data;
    private String page = "status", nameDraft, offsetDraft = "1 0 0", speedDraft;
    private Label statusLabel;
    private ScrollerView content;
    private TabBar<String> tabs;

    public RegisteredVehicleControllerScreen(ControllerScreenData data) {
        super(t("title"));
        this.data = data;
        nameDraft = data.values().getString("Name");
        speedDraft = Double.toString(data.values().getDouble("MaxSpeed"));
    }

    public UUID registrationId() {
        return data.registrationId();
    }

    @Override
    protected float windowWidth() {
        return 476;
    }

    private static Component t(String key) {
        return Component.translatable("screen.sailboatmod.controller." + key);
    }

    @Override
    protected UIElement buildBody() {
        UIElement root = Ui.column();
        root.layout(l -> l.height(280).flexShrink(1).minHeight(0).gapAll(Ui.GAP_S));
        statusLabel = Ui.paragraph(statusText(), "sm-muted");
        tabs =
                TabBar.horizontal(
                        selected -> {
                            page = selected;
                            renderPage();
                        });
        for (String key : new String[] {"status", "cargo", "routes", "power"})
            tabs.add(key, ItemStack.EMPTY, t("tab." + key));
        content = Ui.scroller("sm-pane");
        root.addChildren(statusLabel, tabs.element(), content);
        renderPage();
        return root;
    }

    public void update(ControllerScreenData update, boolean repaint) {
        data = update;
        if (statusLabel != null) {
            statusLabel.setValue(statusText());
            if (repaint) renderPage();
        }
    }

    private Component statusText() {
        String state = data.values().getString("Status").toLowerCase(java.util.Locale.ROOT);
        String key = "screen.sailboatmod.controller.reason." + data.values().getString("Reason");
        return Component.literal(data.values().getString("Name") + " · ")
                .append(t("status." + state))
                .append("\n")
                .append(I18n.exists(key) ? Component.translatable(key) : t("reason.check_status"));
    }

    private void send(ControllerAction action) {
        send(action, "", 0);
    }

    private void send(ControllerAction action, String value, int index) {
        ModNetwork.CHANNEL.sendToServer(
                new RegisteredVehicleControllerActionPacket(
                        data.controllerPos(), data.registrationId(), action, value, index));
    }

    private UIElement button(String label, ControllerAction action) {
        return Ui.button(t(label), Ui.Tone.SECONDARY, () -> send(action));
    }

    private void row(Component label, String value) {
        UIElement row = Ui.row();
        row.layout(l -> l.widthPercent(100).gapAll(Ui.GAP_M));
        Label key = Ui.text(label, "sm-muted");
        key.layout(l -> l.width(96).flexShrink(0));
        Label text = Ui.clippedText(value);
        text.layout(l -> l.width(0).flexGrow(1));
        row.addChildren(key, text);
        content.addScrollViewChild(row);
    }

    private void renderPage() {
        if (content == null) return;
        tabs.select(page);
        content.clearAllScrollViewChildren();
        CompoundTag values = data.values();
        switch (page) {
            case "status" -> {
                row(t("owner"), values.getString("Owner"));
                row(t("position"), values.getString("Position"));
                row(t("size"), values.getString("Size"));
                row(t("mass"), Double.toString(values.getDouble("Mass")));
                row(t("registration"), data.registrationId().toString());
                row(t("physical"), values.getString("PhysicalId"));
                var name = Ui.field(nameDraft, t("name"), value -> nameDraft = value);
                name.layout(l -> l.widthPercent(100));
                content.addScrollViewChild(name);
                UIElement actions = Ui.wrapRow(Ui.GAP_S);
                actions.addChildren(
                        Ui.button(
                                t("rename"),
                                Ui.Tone.SECONDARY,
                                () -> send(ControllerAction.RENAME, nameDraft, 0)),
                        button("register", ControllerAction.REGISTER),
                        button("unregister", ControllerAction.UNREGISTER),
                        button("refresh", ControllerAction.REFRESH));
                content.addScrollViewChild(actions);
                content.addScrollViewChild(Ui.paragraph(t("registration_hint"), "sm-muted"));
            }
            case "cargo" -> {
                content.addScrollViewChild(Ui.paragraph(t("cargo_hint"), "sm-muted"));
                var offset = Ui.field(offsetDraft, t("offset"), value -> offsetDraft = value);
                offset.layout(l -> l.widthPercent(100));
                content.addScrollViewChild(offset);
                content.addScrollViewChild(
                        Ui.button(
                                t("add_cargo"),
                                Ui.Tone.PRIMARY,
                                () -> send(ControllerAction.ADD_CARGO, offsetDraft, 0)));
                for (Tag raw : values.getList("Cargo", Tag.TAG_STRING)) {
                    String item = raw.getAsString();
                    UIElement itemRow = Ui.row();
                    itemRow.layout(l -> l.widthPercent(100).gapAll(Ui.GAP_S));
                    Label label = Ui.text(item);
                    label.layout(l -> l.flexGrow(1));
                    itemRow.addChildren(
                            label,
                            Ui.button(
                                    t("remove"),
                                    Ui.Tone.SECONDARY,
                                    () -> send(ControllerAction.REMOVE_CARGO, item, 0)));
                    content.addScrollViewChild(itemRow);
                }
                row(t("manifest"), Integer.toString(values.getInt("ManifestCount")));
            }
            case "routes" -> {
                row(t("terminal"), values.getString("Terminal"));
                row(t("progress"), values.getInt("Target") + " / " + values.getInt("PointCount"));
                UIElement manage = Ui.wrapRow(Ui.GAP_S);
                manage.addChildren(
                        button("bind_terminal", ControllerAction.BIND_NEAREST_TERMINAL),
                        button("import_routes", ControllerAction.IMPORT_ROUTES));
                content.addScrollViewChild(manage);
                int i = 0;
                for (Tag raw : values.getList("Routes", Tag.TAG_STRING)) {
                    final int index = i++;
                    String label =
                            (index == values.getInt("SelectedRoute") ? "● " : "")
                                    + raw.getAsString();
                    content.addScrollViewChild(
                            Ui.button(
                                    Component.literal(label),
                                    Ui.Tone.SECONDARY,
                                    () -> send(ControllerAction.SELECT_ROUTE, "", index)));
                }
                UIElement drive = Ui.wrapRow(Ui.GAP_S);
                drive.addChildren(
                        button("start", ControllerAction.START),
                        button("pause", ControllerAction.PAUSE),
                        button("resume", ControllerAction.RESUME),
                        button("stop", ControllerAction.STOP));
                content.addScrollViewChild(drive);
                content.addScrollViewChild(
                        Ui.button(
                                t(
                                        values.getBoolean("AutoReturn")
                                                ? "auto_return_on"
                                                : "auto_return_off"),
                                Ui.Tone.SECONDARY,
                                () -> send(ControllerAction.TOGGLE_AUTO_RETURN)));
                content.addScrollViewChild(
                        Ui.button(
                                t(
                                        values.getBoolean("AutoUnload")
                                                ? "auto_unload_on"
                                                : "auto_unload_off"),
                                Ui.Tone.SECONDARY,
                                () -> send(ControllerAction.TOGGLE_AUTO_UNLOAD)));
                content.addScrollViewChild(Ui.paragraph(t("route_hint"), "sm-muted"));
            }
            case "power" -> {
                content.addScrollViewChild(Ui.paragraph(t("power_hint"), "sm-muted"));
                CompoundTag ports = values.getCompound("Ports");
                for (ControlChannel channel : ControlChannel.values()) {
                    String side = ports.getString(channel.name());
                    UIElement portRow = Ui.row();
                    portRow.layout(l -> l.widthPercent(100).gapAll(Ui.GAP_S));
                    Label label =
                            Ui.text(
                                    t(
                                            "channel."
                                                    + channel.name()
                                                            .toLowerCase(java.util.Locale.ROOT)));
                    label.layout(l -> l.width(100));
                    portRow.addChildren(
                            label,
                            Ui.button(
                                    Component.translatable(
                                            "direction.sailboatmod."
                                                    + side.toLowerCase(java.util.Locale.ROOT)),
                                    Ui.Tone.SECONDARY,
                                    () -> {
                                        Direction current;
                                        try {
                                            current = Direction.valueOf(side);
                                        } catch (IllegalArgumentException ignored) {
                                            current = Direction.NORTH;
                                        }
                                        Direction next =
                                                Direction.values()[
                                                        (current.ordinal() + 1)
                                                                % Direction.values().length];
                                        send(
                                                ControllerAction.SET_PORT,
                                                channel.name() + ":" + next.name(),
                                                0);
                                    }));
                    content.addScrollViewChild(portRow);
                }
                content.addScrollViewChild(
                        button(
                                values.getBoolean("PortsConfirmed")
                                        ? "ports_confirmed"
                                        : "confirm_ports",
                                ControllerAction.CONFIRM_PORTS));
                var nativeOffset = Ui.field(offsetDraft, t("offset"), value -> offsetDraft = value);
                nativeOffset.layout(l -> l.widthPercent(100));
                content.addScrollViewChild(nativeOffset);
                UIElement targets = Ui.wrapRow(Ui.GAP_S);
                targets.addChildren(
                        Ui.button(
                                t("bind_throttle"),
                                Ui.Tone.SECONDARY,
                                () -> send(ControllerAction.SET_THROTTLE_TARGET, offsetDraft, 0)),
                        Ui.button(
                                t("bind_steering"),
                                Ui.Tone.SECONDARY,
                                () -> send(ControllerAction.SET_STEERING_TARGET, offsetDraft, 0)),
                        button("clear_native", ControllerAction.CLEAR_NATIVE_TARGETS));
                content.addScrollViewChild(targets);
                row(t("throttle_target"), values.getString("ThrottleTarget"));
                row(t("steering_target"), values.getString("SteeringTarget"));
                content.addScrollViewChild(
                        button(
                                values.getBoolean("Inverted") ? "inverted_on" : "inverted_off",
                                ControllerAction.TOGGLE_INVERTED));
                var speed = Ui.field(speedDraft, t("speed"), value -> speedDraft = value);
                speed.layout(l -> l.widthPercent(100));
                content.addScrollViewChild(speed);
                content.addScrollViewChild(
                        Ui.button(
                                t("save_speed"),
                                Ui.Tone.SECONDARY,
                                () -> send(ControllerAction.SET_SPEED, speedDraft, 0)));
                content.addScrollViewChild(
                        button(
                                values.getBoolean("AllowReverse") ? "reverse_on" : "reverse_off",
                                ControllerAction.TOGGLE_REVERSE));
            }
        }
    }

    @Override
    protected void onUiTick() {
        if (ticks() % 40 == 0) send(ControllerAction.REFRESH, "", 1);
    }
}
