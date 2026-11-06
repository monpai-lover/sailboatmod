package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.monpai.sailboatmod.client.ui.kit.*;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.rail.RailTerminalActionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;

public final class RailFreightTerminalScreen extends ThemedScreen {
    private CompoundTag data;
    private ScrollerView content;
    private static final UUID NONE = new UUID(0, 0);
    public RailFreightTerminalScreen(CompoundTag data) { super(t("title")); this.data = data.copy(); }
    public static void openOrUpdate(CompoundTag data, boolean open) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().location().toString().equals(data.getString("Dimension")) || !data.hasUUID("TerminalId")) return;
        if (mc.screen instanceof RailFreightTerminalScreen screen
                && screen.data.getUUID("TerminalId").equals(data.getUUID("TerminalId")) && screen.data.getLong("Pos") == data.getLong("Pos")) {
            if (data.getLong("Revision") >= screen.data.getLong("Revision") && !screen.data.equals(data)) { screen.data = data.copy(); screen.renderContent(); }
        } else if (open) mc.setScreen(new RailFreightTerminalScreen(data));
    }
    private static Component t(String key, Object... args) { return Component.translatable("screen.sailboatmod.rail." + key, args); }
    @Override protected float windowWidth() { return 470; }
    @Override protected UIElement buildBody() {
        UIElement root = Ui.column(); root.layout(l -> l.height(300).flexShrink(1).minHeight(0).gapAll(Ui.GAP_S));
        root.addChildren(Ui.paragraph(t("description"), "sm-muted"));
        content = Ui.scroller("sm-pane"); root.addChildren(content); renderContent(); return root;
    }
    @Override protected void onUiTick() {
        if (ticks() % 40 == 0) send(RailTerminalActionPacket.Action.REFRESH, BlockPos.ZERO, NONE);
    }
    private void send(RailTerminalActionPacket.Action action, BlockPos station, UUID train) {
        ModNetwork.CHANNEL.sendToServer(new RailTerminalActionPacket(ResourceLocation.parse(data.getString("Dimension")),
                BlockPos.of(data.getLong("Pos")), data.getUUID("TerminalId"), data.getLong("Revision"), action, station, train));
    }
    private void line(Component text) { content.addScrollViewChild(Ui.paragraph(text, "sm-muted")); }
    private void renderContent() {
        if (content == null) return;
        content.clearAllScrollViewChildren();
        String result = data.getString("Result");
        if (!result.isBlank()) line(t("result." + result));
        line(t("warehouse", data.getString("Warehouse").isBlank() ? t("none") : Component.literal(data.getString("Warehouse"))));
        line(t("station", data.getString("Station").isBlank() ? t("none") : Component.literal(data.getString("Station"))));
        if (!data.getBoolean("StationValid")) line(t("station_missing"));
        var stations = data.getList("Stations", Tag.TAG_COMPOUND);
        if (stations.isEmpty()) line(t("no_stations"));
        for (Tag tag : stations) {
            var station = (CompoundTag) tag; var pos = BlockPos.of(station.getLong("Pos")); var id = station.getUUID("Id");
            var button = Ui.button(t("bind_station", pos.toShortString(), id.toString().substring(0, 8)), Ui.Tone.SECONDARY,
                    () -> send(RailTerminalActionPacket.Action.BIND, pos, id));
            button.setActive(!data.hasUUID("StationId") || !data.getUUID("StationId").equals(id));
            content.addScrollViewChild(button);
        }
        line(t("custody_hint"));
        var trains = data.getList("Trains", Tag.TAG_COMPOUND);
        if (trains.isEmpty()) line(t("no_trains"));
        for (Tag tag : trains) {
            var train = (CompoundTag) tag; UUID id = train.getUUID("Id");
            content.addScrollViewChild(Ui.text(Component.literal(train.getString("Name") + " · " + id.toString().substring(0, 8))));
            String status = train.getString("Status"); String key = "screen.sailboatmod.rail.status." + status.toLowerCase(java.util.Locale.ROOT);
            line(Component.translatable(I18n.exists(key) ? key : "screen.sailboatmod.rail.status.unavailable"));
            line(t("cargo", train.getInt("Used"), train.getInt("Slots")));
            line(t("fuel_cargo", train.getInt("FuelSafeSlots")));
            if (!train.getString("Order").isBlank()) line(t("order", train.getString("Order")));
            if (!train.getString("Target").isBlank()) line(t("route", train.getString("Source"), train.getString("Target")));
            UIElement actions = Ui.wrapRow(Ui.GAP_S);
            if (!train.getBoolean("Registered")) {
                var register = Ui.button(t("register"), Ui.Tone.PRIMARY, () -> send(RailTerminalActionPacket.Action.REGISTER, BlockPos.ZERO, id));
                register.setActive(train.getBoolean("Owned")); actions.addChildren(register);
            } else {
                boolean enabled = train.getBoolean("Enabled");
                var toggle = Ui.button(t(enabled ? "disable" : "enable"), Ui.Tone.SECONDARY,
                        () -> send(enabled ? RailTerminalActionPacket.Action.DISABLE : RailTerminalActionPacket.Action.ENABLE, BlockPos.ZERO, id));
                var remove = Ui.button(t("unregister"), Ui.Tone.SECONDARY, () -> send(RailTerminalActionPacket.Action.UNREGISTER, BlockPos.ZERO, id));
                toggle.setActive(train.getBoolean("Owned")); remove.setActive(train.getBoolean("Owned")); actions.addChildren(toggle, remove);
            }
            if (!train.getBoolean("Owned")) line(t("not_owner"));
            content.addScrollViewChild(actions);
        }
    }
}
