package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.monpai.sailboatmod.client.ui.kit.*;
import com.monpai.sailboatmod.client.AirSurfaceClientSelection;
import com.monpai.sailboatmod.client.RegisteredVehicleControllerClientHooks;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.RegisteredVehicleControllerActionPacket;
import com.monpai.sailboatmod.transport.control.*;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.function.Consumer;

/** The two controller blocks share the existing responsive LDLib2 theme and controls. */
public final class RegisteredVehicleControllerScreen extends ThemedScreen {
    private ControllerScreenData data;
    private final Consumer<RegisteredVehicleControllerActionPacket> packetSender;
    private String page = "status", nameDraft, offsetDraft = "1 0 0", speedDraft;
    // These drafts are independent of server refreshes and the existing cargo/flight-terminal offset.
    private String groundThrottleDraft, groundWheelDraft = "1 0 0", groundCalibrationDraft;
    private String takeoffAssistAccelerationDraft;
    private Label takeoffAssistStatusLabel;
    private Button takeoffAssistToggle, takeoffAssistSave;
    private String surfaceRoleDraft="ELEVATOR",surfaceNeutralDraft="0",surfaceMaxDraft="20",surfaceRateDraft="60";
    private int surfacePolarityDraft=1;
    private Button surfacePickButton;
    private Label surfaceValidationLabel;
    private record SurfaceLabels(Label actual,Label target,Label state) { }
    private final Map<String,SurfaceLabels> surfaceLabels=new LinkedHashMap<>();
    private Label fuelStatusLabel;
    private final Map<String,Label> fuelTimeLabels=new LinkedHashMap<>();
    private record FuelEngineLabels(Label available,Label required,Label state) { }
    private record FuelPropellerLabels(Label rpm,Label state) { }
    private final java.util.ArrayList<FuelEngineLabels> fuelEngineLabels=new java.util.ArrayList<>();
    private final java.util.ArrayList<FuelPropellerLabels> fuelPropellerLabels=new java.util.ArrayList<>();
    private Button fuelStartButton,autoReturnButton;
    private Label statusLabel;
    private ScrollerView content;
    private TabBar<String> tabs;

    public RegisteredVehicleControllerScreen(ControllerScreenData data) {
        this(data, packet -> ModNetwork.CHANNEL.sendToServer(packet));
    }

    RegisteredVehicleControllerScreen(ControllerScreenData data, Consumer<RegisteredVehicleControllerActionPacket> packetSender) {
        super(t("title"));
        this.data = data;
        this.packetSender = Objects.requireNonNull(packetSender);
        nameDraft = data.values().getString("Name");
        speedDraft = Double.toString(data.values().getDouble("MaxSpeed"));
        String throttle = data.values().getString("ThrottleTarget");
        groundThrottleDraft = throttle.isBlank() || throttle.equals("-") ? "1 0 0" : throttle;
        groundCalibrationDraft = data.values().getString("GroundCalibration");
        takeoffAssistAccelerationDraft = Double.toString(data.values().contains("TakeoffAssistAcceleration", Tag.TAG_ANY_NUMERIC)
                ? data.values().getDouble("TakeoffAssistAcceleration") : 12);
    }

    public UUID registrationId() {
        return data.registrationId();
    }
    public BlockPos controllerPos(){return data.controllerPos();}

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
        boolean parkingChanged = !data.values().getString("ParkingBrake").equals(update.values().getString("ParkingBrake"))
                || data.values().getBoolean("ParkingBrakeReleased") != update.values().getBoolean("ParkingBrakeReleased");
        boolean assistScopeChanged = supportsTakeoffAssist(data.values()) != supportsTakeoffAssist(update.values());
        boolean aircraftModeChanged = data.values().getBoolean("AirRail") != update.values().getBoolean("AirRail")
                || !data.values().getString("AircraftClass").equals(update.values().getString("AircraftClass"));
        boolean surfacesChanged=!surfaceProfile(data.values()).equals(surfaceProfile(update.values()));
        boolean fuelRowsChanged=!fuelRows(data.values()).equals(fuelRows(update.values()));
        boolean surfaceEditingChanged=data.values().getBoolean("Active")!=update.values().getBoolean("Active")
                ||data.values().getBoolean("Paused")!=update.values().getBoolean("Paused");
        data = update;
        if (statusLabel != null) {
            statusLabel.setValue(statusText());
            if (repaint || parkingChanged && "status".equals(page) || assistScopeChanged && "routes".equals(page)
                    || aircraftModeChanged && ("power".equals(page) || "status".equals(page))
                    ||(surfacesChanged||surfaceEditingChanged||fuelRowsChanged)&&"power".equals(page)) renderPage();
            else {refreshTakeoffAssistValues();refreshAirSurfaceValues();refreshFuelValues();}
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
        packetSender.accept(
                new RegisteredVehicleControllerActionPacket(
                        data.controllerPos(), data.registrationId(), action, value, index));
    }

    private UIElement button(String label, ControllerAction action) {
        return Ui.button(t(label), Ui.Tone.SECONDARY, () -> send(action));
    }

    private void row(Component label, String value) {
        row(label, Component.literal(value));
    }

    private void row(Component label, Component value) {
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
        surfaceLabels.clear();surfacePickButton=null;surfaceValidationLabel=null;
        fuelStatusLabel=null;fuelTimeLabels.clear();fuelEngineLabels.clear();fuelPropellerLabels.clear();fuelStartButton=null;autoReturnButton=null;
        takeoffAssistStatusLabel = null;
        takeoffAssistToggle = null;
        takeoffAssistSave = null;
        CompoundTag values = data.values();
        switch (page) {
            case "status" -> {
                row(t("owner"), values.getString("Owner"));
                row(t("position"), values.getString("Position"));
                row(t("size"), values.getString("Size"));
                row(t("mass"), Double.toString(values.getDouble("Mass")));
                row(t("registration"), data.registrationId().toString());
                row(t("physical"), values.getString("PhysicalId"));
                if (!values.getBoolean("AirRail") && "AIR".equals(values.getString("Kind")) && "FIXED_WING".equals(values.getString("AircraftClass"))) {
                    String parking = switch (values.getString("ParkingBrake")) {
                        case "HOLDING" -> "holding";
                        case "RELEASED" -> "released";
                        default -> "waiting";
                    };
                    row(t("parking_brake"), t("parking_brake." + parking));
                    content.addScrollViewChild(button(values.getBoolean("ParkingBrakeReleased")
                            ? "parking_brake_engage" : "parking_brake_release", ControllerAction.TOGGLE_PARKING_BRAKE));
                }
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
                        button("AIR".equals(values.getString("Kind")) && "FIXED_WING".equals(values.getString("AircraftClass"))
                                ? "bind_stand_airport" : "bind_terminal", ControllerAction.BIND_NEAREST_TERMINAL),
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
                if(values.getBoolean("AirRail"))renderFuelSummary();
                UIElement drive = Ui.wrapRow(Ui.GAP_S);
                fuelStartButton=Ui.button(t(fuelStartLabel(values)),Ui.Tone.SECONDARY,()->{
                    if(fuelStartAllowed(data.values()))send(ControllerAction.START);
                });
                drive.addChildren(
                        fuelStartButton,
                        button("pause", ControllerAction.PAUSE),
                        button("resume", ControllerAction.RESUME),
                        button("stop", ControllerAction.STOP));
                content.addScrollViewChild(drive);
                autoReturnButton=Ui.button(
                                t(
                                        values.getBoolean("AutoReturn")
                                                ? "auto_return_on"
                                                : "auto_return_off"),
                                Ui.Tone.SECONDARY,
                                () -> {if(returnEditingAllowed(data.values()))send(ControllerAction.TOGGLE_AUTO_RETURN);});
                content.addScrollViewChild(autoReturnButton);
                content.addScrollViewChild(
                        Ui.button(
                                t(
                                        values.getBoolean("AutoUnload")
                                                ? "auto_unload_on"
                                                : "auto_unload_off"),
                                Ui.Tone.SECONDARY,
                                () -> send(ControllerAction.TOGGLE_AUTO_UNLOAD)));
                content.addScrollViewChild(Ui.paragraph(t("route_hint"), "sm-muted"));
                refreshFuelValues();
                if (supportsTakeoffAssist(values)) renderTakeoffAssist(values);
            }
            case "power" -> {
                if ("AIR".equals(values.getString("Kind"))) {
                    content.addScrollViewChild(Ui.paragraph(t(values.getBoolean("AirRail")?"air_rail_hint":"flight_hint"), "sm-muted"));
                    row(t("aircraft_class"), values.getString("AircraftClass"));
                    row(t("flight_phase"), values.getString("FlightPhase"));
                    if(values.getBoolean("AirRail"))row(t("air_rail_forward"),values.getString("AirRailForward"));
                    else {
                        row(t("flight_terminal"), values.getString("FlightTerminal"));
                        row(t("flight_computer"), values.getString("FlightComputerMode"));
                        row(t("flight_fresh"), values.getBoolean("FlightStateFresh") ? "✓" : "—");
                        content.addScrollViewChild(Ui.paragraph(t("hybrid_unavailable"), "sm-muted"));
                    }
                    UIElement types = Ui.wrapRow(Ui.GAP_S);
                    for (String type : new String[] {"AIRSHIP", "ROTORCRAFT", "FIXED_WING"})
                        types.addChildren(Ui.button(t("aircraft." + type.toLowerCase(java.util.Locale.ROOT)),
                                Ui.Tone.SECONDARY, () -> send(ControllerAction.SET_AIRCRAFT_CLASS, type, 0)));
                    content.addScrollViewChild(types);
                    if(!values.getBoolean("AirRail")) {
                        var terminal = Ui.field(offsetDraft, t("offset"), value -> offsetDraft = value);
                        terminal.layout(l -> l.widthPercent(100));
                        content.addScrollViewChild(terminal);
                        content.addScrollViewChild(Ui.button(t("bind_flight_terminal"), Ui.Tone.PRIMARY,
                                () -> send(ControllerAction.SET_FLIGHT_TERMINAL, offsetDraft, 0)));
                    }
                    var speed = Ui.field(speedDraft, t(values.getBoolean("AirRail") ? "air_rail_speed" : "planning_speed"), value -> speedDraft = value);
                    content.addScrollViewChild(speed);
                    content.addScrollViewChild(Ui.paragraph(t(values.getBoolean("AirRail") ? "air_rail_speed_hint" : "planning_speed_hint"), "sm-muted"));
                    content.addScrollViewChild(Ui.button(t("save_planning_speed"), Ui.Tone.SECONDARY,
                            () -> send(ControllerAction.SET_SPEED, speedDraft, 0)));
                    if(values.getBoolean("AirRail")){renderAirFuel(values);renderAirSurfaces(values);}
                    else renderAirGroundPower(values);
                    break;
                }
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

    private void renderAirSurfaces(CompoundTag values) {
        content.addScrollViewChild(Ui.paragraph(t("air_surface_title"),"sm-strong"));
        content.addScrollViewChild(Ui.paragraph(t("air_surface_hint"),"sm-muted"));
        content.addScrollViewChild(Ui.paragraph(t("air_surface_edit_hint"),"sm-muted"));
        for(String role:AirSurfaceClientSelection.ROLES) {
            var bindings=values.getList("AirSurfaces",Tag.TAG_COMPOUND).stream().map(raw->(CompoundTag)raw)
                    .filter(row->role.equals(row.getString("Role"))).toList();
            if(bindings.isEmpty()){row(t("air_surface_role."+role.toLowerCase(Locale.ROOT)),t("air_surface_unbound"));continue;}
            for(var binding:bindings)renderAirSurfaceBinding(binding,values.getBoolean("Active"),values.getBoolean("Paused"));
        }
        content.addScrollViewChild(Ui.paragraph(t("air_surface_configure"),"sm-strong"));
        UIElement selectors=Ui.wrapRow(Ui.GAP_S);
        selectors.addChildren(Ui.button(Component.translatable("screen.sailboatmod.controller.air_surface_role_choice",
                t("air_surface_role."+surfaceRoleDraft.toLowerCase(Locale.ROOT))),Ui.Tone.SECONDARY,()->{
                    surfaceRoleDraft=AirSurfaceClientSelection.ROLES.get((AirSurfaceClientSelection.ROLES.indexOf(surfaceRoleDraft)+1)%AirSurfaceClientSelection.ROLES.size());renderPage();
                }),Ui.button(t(surfacePolarityDraft==1?"air_surface_positive":"air_surface_negative"),Ui.Tone.SECONDARY,()->{
                    surfacePolarityDraft=-surfacePolarityDraft;renderPage();
                }));
        content.addScrollViewChild(selectors);
        surfaceField("air_surface_neutral",surfaceNeutralDraft,value->{surfaceNeutralDraft=value;refreshSurfaceDraft();});
        surfaceField("air_surface_max",surfaceMaxDraft,value->{surfaceMaxDraft=value;refreshSurfaceDraft();});
        surfaceField("air_surface_rate",surfaceRateDraft,value->{surfaceRateDraft=value;refreshSurfaceDraft();});
        surfaceValidationLabel=Ui.paragraph(Component.empty(),"sm-muted");content.addScrollViewChild(surfaceValidationLabel);
        surfacePickButton=Ui.button(t("air_surface_pick"),Ui.Tone.PRIMARY,()->{
            if(surfaceEditingAllowed())surfaceDraft().ifPresent(config->RegisteredVehicleControllerClientHooks.beginSurfacePick(this,config));
        });
        content.addScrollViewChild(surfacePickButton);refreshSurfaceDraft();
        content.addScrollViewChild(Ui.paragraph(t("air_surface_management_hint"),"sm-muted"));
        content.addScrollViewChild(Ui.paragraph(t("air_surface_clear_hint"),"sm-muted"));
    }
    private void surfaceField(String label,String draft,Consumer<String> changed) {
        content.addScrollViewChild(Ui.paragraph(t(label),"sm-muted"));
        var field=Ui.field(draft,t(label),changed);field.setCharValidator(c->Character.isDigit(c)||c=='-'||c=='.');field.setTextValidator(value->true);
        field.layout(layout->layout.widthPercent(100));content.addScrollViewChild(field);
    }
    private java.util.Optional<AirSurfaceClientSelection.Config> surfaceDraft(){return AirSurfaceClientSelection.parse(surfaceRoleDraft,surfaceNeutralDraft,surfacePolarityDraft,surfaceMaxDraft,surfaceRateDraft);}
    private boolean surfaceEditingAllowed(){return AirSurfaceClientSelection.canEdit(data.values().getBoolean("Active"),data.values().getBoolean("Paused"));}
    private void refreshSurfaceDraft() {
        boolean valid=surfaceDraft().isPresent(),editable=surfaceEditingAllowed();if(surfacePickButton!=null)surfacePickButton.setActive(valid&&editable);
        if(surfaceValidationLabel!=null)surfaceValidationLabel.setValue(!editable?t("air_surface_pause_to_edit"):valid?Component.empty():t("air_surface_invalid_config"));
    }
    private void renderAirSurfaceBinding(CompoundTag binding,boolean active,boolean paused) {
        String role=binding.getString("Role");BlockPos position=BlockPos.of(binding.getLong("Position"));String key=role+";"+position.getX()+","+position.getY()+","+position.getZ();
        content.addScrollViewChild(Ui.paragraph(t("air_surface_role."+role.toLowerCase(Locale.ROOT)),"sm-strong"));
        row(t("air_surface_position"),position.toShortString());
        var actual=surfaceLiveRow("air_surface_actual");var target=surfaceLiveRow("air_surface_target");var state=surfaceLiveRow("air_surface_state");
        surfaceLabels.put(key,new SurfaceLabels(actual,target,state));updateSurfaceLabels(binding,surfaceLabels.get(key));
        content.addScrollViewChild(Ui.paragraph(Component.translatable("screen.sailboatmod.controller.air_surface_limits",
                binding.getDouble("Neutral"),I18n.get("screen.sailboatmod.controller."+(binding.getInt("Polarity")==-1?"air_surface_negative":"air_surface_positive")),
                binding.getDouble("Max"),binding.getDouble("Rate")),"sm-muted"));
        UIElement actions=Ui.wrapRow(Ui.GAP_S);
        if("HOLD".equals(binding.getString("Mode"))) {
            var config=AirSurfaceClientSelection.parse(role,Double.toString(binding.getDouble("Neutral")),binding.getInt("Polarity"),
                    Double.toString(binding.getDouble("Max")),Double.toString(binding.getDouble("Rate")));
            var resume=Ui.button(t("air_surface_follow"),Ui.Tone.SECONDARY,()->{
                if(surfaceEditingAllowed())config.ifPresent(value->send(ControllerAction.BIND_AIR_SURFACE,value.value(position),0));
            });
            resume.setActive(config.isPresent()&&AirSurfaceClientSelection.canEdit(active,paused));actions.addChildren(resume);
        } else {
            var hold=Ui.button(t("air_surface_hold"),Ui.Tone.SECONDARY,()->{if(surfaceEditingAllowed())send(ControllerAction.REMOVE_AIR_SURFACE,key,0);});
            hold.setActive(AirSurfaceClientSelection.canEdit(active,paused));actions.addChildren(hold);
        }
        var clear=Ui.button(t("air_surface_clear"),Ui.Tone.SECONDARY,()->{
            if(AirSurfaceClientSelection.canRestoreDrive(data.values().getBoolean("Active"),data.values().getBoolean("Paused")))send(ControllerAction.CLEAR_AIR_SURFACE_BINDING,key,0);
        });
        clear.setActive(AirSurfaceClientSelection.canRestoreDrive(active,paused));actions.addChildren(clear);content.addScrollViewChild(actions);
    }
    private Label surfaceLiveRow(String title) {
        return liveRow(title,96);
    }
    private Label liveRow(String title,int titleWidth) {
        UIElement row=Ui.row();row.layout(l->l.widthPercent(100).gapAll(Ui.GAP_M));
        var label=Ui.text(t(title),"sm-muted");label.layout(l->l.width(titleWidth).flexShrink(0));
        var value=Ui.clippedText(Component.literal("—"));value.layout(l->l.width(0).flexGrow(1));row.addChildren(label,value);content.addScrollViewChild(row);return value;
    }
    private static void updateSurfaceLabels(CompoundTag binding,SurfaceLabels labels) {
        labels.actual().setValue(surfaceAngle(binding,"ActualAngle"));labels.target().setValue(surfaceAngle(binding,"TargetAngle"));
        String reason=binding.getString("Reason"),key="screen.sailboatmod.controller.reason."+reason;
        labels.state().setValue(!reason.isBlank()?(I18n.exists(key)?Component.translatable(key):t("air_surface_unavailable")):
                t(!binding.getBoolean("Available")?"air_surface_unavailable":"HOLD".equals(binding.getString("Mode"))?"air_surface_held":"air_surface_following"));
    }
    private static Component surfaceAngle(CompoundTag binding,String name) {
        if(!binding.getBoolean("Available")||!binding.contains(name,Tag.TAG_ANY_NUMERIC)||!Double.isFinite(binding.getDouble(name)))return Component.literal("—");
        return Component.literal(String.format(Locale.ROOT,"%.1f°",binding.getDouble(name)));
    }
    private void refreshAirSurfaceValues() {
        for(Tag raw:data.values().getList("AirSurfaces",Tag.TAG_COMPOUND)) {
            var binding=(CompoundTag)raw;var position=BlockPos.of(binding.getLong("Position"));
            var labels=surfaceLabels.get(binding.getString("Role")+";"+position.getX()+","+position.getY()+","+position.getZ());
            if(labels!=null)updateSurfaceLabels(binding,labels);
        }
    }
    private static String surfaceProfile(CompoundTag values) {
        var result=new StringBuilder();for(Tag raw:values.getList("AirSurfaces",Tag.TAG_COMPOUND)) {
            var binding=(CompoundTag)raw;for(String key:new String[]{"Role","Mode"})result.append(binding.getString(key)).append(';');
            result.append(binding.getLong("Position")).append(';').append(binding.getInt("Polarity")).append(';');
            for(String key:new String[]{"Neutral","Max","Rate"})result.append(binding.getDouble(key)).append(';');
        }return result.toString();
    }

    private void renderFuelSummary() {
        fuelStatusLabel=liveRow("fuel_title",152);
    }
    private void renderAirFuel(CompoundTag values) {
        content.addScrollViewChild(Ui.paragraph(t("fuel_title"),"sm-strong"));
        renderFuelSummary();
        for(String[] field:new String[][]{{"PlannedTicks","fuel_planned"},{"ReturnTicks","fuel_return"},{"ReserveTicks","fuel_reserve"},{"RequiredTicks","fuel_required"},{"AvailableTicks","fuel_available"}})
            fuelTimeLabels.put(field[0],liveRow(field[1],152));
        var fuel=values.getCompound("AirFuel");int index=0;
        for(Tag raw:fuel.getList("Engines",Tag.TAG_COMPOUND)) {
            var engine=(CompoundTag)raw;
            content.addScrollViewChild(Ui.paragraph(fuelHardwareTitle("fuel_engine",++index,engine),"sm-strong"));
            fuelEngineLabels.add(new FuelEngineLabels(liveRow("fuel_available",152),liveRow("fuel_required",152),liveRow("fuel_engine_state",152)));
        }
        index=0;
        for(Tag raw:fuel.getList("Propellers",Tag.TAG_COMPOUND)) {
            var propeller=(CompoundTag)raw;
            content.addScrollViewChild(Ui.paragraph(fuelHardwareTitle("fuel_propeller",++index,propeller),"sm-strong"));
            fuelPropellerLabels.add(new FuelPropellerLabels(liveRow("fuel_propeller_rpm",152),liveRow("fuel_propeller_state",152)));
        }
        content.addScrollViewChild(Ui.paragraph(t("fuel_hint"),"sm-muted"));
        refreshFuelValues();
    }
    private Component fuelHardwareTitle(String key,int index,CompoundTag hardware) {
        var title=Component.translatable("screen.sailboatmod.controller."+key,index);
        if(hardware.contains("Position",Tag.TAG_LONG))title.append(" ("+BlockPos.of(hardware.getLong("Position")).subtract(data.controllerPos()).toShortString()+")");
        return title;
    }
    private void refreshFuelValues() {
        var values=data.values();var fuel=values.getCompound("AirFuel");
        if(fuelStatusLabel!=null)fuelStatusLabel.setValue(!fuel.getBoolean("Evaluated")?t("fuel_check_pending"):
                fuel.getBoolean("Ready")?t("fuel_ready"):fuelReason(fuel.getString("Reason")));
        for(var entry:fuelTimeLabels.entrySet())entry.getValue().setValue(fuelDuration(fuel,entry.getKey(),!entry.getKey().equals("AvailableTicks")));
        var engines=fuel.getList("Engines",Tag.TAG_COMPOUND);
        for(int i=0;i<Math.min(engines.size(),fuelEngineLabels.size());i++) {
            var engine=engines.getCompound(i);var labels=fuelEngineLabels.get(i);
            labels.available().setValue(fuelDuration(engine,"AvailableTicks",false));labels.required().setValue(fuelDuration(engine,"RequiredTicks",true));
            if(validFuelTicks(engine,"AvailableTicks")&&validFuelTicks(engine,"RequiredTicks")&&engine.getLong("AvailableTicks")<engine.getLong("RequiredTicks")) {
                var shortage=new CompoundTag();shortage.putLong("Ticks",engine.getLong("RequiredTicks")-engine.getLong("AvailableTicks"));
                labels.state().setValue(Component.translatable("screen.sailboatmod.controller.fuel_shortfall",fuelDuration(shortage,"Ticks",true)));
            } else labels.state().setValue(validFuelTicks(engine,"AvailableTicks")&&validFuelTicks(engine,"RequiredTicks")&&engine.getBoolean("Ready")
                    ?t("fuel_ready"):fuelReason(engine.getString("Reason")));
        }
        var propellers=fuel.getList("Propellers",Tag.TAG_COMPOUND);
        for(int i=0;i<Math.min(propellers.size(),fuelPropellerLabels.size());i++) {
            var propeller=propellers.getCompound(i);var labels=fuelPropellerLabels.get(i);
            boolean readable=propeller.contains("Rpm",Tag.TAG_ANY_NUMERIC)&&Double.isFinite(propeller.getDouble("Rpm"));
            labels.rpm().setValue(readable?Component.literal(String.format(Locale.ROOT,"%.1f RPM",propeller.getDouble("Rpm"))):t("fuel_unknown"));
            labels.state().setValue(readable&&propeller.getBoolean("Assembled")&&propeller.getBoolean("Moving")&&propeller.getDouble("Rpm")!=0
                    ?t("fuel_propeller_running"):fuelReason(propeller.getString("Reason").isBlank()?"fuel_power_waiting":propeller.getString("Reason")));
        }
        if(fuelStartButton!=null){fuelStartButton.setText(t(fuelStartLabel(values)));fuelStartButton.setActive(fuelStartAllowed(values));}
        if(autoReturnButton!=null){autoReturnButton.setActive(returnEditingAllowed(values));autoReturnButton.setText(t(values.getBoolean("AutoReturn")?"auto_return_on":"auto_return_off"));}
    }
    private static boolean fuelStartAllowed(CompoundTag values) {
        if(!values.getBoolean("AirRail"))return true;
        var fuel=values.getCompound("AirFuel");
        return !values.getBoolean("Active")&&!values.getBoolean("Paused")&&(!fuel.getBoolean("Evaluated")||fuel.getBoolean("Ready"));
    }
    private static String fuelStartLabel(CompoundTag values) {
        return values.getBoolean("AirRail")&&!values.getCompound("AirFuel").getBoolean("Evaluated")?"fuel_check_start":"start";
    }
    private static boolean returnEditingAllowed(CompoundTag values){return !values.getBoolean("Active")&&!values.getBoolean("Paused");}
    private static boolean validFuelTicks(CompoundTag values,String key){return values.contains(key,Tag.TAG_LONG)&&values.getLong(key)>=0;}
    private static Component fuelDuration(CompoundTag values,String key,boolean ceiling) {
        if(!validFuelTicks(values,key))return t("fuel_unknown");
        long ticks=values.getLong(key),seconds=ticks/20+(ceiling&&ticks%20!=0?1:0);
        return seconds<60?Component.translatable("screen.sailboatmod.controller.fuel_time_seconds",seconds):
                Component.translatable("screen.sailboatmod.controller.fuel_time_minutes",seconds/60,seconds%60);
    }
    private static Component fuelReason(String reason) {
        return switch(reason) {
            case "fuel_insufficient","fuel_empty","fuel_engine_missing","fuel_unknown","fuel_power_waiting","fuel_network_unavailable","fuel_network_foreign","fuel_duration_unavailable",
                    "fuel_no_engines","fuel_power_not_ready","fuel_budget_invalid","fuel_budget_overflow","fuel_controller_not_loaded","fuel_native_abi_unavailable",
                    "fuel_native_state_unavailable","fuel_body_unavailable","fuel_body_not_loaded","fuel_body_changed","fuel_observation_limit","fuel_unsupported_engine",
                    "fuel_unsupported_propeller","fuel_unsupported_fuel","fuel_unsupported_source","fuel_foreign_source","fuel_foreign_propeller_part","fuel_network_unloaded",
                    "fuel_invalid_inventory","fuel_inventory_changed","fuel_waiting_power","fuel_overstressed","fuel_propeller_unassembled","fuel_propeller_stopped",
                    "fuel_propeller_missing","fuel_world_frozen" -> t("reason."+reason);
            default -> t("reason.fuel_unknown");
        };
    }
    private static String fuelRows(CompoundTag values) {
        var result=new StringBuilder();var fuel=values.getCompound("AirFuel");
        for(String key:new String[]{"Engines","Propellers"}) {
            result.append(key).append(':');
            for(Tag raw:fuel.getList(key,Tag.TAG_COMPOUND)){var row=(CompoundTag)raw;result.append(row.contains("Position",Tag.TAG_LONG)?Long.toString(row.getLong("Position")):"?").append(';');}
        }
        return result.toString();
    }

    private void renderAirGroundPower(CompoundTag values) {
        content.addScrollViewChild(Ui.paragraph(t("ground_title"), "sm-strong"));
        content.addScrollViewChild(Ui.paragraph(t("ground_hardware_hint"), "sm-muted"));
        content.addScrollViewChild(Ui.paragraph(t("ground_drive_separation_hint"), "sm-muted"));
        row(t("ground_capability"), localizedGroundValue("reason.", values.getString("GroundCapability")));
        row(t("ground_phase"), localizedGroundValue("ground_phase.", values.getString("GroundPhase")));
        row(t("ground_point"), Integer.toString(values.getInt("GroundPoint")));
        row(t("ground_source_stand"), values.getString("SourceStand"));
        row(t("ground_target_stand"), values.getString("TargetStand"));
        row(t("throttle_target"), values.getString("ThrottleTarget"));

        var throttle = Ui.field(groundThrottleDraft, t("ground_throttle_offset"), value -> groundThrottleDraft = value);
        throttle.layout(l -> l.widthPercent(100));
        content.addScrollViewChild(throttle);
        content.addScrollViewChild(Ui.button(t("bind_throttle"), Ui.Tone.SECONDARY,
                () -> send(ControllerAction.SET_THROTTLE_TARGET, groundThrottleDraft, 0)));
        CompoundTag ports = values.getCompound("Ports");
        for (ControlChannel channel : new ControlChannel[] {ControlChannel.LEFT, ControlChannel.RIGHT, ControlChannel.BRAKE}) {
            String side = ports.getString(channel.name());
            UIElement port = Ui.row();
            port.layout(l -> l.widthPercent(100).gapAll(Ui.GAP_S));
            Label label = Ui.text(t("channel." + channel.name().toLowerCase(java.util.Locale.ROOT)));
            label.layout(l -> l.width(100));
            port.addChildren(label, Ui.button(Component.translatable("direction.sailboatmod." + side.toLowerCase(java.util.Locale.ROOT)),
                    Ui.Tone.SECONDARY, () -> {
                        Direction current;
                        try { current = Direction.valueOf(side); }
                        catch (IllegalArgumentException ignored) { current = Direction.NORTH; }
                        Direction next = Direction.values()[(current.ordinal() + 1) % Direction.values().length];
                        send(ControllerAction.SET_PORT, channel.name() + ":" + next.name(), 0);
                    }));
            content.addScrollViewChild(port);
        }
        content.addScrollViewChild(button(values.getBoolean("PortsConfirmed") ? "ports_confirmed" : "confirm_ports", ControllerAction.CONFIRM_PORTS));

        ListTag wheels = values.getList("GroundWheels", Tag.TAG_STRING);
        row(t("ground_wheels"), wheels.size() + " / 32");
        var wheelOffset = Ui.field(groundWheelDraft, t("ground_wheel_offset"), value -> groundWheelDraft = value);
        wheelOffset.layout(l -> l.widthPercent(100));
        content.addScrollViewChild(wheelOffset);
        if (wheels.size() < 32) content.addScrollViewChild(Ui.button(t("ground_add_wheel"), Ui.Tone.SECONDARY,
                () -> send(ControllerAction.ADD_GROUND_WHEEL, groundWheelDraft, 0)));
        else content.addScrollViewChild(Ui.paragraph(t("ground_wheel_limit"), "sm-muted"));
        for (Tag raw : wheels) {
            String offset = raw.getAsString();
            UIElement wheel = Ui.row();
            wheel.layout(l -> l.widthPercent(100).gapAll(Ui.GAP_S));
            Label label = Ui.text(offset);
            label.layout(l -> l.width(0).flexGrow(1));
            wheel.addChildren(label, Ui.button(t("remove"), Ui.Tone.SECONDARY,
                    () -> send(ControllerAction.REMOVE_GROUND_WHEEL, offset, 0)));
            content.addScrollViewChild(wheel);
        }

        content.addScrollViewChild(Ui.paragraph(t("ground_calibration_hint"), "sm-muted"));
        row(t("ground_saved_calibration"), values.getString("GroundCalibration"));
        row(t("ground_calibration_status"), I18n.get("screen.sailboatmod.controller."
                + (values.getBoolean("GroundCalibrated") ? "ground_calibrated" : "ground_not_calibrated")));
        var calibration = Ui.field(groundCalibrationDraft, t("ground_calibration_values"), value -> groundCalibrationDraft = value);
        calibration.layout(l -> l.widthPercent(100));
        content.addScrollViewChild(calibration);
        content.addScrollViewChild(Ui.button(t("ground_save_calibration"), Ui.Tone.SECONDARY,
                () -> send(ControllerAction.SET_GROUND_CALIBRATION, groundCalibrationDraft, 0)));
        content.addScrollViewChild(Ui.paragraph(t("ground_confirm_hint"), "sm-muted"));
        UIElement confirmation = Ui.wrapRow(Ui.GAP_S);
        confirmation.addChildren(button("ground_confirm_calibration", ControllerAction.CONFIRM_GROUND_CALIBRATION),
                button("ground_clear_calibration", ControllerAction.CLEAR_GROUND_CALIBRATION));
        content.addScrollViewChild(confirmation);
    }

    private static String localizedGroundValue(String prefix, String value) {
        if (value.isBlank()) return "—";
        String key = "screen.sailboatmod.controller." + prefix + value.toLowerCase(java.util.Locale.ROOT);
        return I18n.exists(key) ? I18n.get(key) : value;
    }

    private static boolean supportsTakeoffAssist(CompoundTag values) {
        return !values.getBoolean("AirRail") && "AIR".equals(values.getString("Kind")) && "FIXED_WING".equals(values.getString("AircraftClass"));
    }

    private void renderTakeoffAssist(CompoundTag values) {
        content.addScrollViewChild(Ui.paragraph(t("takeoff_assist_title"), "sm-strong"));
        content.addScrollViewChild(Ui.paragraph(t("takeoff_assist_hint"), "sm-muted"));
        UIElement status = Ui.row();
        status.layout(l -> l.widthPercent(100).gapAll(Ui.GAP_M));
        Label label = Ui.text(t("takeoff_assist_status"), "sm-muted");
        label.layout(l -> l.width(96).flexShrink(0));
        takeoffAssistStatusLabel = Ui.clippedText(takeoffAssistStatus(values));
        takeoffAssistStatusLabel.layout(l -> l.width(0).flexGrow(1));
        status.addChildren(label, takeoffAssistStatusLabel);
        content.addScrollViewChild(status);
        takeoffAssistToggle = Ui.button(t(values.getBoolean("TakeoffAssistEnabled")
                        ? "takeoff_assist_disable" : "takeoff_assist_enable"), Ui.Tone.SECONDARY,
                () -> send(ControllerAction.TOGGLE_TAKEOFF_ASSIST));
        content.addScrollViewChild(takeoffAssistToggle);
        content.addScrollViewChild(Ui.paragraph(t("takeoff_assist_acceleration"), "sm-muted"));
        var acceleration = Ui.field(takeoffAssistAccelerationDraft, t("takeoff_assist_acceleration"), value -> {
            takeoffAssistAccelerationDraft = value;
            if (takeoffAssistSave != null) takeoffAssistSave.setActive(validTakeoffAssistAcceleration());
        });
        acceleration.setCharValidator(chr -> Character.isDigit(chr) || chr == '.');
        acceleration.setTextValidator(value -> true);
        acceleration.layout(l -> l.widthPercent(100));
        content.addScrollViewChild(acceleration);
        takeoffAssistSave = Ui.button(t("takeoff_assist_save_acceleration"), Ui.Tone.SECONDARY, () -> {
            if (validTakeoffAssistAcceleration())
                send(ControllerAction.SET_TAKEOFF_ASSIST_ACCELERATION, takeoffAssistAccelerationDraft, 0);
        });
        takeoffAssistSave.setActive(validTakeoffAssistAcceleration());
        content.addScrollViewChild(takeoffAssistSave);
    }

    private void refreshTakeoffAssistValues() {
        if (takeoffAssistStatusLabel != null) takeoffAssistStatusLabel.setValue(takeoffAssistStatus(data.values()));
        if (takeoffAssistToggle != null) takeoffAssistToggle.setText(t(data.values().getBoolean("TakeoffAssistEnabled")
                ? "takeoff_assist_disable" : "takeoff_assist_enable"));
    }

    private static Component takeoffAssistStatus(CompoundTag values) {
        String status = values.getString("TakeoffAssistStatus");
        if (status.isBlank()) status = values.getBoolean("TakeoffAssistEnabled") ? "armed" : "off";
        return switch (status) {
            case "off", "armed", "active", "airborne", "waiting", "blocked" -> t("takeoff_assist_status." + status);
            default -> Component.literal(status);
        };
    }

    private boolean validTakeoffAssistAcceleration() {
        try {
            double value = Double.parseDouble(takeoffAssistAccelerationDraft);
            return Double.isFinite(value) && value >= 0 && value <= 20;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    @Override
    protected void onUiTick() {
        if (ticks() % 40 == 0) send(ControllerAction.REFRESH, "", 1);
    }
}
