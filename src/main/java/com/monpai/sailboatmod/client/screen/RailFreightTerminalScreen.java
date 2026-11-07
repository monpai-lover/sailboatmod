package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.monpai.sailboatmod.client.ui.kit.*;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.rail.RailTerminalActionPacket;
import com.monpai.sailboatmod.transport.rail.RailRoute;
import com.monpai.sailboatmod.transport.rail.ui.RailTerminalStopovers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

public final class RailFreightTerminalScreen extends ThemedScreen {
    private CompoundTag data;
    private ScrollerView content;
    private String nameDraft;
    private long pendingRenameUntil;
    private boolean stopoverPage;
    private long pendingStopoversUntil;
    private RailTerminalActionPacket.Action pendingStopoverAction;
    private boolean actionTimedOut;
    private RailTerminalStopovers.Choice stopoverTarget;
    private record StopoverRow(RailTerminalStopovers.Choice choice, String seconds) {}
    private final List<StopoverRow> stopoverDraft = new ArrayList<>();
    private List<StopoverRow> savedStops = List.of();
    private long stopoverRevision;
    private static final UUID NONE = new UUID(0, 0);
    private static final long ACTION_TIMEOUT_TICKS = 100;
    public RailFreightTerminalScreen(CompoundTag data) { super(t("title")); this.data = data.copy(); this.nameDraft = data.getString("CustomName"); }
    public static void openOrUpdate(CompoundTag data, boolean open) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().location().toString().equals(data.getString("Dimension")) || !data.hasUUID("TerminalId")) return;
        if (mc.screen instanceof RailFreightTerminalScreen screen
                && screen.data.getUUID("TerminalId").equals(data.getUUID("TerminalId")) && screen.data.getLong("Pos") == data.getLong("Pos")) {
            String result = data.getString("Result");
            String action = data.getString("ReplyAction");
            boolean renameReply = !result.isBlank() && (action.equals(RailTerminalActionPacket.Action.RENAME.name())
                    || action.isBlank() && screen.pendingRenameUntil != 0 && !supportsStopovers(data));
            boolean stopsReply = !result.isBlank() && screen.pendingStopoverAction != null
                    && action.equals(screen.pendingStopoverAction.name());
            if (data.getLong("Revision") >= screen.data.getLong("Revision")
                    && (!screen.data.equals(data) || renameReply || stopsReply)) {
                if (screen.nameDraft.equals(screen.data.getString("CustomName")) || renameReply && result.equals("saved"))
                    screen.nameDraft = data.getString("CustomName");
                if (renameReply) screen.pendingRenameUntil = 0;
                boolean reloadStopovers = !screen.stopoversDirty();
                if (stopsReply) {
                    screen.pendingStopoversUntil = 0;
                    screen.pendingStopoverAction = null;
                    reloadStopovers |= result.equals("saved");
                }
                if (renameReply || stopsReply) screen.actionTimedOut = false;
                screen.data = data.copy();
                if (reloadStopovers) screen.selectStopoverTarget(screen.stopoverTarget);
                screen.renderContent();
            }
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
        long now = ticks();
        boolean renameExpired = pendingRenameUntil != 0 && now >= pendingRenameUntil;
        boolean stopsExpired = pendingStopoversUntil != 0 && now >= pendingStopoversUntil;
        if (renameExpired || stopsExpired) {
            if (renameExpired) pendingRenameUntil = 0;
            if (stopsExpired) {
                pendingStopoversUntil = 0;
                pendingStopoverAction = null;
            }
            actionTimedOut = true;
            renderContent();
        }
        if (now % 40 == 0) send(RailTerminalActionPacket.Action.REFRESH, BlockPos.ZERO, NONE);
    }
    private void send(RailTerminalActionPacket.Action action, BlockPos station, UUID train) {
        ModNetwork.CHANNEL.sendToServer(new RailTerminalActionPacket(ResourceLocation.parse(data.getString("Dimension")),
                BlockPos.of(data.getLong("Pos")), data.getUUID("TerminalId"), data.getLong("Revision"), action, station, train));
    }
    private void rename(String value) {
        if (pendingRenameUntil != 0 || !com.monpai.sailboatmod.dock.FacilityNames.validOverride(value)) return;
        pendingRenameUntil = ticks() + ACTION_TIMEOUT_TICKS;
        actionTimedOut = false;
        ModNetwork.CHANNEL.sendToServer(new RailTerminalActionPacket(ResourceLocation.parse(data.getString("Dimension")),
                BlockPos.of(data.getLong("Pos")), data.getUUID("TerminalId"), data.getLong("Revision"),
                RailTerminalActionPacket.Action.RENAME, BlockPos.ZERO, NONE, value));
    }
    private void line(Component text) { content.addScrollViewChild(Ui.paragraph(text, "sm-muted")); }
    private void renderContent() {
        if (content == null) return;
        content.clearAllScrollViewChildren();
        line(t("terminal_name", data.getString("Name")));
        if (supportsStopovers(data)) {
            UIElement tabs = Ui.wrapRow(Ui.GAP_S);
            tabs.addChildren(
                    Ui.button(t("terminal_tab"), stopoverPage ? Ui.Tone.SECONDARY : Ui.Tone.PRIMARY, () -> showStopovers(false)),
                    Ui.button(t("stopover_tab"), stopoverPage ? Ui.Tone.PRIMARY : Ui.Tone.SECONDARY, () -> showStopovers(true)));
            content.addScrollViewChild(tabs);
        }
        String result = data.getString("Result");
        if (!result.isBlank()) line(t("result." + result));
        if (actionTimedOut) line(t("action_timeout"));
        if (stopoverPage && supportsStopovers(data)) {
            renderStopovers();
            return;
        }
        line(t("station", data.getString("Station").isBlank() ? t("none") : Component.literal(data.getString("Station"))));
        if (!data.getBoolean("StationValid")) line(t("station_missing"));
        var stations = data.getList("Stations", Tag.TAG_COMPOUND);
        if (stations.isEmpty()) line(t("no_stations"));
        for (Tag tag : stations) {
            var station = (CompoundTag) tag; var pos = BlockPos.of(station.getLong("Pos")); var id = station.getUUID("Id");
            boolean bound = data.hasUUID("StationId") && data.getUUID("StationId").equals(id);
            var tone = !data.getBoolean("StationValid") && stations.size() == 1 && !bound ? Ui.Tone.PRIMARY : Ui.Tone.SECONDARY;
            var button = Ui.button(t("bind_station", pos.toShortString(), id.toString().substring(0, 8)), tone,
                    () -> send(RailTerminalActionPacket.Action.BIND, pos, id));
            button.setActive(!bound);
            content.addScrollViewChild(button);
        }
        line(t("warehouse", data.getString("Warehouse").isBlank() ? t("none") : Component.literal(data.getString("Warehouse"))));
        line(t("custody_hint"));
        var trains = data.getList("Trains", Tag.TAG_COMPOUND);
        if (trains.isEmpty()) line(t("no_trains"));
        for (Tag tag : trains) {
            var train = (CompoundTag) tag; UUID id = train.getUUID("Id");
            content.addScrollViewChild(Ui.text(Component.literal(train.getString("Name") + " · " + id.toString().substring(0, 8))));
            String status = train.getString("Status"); String key = "screen.sailboatmod.rail.status." + status.toLowerCase(java.util.Locale.ROOT);
            line(Component.translatable(I18n.exists(key) ? key : "screen.sailboatmod.rail.status.unavailable"));
            if (!train.getString("BlockedFrom").isBlank() && !train.getString("BlockedTo").isBlank())
                line(t("blocked_leg", train.getString("BlockedFrom"), train.getString("BlockedTo")));
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
        content.addScrollViewChild(Ui.divider());
        var name = Ui.field(nameDraft, Component.literal(data.getString("Name")), value -> nameDraft = value);
        content.addScrollViewChild(name);
        UIElement names = Ui.wrapRow(Ui.GAP_S);
        var saveName = Ui.button(t("save_name"), Ui.Tone.SECONDARY, () -> rename(nameDraft));
        var followTown = Ui.button(t("follow_town"), Ui.Tone.SECONDARY, () -> rename(""));
        saveName.setActive(pendingRenameUntil == 0);
        followTown.setActive(pendingRenameUntil == 0);
        names.addChildren(saveName, followTown);
        content.addScrollViewChild(names);
        line(t("name_hint"));
        line(t("native_name_hint"));
    }

    private void showStopovers(boolean show) {
        stopoverPage = show;
        renderContent();
    }

    private static boolean supportsStopovers(CompoundTag data) {
        return data.contains("StopoverPlans", Tag.TAG_LIST);
    }

    private boolean stopoversDirty() { return !stopoverDraft.equals(savedStops); }

    private List<RailTerminalStopovers.Plan> savedStopovers() {
        return data.getList("StopoverPlans", Tag.TAG_COMPOUND).stream()
                .map(raw -> RailTerminalStopovers.Plan.load((CompoundTag) raw)).toList();
    }

    private void selectStopoverTarget(RailTerminalStopovers.Choice target) {
        stopoverTarget = target;
        stopoverRevision = data.getLong("Revision");
        savedStops = savedStopovers().stream().filter(plan -> plan.destination().equals(target))
                .findFirst().map(RailFreightTerminalScreen::stopoverRows).orElse(List.of());
        stopoverDraft.clear();
        stopoverDraft.addAll(savedStops);
    }

    private static List<StopoverRow> stopoverRows(RailTerminalStopovers.Plan plan) {
        var rows = new ArrayList<StopoverRow>();
        for (int i = 0; i < plan.stops().size(); i++)
            rows.add(new StopoverRow(plan.stops().get(i), Integer.toString(plan.dwellSeconds().get(i))));
        return List.copyOf(rows);
    }

    private void renderStopovers() {
        boolean timed = data.getBoolean("TimedStopovers");
        line(t(timed ? "stopover_timed_hint" : "stopover_hint"));
        if (data.getBoolean("StopoversUnreadable")) {
            line(t("result.invalid_stopover_data"));
            return;
        }
        Map<RailTerminalStopovers.Choice, String> choices = new LinkedHashMap<>();
        for (Tag raw : data.getList("StopoverChoices", Tag.TAG_COMPOUND)) {
            var entry = (CompoundTag) raw;
            choices.put(RailTerminalStopovers.Choice.load(entry), entry.getString("Name"));
        }
        if (stopoverTarget == null) {
            var targets = new LinkedHashSet<>(choices.keySet());
            savedStopovers().forEach(plan -> targets.add(plan.destination()));
            if (targets.isEmpty()) line(t("stopover_no_targets"));
            for (var target : targets)
                content.addScrollViewChild(Ui.button(Component.literal(stopoverLabel(target, choices)), Ui.Tone.SECONDARY,
                        () -> { selectStopoverTarget(target); renderContent(); }));
            return;
        }
        boolean stale = stopoverRevision != data.getLong("Revision");
        boolean editable = pendingStopoversUntil == 0 && !stale;
        boolean targetAvailable = choices.containsKey(stopoverTarget);
        line(t("stopover_target", stopoverLabel(stopoverTarget, choices)));
        if (!targetAvailable) line(t("stopover_unavailable"));
        if (stale) line(t("stopover_stale_draft"));
        if (stopoverDraft.isEmpty()) line(t("stopover_direct"));
        var save = Ui.button(t("stopover_save"), Ui.Tone.PRIMARY, () -> saveStopovers(choices.keySet()));
        var discard = Ui.button(t("stopover_discard"), Ui.Tone.SECONDARY,
                () -> { selectStopoverTarget(stopoverTarget); renderContent(); });
        var changeTarget = Ui.button(t("stopover_choose_target"), Ui.Tone.SECONDARY,
                () -> { selectStopoverTarget(null); renderContent(); });
        Runnable refreshControls = () -> {
            save.setActive(stopoversSaveable(choices.keySet()));
            discard.setActive(pendingStopoversUntil == 0 && (stopoversDirty() || stale));
            changeTarget.setActive(pendingStopoversUntil == 0 && !stopoversDirty());
        };
        for (int i = 0; i < stopoverDraft.size(); i++) {
            int index = i;
            var row = stopoverDraft.get(i);
            var choice = row.choice();
            line(Component.literal((i + 1) + ". " + stopoverLabel(choice, choices)));
            if (!choices.containsKey(choice)) line(t("stopover_unavailable"));
            UIElement actions = Ui.wrapRow(Ui.GAP_S);
            if (timed) {
                actions.addChildren(Ui.text(t("stopover_seconds")));
                var field = Ui.signedField(row.seconds(), t("stopover_seconds"), 1, RailRoute.MAX_STOPOVER_SECONDS, value -> {
                    stopoverDraft.set(index, new StopoverRow(choice, value));
                    refreshControls.run();
                });
                field.layout(l -> l.width(66));
                field.setActive(editable);
                Ui.setClass(field, "sm-invalid", !row.seconds().isEmpty() && !validStopoverSeconds(row));
                actions.addChildren(field);
            }
            var up = Ui.button(t("stopover_up"), Ui.Tone.SECONDARY, () -> moveStopover(index, -1));
            var down = Ui.button(t("stopover_down"), Ui.Tone.SECONDARY, () -> moveStopover(index, 1));
            var remove = Ui.button(t("stopover_remove"), Ui.Tone.SECONDARY,
                    () -> { stopoverDraft.remove(index); renderContent(); });
            up.setActive(editable && i > 0);
            down.setActive(editable && i + 1 < stopoverDraft.size());
            remove.setActive(editable);
            actions.addChildren(up, down, remove);
            content.addScrollViewChild(actions);
        }
        UIElement controls = Ui.wrapRow(Ui.GAP_S);
        refreshControls.run();
        controls.addChildren(save, discard, changeTarget);
        content.addScrollViewChild(controls);
        if (!targetAvailable || stopoverDraft.size() >= RailRoute.MAX_STOPOVERS) return;
        for (var choice : choices.keySet()) {
            if (choice.terminalPos().equals(stopoverTarget.terminalPos()) || choice.station().equals(stopoverTarget.station())
                    || stopoverDraft.stream().map(StopoverRow::choice).anyMatch(stop -> stop.terminalPos().equals(choice.terminalPos())
                    || stop.station().equals(choice.station()))) continue;
            var add = Ui.button(t("stopover_add", stopoverLabel(choice, choices)), Ui.Tone.SECONDARY,
                    () -> { stopoverDraft.add(new StopoverRow(choice, Integer.toString(RailRoute.DEFAULT_STOPOVER_SECONDS))); renderContent(); });
            add.setActive(editable);
            content.addScrollViewChild(add);
        }
    }

    private static String stopoverLabel(RailTerminalStopovers.Choice choice, Map<RailTerminalStopovers.Choice, String> choices) {
        String name = choices.getOrDefault(choice, "");
        String position = choice.station().stationPos().toShortString();
        return name.isBlank() ? position : name + " · " + position;
    }

    private void moveStopover(int index, int delta) {
        Collections.swap(stopoverDraft, index, index + delta);
        renderContent();
    }

    private static boolean validStopoverSeconds(StopoverRow row) {
        return Ui.isNumberWithin(row.seconds(), 1, RailRoute.MAX_STOPOVER_SECONDS);
    }

    private boolean stopoversSaveable(Set<RailTerminalStopovers.Choice> available) {
        return stopoverTarget != null && pendingStopoversUntil == 0 && stopoverRevision == data.getLong("Revision")
                && stopoversDirty() && (stopoverDraft.isEmpty() || available.contains(stopoverTarget)
                && stopoverDraft.stream().map(StopoverRow::choice).allMatch(available::contains))
                && (!data.getBoolean("TimedStopovers") || stopoverDraft.stream().allMatch(RailFreightTerminalScreen::validStopoverSeconds));
    }

    private void saveStopovers(Set<RailTerminalStopovers.Choice> available) {
        if (!stopoversSaveable(available)) return;
        var dimension = ResourceLocation.parse(data.getString("Dimension"));
        var position = BlockPos.of(data.getLong("Pos"));
        var id = data.getUUID("TerminalId");
        var choices = stopoverDraft.stream().map(StopoverRow::choice).toList();
        var packet = data.getBoolean("TimedStopovers")
                ? RailTerminalActionPacket.setTimedStopovers(dimension, position, id, stopoverRevision, stopoverTarget, choices,
                        stopoverDraft.stream().map(row -> Integer.parseInt(row.seconds().trim())).toList())
                : RailTerminalActionPacket.setStopovers(dimension, position, id, stopoverRevision, stopoverTarget, choices);
        pendingStopoversUntil = ticks() + ACTION_TIMEOUT_TICKS;
        pendingStopoverAction = packet.action();
        actionTimedOut = false;
        ModNetwork.CHANNEL.sendToServer(packet);
        renderContent();
    }
}
