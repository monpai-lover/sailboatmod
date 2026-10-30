package com.monpai.sailboatmod.compat.flightcontrol;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.monpai.sailboatmod.compat.flightcontrol.FlightControlBridge.*;

/** Uses public FC methods only. No optional classes appear in a JVM method descriptor. */
final class FlightControlReflection implements FlightControlProtocol.Port {
    private static final String TERMINAL = "ace.flight.block.NavigationTerminalBlockEntity";
    private static final String COMPUTER = "ace.flight.block.FlightControlComputerBlockEntity";
    private static final String NAV = "ace.flight.navigation.";
    private static final String PROFILE_ITEM = "ace.flight.item.NavigationProfileItem";
    private static final Class<?>[] NO_TYPES = new Class<?>[0];
    private final ServerLevel level;
    private final BlockEntity terminal;
    private final ClassLoader loader;

    private FlightControlReflection(ServerLevel level, BlockEntity terminal) {
        this.level = level;
        this.terminal = terminal;
        this.loader = terminal.getClass().getClassLoader();
    }

    static FlightControlReflection open(ServerLevel level, BlockPos position) {
        if (level == null || position == null || !level.getServer().isSameThread())
            throw new IllegalStateException("not_server_thread");
        if (!level.hasChunkAt(position)) throw new IllegalStateException("terminal_chunk_unloaded");
        BlockEntity terminal = level.getBlockEntity(position);
        if (terminal == null || terminal.isRemoved() || !TERMINAL.equals(terminal.getClass().getName()))
            throw new IllegalStateException("real_navigation_terminal_required");
        return new FlightControlReflection(level, terminal);
    }

    @Override public TerminalSnapshot snapshot() throws ReflectiveOperationException {
        Object task = read(terminal, "getTaskSnapshot");
        Object slot = read(terminal, "getProfileSlotSnapshot");
        Object fingerprint = read(task, "profileFingerprint");
        Object computer = computer();
        boolean linked = computer != null
                && bool(call(terminal, "isLinkedTo", new Class<?>[]{BlockPos.class}, ((BlockEntity) computer).getBlockPos()))
                && bool(call(computer, "hasReciprocalControllerAt", new Class<?>[]{BlockPos.class}, terminal.getBlockPos()))
                && sharesStructure((BlockEntity) computer);
        UUID structure = null;
        if (computer != null) {
            Object vehicle = read(computer, "getNavigationVehicleStateSnapshot");
            if (bool(read(vehicle, "available"))) structure = (UUID) read(vehicle, "structureId");
        }
        Object observation = read(terminal, "captureObservations");
        Map<?, ?> values = (Map<?, ?>) read(observation, "values");
        return new TerminalSnapshot(true, "", bool(read(terminal, "isEffectivelyEnabled")), linked,
                bool(read(slot, "hasValidDefinition")), enumName(read(task, "phase")),
                enumName(read(task, "blockReason")), String.valueOf(read(task, "detail")),
                (UUID) read(task, "taskId"), new ExpectedState(number(read(terminal, "getConfigRevision")),
                number(read(task, "revision")), number(read(task, "slotGeneration")), fingerprint(fingerprint)),
                integer(values.get("current_path")), integer(values.get("path_count")), structure);
    }

    VehicleSnapshot vehicleSnapshot() throws ReflectiveOperationException {
        Object computer = computer();
        if (computer == null) throw new IllegalStateException("computer_unavailable");
        Object vehicle = read(computer, "getNavigationVehicleStateSnapshot");
        boolean available = bool(read(vehicle, "available"));
        // A conservative age bound; stale telemetry never establishes arrival or custody.
        boolean fresh = available && bool(call(vehicle, "isFreshAt", new Class<?>[]{long.class, long.class},
                System.nanoTime(), 500_000_000L));
        Object network = read(terminal, "getControlNetworkSnapshot");
        return new VehicleSnapshot(available, String.valueOf(read(vehicle, "reason")),
                (UUID) read(vehicle, "structureId"), decimal(read(vehicle, "positionX")),
                decimal(read(vehicle, "positionY")), decimal(read(vehicle, "positionZ")),
                decimal(read(vehicle, "worldSpeed")), enumName(read(computer, "getAirframeMode")),
                (int) number(read(network, "computerMode")), bool(read(network, "computerWorking")),
                number(read(vehicle, "sampledNanos")), fresh);
    }

    boolean sharesStructure(BlockEntity other) throws ReflectiveOperationException {
        if (other == null || other.isRemoved() || other.getLevel() != terminal.getLevel()) return false;
        Object helper = type("dev.ryanhcode.sable.Sable").getField("HELPER").get(null);
        Class<?> bodyType = type("dev.ryanhcode.sable.sublevel.ServerSubLevel");
        Object first = call(helper, "getContaining", new Class<?>[]{BlockEntity.class}, terminal);
        Object second = call(helper, "getContaining", new Class<?>[]{BlockEntity.class}, other);
        if (!bodyType.isInstance(first) || !bodyType.isInstance(second)
                || bool(read(first, "isRemoved")) || bool(read(second, "isRemoved"))) return false;
        Object id = read(first, "getUniqueId");
        return id != null && id.equals(read(second, "getUniqueId")) && read(first, "getLevel") == read(second, "getLevel");
    }

    @Override public boolean defaultEnabled() throws ReflectiveOperationException {
        return bool(read(terminal, "isDefaultEnabled"));
    }

    @Override public boolean setDefaultEnabled(long revision, boolean value) throws ReflectiveOperationException {
        Object result = call(terminal, "setDefaultEnabled", new Class<?>[]{long.class, boolean.class}, revision, value);
        return "APPLIED".equals(enumName(read(result, "status")));
    }

    @Override public boolean action(Action action, ExpectedState expected) throws ReflectiveOperationException {
        Object task = read(terminal, "getTaskSnapshot");
        Object rawFingerprint = read(task, "profileFingerprint");
        if (!Objects.equals(expected.profileFingerprint(), fingerprint(rawFingerprint))) return false;
        Class<?> actionType = type(NAV + "NavigationTaskAction");
        Object result = call(terminal, "requestAction", new Class<?>[]{actionType, long.class, long.class,
                        type(NAV + "NavigationProfileFingerprint")}, enumValue(actionType, action.name()),
                expected.taskRevision(), expected.slotGeneration(), rawFingerprint);
        return bool(read(result, "accepted"));
    }

    String installedProfileFingerprint() throws ReflectiveOperationException {
        ItemStack held = (ItemStack) read(terminal, "getHeldItem");
        Class<?> itemType = type(PROFILE_ITEM);
        if (!bool(staticCall(itemType, "isNavigationProfile", new Class<?>[]{ItemStack.class}, held)))
            return "";
        Object decoded = staticCall(itemType, "readDefinition", new Class<?>[]{ItemStack.class}, held);
        if (!bool(read(decoded, "valid"))) return "";
        Object definition = ((Optional<?>) read(decoded, "definition")).orElseThrow();
        return fingerprint(read(definition, "fingerprint"));
    }

    Result submit(RoutePlan plan, ExpectedState expected) throws ReflectiveOperationException {
        TerminalSnapshot before = snapshot();
        String problem = FlightControlProtocol.submissionProblem(before, defaultEnabled(), expected);
        if (!problem.isEmpty()) return new Result(false, problem, before);
        Object fcc = computer();
        String requiredMode = plan.airframe() == Airframe.FIXED_WING ? "FIXED_WING" : "GENERAL";
        if (!requiredMode.equals(enumName(read(fcc, "getAirframeMode"))))
            return new Result(false, "airframe_mode_mismatch:" + requiredMode, before);
        ItemStack held = (ItemStack) read(terminal, "getHeldItem");
        Class<?> itemType = type(PROFILE_ITEM);
        if (!bool(staticCall(itemType, "isNavigationProfile", new Class<?>[]{ItemStack.class}, held)))
            return new Result(false, "navigation_profile_item_required", before);
        Object existing = staticCall(itemType, "readDefinition", new Class<?>[]{ItemStack.class}, held);
        String status = enumName(read(existing, "status"));
        if (!status.equals("VALID") && !status.equals("UNCONFIGURED"))
            return new Result(false, "invalid_profile", before);

        // Validate constructors, schema, hash and round-trip on a detached copy before committing.
        Object route = buildRoute(plan, level.dimension().location());
        ItemStack candidate = held.copy();
        Class<?> routeType = type(NAV + "NavigationProfileDefinition$WaypointRoute");
        Object definition = staticCall(itemType, status.equals("VALID") ? "updateWaypointRoute" : "createWaypointRoute",
                new Class<?>[]{ItemStack.class, routeType}, candidate, route);
        Object roundTrip = staticCall(itemType, "readDefinition", new Class<?>[]{ItemStack.class}, candidate);
        if (!bool(read(roundTrip, "valid"))) return new Result(false, "generated_profile_invalid", before);
        Object decoded = ((Optional<?>) read(roundTrip, "definition")).orElseThrow();
        if (!Objects.equals(read(decoded, "waypointRoute"), route)
                || !Objects.equals(read(decoded, "fingerprint"), read(definition, "fingerprint")))
            return new Result(false, "generated_profile_roundtrip_mismatch", before);
        TerminalSnapshot rechecked = snapshot();
        if (!expected.equals(rechecked.expected()) || read(terminal, "getHeldItem") != held)
            return new Result(false, "stale_view", rechecked);
        staticCall(itemType, "writeDefinition", new Class<?>[]{ItemStack.class, type(NAV + "NavigationProfileDefinition")}, held, definition);
        // notify invalidates the slot and releases the old task. The normal next BE tick observes the new card.
        read(terminal, "notifyExternalProfileSlotMutation");
        terminal.setChanged();
        read(terminal, "sendData");
        return new Result(true, "profile_pending", snapshot(), fingerprint(read(definition, "fingerprint")));
    }

    private Object buildRoute(RoutePlan plan, ResourceLocation dimension) throws ReflectiveOperationException {
        Class<?> axisType = type(NAV + "NavigationProfileDraft$AxisDraft");
        Object levelAxis = staticCall(axisType, "fixedAngle", new Class<?>[]{double.class}, 0.0);
        Object yaw = staticCall(axisType, "velocityDirection", NO_TYPES);
        Class<?> flightPreset = type(NAV + "NavigationProfileDefinition$FlightPreset");
        Class<?> attitude = type(NAV + "NavigationProfileDefinition$AttitudeControl");
        Object attitudeValue = enumValue(attitude, plan.airframe() == Airframe.FIXED_WING ? "FOLLOW_FLIGHT_TERMINAL" : "CUSTOM");
        Object direct = enumValue(type(NAV + "NavigationProfileDefinition$DirectFlightPreset"), "INSTANCE");
        Constructor<?> pathConstructor = type(NAV + "NavigationProfileDraft$PathDraft").getConstructor(
                double.class, double.class, double.class, axisType, axisType, axisType, int.class, int.class,
                Optional.class, Optional.class, Optional.class, flightPreset, Optional.class, attitude);
        List<Object> paths = new ArrayList<>();
        for (int index = 0; index < plan.waypoints().size(); index++) {
            Waypoint point = plan.waypoints().get(index);
            // FC requires direct touchdown/stop paths. SmoothDirect is used only for preceding fixed-wing legs.
            boolean landingFinal = plan.endBehavior() == EndBehavior.APPROACH_LANDING && index >= plan.waypoints().size() - 2;
            Object preset = plan.airframe() == Airframe.FIXED_WING && !landingFinal
                    ? type(NAV + "NavigationProfileDefinition$SmoothDirectFlightPreset").getConstructor().newInstance() : direct;
            paths.add(pathConstructor.newInstance(point.x(), point.y(), point.z(), levelAxis, levelAxis, yaw,
                    plan.linearThrottle(), plan.angularThrottle(), Optional.empty(), Optional.empty(), Optional.empty(),
                    preset, Optional.empty(), attitudeValue));
        }
        Class<?> ending = type(NAV + "NavigationProfileDraft$EndBehavior");
        Object draft = type(NAV + "NavigationProfileDraft").getConstructor(String.class, List.class, ending)
                .newInstance(plan.name(), paths, enumValue(ending, plan.endBehavior().name()));
        return call(draft, "toWaypointRoute", new Class<?>[]{ResourceLocation.class}, dimension);
    }

    private Object computer() throws ReflectiveOperationException {
        BlockPos position = (BlockPos) read(terminal, "getLinkedComputerPos");
        if (position == null || !level.hasChunkAt(position)) return null;
        BlockEntity result = level.getBlockEntity(position);
        return result != null && !result.isRemoved() && type(COMPUTER).isInstance(result) ? result : null;
    }
    private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }
    private static Object read(Object target, String method) throws ReflectiveOperationException { return call(target, method, NO_TYPES); }
    private static Object call(Object target, String method, Class<?>[] parameters, Object... values) throws ReflectiveOperationException {
        return target.getClass().getMethod(method, parameters).invoke(target, values);
    }
    private static Object staticCall(Class<?> target, String method, Class<?>[] parameters, Object... values) throws ReflectiveOperationException {
        Method resolved = target.getMethod(method, parameters);
        return resolved.invoke(null, values);
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String name) { return Enum.valueOf((Class) type, name); }
    private static String enumName(Object value) { return value instanceof Enum<?> item ? item.name() : String.valueOf(value); }
    private static String fingerprint(Object value) { return value == null ? "" : value.toString(); }
    private static boolean bool(Object value) { return Boolean.TRUE.equals(value); }
    private static long number(Object value) { return ((Number) value).longValue(); }
    private static double decimal(Object value) { return ((Number) value).doubleValue(); }
    private static Integer integer(Object value) {
        return value instanceof Number number && Double.isFinite(number.doubleValue()) ? number.intValue() : null;
    }
}
