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
import java.util.Set;
import java.lang.invoke.MethodHandles;

import static com.monpai.sailboatmod.compat.flightcontrol.FlightControlBridge.*;

/** Public control API plus version-pinned read-only lease evidence. No optional types enter our descriptors. */
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

    GroundNeutralSnapshot requestGroundNeutral(BlockEntity requester,UUID owner,Object mission,UUID body,String profile)
            throws ReflectiveOperationException {
        var context=groundNeutralContext(requester,owner,mission,body,profile);
        Object fcc=computer();long now=System.nanoTime();
        var lease=NativeGroundNeutral.request(fcc,context,now,true);
        var ack=NativeGroundNeutral.inspect(lease,context,now);
        return new GroundNeutralSnapshot(lease!=null,ack.reason(),lease==null?null:new GroundNeutralHandle(fcc,context,lease),
                ack.acknowledged(),ack.generation(),ack.physicsTimeSeconds(),ack.observations(),ack.observedAtNanos());
    }

    GroundNeutralSnapshot requestGroundHold(BlockEntity requester,UUID owner,Object mission,UUID body,String profile)
            throws ReflectiveOperationException {
        var context=groundNeutralContext(requester,owner,mission,body,profile,NativeGroundNeutral.Scope.HOLD);
        Object fcc=computer();long now=System.nanoTime();
        var lease=NativeGroundNeutral.request(fcc,context,now,true);
        var ack=NativeGroundNeutral.inspect(lease,context,now);
        return new GroundNeutralSnapshot(lease!=null,ack.reason(),lease==null?null:new GroundNeutralHandle(fcc,context,lease),
                ack.acknowledged(),ack.generation(),ack.physicsTimeSeconds(),ack.observations(),ack.observedAtNanos());
    }

    private NativeGroundNeutral.Context groundNeutralContext(BlockEntity requester,UUID owner,Object mission,UUID expectedBody,String profile)
            throws ReflectiveOperationException {
        return groundNeutralContext(requester,owner,mission,expectedBody,profile,NativeGroundNeutral.Scope.DRIVE);
    }
    private NativeGroundNeutral.Context groundNeutralContext(BlockEntity requester,UUID owner,Object mission,UUID expectedBody,String profile,
            NativeGroundNeutral.Scope scope) throws ReflectiveOperationException {
        if(requester==null||requester.isRemoved()||requester.getLevel()!=level||owner==null||mission==null
                ||expectedBody==null||profile==null||profile.isBlank()
                ||!(requester instanceof com.monpai.sailboatmod.transport.LogisticsCarrier carrier)
                ||!owner.equals(carrier.getTransportUuid()))throw new IllegalStateException("ground_native_binding_changed");
        if(scope==NativeGroundNeutral.Scope.HOLD){
            if(!(requester instanceof com.monpai.sailboatmod.block.entity.RegisteredVehicleControllerBlockEntity rvc)
                    ||!rvc.nativeGroundHoldRegistrationMatches(owner,expectedBody))throw new IllegalStateException("ground_native_hold_unavailable");
            var proof=rvc.nativeGroundHoldProof();long age=proof==null?-1:System.nanoTime()-proof.validatedAtNanos();
            if(proof==null||proof.requester()!=requester||!owner.equals(proof.ownerRegistration())||proof.mission()!=mission
                    ||!expectedBody.equals(proof.physicalId())||!profile.equals(proof.profile())
                    ||age<0||age>NativeGroundNeutral.LEASE_NANOS)throw new IllegalStateException("ground_native_hold_unavailable");
        }
        Object fcc=computer();var state=snapshot();var vehicle=vehicleSnapshot();
        boolean drive=scope==NativeGroundNeutral.Scope.DRIVE;
        if(fcc==null||!state.available()||!state.linked()||state.active()||state.enabled()
                ||drive&&(!state.profileValid()||!profile.equals(state.expected().profileFingerprint()))
                ||!expectedBody.equals(state.structureId())||!profile.equals(installedProfileFingerprint())
                ||!vehicle.available()||!vehicle.fresh()||!expectedBody.equals(vehicle.structureId())
                ||!vehicle.computerWorking()||vehicle.computerMode()!=0
                ||bool(privateValue(fcc,COMPUTER,"workDryRun",boolean.class))
                ||scope==NativeGroundNeutral.Scope.HOLD&&bool(privateValue(fcc,COMPUTER,"debugApplyForces",boolean.class))
                ||!controlOwnership().provesReleased(expectedBody))throw new IllegalStateException("ground_control_conflict");
        Object helper=type("dev.ryanhcode.sable.Sable").getField("HELPER").get(null);
        Object physical=call(helper,"getContaining",new Class<?>[]{BlockEntity.class},requester);
        if(physical==null||physical!=call(helper,"getContaining",new Class<?>[]{BlockEntity.class},terminal)
                ||physical!=call(helper,"getContaining",new Class<?>[]{BlockEntity.class},fcc)
                ||bool(read(physical,"isRemoved"))||read(physical,"getLevel")!=level
                ||!expectedBody.equals(read(physical,"getUniqueId")))throw new IllegalStateException("flight_binding_lost");
        return new NativeGroundNeutral.Context(requester,owner,mission,terminal,physical,profile,
                number(read(fcc,"getNavigationTopologyEpoch")),
                (Long)privateValue(fcc,COMPUTER,"navigationSessionEpoch",long.class),scope);
    }

    GroundNeutralRelease groundNeutralReleaseSnapshot(Object handle)throws ReflectiveOperationException {
        Object fcc=computer();Object vehicle=fcc==null?null:read(fcc,"getNavigationVehicleStateSnapshot");
        Object identity=handle instanceof GroundNeutralHandle own?new GroundNeutralBarrierIdentity(own,
                privateValue(fcc,COMPUTER,"navigationThrustSnapshot",type(NAV+"NavigationThrustSnapshot"))):handle;
        return new GroundNeutralRelease(vehicle==null?-1:decimal(read(vehicle,"physicsTimeSeconds")),
                vehicle==null?-1:number(read(vehicle,"sampledNanos")),0,identity);
    }

    boolean normalFrameAfterGroundRelease(UUID body,String profile,GroundNeutralRelease barrier)throws ReflectiveOperationException {
        if(!(barrier.identity() instanceof GroundNeutralBarrierIdentity capturedBarrier))return false;
        var own=capturedBarrier.owner();
        if(own.computer()!=computer()||!(own.context().requester() instanceof BlockEntity requester))return false;
        var current=groundNeutralContext(requester,own.context().ownerRegistration(),own.context().mission(),body,profile);
        var captured=own.context();
        if(current.body()!=captured.body()||current.terminal()!=captured.terminal()
                ||current.topologyEpoch()!=captured.topologyEpoch()||current.sessionEpoch()!=captured.sessionEpoch())return false;
        Object fcc=own.computer(),vehicle=read(fcc,"getNavigationVehicleStateSnapshot");
        long sampled=number(read(vehicle,"sampledNanos"));double physics=decimal(read(vehicle,"physicsTimeSeconds"));
        if(sampled<=barrier.sampledNanos()||sampled<=barrier.releasedAtNanos()
                ||!Double.isFinite(physics)||physics<=barrier.physicsTimeSeconds())return false;
        Object rawThrust=privateValue(fcc,COMPUTER,"navigationThrustSnapshot",type(NAV+"NavigationThrustSnapshot"));
        Object thrust=call(fcc,"getNavigationThrustSnapshot",new Class<?>[]{type(NAV+"NavigationVehicleStateSnapshot")},vehicle);
        if(bool(read(thrust,"available"))&&bool(call(thrust,"matches",
                new Class<?>[]{type(NAV+"NavigationVehicleStateSnapshot"),long.class,long.class,long.class},
                vehicle,current.topologyEpoch(),current.sessionEpoch(),System.nanoTime())))return true;
        // Exact native normal-idle branch is a callback witness, never a substitute for the later power/performance/START gates.
        return rawThrust!=null&&rawThrust!=capturedBarrier.thrustBefore()
                &&"no_active_controller".equals(read(rawThrust,"reason"))
                &&rawThrust==privateValue(fcc,COMPUTER,"navigationThrustSnapshot",type(NAV+"NavigationThrustSnapshot"))
                &&vehicle==read(fcc,"getNavigationVehicleStateSnapshot")
                &&current.topologyEpoch()==number(read(fcc,"getNavigationTopologyEpoch"))
                &&current.sessionEpoch()==(Long)privateValue(fcc,COMPUTER,"navigationSessionEpoch",long.class);
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

    boolean isNativeRecoveryCandidate() throws ReflectiveOperationException {
        Object task = read(terminal, "getTaskSnapshot");
        return "PREEMPTED".equals(enumName(read(task, "phase")))
                && "LEASE_LOST".equals(enumName(read(task, "blockReason")))
                && Set.of("route_lease_renew:STALE_TOKEN", "route_command_publish:STALE_TOKEN")
                .contains(String.valueOf(read(task, "detail")));
    }

    /** Narrow 0.8.3-only reads: exact field handles avoid resolving unrelated client-only signatures. */
    FlightTaskSuccessorRegistry.State ownershipState() throws ReflectiveOperationException {
        TerminalSnapshot state = snapshot();
        Object fcc = computer();
        if (fcc == null) throw new IllegalStateException("computer_unavailable");
        Object network = read(terminal, "getControlNetworkSnapshot");
        Object slot = read(terminal, "getProfileSlotSnapshot");
        String actualFingerprint = fingerprint(((Optional<?>) read(slot, "fingerprint")).orElse(null));
        StringBuilder controllers = new StringBuilder();
        for (Object controller : (List<?>) read(network, "controllers")) {
            for (String field : List.of("index", "position", "nodeIncarnation", "loaded", "linkedToComputer",
                    "kind", "settings", "capabilities", "localAuthorityRevision", "localAuthority"))
                controllers.append(field).append('=').append(read(controller, field)).append(';');
        }
        long topology = number(read(network, "topologyEpoch"));
        Object identity = privateValue(terminal, TERMINAL, "activeIdentity", NAV + "NavigationLeaseIdentity");
        Object token = privateValue(terminal, TERMINAL, "activeLeaseToken", NAV + "NavigationLeaseBroker$LeaseToken");
        Object route = privateValue(terminal, TERMINAL, "activeRouteBinding", NAV + "NavigationRouteBinding");
        BlockPos activeComputer = (BlockPos) privateValue(terminal, TERMINAL, "activeComputerPos", BlockPos.class);
        Object broker = privateValue(fcc, COMPUTER, "navigationLeaseBroker", NAV + "NavigationLeaseBroker");
        Object frame = read(broker, "effectiveFrame"); // AtomicReference.get, not expire/renew/reconcile.
        List<FlightControlLeaseProof.Entry> entries = new ArrayList<>();
        for (var entry : ((Map<?, ?>) read(frame, "commands")).entrySet()) {
            Object value = entry.getValue();
            entries.add(new FlightControlLeaseProof.Entry(entry.getKey(), leaseIdentity(read(value, "identity")),
                    number(read(value, "leaseGeneration")), number(read(value, "deadlineNanos"))));
        }
        Long generation = token == null ? null : (Long) privateValue(token, NAV + "NavigationLeaseBroker$LeaseToken", "generation", long.class);
        Set<?> domains = route == null ? Set.of() : (Set<?>) read(route, "domains");
        boolean sameRoute = route != null && state.expected().profileFingerprint().equals(fingerprint(read(route, "profileFingerprint")))
                && state.expected().slotGeneration() == number(read(route, "slotGeneration"))
                && topology == number(read(route, "topologyEpoch"));
        var evidence = FlightControlLeaseProof.inspect(leaseIdentity(identity), generation, domains, entries,
                state.structureId(), state.taskId(), terminal.getBlockPos().asLong(),
                sameRoute && ((BlockEntity) fcc).getBlockPos().equals(activeComputer), System.nanoTime());
        boolean ready = state.available() && state.linked() && state.profileValid() && state.structureId() != null
                && "AVAILABLE".equals(enumName(read(network, "status"))) && bool(read(network, "computerWorking"))
                && number(read(network, "computerMode")) == 0 && topology == number(read(frame, "topologyEpoch"))
                && ((BlockEntity) fcc).getBlockPos().equals(read(network, "computerPosition"))
                && level.dimension().location().toString().equals(read(network, "dimensionId"))
                && state.expected().slotGeneration() == number(read(slot, "slotGeneration"))
                && state.expected().profileFingerprint().equals(actualFingerprint)
                && state.expected().profileFingerprint().equals(installedProfileFingerprint());
        return new FlightTaskSuccessorRegistry.State(state.taskId(), state.phase(), state.blockReason(), state.detail(),
                state.expected().configRevision(), state.expected().slotGeneration(), state.expected().profileFingerprint(),
                ready, defaultEnabled(), state.enabled(),
                new FlightTaskSuccessorRegistry.Binding(state.structureId(), ((BlockEntity) fcc).getBlockPos().toShortString(),
                        topology, (Long) privateValue(fcc, COMPUTER, "navigationSessionEpoch", long.class), controllers.toString()),
                evidence.owned(), evidence.foreign());
    }

    FlightControlBridge.ControlOwnership controlOwnership() throws ReflectiveOperationException {
        Object fcc = computer();
        if (fcc == null) throw new IllegalStateException("computer_unavailable");
        Object broker = privateValue(fcc, COMPUTER, "navigationLeaseBroker", NAV + "NavigationLeaseBroker");
        // All native broker mutations synchronize on this object. These reads never expire/acquire/release a lease.
        synchronized (broker) {
            Object frame = read(broker, "effectiveFrame");
            var before = authorityObservation(fcc, broker, frame, snapshot());
            var state = ownershipState(); // Keep the stricter flight/recovery readiness proof unchanged.
            var occupancy = new FlightControlLeaseProof.NativeOccupancy((int) number(read(broker, "activeBundleCount")),
                    ((Map<?, ?>) privateValue(broker, NAV + "NavigationLeaseBroker", "leasesByOwner", Map.class)).size(),
                    ((Map<?, ?>) privateValue(broker, NAV + "NavigationLeaseBroker", "claims", Map.class)).size(),
                    ((Map<?, ?>) read(frame, "commands")).size());
            var current = snapshot();
            var after = authorityObservation(fcc, broker, read(broker, "effectiveFrame"), current);
            boolean flightConsistent = state.available() && state.binding() != null
                    && Objects.equals(state.taskId(), current.taskId())
                    && Objects.equals(state.binding().bodyId(), current.structureId())
                    && state.configRevision() == current.expected().configRevision()
                    && state.slotGeneration() == current.expected().slotGeneration()
                    && state.fingerprint().equals(current.expected().profileFingerprint());
            return FlightControlLeaseProof.control(before, after, occupancy, flightConsistent, state.leaseOwned(), state.foreignLease());
        }
    }

    private FlightControlLeaseProof.AuthorityObservation authorityObservation(Object fcc, Object broker, Object frame,
            TerminalSnapshot state) throws ReflectiveOperationException {
        Object network = read(terminal, "getControlNetworkSnapshot"), slot = read(terminal, "getProfileSlotSnapshot");
        long topology = number(read(broker, "topologyEpoch"));
        BlockEntity computer = (BlockEntity) fcc;
        Object helper = type("dev.ryanhcode.sable.Sable").getField("HELPER").get(null);
        Object actualBody = call(helper, "getContaining", new Class<?>[]{BlockEntity.class}, terminal);
        Object computerBody = call(helper, "getContaining", new Class<?>[]{BlockEntity.class}, computer);
        Class<?> bodyType = type("dev.ryanhcode.sable.sublevel.ServerSubLevel");
        boolean actualBodyAvailable = bodyType.isInstance(actualBody) && actualBody == computerBody
                && !bool(read(actualBody, "isRemoved")) && read(actualBody, "getLevel") == level;
        UUID actualBodyId = actualBodyAvailable ? (UUID) read(actualBody, "getUniqueId") : null;
        boolean observed = state.available() && computer() == fcc && actualBodyAvailable
                && level.getBlockEntity(terminal.getBlockPos()) == terminal
                && privateValue(fcc, COMPUTER, "navigationLeaseBroker", NAV + "NavigationLeaseBroker") == broker
                && "AVAILABLE".equals(enumName(read(network, "status")))
                && computer.getBlockPos().equals(read(network, "computerPosition"))
                && level.dimension().location().toString().equals(read(network, "dimensionId"))
                && topology == number(read(network, "topologyEpoch")) && topology == number(read(frame, "topologyEpoch"))
                && state.expected().slotGeneration() == number(read(slot, "slotGeneration"));
        var activity = new FlightControlLeaseProof.Activity(
                privateValue(terminal, TERMINAL, "activeIdentity", NAV + "NavigationLeaseIdentity"),
                privateValue(terminal, TERMINAL, "activeLeaseToken", NAV + "NavigationLeaseBroker$LeaseToken"),
                privateValue(terminal, TERMINAL, "activeRouteBinding", NAV + "NavigationRouteBinding"),
                privateValue(terminal, TERMINAL, "activeComputerPos", BlockPos.class));
        return new FlightControlLeaseProof.AuthorityObservation(terminal, fcc, broker, frame, state.structureId(), actualBodyId, actualBody,
                level.dimension().location().toString(), terminal.getBlockPos().asLong(), computer.getBlockPos().asLong(),
                topology, (Long) privateValue(fcc, COMPUTER, "navigationSessionEpoch", long.class),
                state.expected().configRevision(), state.expected().slotGeneration(), state.expected().taskRevision(),
                state.taskId(), state.expected().profileFingerprint(), observed, state.linked(), activity);
    }

    FlightControlBridge.PowerSnapshot powerSnapshot() throws ReflectiveOperationException {
        Object fcc = computer();
        if (!(fcc instanceof BlockEntity computer) || !sharesStructure(computer))
            throw new IllegalStateException("computer_unavailable");
        List<?> bindings = List.copyOf((List<?>) type(COMPUTER).getField("linkedThrusters").get(fcc));
        if (bindings.size() > 128) throw new IllegalStateException("too_many_flight_thrusters");
        Class<?> thrusterType = type("ace.flight.api.IFCCThruster");
        var lookup = MethodHandles.publicLookup();
        var linked = lookup.findVirtual(thrusterType, "isFCCLinked", java.lang.invoke.MethodType.methodType(boolean.class));
        var valid = lookup.findVirtual(thrusterType, "isFCCLinkConfigurationValid", java.lang.invoke.MethodType.methodType(boolean.class));
        var linkedComputer = lookup.findVirtual(thrusterType, "getFCCLinkedComputerPos", java.lang.invoke.MethodType.methodType(BlockPos.class));
        var maximum = lookup.findVirtual(thrusterType, "getFCCMaxThrust", java.lang.invoke.MethodType.methodType(float.class));
        int powered = 0;
        double total = 0;
        for (Object binding : bindings) {
            BlockPos relative = (BlockPos) read(binding, "relativePos");
            BlockPos pos = computer.getBlockPos().offset(relative);
            if (!level.hasChunkAt(pos)) throw new IllegalStateException("flight_thruster_unloaded");
            BlockEntity thruster = level.getBlockEntity(pos);
            if (thruster == null || thruster.isRemoved() || !thrusterType.isInstance(thruster)
                    || !sharesStructure(thruster)) throw new IllegalStateException("flight_thruster_binding_lost");
            try {
                if (!(boolean) linked.invoke(thruster) || !(boolean) valid.invoke(thruster)
                        || !computer.getBlockPos().equals((BlockPos) linkedComputer.invoke(thruster)))
                    throw new IllegalStateException("flight_thruster_binding_lost");
                float capacity = (float) maximum.invoke(thruster);
                if (!Float.isFinite(capacity) || capacity < 0) throw new IllegalStateException("flight_power_unknown");
                if (capacity > 1e-6) { powered++; total += capacity; }
            } catch (ReflectiveOperationException | RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new ReflectiveOperationException("flight_power_query_failed", failure); }
        }
        return new FlightControlBridge.PowerSnapshot(true, powered > 0 ? "" : "flight_power_required",
                bindings.size(), powered, total);
    }

    FixedWingPerformanceSnapshot fixedWingPerformanceSnapshot() throws ReflectiveOperationException {
        Object fcc = computer();
        if (!(fcc instanceof BlockEntity computer) || !sharesStructure(computer)
                || !bool(call(terminal, "isLinkedTo", new Class<?>[]{BlockPos.class}, computer.getBlockPos()))
                || !bool(call(fcc, "hasReciprocalControllerAt", new Class<?>[]{BlockPos.class}, terminal.getBlockPos())))
            throw new IllegalStateException("flight_binding_lost");
        if (!"FIXED_WING".equals(enumName(read(fcc, "getAirframeMode"))))
            throw new IllegalStateException("fixed_wing_reference_unavailable");
        // The public getter validates the live reference and its native 100-tick age gate.
        // The raw cache below supplies only sample age/coherence, never a readiness fallback.
        Object estimate = read(fcc, "getFixedWingEstimate");
        Object cached = privateValue(fcc, COMPUTER, "fixedWingEstimate", "ace.flight.physics.FixedWingPerformance$CachedEstimate");
        if (!bool(read(estimate, "ready")) || cached == null || read(cached, "estimate") != estimate)
            throw new IllegalStateException("fixed_wing_reference_unavailable");
        VehicleSnapshot vehicle = vehicleSnapshot();
        if (!vehicle.available() || !vehicle.fresh() || vehicle.structureId() == null)
            throw new IllegalStateException("flight_state_stale");
        double[] maximum = new double[3];
        double forward = Double.POSITIVE_INFINITY;
        int count = 0;
        List<?> entries = (List<?>) read(fcc, "getLinkedControllerEntriesSnapshot");
        if (entries.size() > 128) throw new IllegalStateException("too_many_flight_controllers");
        Class<?> velocityType = type("ace.flight.block.VelocityCouplerBlockEntity");
        for (Object entry : entries) {
            BlockPos pos = computer.getBlockPos().offset((BlockPos) read(entry, "relativePos"));
            if (!level.hasChunkAt(pos)) throw new IllegalStateException("flight_controller_unloaded");
            BlockEntity controller = level.getBlockEntity(pos);
            if (controller == null || controller.isRemoved() || !sharesStructure(controller))
                throw new IllegalStateException("flight_controller_binding_lost");
            if (!velocityType.isInstance(controller)) continue;
            if (!computer.getBlockPos().equals(read(controller, "getLinkedComputerPos"))
                    || !bool(call(fcc, "hasReciprocalControllerAt", new Class<?>[]{BlockPos.class}, pos)))
                throw new IllegalStateException("flight_controller_binding_lost");
            BlockPos forwardPos = (BlockPos) read(controller, "getLinkedForwardIndicatorPos");
            if (forwardPos == null || !level.hasChunkAt(forwardPos))
                throw new IllegalStateException("flight_forward_source_binding_lost");
            BlockEntity forwardSource = level.getBlockEntity(forwardPos);
            if (forwardSource == null || forwardSource.isRemoved()
                    || !type("ace.flight.block.ForwardIndicatorBlockEntity").isInstance(forwardSource))
                throw new IllegalStateException("flight_forward_source_binding_lost");
            requireForwardReference(forwardSource, read(controller, "getBodyForwardFacing"), pos, sharesStructure(forwardSource));
            double[] bounds = velocityBounds(controller);
            for (int axis = 0; axis < 3; axis++) maximum[axis] = Math.max(maximum[axis], bounds[axis]);
            forward = Math.min(forward, bounds[3]);
            count++;
        }
        if (count == 0) throw new IllegalStateException("fixed_wing_velocity_controller_required");
        return new FixedWingPerformanceSnapshot(true, "", vehicle.structureId(), number(read(cached, "sampledGameTick")),
                level.getGameTime(), enumName(read(estimate, "status")), decimal(read(estimate, "v1")),
                decimal(read(estimate, "vr")), decimal(read(fcc, "getFixedWingLandingPitchDegrees")), forward,
                Math.hypot(Math.hypot(maximum[0], maximum[1]), maximum[2]));
    }

    static void requireForwardReference(Object source, Object facing, BlockPos target, boolean sameStructure)
            throws ReflectiveOperationException {
        if (!sameStructure || source == null || facing == null || !facing.equals(read(source, "getForwardFacing"))
                || !((List<?>) read(source, "getLinkedTargetWorldPositions")).contains(target))
            throw new IllegalStateException("flight_forward_source_binding_lost");
    }

    /** Actual native signed speed ceilings; a large other axis cannot mask an insufficient forward limit. */
    static double[] velocityBounds(Object controller) throws ReflectiveOperationException {
        double[] bounds = new double[4];
        double[] positive = new double[3], negative = new double[3];
        String[] axes = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            positive[i] = decimal(controller.getClass().getField("maxV" + axes[i] + "Pos").get(controller));
            negative[i] = decimal(controller.getClass().getField("maxV" + axes[i] + "Neg").get(controller));
            if (!Double.isFinite(positive[i]) || positive[i] < 0 || !Double.isFinite(negative[i]) || negative[i] < 0)
                throw new IllegalStateException("fixed_wing_speed_invalid");
            bounds[i] = Math.max(positive[i], negative[i]);
        }
        Object facing = read(controller, "getBodyForwardFacing");
        net.minecraft.core.Direction direction = (net.minecraft.core.Direction) read(facing, "direction");
        bounds[3] = switch (direction) {
            case EAST -> positive[0]; case WEST -> negative[0];
            case SOUTH -> positive[2]; case NORTH -> negative[2];
            default -> throw new IllegalStateException("fixed_wing_forward_unknown");
        };
        return bounds;
    }

    private FlightControlLeaseProof.Identity leaseIdentity(Object identity) throws ReflectiveOperationException {
        return identity == null ? null : new FlightControlLeaseProof.Identity((UUID) read(identity, "structureId"),
                (UUID) read(identity, "taskId"), number(read(identity, "terminalPosition")), (UUID) read(identity, "planningSession"));
    }
    private Object privateValue(Object target, String owner, String name, String valueType) throws ReflectiveOperationException {
        return privateValue(target, owner, name, type(valueType));
    }
    private Object privateValue(Object target, String owner, String name, Class<?> valueType) throws ReflectiveOperationException {
        Class<?> ownerType = type(owner);
        try { return MethodHandles.privateLookupIn(ownerType, MethodHandles.lookup()).findGetter(ownerType, name, valueType).invoke(target); }
        catch (ReflectiveOperationException failure) { throw failure; }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new ReflectiveOperationException("native_ownership_field_unavailable:" + name, failure); }
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
        Object route = buildRoute(plan, level.dimension().location(), loader);
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

    static Object buildRoute(RoutePlan plan, ResourceLocation dimension, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> axisType = Class.forName(NAV + "NavigationProfileDraft$AxisDraft", false, loader);
        Object levelAxis = staticCall(axisType, "fixedAngle", new Class<?>[]{double.class}, 0.0);
        Object yaw = staticCall(axisType, "velocityDirection", NO_TYPES);
        Class<?> flightPreset = Class.forName(NAV + "NavigationProfileDefinition$FlightPreset", false, loader);
        Class<?> attitude = Class.forName(NAV + "NavigationProfileDefinition$AttitudeControl", false, loader);
        Object attitudeValue = enumValue(attitude, plan.airframe() == Airframe.FIXED_WING ? "FOLLOW_FLIGHT_TERMINAL" : "CUSTOM");
        Object direct = enumValue(Class.forName(NAV + "NavigationProfileDefinition$DirectFlightPreset", false, loader), "INSTANCE");
        Constructor<?> pathConstructor = Class.forName(NAV + "NavigationProfileDraft$PathDraft", false, loader).getConstructor(
                double.class, double.class, double.class, axisType, axisType, axisType, int.class, int.class,
                Optional.class, Optional.class, Optional.class, flightPreset, Optional.class, attitude);
        List<Object> paths = new ArrayList<>();
        for (Waypoint point : plan.waypoints()) {
            // FC 0.8.3 rejects SmoothDirect whenever fixed-wing guidance is active, including cruise legs.
            paths.add(pathConstructor.newInstance(point.x(), point.y(), point.z(), levelAxis, levelAxis, yaw,
                    plan.linearThrottle(), plan.angularThrottle(), Optional.empty(), Optional.empty(), Optional.empty(),
                    direct, Optional.empty(), attitudeValue));
        }
        Class<?> ending = Class.forName(NAV + "NavigationProfileDraft$EndBehavior", false, loader);
        Object draft = Class.forName(NAV + "NavigationProfileDraft", false, loader).getConstructor(String.class, List.class, ending)
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
