package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.block.LandVehicleRegistrationControllerBlock;
import com.monpai.sailboatmod.block.AirVehicleRegistrationControllerBlock;
import com.monpai.sailboatmod.compat.flightcontrol.FlightControlBridge;
import com.monpai.sailboatmod.compat.flightcontrol.NativeGroundNeutral;
import com.monpai.sailboatmod.compat.flightcontrol.NativeTakeoffAssist;
import com.monpai.sailboatmod.transport.air.*;
import com.monpai.sailboatmod.transport.board.*;
import static com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.*;
import com.monpai.sailboatmod.transport.air.surface.AircraftSurfaceBinding;
import com.monpai.sailboatmod.transport.air.surface.AircraftSurfaceController;
import com.monpai.sailboatmod.transport.air.airport.*;
import com.monpai.sailboatmod.transport.air.ground.AircraftGroundMission;
import com.monpai.sailboatmod.transport.air.ground.GroundRunwayReadiness;
import com.monpai.sailboatmod.transport.air.ground.TaxiRouteGeometry;
import com.monpai.sailboatmod.transport.air.ground.GroundRecoveryIngress;
import com.monpai.sailboatmod.block.RegisteredVehicleControllerBlock;
import com.monpai.sailboatmod.compat.aeronautics.AeronauticsVehicleBridge;
import com.monpai.sailboatmod.compat.aeronautics.RailAircraftMotionBridge;
import com.monpai.sailboatmod.compat.aeronautics.RailAircraftProjectedHull;
import com.monpai.sailboatmod.dock.DockRegistry;
import com.monpai.sailboatmod.dock.PostStationRegistry;
import com.monpai.sailboatmod.entity.CarriageEntity;
import com.monpai.sailboatmod.entity.SailboatEntity;
import com.monpai.sailboatmod.item.RouteBookItem;
import com.monpai.sailboatmod.item.TransportRouteBook;
import com.monpai.sailboatmod.market.*;
import com.monpai.sailboatmod.market.logistics.ShippingTraceService;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.OpenRegisteredVehicleControllerPacket;
import com.monpai.sailboatmod.registry.ModBlockEntities;
import com.monpai.sailboatmod.route.*;
import com.monpai.sailboatmod.transport.*;
import com.monpai.sailboatmod.transport.control.*;
import com.monpai.sailboatmod.transport.physical.*;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/** A logistics controller, never a second vehicle, engine or copy of its cargo. */
public final class RegisteredVehicleControllerBlockEntity extends BlockEntity
        implements LogisticsCarrier, AirportApproachState {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(RegisteredVehicleControllerBlockEntity.class);
    private ControllerRegistration registration;
    private String vehicleName = "", ownerName = "", pendingShipper = "", reason = "";
    private ControllerStatus status = ControllerStatus.UNASSEMBLED;
    private final List<BlockPos> cargoOffsets = new ArrayList<>();
    private final EnumMap<ControlChannel, Direction> ports = new EnumMap<>(ControlChannel.class);
    private final EnumMap<Direction, Integer> signals = new EnumMap<>(Direction.class);
    private BlockPos throttleOffset, steeringOffset, sourceTerminal, destinationTerminal;
    private boolean portsConfirmed,
            transmissionInverted,
            allowReverse = true,
            autoReturn,
            autoUnload;
    private boolean active, paused, returnTrip, manualTakeover;
    private double maxSpeed = 4.0D;
    private TakeoffAssistConfig takeoffAssistConfig = TakeoffAssistConfig.DEFAULT;
    private int rentalPrice = -1, selectedRoute, targetIndex, arrivalTicks, stalledTicks;
    private long departureMillis;
    private double missionDistance;
    private final List<RouteDefinition> routeCatalog = new ArrayList<>();
    private final List<Vec3> activeRoute = new ArrayList<>();
    private TownCarrierVoyageTracker townVoyage = new TownCarrierVoyageTracker();
    private boolean townVoyageDirty = true;
    private boolean townVoyageSettlingAtDestination;
    private final List<ShipmentManifestEntry> manifest = new ArrayList<>();
    private PhysicalVehicleView view;
    private boolean bindingValid;
    private final int runtimeId = TransportRegistry.allocateRuntimeId();
    private final TransportLoadingWindow loading = new TransportLoadingWindow();
    private AirportChunkBinding airportChunkBinding;
    private Set<net.minecraft.world.level.ChunkPos> airportChunkFootprint = Set.of();
    private record AirportChunkBinding(UUID registration, UUID physical, AirportBlockEntity source, long sourceRevision,
            List<AirportFacility> sourceFacilities, AirportGroundLayout sourceGround, AirportBlockEntity target, long targetRevision,
            List<AirportFacility> targetFacilities, AirportGroundLayout targetGround) { }
    private final SweptTerrainProbe terrainProbe = new SweptTerrainProbe();
    private final NativeRouteProgression.TerrainPaths navigationTerrain = new NativeRouteProgression.TerrainPaths();
    private final GroundTerrainWindow groundTerrainWindow = new GroundTerrainWindow();
    private SweptTerrainProbe.Result terrainResult = SweptTerrainProbe.Result.WAITING;
    private boolean cargoFaultReported;
    private Vec3 lastProgressPosition;
    private AircraftClass aircraftClass = AircraftClass.AIRSHIP;
    private BlockPos flightTerminalOffset;
    private String flightPhase = "IDLE", flightFingerprint = "", departureFacility = "", arrivalFacility = "";
    private boolean flightPending;
    private int flightPendingTicks;
    private UUID flightTask;
    private RegisteredAircraftRailController railAircraft = new RegisteredAircraftRailController();
    private RegisteredAircraftSurfaces airSurfaces = new RegisteredAircraftSurfaces();
    private RegisteredAircraftFuelController aircraftFuel = new RegisteredAircraftFuelController();
    private String fuelReturnKey="";
    private com.monpai.sailboatmod.transport.air.rail.RailAircraftPath fuelReturnPath;
    private long fuelHealthTick=Long.MIN_VALUE;
    private boolean returnFlightPending, departureFlightPending, flightRecovering, recoveryRequired;
    private BlockPos returnOrigin, dispatchDestination;
    private UUID airportPreflightAttempt;
    private AirportGroundPreparation groundPreparation;
    private GroundSelectionIdentity groundSelectionIdentity;
    private record GroundSelectionIdentity(UUID attempt,UUID body,BlockPos source,long sourceRevision,
            BlockPos target,long targetRevision,RegisteredAircraftGroundMission.Configuration configuration) { }

    private final List<BlockPos> groundWheelOffsets = new ArrayList<>();
    private RegisteredAircraftGroundMission.Configuration groundConfiguration;
    private UUID groundCalibrationBody;
    private RegisteredAircraftGroundMission groundMission;
    private GroundAssignment nextGroundSourceAssignment, nextGroundTargetAssignment;
    private NativeGroundObservation groundObservation = NativeGroundObservation.unavailable("ground_wheels_required");
    private long groundObservationTick = -1;
    private GroundedFlightReference groundedFlightReference;
    private record GroundReferenceConfiguration(UUID attempt,String dimension,BlockPos terminal,
            RegisteredAircraftGroundMission.Configuration calibration,UUID calibrationBody,double mass) { }
    interface GroundNeutralPort {
        FlightControlBridge.GroundNeutralSnapshot request();
        FlightControlBridge.GroundNeutralRelease release(Object handle);
        boolean normalAfter(FlightControlBridge.GroundNeutralRelease barrier);
    }
    record GroundHoldEvidence(UUID body, boolean fresh, boolean supported, boolean hardware,
            boolean profileBound, boolean nativeReleased, boolean foreignOwner, boolean manual,
            boolean wholeStand, int configuredWheels, int positiveWheels) { }
    interface GroundHoldPort {
        boolean neutral(int signal);
        void brake15();
        FlightControlBridge.GroundNeutralSnapshot requestHold();
        void releaseHold(Object handle);
        void clearOnlyOurs();
    }
    private volatile NativeGroundNeutral.HoldProof groundHoldProof;
    private Object groundHoldLease;
    private long groundHoldGeneration;
    private GroundHoldPort groundHoldPort;
    private boolean groundDriveAcquiring;
    public NativeGroundNeutral.HoldProof nativeGroundHoldProof(){return groundHoldProof;}
    public boolean nativeGroundHoldRegistrationMatches(UUID owner,UUID body){
        return registration!=null&&registration.registered()&&Objects.equals(owner,registration.registrationId())
                &&Objects.equals(body,registration.physicalId());
    }
    /** Fresh stop-only ownership. This does not advance the mission, grant drive, or certify rest. */
    boolean maintainGroundHold(GroundHoldEvidence e,GroundHoldPort port){
        boolean previous=groundHoldLease!=null;
        boolean identity=e!=null&&registration!=null&&registration.registered()
                &&Objects.equals(e.body(),registration.physicalId())&&Objects.equals(e.body(),groundCalibrationBody);
        boolean eligible=identity&&e.fresh()&&e.supported()&&e.hardware()&&e.profileBound()&&e.nativeReleased()
                &&!e.foreignOwner()&&!e.manual()&&e.configuredWheels()>=RegisteredAircraftParkingBrake.MINIMUM_WHEELS&&e.positiveWheels()==e.configuredWheels()
                &&groundConfiguration!=null&&groundConfiguration.calibration().liveVerified();
        boolean recovery=knownGroundIntent()&&active&&(paused||parkingBrake().held())&&!groundDriveAcquiring;
        boolean parking=groundMission==null&&e!=null&&parkingBrake().holdsBody(e.body());
        if(port==null||!eligible||(!recovery&&!parking)){
            clearGroundHoldLease();
            if(port!=null&&e!=null&&(e.foreignOwner()||e.manual())&&previous){port.clearOnlyOurs();parkingBrake().forget();}
            return false;
        }
        groundHoldPort=port;
        try{
            if(!port.neutral(groundConfiguration.calibration().neutralThrottleSignal())){clearGroundHoldLease();return false;}
            port.brake15();
            // New ownership follows successful normal stop commands and fresh proof, never saved NBT bits.
            parkingBrake().adopt(e.body());groundOutputsOwned=false;groundOutputsNeutral=false;
            Object scope=parking?parkingBrake():groundMission.plan();
            groundHoldProof=new NativeGroundNeutral.HoldProof(this,getTransportUuid(),scope,e.body(),flightFingerprint,System.nanoTime(),parking);
            var observed=port.requestHold();
            if(observed==null||!observed.available()||observed.handle()==null||observed.generation()<=0){clearGroundHoldLease();return false;}
            if(groundHoldLease!=null&&groundHoldGeneration!=observed.generation())port.releaseHold(groundHoldLease);
            groundHoldLease=observed.handle();groundHoldGeneration=observed.generation();
            long age=System.nanoTime()-observed.observedAtNanos();
            return observed.acknowledged()&&observed.observations()>=2&&Double.isFinite(observed.physicsTimeSeconds())
                    &&age>=0&&age<=NativeGroundNeutral.ACK_NANOS;
        }catch(RuntimeException unavailable){clearGroundHoldLease();return false;}
    }
    private static boolean knownGroundPhase(AircraftGroundMission.Phase phase){
        return phase!=null&&switch(phase){case TAXI_OUT,HOLD_LINE,LINE_UP,TAXI_IN,STAND_STABILIZING,COMPLETE->true;default->false;};
    }
    private boolean knownGroundIntent(){return groundMission!=null&&!invalidGroundMission&&knownGroundPhase(groundMission.state().phase());}
    private void clearGroundHoldLease(){
        Object handle=groundHoldLease;GroundHoldPort port=groundHoldPort;
        groundHoldLease=null;groundHoldGeneration=0;groundHoldPort=null;groundHoldProof=null;
        if(handle!=null&&port!=null)try{port.releaseHold(handle);}catch(RuntimeException unavailable){ }
    }
    private void updateGroundHold(ServerLevel server){
        if(transportKind()!=Kind.AIR||aircraftClass!=AircraftClass.FIXED_WING||groundDriveAcquiring)return;
        boolean recovery=knownGroundIntent()&&active&&(paused||parkingBrake().held())&&!groundOutputsOwned;
        if(paused&&knownGroundIntent()&&active)recovery=true;
        boolean parked=groundMission==null&&view!=null&&parkingBrake().holdsBody(view.physicalId());
        if(!recovery&&!parked){clearGroundHoldLease();return;}
        boolean hardware=parkingHardwareReady();
        NativeGroundObservation observation=hardware?observeGround(server):NativeGroundObservation.unavailable("ground_hardware_required");
        boolean fresh=hardware&&observation.available()&&groundObservationTick==server.getGameTime()
                &&view!=null&&Objects.equals(observation.physicalId(),view.physicalId());
        int positive=0;
        if(fresh)for(var wheel:observation.wheels())if(wheel.supported()&&Double.isFinite(wheel.tireRadius())&&wheel.tireRadius()>0
                &&Double.isFinite(wheel.suspensionCompression())&&wheel.suspensionCompression()>0&&wheel.worldCenter()!=null
                &&Double.isFinite(wheel.worldCenter().x)&&Double.isFinite(wheel.worldCenter().y)&&Double.isFinite(wheel.worldCenter().z))positive++;
        var authority=groundOwnership(server);var terminal=flightTerminalState(server);
        boolean profile=false;
        if(flightTerminalOffset!=null){var installed=FlightControlBridge.inspectInstalledProfile(server,worldPosition.offset(flightTerminalOffset));
            profile=installed.available()&&flightFingerprint!=null&&!flightFingerprint.isBlank()
                    &&flightFingerprint.equals(installed.fingerprint())&&terminal.available()&&terminal.linked();}
        GroundHoldPort port=new GroundHoldPort(){
            public boolean neutral(int signal){return parkingOutputs(server).neutral(signal);}
            public void brake15(){parkingOutputs(server).brake();}
            public FlightControlBridge.GroundNeutralSnapshot requestHold(){var proof=groundHoldProof;
                return FlightControlBridge.requestGroundHold(server,RegisteredVehicleControllerBlockEntity.this,getTransportUuid(),
                        proof==null?null:proof.mission(),flightTerminalOffset==null?null:worldPosition.offset(flightTerminalOffset),
                        view==null?null:view.physicalId(),flightFingerprint,proof!=null);}
            public void releaseHold(Object handle){FlightControlBridge.releaseGroundNeutral(server,
                    flightTerminalOffset==null?null:worldPosition.offset(flightTerminalOffset),handle);}
            public void clearOnlyOurs(){yieldParkingBrake();}
        };
        var e=new GroundHoldEvidence(view==null?null:view.physicalId(),fresh,fresh&&observation.fullyGrounded(),hardware,profile,
                view!=null&&authority.provesReleased(view.physicalId()),authority.available()&&(authority.anyActiveLease()||authority.foreignLease())
                    ||terminal.available()&&(terminal.active()||terminal.enabled()),manualTakeover,false,groundWheelOffsets.size(),positive);
        maintainGroundHold(e,port);
        if(parkingBrake().held()&&hardware)parkingHardwareKey=parkingHardwareKey();
    }
    private GroundNeutralPort groundNativePort;
    private Object groundNativeLease;
    private long groundNativeGeneration;
    private FlightControlBridge.GroundNeutralRelease groundNativeRelease;
    private int groundNativeWaitTicks;
    private boolean groundOutputsOwned, groundOutputsNeutral = true, groundStartRequested, groundReleaseRequested;
    private RegisteredAircraftParkingBrake parkingBrake;
    private List<Object> parkingHardwareKey;
    private boolean invalidGroundMission;



    public RegisteredVehicleControllerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.REGISTERED_VEHICLE_CONTROLLER_BLOCK_ENTITY.get(), pos, state);
        Kind kind =
                state.getBlock() instanceof AirVehicleRegistrationControllerBlock ? Kind.AIR
                        : state.getBlock() instanceof LandVehicleRegistrationControllerBlock ? Kind.LAND : Kind.WATER;
        registration = new ControllerRegistration(UUID.randomUUID(), "", kind);
        Direction[] sides = {
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.EAST,
            Direction.UP,
            Direction.DOWN
        };
        for (ControlChannel channel : ControlChannel.values())
            ports.put(channel, sides[channel.ordinal()]);
        maxSpeed = kind == Kind.AIR ? 10.0D : kind == Kind.LAND ? 6.0D : 4.0D;
    }

    public void initializeOwner(Player player) {
        if (player != null && registration.ownerUuid().isBlank()) {
            registration =
                    new ControllerRegistration(
                            registration.registrationId(),
                            player.getUUID().toString(),
                            registration.kind());
            ownerName = player.getName().getString();
            setChanged();
        }
    }

    public static void serverTick(
            Level level,
            BlockPos pos,
            BlockState state,
            RegisteredVehicleControllerBlockEntity core) {
        if (level instanceof ServerLevel server) {
            // FC may automatically start its persisted enabled card on its first tick. Guard every
            // tick during initialization, independently of the slower navigation/telemetry cadence.
            if (core.flightRecovering && !core.railAircraftAutomation()) core.blockReloadReplay(server);
            if (core.parkingBrake().held() && !core.railAircraftAutomation()) core.updateParkingBrake(server, false);
            if (core.groundOutputsOwned && core.paused) core.stopOwnedGroundOutputs(server, core.manualTakeover);
            if (server.getGameTime() % 5 == 0 || core.railAircraftAutomation() && (core.active || core.railAircraft.hasMission()) || core.groundMission != null
                    && core.groundMission.state().phase() != AircraftGroundMission.Phase.FC_ACTIVE) core.tickServer(server);
            core.updateTakeoffAssist();
            core.publishTownVoyage(server);
        }
    }

    private TakeoffAssistConfig takeoffAssistConfig() {
        return takeoffAssistConfig == null ? TakeoffAssistConfig.DEFAULT : takeoffAssistConfig;
    }

    private void updateTakeoffAssist() {
        if (railAircraftAutomation()) {
            NativeTakeoffAssist.remove(this);
            com.monpai.sailboatmod.compat.flightcontrol.NativeFixedWingPitchBootstrap.update(
                    this,getTransportUuid(),target(flightTerminalOffset),List.of(),false);
            return;
        }
        // A separate native navigation task may fly while the cargo mission is paused. Its exact
        // native lease is checked in every physics callback; saved cargo state never grants one.
        boolean eligible = transportKind() == Kind.AIR && aircraftClass == AircraftClass.FIXED_WING
                && registration != null && registration.registered() && view != null && view.available()
                && Objects.equals(registration.physicalId(), view.physicalId())
                && groundConfiguration != null && groundConfiguration.calibration().liveVerified()
                && Objects.equals(groundCalibrationBody, view.physicalId())
                && !manualTakeover && !groundOutputsOwned && !flightRecovering;
        NativeTakeoffAssist.update(this, getTransportUuid(), target(flightTerminalOffset),
                List.copyOf(groundWheelOffsets), takeoffAssistConfig(), eligible);
        com.monpai.sailboatmod.compat.flightcontrol.NativeFixedWingPitchBootstrap.update(
                this, getTransportUuid(), target(flightTerminalOffset), List.copyOf(groundWheelOffsets), eligible);
    }

    private void tickServer(ServerLevel server) {
        updatePhysicalView(AeronauticsVehicleBridge.resolve(this, facing()).orElse(null));
        bindingValid = false;
        if (view == null || !view.available()) {
            clearGroundNativeLease();
            clearAirportLoading();
            yieldParkingBrake();
            TransportRegistry.unregister(server, getTransportUuid());
            if (transportKind() == Kind.AIR) stopOwnedGroundOutputs(server, false);
            ownSignalsOff();
            if (transportKind() == Kind.AIR && active) {
                // Startup may resolve Sable after either block entity. Keep the persisted live
                // registration/tickets until it does; lack of telemetry is not a lost aircraft.
                status = ControllerStatus.WAITING_TERRAIN;
                reason = flightRecovering ? "flight_recovery_initializing" : "physical_unavailable";
                return;
            }
            loading.close();
            status =
                    registration.registered()
                            ? ControllerStatus.UNLOADED
                            : ControllerStatus.UNASSEMBLED;
            reason = "physical_unavailable";
            if (registration.registered()) remember(server);
            return;
        }
        if (!registration.registered()) {
            clearGroundNativeLease();
            yieldParkingBrake();
            clearAirportLoading();
            status = ControllerStatus.UNREGISTERED;
            return;
        }
        LogisticsCarrier duplicate = TransportRegistry.resolve(server, getTransportUuid());
        if (duplicate != null && duplicate != this) {
            yieldParkingBrake();
            fault("duplicate_controller", false);
            return;
        }
        RegisteredTransportSavedData saved = RegisteredTransportSavedData.get(server);
        if (!saved.claim(dimensionId(), view.physicalId(), getTransportUuid())) {
            yieldParkingBrake();
            fault("duplicate_controller", false);
            return;
        }
        registration.register(view.physicalId(), view.mass(), true);
        bindingValid = true;
        TransportRegistry.register(server, this);
        if (railAircraftAutomation()) {
            clearGroundNativeLease();
            yieldParkingBrake();
            remember(server);
            tickRailAircraft(server);
            return;
        }
        updateParkingBrake(server, true);
        updateGroundHold(server);
        remember(server);
        if (transportKind() == Kind.AIR) refreshAirportLoading(server);
        if (groundMission != null && active) loading.setPinnedEndpoints(List.of(groundMission.sourceAirport(), groundMission.targetAirport()));
        else if (active && aircraftClass == AircraftClass.FIXED_WING && airportPreflightPending() && destinationTerminal != null) {
            BlockPos preflightOrigin = returnFlightPending ? returnOrigin : sourceTerminal;
            if (preflightOrigin != null) loading.setPinnedEndpoints(List.of(preflightOrigin, destinationTerminal));
        }
        if (transportKind() == Kind.AIR && active && (flightRecovering || recoveryRequired)) {
            loading.update(server,getTransportUuid(),view.physicalId(),view.worldBounds(),view.worldPosition());
            // Let bounded landing scans progress while the pilot brings the craft to either airport.
            DockBlockEntity destination = resolveDestination(server);
            if (destination instanceof AirportBlockEntity airport) airport.isCarrierInsideDockZone(this);
            BlockPos origin = returnTrip ? returnOrigin : sourceTerminal;
            if (origin != null && server.hasChunkAt(origin) && server.getBlockEntity(origin) instanceof AirportBlockEntity airport)
                airport.isCarrierInsideDockZone(this);
            updateTrace(server);
            recordFlightTelemetry(server);
            return;
        }
        if (transportKind() != Kind.AIR && !ControllerRegistration.validControlPorts(ports)) {
            portsConfirmed = false;
            fault("configure_power", false);
            return;
        }
        if (transportKind() == Kind.AIR && active && airportPreflightPending() && !paused) {
            BlockPos origin = returnFlightPending ? returnOrigin : sourceTerminal;
            if (origin != null && server.hasChunkAt(origin)
                    && server.getBlockEntity(origin) instanceof AirportBlockEntity departure
                    && prepareAircraftFlight(server, departure)) {
                returnFlightPending = departureFlightPending = false;
                setChanged();
            } else {
                if (!FlightResumePolicy.waitsForAirport(reason)) clearAirportPreflight(server);
                flightPhase = "PREFLIGHT";
                status = ControllerStatus.WAITING_TERRAIN;
                loading.update(server,getTransportUuid(),view.physicalId(),view.worldBounds(),view.worldPosition());
                updateTrace(server);
                recordFlightTelemetry(server);
                return;
            }
        }
        if (!active || paused) {
            if (transportKind() == Kind.AIR && active && paused) {
                // Continue following an airborne craft after relinquishing control; never unload it under a pilot.
                loading.update(server,getTransportUuid(),view.physicalId(),view.worldBounds(),view.worldPosition());
                updateTrace(server);
                recordFlightTelemetry(server);
            }
            if (!paused) status = ControllerStatus.IDLE;
            if (!active
                    && sourceTerminal != null
                    && server.hasChunkAt(sourceTerminal)
                    && server.getBlockEntity(sourceTerminal) instanceof DockBlockEntity dock
                    && dock.isCarrierInsideDockZone(this)
                    && manifest.isEmpty()) dock.tryLoadPickupCargo(this);
            return;
        }
        if (!cargoOffsets.isEmpty()) {
            try {
                cargo();
            } catch (RuntimeException failure) {
                cargoFailure(failure);
                return;
            }
        } else if (!manifest.isEmpty()) {
            fault("cargo_missing", false);
            return;
        }
        if (!controlsReady()) {
            if (groundMission != null) { groundFailure(server, "ground_control_conflict", false); return; }
            fault("configure_power", false);
            return;
        }
        if (targetIndex < 0 || targetIndex >= activeRoute.size()) {
            fault("missing_route", false);
            return;
        }
        if (transportKind() == Kind.AIR) {
            tickAircraft(server);
            return;
        }
        double radius =
                Math.max(
                        2.0D,
                        Math.max(view.worldBounds().getXsize(), view.worldBounds().getZsize())
                                        * 0.5D
                                + 1.0D);
        DockBlockEntity destination = resolveDestination(server);
        boolean atDock = destination != null && destination.isCarrierInsideDockZone(this);
        boolean terminalShortcutReady = atDock
                && loading.isWindowLoaded(server, view.worldBounds(), activeRoute.getLast());
        boolean[] chunksReady = { false };
        NativeRouteProgression.Selection selection = NativeRouteProgression.selectLoaded(
                activeRoute, targetIndex, view.worldPosition(), view.linearVelocity(), radius,
                target -> clearSweptTerrain(server, target), terminalShortcutReady ? destination::isInsideDockZone : null,
                target -> {
                    chunksReady[0] = loading.update(
                            server, getTransportUuid(), view.physicalId(), view.worldBounds(), target);
                    return chunksReady[0];
                });
        if (selection == null) {
            fault("missing_route", false);
            return;
        }
        Vec3 target = selection.target();
        boolean terrainReady = selection.terrainReady();
        if (terrainReady && targetIndex != selection.index()) {
            targetIndex = selection.index();
            arrivalTicks = 0;
            setChanged();
        }
        boolean finalLeg = targetIndex == activeRoute.size() - 1;
        if (finalLeg && destination != null) {
            radius = NativeNavigationDriver.terminalArrivalRadius(radius,
                    target.subtract(Vec3.atCenterOf(destination.getBlockPos())),
                    destination.getZoneMinX(), destination.getZoneMaxX(),
                    destination.getZoneMinZ(), destination.getZoneMaxZ());
            if (!Double.isFinite(radius) || radius < 0) {
                fault("missing_route", false);
                return;
            }
        }
        NativeNavigationDriver.Command command =
                NativeNavigationDriver.drive(
                        new NativeNavigationDriver.Input(
                                transportKind(),
                                view.worldPosition(),
                                view.worldForward(),
                                view.linearVelocity(),
                                target,
                                maxSpeed,
                                radius,
                                terrainReady,
                                false,
                                allowReverse),
                        finalLeg && atDock, finalLeg);
        if (!applyCommand(command)) return;
        status = command.waiting() ? ControllerStatus.WAITING_TERRAIN : ControllerStatus.RUNNING;
        reason =
                !chunksReady[0]
                        ? loading.reason()
                        : terrainReady
                                ? command.reason()
                                : terrainResult == SweptTerrainProbe.Result.BLOCKED
                                        ? "obstacle"
                                        : "waiting_terrain";
        boolean slow = view.linearVelocity().horizontalDistanceSqr() <= 0.25D * 0.25D;
        if (terrainReady
                && finalLeg
                && slow
                && (atDock || (destination == null && manifest.isEmpty() && command.arrived()))) {
            arrivalTicks += 5;
            if (arrivalTicks >= 20) arrive(server, destination);
        } else arrivalTicks = 0;
        if (status == ControllerStatus.RUNNING && !command.arrived()) {
            if (lastProgressPosition != null
                    && lastProgressPosition.distanceToSqr(view.worldPosition()) < 0.01D)
                stalledTicks += 5;
            else {
                stalledTicks = 0;
                lastProgressPosition = view.worldPosition();
            }
            if (stalledTicks > 400) fault("no_progress", false);
        }
        if (active) updateTrace(server);
    }

    @Override public AircraftClass aircraftClass() { return aircraftClass; }

    @Override public Vec3 velocityMetersPerSecond() {
        return view == null ? new Vec3(Double.NaN, Double.NaN, Double.NaN) : view.linearVelocity();
    }

    @Override public boolean physicalArrivalReady() {
        if (transportKind() != Kind.AIR || view == null || !view.available() || !bindingValid) return false;
        if (level instanceof ServerLevel server) {
            FlightMission mission = FlightMissionSavedData.get(server).get(getTransportUuid());
            return mission == null || mission.representation() == FlightMission.Representation.PHYSICAL;
        }
        return false;
    }

    @Override public String transportMode() {
        if (transportKind() != Kind.AIR) return transportKind() == Kind.LAND ? "LAND" : "PORT";
        return switch (aircraftClass) { case AIRSHIP -> "AIRSHIP"; case ROTORCRAFT -> "HELICOPTER"; case FIXED_WING -> "AIRPLANE"; };
    }

    private FlightControlBridge.TerminalSnapshot flightTerminalState(ServerLevel server) {
        return FlightControlBridge.inspect(server, flightTerminalOffset == null ? worldPosition : worldPosition.offset(flightTerminalOffset));
    }

    @Override public boolean railAircraftAutomation() {
        return transportKind()==Kind.AIR && aircraftClass==AircraftClass.FIXED_WING;
    }
    private RegisteredAircraftRailController.Context railContext(ServerLevel server) {
        return new RegisteredAircraftRailController.Context(server,this,this,view,facing());
    }
    private RegisteredAircraftSurfaces.Context surfaceContext() {
        return view==null||!view.available()?null:new RegisteredAircraftSurfaces.Context(this,getTransportUuid(),view.physicalId(),facing());
    }
    private RegisteredAircraftFuelController.Context fuelContext() {
        return view==null||!view.available()?null:new RegisteredAircraftFuelController.Context(this,view.physicalId());
    }
    private NativeControlResult checkAircraftFuel(ServerLevel server,
            com.monpai.sailboatmod.transport.air.rail.RailAircraftPath path,double fromDistance,
            AirportBlockEntity source,AirportBlockEntity destination,GroundAssignment sourceAssignment,GroundAssignment destinationAssignment) {
        if(source==null||destination==null||path==null)return aircraftFuel.unavailable("fuel_duration_unavailable");
        boolean returning=!returnTrip&&(autoReturn||!manifest.isEmpty());
        try {
            if(returning) {
                var authored=new ArrayList<>(FlightRoutePlanner.cruiseForLeg(selected().waypoints(),returnTrip));Collections.reverse(authored);
                var arrival=path.sample(path.length());
                var futureHull=RailAircraftProjectedHull.at(this,facing(),arrival.position(),arrival.tangent(),0)
                        .orElseThrow(()->new IllegalStateException("fuel_projected_hull_unavailable"));
                double futureYaw=Math.toDegrees(Math.atan2(-arrival.tangent().x,arrival.tangent().z));
                double returnDiameter=com.monpai.sailboatmod.transport.air.rail.RailAirportSelection.sweepDiameter(
                        new com.monpai.sailboatmod.transport.air.rail.RailAirportSelection.Aircraft(view.physicalId(),futureHull,arrival.position(),futureYaw));
                String key=path.fingerprint()+":"+source.getBlockPos().asLong()+":"+destination.getBlockPos().asLong()+":"
                        +source.groundLayoutRevision()+":"+destination.groundLayoutRevision()+":"
                        +sourceAssignment+":"+destinationAssignment+":"+authored+":"+maxSpeed+":"+returnDiameter;
                if(!key.equals(fuelReturnKey)) {
                    fuelReturnPath=RailReturnFlightPlanner.build(path,
                            new RailReturnFlightPlanner.Airport(source.groundLayout(),source.facilities(),sourceAssignment),
                            new RailReturnFlightPlanner.Airport(destination.groundLayout(),destination.facilities(),destinationAssignment),
                            authored,Math.max(3,Math.min(60,maxSpeed)),returnDiameter);
                    fuelReturnKey=key;
                }
            }
            return aircraftFuel.check(fuelContext(),path,fromDistance,returning?fuelReturnPath:null);
        }catch(IllegalArgumentException|IllegalStateException unavailable){return aircraftFuel.unavailable("fuel_return_plan_unavailable");}
    }
    private NativeControlResult checkCurrentAircraftFuel(ServerLevel server) {
        var mission=railAircraft.mission();
        if(mission==null)return aircraftFuel.unavailable("fuel_duration_unavailable");
        return checkAircraftFuel(server,mission.path(),mission.distance(),groundAirport(server,mission.source()),
                groundAirport(server,mission.target()),mission.sourceAssignment(),mission.targetAssignment());
    }
    private void fuelFailure(ServerLevel server,String why) {
        railAircraft.park(railContext(server));airSurfaces.pause(surfaceContext());paused=true;
        reason=why==null||why.isBlank()?"fuel_unknown":why;status=ControllerStatus.FAULT;flightPhase="PAUSED";
        remember(server);setChanged();
    }
    private static AircraftSurfaceController.Attitude surfaceAttitude(com.monpai.sailboatmod.transport.air.rail.RailAircraftPath.Sample s) {
        return new AircraftSurfaceController.Attitude(s.pitchRadians(),s.bankRadians(),Math.atan2(s.tangent().z,s.tangent().x));
    }
    private NativeControlResult tickAirSurfaces(ServerLevel server) {
        var mission=railAircraft.mission();var context=surfaceContext();
        if(context==null)return NativeControlResult.failure("surface_body_changed");
        if(!active||mission==null||mission.completed()||railAircraft.cancelled())return airSurfaces.neutral(context);
        if(paused||!railAircraft.reason().isBlank())return airSurfaces.tick(context,server.getGameTime(),null,true);
        if(airSurfaces.bindings().isEmpty()&&!airSurfaces.invalid())return NativeControlResult.applied();
        var read=RailAircraftMotionBridge.attitude(this,facing());
        if(read.isEmpty())return NativeControlResult.failure("surface_actual_attitude_unavailable");
        var actual=read.orElseThrow();var path=mission.path();Double pending=railAircraft.pendingDistance();
        double distance=pending==null?mission.distance():pending;var target=path.sample(distance);
        double previewSeconds=.35;var ahead=path.sample(Math.min(path.length(),distance+Math.max(.1,target.speed())*previewSeconds));
        var input=new AircraftSurfaceController.Input(new AircraftSurfaceController.Attitude(actual.pitchRadians(),actual.bankRadians(),actual.headingRadians()),
                surfaceAttitude(target),surfaceAttitude(ahead),previewSeconds,target.speed(),target.phase(),.05);
        return airSurfaces.tick(context,server.getGameTime(),input,false);
    }
    private boolean railNativeControlFree(ServerLevel server) {
        var ownership=RailAircraftMotionBridge.foreignFlightControlStatus(this);
        if(!ownership.success())reason=ownership.reason();
        return ownership.success();
    }
    private void tickRailAircraft(ServerLevel server) {
        var context=railContext(server);
        if(manualTakeover){airSurfaces.neutral(surfaceContext());railAircraft.manualRelease(context);status=ControllerStatus.MANUAL;return;}
        if(railAircraft.invalid()){railAircraft.park(context);paused=true;status=ControllerStatus.FAULT;reason="rail_recovery_review";return;}
        if(active&&!railNativeControlFree(server)){
            railAircraft.park(context);paused=true;status=ControllerStatus.FAULT;return;
        }
        if(active && airportPreflightPending() && !paused) {
            BlockPos origin=returnFlightPending?returnOrigin:sourceTerminal;
            if(origin!=null&&server.hasChunkAt(origin)&&server.getBlockEntity(origin) instanceof AirportBlockEntity source
                    &&prepareAircraftFlight(server,source)){
                returnFlightPending=departureFlightPending=false;setChanged();
            }else{
                railAircraft.park(context);status=ControllerStatus.WAITING_TERRAIN;flightPhase="PREFLIGHT";
                if(!FlightResumePolicy.waitsForAirport(reason)){paused=true;status=ControllerStatus.FAULT;}
                updateTrace(server);recordFlightTelemetry(server);return;
            }
        }
        var mission=railAircraft.mission();
        if(active&&mission==null&&!airportPreflightPending()){railAircraft.park(context);paused=true;status=ControllerStatus.FAULT;reason="rail_recovery_review";return;}
        if(mission!=null&&active&&!mission.completed()&&!railAircraft.cancelled()) {
            AirportBlockEntity source=groundAirport(server,mission.source()),target=groundAirport(server,mission.target());
            if(source!=null&&target!=null)configureAirportLoading(server,source,target);
            loading.setPinnedEndpoints(List.of(mission.source(),mission.target()));
        }
        boolean loaded=loading.update(server,getTransportUuid(),view.physicalId(),view.worldBounds(),view.worldPosition());
        aircraftFuel.poll(fuelContext());
        if(active&&!paused&&mission!=null&&!mission.completed()&&!airportPreflightPending()
                &&(fuelHealthTick==Long.MIN_VALUE||server.getGameTime()<fuelHealthTick||server.getGameTime()-fuelHealthTick>=20)) {
            fuelHealthTick=server.getGameTime();var health=aircraftFuel.exhausted(fuelContext());
            if(!health.success()){fuelFailure(server,health.reason());return;}
            // Keep remaining-duration UI current; a reserve shortfall alone does not stop an airborne aircraft.
            checkCurrentAircraftFuel(server);
        }
        if(!airSurfaces.bindings().isEmpty()&&!railAircraft.owns(context))railAircraft.park(context);
        railAircraft.tick(context,active,paused,sample->loaded&&railPathClear(server,sample),sample->{
            if(sample.phase()==com.monpai.sailboatmod.transport.air.rail.RailAircraftPath.Phase.TAKEOFF
                    &&mission!=null&&mission.phase()!=com.monpai.sailboatmod.transport.air.rail.RailAircraftPath.Phase.TAKEOFF)
                return checkCurrentAircraftFuel(server);
            return NativeControlResult.applied();
        });
        if(active&&!paused&&!railAircraft.cancelled()&&railAircraft.reason().startsWith("fuel_")){fuelFailure(server,railAircraft.reason());return;}
        var surfaceResult=tickAirSurfaces(server);
        if(!surfaceResult.success()) {
            railAircraft.park(context);airSurfaces.pause(surfaceContext());paused=true;reason=surfaceResult.reason();
            status=ControllerStatus.FAULT;flightPhase="PAUSED";setChanged();return;
        }
        if(mission!=null){
            flightPhase=paused?"PAUSED":mission.phase().name();
            targetIndex=Math.min(Math.max(0,activeRoute.size()-1),(int)Math.floor(mission.distance()/Math.max(1,mission.path().length())*Math.max(0,activeRoute.size()-1)));
        }
        if(active&&!railAircraft.cancelled()) {
            if(!railAircraft.reason().isBlank())reason=railAircraft.reason();else if(!paused)reason="";
        }
        status=paused?ControllerStatus.PAUSED:active?(reason.isBlank()?ControllerStatus.RUNNING:ControllerStatus.WAITING_TERRAIN):ControllerStatus.IDLE;
        if(active&&!paused&&mission!=null){
            var journal=FlightMissionSavedData.get(server).get(getTransportUuid());
            if(journal!=null){journal.physicalObservation(view.worldPosition(),targetIndex,flightPhase,reason);FlightMissionSavedData.get(server).setDirty();}
            AirportBlockEntity target=groundAirport(server,mission.target());
            if(target!=null&&railAircraft.ready(context,target)){arrive(server,target);return;}
        }
        if(!active&&!paused&&sourceTerminal!=null&&manifest.isEmpty()&&server.hasChunkAt(sourceTerminal)
                &&server.getBlockEntity(sourceTerminal) instanceof AirportBlockEntity source){
            // Loading terrain is preparatory; pickup still passes the airport's real stand and inventory gates.
            if(configureAirportLoading(server,source,source)
                    &&loading.update(server,getTransportUuid(),view.physicalId(),view.worldBounds(),view.worldPosition()))source.tryLoadPickupCargo(this);
        }
        if(active){updateTrace(server);recordFlightTelemetry(server);}
        setChanged();
    }
    private boolean railPathClear(ServerLevel server,com.monpai.sailboatmod.transport.air.rail.RailAircraftPath.Sample next) {
        AABB hull=railAircraft.sweptBounds(railContext(server),next).orElse(null);
        if(hull==null)return false;
        AABB area=new AABB(hull.minX+.02,hull.minY+.1,hull.minZ+.02,hull.maxX-.02,hull.maxY-.02,hull.maxZ-.02);
        terrainResult=terrainProbe.scan(area,16_384,server::hasChunkAt,
                at->server.getBlockState(at).getCollisionShape(server,at).toAabbs().stream()
                        .anyMatch(box->box.move(at.getX(),at.getY(),at.getZ()).intersects(area)));
        return terrainResult==SweptTerrainProbe.Result.CLEAR;
    }

    /** Initial binding/admission only; it never stands in for a captured arrival or native ground permit. */
    private boolean idleFixedWingAirportBinding() {
        if(railAircraftAutomation())return level instanceof ServerLevel&&registration.registered()&&bindingValid&&view!=null&&view.available()
                &&Objects.equals(registration.physicalId(),view.physicalId())&&!active&&!paused&&!manualTakeover&&manifest.isEmpty();
        if (transportKind() != Kind.AIR || aircraftClass != AircraftClass.FIXED_WING
                || !(level instanceof ServerLevel server) || !registration.registered() || !bindingValid
                || view == null || !view.available() || !Objects.equals(registration.physicalId(), view.physicalId())
                || status != ControllerStatus.IDLE || active || paused || returnTrip || manualTakeover
                || !manifest.isEmpty() || groundMission != null || invalidGroundMission
                || flightPending || departureFlightPending || returnFlightPending || flightRecovering || recoveryRequired
                || flightTask != null || airportPreflightAttempt != null
                || nextGroundSourceAssignment != null || nextGroundTargetAssignment != null
                || groundOutputsOwned || !groundOutputsNeutral || groundStartRequested || groundReleaseRequested) return false;
        var mission = FlightMissionSavedData.get(server).get(getTransportUuid());
        return mission == null || !mission.active() && mission.representation() == FlightMission.Representation.PHYSICAL
                && ("PARKED".equals(mission.phase()) || "CANCELLED".equals(mission.phase()));
    }

    private List<AirportBlockEntity> parkedStandAirports(ServerLevel server) {
        List<AirportBlockEntity> matches = new ArrayList<>();
        for (BlockPos pos : AirportRegistry.get(server)) {
            if (!server.hasChunkAt(pos) || !(server.getBlockEntity(pos) instanceof AirportBlockEntity airport)
                    || airport.isRemoved() || airport.getLevel() != server) continue;
            if (airport.groundLayout().stands().stream().anyMatch(stand -> stand.cargoEnabled()
                    && stand.contains(view.worldBounds(), groundYawDegrees(), view.linearVelocity().length()))) matches.add(airport);
        }
        return matches;
    }

    /** Package-local airport candidate escape from an empty source binding; active delivery stays on its original gate. */
    boolean canBootstrapAtAirport(AirportBlockEntity airport) {
        if (sourceTerminal != null || !idleFixedWingAirportBinding()) return false;
        var matches = parkedStandAirports((ServerLevel) level);
        return matches.size() == 1 && matches.getFirst() == airport && (!railAircraftAutomation()
                ||airport.railDepartureReady(this,view,groundYawDegrees(),RailAircraftMotionBridge.angularSpeed(this).orElse(Double.NaN)));
    }

    private boolean bindParkedStandAirport(ServerLevel server) {
        if (!idleFixedWingAirportBinding()) { reason = "airport_binding_busy"; return false; }
        var matches = parkedStandAirports(server);
        if (matches.size() != 1) {
            reason = matches.isEmpty() ? "airport_stand_required" : "airport_stand_ambiguous";
            return false;
        }
        sourceTerminal = matches.getFirst().getBlockPos().immutable();
        reason = "";
        return true;
    }

    private boolean airportPreflightPending() { return returnFlightPending || departureFlightPending; }

    record TownRailIntent(UUID legId, BlockPos source, BlockPos target, boolean timed) { }

    /** Native phase tasks may change within a voyage; only a rail preparation/mission owns its native voyage UUID. */
    static UUID townVoyageLegId(UUID bindingId, TownRailIntent rail, UUID runtimePhaseTask) {
        return rail != null ? rail.legId() : bindingId;
    }

    record TownRailProgress(com.monpai.sailboatmod.transport.air.rail.RailAircraftPath path,
            double confirmedDistance, int actualStandTicks) { }

    static TownRailProgress townRailProgress(boolean timed,
            com.monpai.sailboatmod.transport.air.rail.RailAircraftMission mission) {
        return !timed || mission == null || mission.completed() ? null
                : new TownRailProgress(mission.path(), mission.distance(), mission.actualStandTicks());
    }

    static Endpoint townGroundSource(boolean returning, java.util.Optional<TownVoyageBinding.Leg> leg,
            Endpoint originalSource) {
        return returning ? leg.map(TownVoyageBinding.Leg::source).orElse(Endpoint.unknown()) : originalSource;
    }

    static State townObservationState(boolean loaded, boolean recovering, boolean paused,
            ControllerStatus status, String reason, boolean settlingAtDestination) {
        if (!loaded) return State.UNLOADED;
        if (recovering) return State.RECOVERING;
        State state = TownVoyagePublisher.state(paused, reason);
        if (state == State.MOVING && status == ControllerStatus.WAITING_TERRAIN) return State.WAITING_TERRAIN;
        if (state == State.MOVING && (status == ControllerStatus.FAULT || settlingAtDestination)) return State.WAITING_RESOURCE;
        return state;
    }


    /** Observational identity only; never advances or resumes the flight journal. */
    static TownRailIntent townRailIntent(boolean active, boolean preflight, boolean returning,
            UUID preparation, com.monpai.sailboatmod.transport.air.rail.RailAircraftMission mission,
            BlockPos source, BlockPos target, BlockPos returnOrigin) {
        if (!active) return null;
        if (preflight) return preparation == null ? null : new TownRailIntent(
                preparation, returning ? returnOrigin : source, target, false);
        if (mission == null || mission.completed()) return null;
        return new TownRailIntent(mission.id(), mission.source(), mission.target(), true);
    }

    private void clearAirportLoading() {
        airportChunkBinding = null; airportChunkFootprint = Set.of(); loading.clearAirportPins();
    }

    private boolean configureAirportLoading(ServerLevel server, AirportBlockEntity source, AirportBlockEntity target) {
        if (!loading.bind(server,getTransportUuid(),view.physicalId())) return false;
        loading.setPinnedEndpoints(List.of(source.getBlockPos(),target.getBlockPos()));
        var binding = new AirportChunkBinding(getTransportUuid(),view.physicalId(),source,source.groundLayoutRevision(),
                source.facilities(),source.groundLayout(),target,target.groundLayoutRevision(),target.facilities(),target.groundLayout());
        if (!binding.equals(airportChunkBinding)) {
            var footprint = AirportLoadingFootprint.collect(binding.sourceFacilities(),binding.sourceGround(),binding.targetFacilities(),binding.targetGround());
            if (footprint.isEmpty()) { clearAirportLoading(); loading.rejectAirportPins(); return false; }
            airportChunkBinding = binding; airportChunkFootprint = footprint.orElseThrow();
        }
        // Reapply after a moving-window close/rebind; cached geometry is never itself a live ticket.
        if(groundIngressCandidate==null)return loading.setAirportPins(airportChunkFootprint);
        Set<ChunkPos> desired=new LinkedHashSet<>(airportChunkFootprint);desired.addAll(groundIngressCandidate.footprint());
        return loading.setAirportPins(desired);
    }

    private void refreshAirportLoading(ServerLevel server) {
        if (!active || paused || flightRecovering || recoveryRequired || status == ControllerStatus.FAULT
                || status == ControllerStatus.MANUAL || status == ControllerStatus.PAUSED) { clearAirportLoading(); return; }
        if (!loading.bind(server,getTransportUuid(),view.physicalId())) { clearAirportLoading(); return; }
        BlockPos origin = groundMission == null ? (returnTrip || returnFlightPending ? returnOrigin : sourceTerminal) : groundMission.sourceAirport();
        BlockPos target = groundMission == null ? destinationTerminal : groundMission.targetAirport();
        if (origin == null || target == null) { clearAirportLoading(); return; }
        loading.setPinnedEndpoints(List.of(origin,target));
        AirportBlockEntity source = groundAirport(server,origin), destination = groundAirport(server,target);
        if (source == null || destination == null || source == destination || source.isRemoved() || destination.isRemoved()
                || source.getLevel() != server || destination.getLevel() != server) { clearAirportLoading(); return; }
        configureAirportLoading(server,source,destination);
    }

    /** Metadata is acquired first. Only real loaded airport BEs can supply a bounded terrain footprint. */
    private boolean prepareAirportLoading(ServerLevel server, AirportBlockEntity source) {
        resolveAirportDestinationPosition(server);
        if (destinationTerminal == null || source.getBlockPos().equals(destinationTerminal)) {
            clearAirportLoading(); reason = "destination_airport_required"; return false;
        }
        if (!loading.bind(server,getTransportUuid(),view.physicalId())) { clearAirportLoading(); reason = loading.reason(); return false; }
        loading.setPinnedEndpoints(List.of(source.getBlockPos(),destinationTerminal));
        if (!server.hasChunkAt(destinationTerminal)) {
            clearAirportLoading(); loading.update(server,getTransportUuid(),view.physicalId(),view.worldBounds(),view.worldPosition());
            reason = "waiting_airport_metadata"; return false;
        }
        if (!(server.getBlockEntity(destinationTerminal) instanceof AirportBlockEntity target) || source.isRemoved() || target.isRemoved()
                || source.getLevel() != server || target.getLevel() != server
                || !server.hasChunkAt(source.getBlockPos()) || server.getBlockEntity(source.getBlockPos()) != source) {
            clearAirportLoading(); reason = "destination_airport_required"; return false;
        }
        if (!configureAirportLoading(server,source,target)) { reason = loading.reason(); return false; }
        if (!loading.update(server,getTransportUuid(),view.physicalId(),view.worldBounds(),view.worldPosition())) {
            reason = "airport_loading_too_large".equals(loading.reason()) ? loading.reason() : "waiting_airport_terrain"; return false;
        }
        return true;
    }

    private boolean queueAirportDeparture(ServerLevel server) {
        if (!FlightResumePolicy.waitsForAirport(reason)) { clearAirportPreflight(server); clearAirportLoading(); return false; }
        departureFlightPending = true; flightPending = false; flightPhase = "PREFLIGHT";
        return true;
    }

    private void clearAirportPreflight(ServerLevel server) {
        resetGroundedFlightReference();
        groundPreparation = null; groundSelectionIdentity = null;
        if (airportPreflightAttempt == null) return;
        for (BlockPos terminal : new BlockPos[] { sourceTerminal, returnOrigin, destinationTerminal })
            if (terminal != null && server.hasChunkAt(terminal)
                    && server.getBlockEntity(terminal) instanceof AirportBlockEntity airport)
                airport.clearPreflight(airportPreflightAttempt);
        airportPreflightAttempt = null;
        setChanged();
    }

    /** Disable only our real installed card; a missing FCC snapshot must not cause a premature fault. */
    private void blockReloadReplay(ServerLevel server) {
        if (!flightRecovering || transportKind() != Kind.AIR) return;
        clearAirportLoading();
        flightPhase = "RECOVERY_REQUIRED";
        paused = true;
        status = ControllerStatus.WAITING_TERRAIN;
        reason = "flight_recovery_initializing";
        if (flightTerminalOffset == null) { reason = "flight_terminal_required"; return; }
        BlockPos terminal = worldPosition.offset(flightTerminalOffset);
        var installed = FlightControlBridge.inspectInstalledProfile(server, terminal);
        var guard = FlightRecoveryPolicy.guard(FlightControlBridge.sameStructure(server, worldPosition, terminal),
                installed.available(), flightFingerprint, installed.fingerprint());
        if (guard == FlightRecoveryPolicy.Guard.WAIT) return;
        if (guard == FlightRecoveryPolicy.Guard.CONFLICT) {
            status = ControllerStatus.MANUAL;
            reason = "flight_recovery_identity_conflict";
            return;
        }
        var state = FlightControlBridge.inspect(server, terminal);
        if (!state.available()) return;
        var cancelled = FlightControlBridge.requestAction(server, terminal, FlightControlBridge.Action.CANCEL, state.expected());
        if (!cancelled.accepted()) {
            status = ControllerStatus.MANUAL;
            reason = cancelled.reason();
            return;
        }
        // FC does not persist route progress. Never adopt its fresh task or restart the old card.
        flightRecovering = false;
        recoveryRequired = true;
        flightPending = false;
        flightTask = null;
        status = ControllerStatus.FAULT;
        reason = "flight_recovery_land_at_airport";
        FlightMission mission = FlightMissionSavedData.get(server).get(getTransportUuid());
        if (mission != null) { mission.requireRecovery(reason); FlightMissionSavedData.get(server).setDirty(); }
        setChanged();
    }

    private boolean prepareAircraftFlight(ServerLevel server, DockBlockEntity departure) {
        if(railAircraftAutomation()) {
            if(!(departure instanceof AirportBlockEntity source)||!controlsReady()){reason="rail_motion_unavailable";return false;}
            if(!railNativeControlFree(server))return false;
            if(!prepareAirportLoading(server,source))return false;
            if(!(resolveDestination(server) instanceof AirportBlockEntity destination)||source==destination){reason="destination_airport_required";return false;}
            boolean prepared=railAircraft.prepare(railContext(server),source,destination,
                    FlightRoutePlanner.cruiseForLeg(selected().waypoints(),returnTrip),Math.max(3,Math.min(60,maxSpeed)),
                    nextGroundSourceAssignment,nextGroundTargetAssignment,p->checkAircraftFuel(server,p.path(),0,source,destination,p.source(),p.destination()));
            reason=railAircraft.reason();
            if(!prepared)return false;
            nextGroundSourceAssignment=nextGroundTargetAssignment=null;
            var rail=railAircraft.mission();invalidateTownGeometry(); activeRoute.clear();
            int segments=Math.max(2,Math.min(255,(int)Math.ceil(rail.path().length()/32)));
            for(int i=0;i<=segments;i++)activeRoute.add(rail.path().sample(rail.path().length()*i/segments).position());
            targetIndex=0;flightPending=false;flightTask=null;flightRecovering=recoveryRequired=false;flightPhase=rail.phase().name();
            departureFacility=rail.sourceAssignment().runwayId();arrivalFacility=rail.targetAssignment().runwayId();
            FlightMissionSavedData.get(server).put(FlightMission.start(getTransportUuid(),dimensionId(),worldPosition,aircraftClass,
                    manifest.stream().map(ShipmentManifestEntry::shippingOrderId).filter(id->!id.isBlank()).toList(),activeRoute,maxSpeed));
            clearAirportPreflight(server);return true;
        }
        if (!(departure instanceof AirportBlockEntity source) || !controlsReady()) { reason = "flight_terminal_required"; return false; }
        if (!prepareAirportLoading(server,source)) return false;
        DockBlockEntity destination = resolveDestination(server);
        if (!(destination instanceof AirportBlockEntity airport) || source == airport) { reason = "destination_airport_required"; return false; }
        FixedWingGroundPolicy.Mode groundMode = FixedWingGroundPolicy.mode(aircraftClass == AircraftClass.FIXED_WING,
                hasGroundLayout(source), hasGroundLayout(airport));
        if (groundMode == FixedWingGroundPolicy.Mode.INCOMPLETE) { reason = "ground_layout_incomplete"; return false; }
        if (groundMode == FixedWingGroundPolicy.Mode.STAND) return prepareGroundFlight(server, source, airport);
        if (invalidGroundMission) { reason = "ground_recovery_review"; return false; }
        var state = flightTerminalState(server);
        if (!state.available() || !state.linked()
                || ((state.active() || state.enabled()) && (flightTask == null || !Objects.equals(flightTask, state.taskId())))) {
            reason = "flight_control_conflict"; return false;
        }
        if (airportPreflightAttempt == null) { airportPreflightAttempt = UUID.randomUUID(); setChanged(); }
        var origin = source.preparedDeparture(airportPreflightAttempt, this, aircraftClass, view.linearVelocity().length());
        var landing = airport.preparedLanding(airportPreflightAttempt, getTransportUuid(), aircraftClass);
        if (origin.isEmpty() || landing.isEmpty()) { reason = "airport_unavailable"; return false; }
        var vehicle = FlightControlBridge.inspectVehicle(server, worldPosition.offset(flightTerminalOffset));
        if (aircraftClass != AircraftClass.FIXED_WING && (!vehicle.available() || !vehicle.fresh())) { reason = "flight_state_stale"; return false; }
        try {
            double refHeight = vehicle.y() - view.worldBounds().minY;
            GroundedFlightReference.Measurement reference = null;
            if (aircraftClass == AircraftClass.FIXED_WING) {
                reference = measureGroundedFlightReference(server, vehicle);
                if (reference == null) return false;
                if (!reference.matchesSurface(origin.get().start().getY())) {
                    resetGroundedFlightReference(); reason = "ground_surface_airport_mismatch"; return false;
                }
                refHeight = reference.referenceAboveSurface();
            }
            List<Vec3> authored = FlightRoutePlanner.cruiseForLeg(selected().waypoints(), returnTrip);
            double diameter = FlightRoutePlanner.requiredHorizontalDiameter(view.worldBounds(), new Vec3(vehicle.x(), vehicle.y(), vehicle.z()));
            double planningSpeed = aircraftClass == AircraftClass.FIXED_WING
                    ? fixedWingPlanningSpeed(server, origin.get(), landing.get(), vehicle, diameter) : maxSpeed;
            List<Vec3> flightRoute = FlightRoutePlanner.plan(aircraftClass, origin.get(), landing.get(), authored,
                    refHeight, diameter, planningSpeed);
            if (flightRoute.stream().anyMatch(p -> p.y >= server.getMaxBuildHeight() || p.y < server.getMinBuildHeight())) {
                reason = "flight_outside_height"; return false;
            }
            if (reference != null && !reference.freshAt(server.getGameTime(),System.nanoTime())) {
                resetGroundedFlightReference(); reason = "waiting_ground_surface_reference"; return false;
            }
            if (!airport.reservePreparedFacility(airportPreflightAttempt, landing.get().id(), getTransportUuid(), 6000)) { reason = "airport_reserved"; return false; }
            var disabled = FlightControlBridge.requestAction(server, worldPosition.offset(flightTerminalOffset),
                    FlightControlBridge.Action.CANCEL, state.expected());
            if (!disabled.accepted()) { airport.releaseReservation(getTransportUuid()); reason = disabled.reason(); return false; }
            var result = FlightControlBridge.submitRoute(server, worldPosition.offset(flightTerminalOffset),
                    new FlightControlBridge.RoutePlan("Sailboat cargo " + getTransportUuid().toString().substring(0, 8),
                            flightRoute.stream().map(p -> new FlightControlBridge.Waypoint(p.x,p.y,p.z)).toList(),
                            FlightControlBridge.Airframe.valueOf(aircraftClass.name()),
                            aircraftClass == AircraftClass.FIXED_WING ? FlightControlBridge.EndBehavior.APPROACH_LANDING
                                    : FlightControlBridge.EndBehavior.HOVER, 10, 10), disabled.snapshot().expected());
            if (!result.accepted()) { airport.releaseReservation(getTransportUuid()); reason = result.reason(); return false; }
            invalidateTownGeometry(); activeRoute.clear(); activeRoute.addAll(flightRoute); targetIndex = 0;
            departureFacility = origin.get().id(); arrivalFacility = landing.get().id(); flightRecovering = recoveryRequired = false; flightPending = true; flightPendingTicks = 0;
            flightFingerprint = result.submittedProfileFingerprint(); flightTask = null; flightPhase = "PREFLIGHT";
            FlightMissionSavedData.get(server).put(FlightMission.start(getTransportUuid(), dimensionId(), worldPosition,
                    aircraftClass, manifest.stream().map(ShipmentManifestEntry::shippingOrderId).filter(id -> !id.isBlank()).toList(),
                    activeRoute, maxSpeed));
            clearAirportPreflight(server);
            return true;
        } catch (IllegalArgumentException invalid) {
            airport.releaseReservation(getTransportUuid()); reason = invalid.getMessage(); return false;
        }
    }

    private void tickAircraft(ServerLevel server) {
        if (groundMission != null) { tickGroundAircraft(server); return; }
        tickFlightAircraft(server, false);
    }

    private void tickFlightAircraft(ServerLevel server, boolean standBound) {
        var state = flightTerminalState(server);
        if (!state.available() || !state.linked() || !view.physicalId().equals(state.structureId())) { fault("flight_binding_lost", false); return; }
        if ((!flightPending || state.profileValid()) && !flightFingerprint.equals(state.expected().profileFingerprint())) { fault("flight_profile_changed", true); return; }
        if (flightPending) {
            flightPendingTicks += 5;
            if (!state.profileValid()) {
                reason = "flight_profile_pending";
                if (flightPendingTicks >= 100) fault("flight_profile_invalid", false);
                return;
            }
            boolean ready = loading.update(server, getTransportUuid(), view.physicalId(), view.worldBounds(), activeRoute.getFirst());
            if (!ready) { reason = loading.reason(); status = ControllerStatus.WAITING_TERRAIN; return; }
            String preflightFailure = fixedWingStartFailure(server);
            if (!preflightFailure.isEmpty()) {
                if ("fixed_wing_reference_unavailable".equals(preflightFailure)) { reason = preflightFailure; status = ControllerStatus.WAITING_TERRAIN; }
                else fault(preflightFailure, false);
                return;
            }
            var result = FlightControlBridge.requestAction(server, worldPosition.offset(flightTerminalOffset),
                    FlightControlBridge.Action.START, state.expected());
            if (!result.accepted()) { fault(result.reason(), false); return; }
            flightPending = false; flightTask = result.snapshot().taskId(); flightPhase = "TAKING_OFF"; setChanged();
            FlightControlBridge.claimOwnedTask(server, worldPosition.offset(flightTerminalOffset),
                    getTransportUuid(), view.physicalId(), result.snapshot());
            state = result.snapshot();
        }
        if (state.taskId() != null && !Objects.equals(flightTask, state.taskId())) {
            var successor = FlightControlBridge.consumeOwnedSuccessor(server,
                    worldPosition.offset(flightTerminalOffset), getTransportUuid(), view.physicalId(),
                    flightTask, state);
            if (successor == FlightControlBridge.SuccessorDecision.PENDING) {
                // A native automatic resume publishes STARTING before acquiring its lease.
                // Keep the cargo aboard until a later snapshot proves actual control ownership.
                status = ControllerStatus.WAITING_TERRAIN;
                reason = "flight_recovery_initializing";
                arrivalTicks = 0;
                updateTrace(server);
                recordFlightTelemetry(server);
                return;
            }
            if (successor != FlightControlBridge.SuccessorDecision.ACCEPTED) {
                fault("flight_control_conflict", true); return;
            }
            flightTask = state.taskId();
            setChanged();
        }
        if (state.currentPath() != null) targetIndex = Math.max(0, Math.min(activeRoute.size()-1, state.currentPath()-1));
        if (state.completed() || "HOLDING".equals(state.phase())) flightPhase = "LANDING";
        else if (targetIndex == 0) flightPhase = "TAKING_OFF";
        else if (targetIndex >= activeRoute.size()-2) flightPhase = "APPROACHING";
        else flightPhase = "CRUISING";
        boolean chunksReady = loading.update(server, getTransportUuid(), view.physicalId(), view.worldBounds(), activeRoute.get(targetIndex));
        // Diagnostics never cut an airborne fixed wing's thrust because a terrain query is pending.
        boolean terrainReady = chunksReady && clearFlightTerrain(server);
        status = terrainReady ? ControllerStatus.RUNNING : ControllerStatus.WAITING_TERRAIN;
        reason = !chunksReady ? loading.reason() : terrainReady ? "" : terrainResult == SweptTerrainProbe.Result.BLOCKED ? "flight_obstacle_warning" : "waiting_terrain";
        if ("BLOCKED".equals(state.phase()) || "PREEMPTED".equals(state.phase()) || "FAILED".equals(state.phase())) {
            clearAirportLoading();
            status = ControllerStatus.FAULT; reason = state.blockReason(); flightPhase = state.phase();
        }
        DockBlockEntity destination = resolveDestination(server);
        if (!standBound && destination instanceof AirportBlockEntity airport) {
            if (server.getGameTime()%20==0 && !airport.reserveFacility(arrivalFacility,getTransportUuid(),6000)) reason="airport_reserved";
            if (targetIndex >= activeRoute.size()-2 && airport.isCarrierInsideDockZone(this)) {
                arrivalTicks += 5;
                if (arrivalTicks >= 20) { arrive(server, airport); return; }
            } else arrivalTicks=0;
        }
        FlightMission mission = FlightMissionSavedData.get(server).get(getTransportUuid());
        if (mission != null) { mission.physicalObservation(view.worldPosition(),targetIndex,flightPhase,reason); FlightMissionSavedData.get(server).setDirty(); }
        updateTrace(server);
        recordFlightTelemetry(server);
    }

    private static boolean hasGroundLayout(AirportBlockEntity airport) {
        return !airport.groundLayout().stands().isEmpty() || !airport.groundLayout().taxiRoutes().isEmpty();
    }

    private RegisteredAircraftParkingBrake parkingBrake() {
        if (parkingBrake == null) parkingBrake = new RegisteredAircraftParkingBrake();
        return parkingBrake;
    }

    private void yieldParkingBrake() {
        clearGroundHoldLease();
        if (parkingBrake().held()) ownSignalsOff();
        parkingBrake().forget(); parkingHardwareKey = null;
    }

    private List<Object> parkingHardwareKey() {
        List<Object> key = new ArrayList<>();
        key.add(target(throttleOffset)); key.add(Map.copyOf(ports)); key.add(groundConfiguration);
        key.add(groundCalibrationBody);
        for (BlockPos offset : groundWheelOffsets) key.add(target(offset));
        return List.copyOf(key);
    }

    private RegisteredAircraftParkingBrake.Outputs parkingOutputs(ServerLevel server) {
        return new RegisteredAircraftParkingBrake.Outputs() {
            public boolean neutral(int signal) {
                var result = PhysicalVehicleBridge.nativeThrottle(RegisteredVehicleControllerBlockEntity.this, target(throttleOffset), signal);
                if ("manual_control".equals(result.reason())) { manualTakeover = true; yieldParkingBrake(); }
                return result.success();
            }
            public void brake() {
                signals.clear(); signals.put(ports.get(ControlChannel.LEFT),0); signals.put(ports.get(ControlChannel.RIGHT),0);
                signals.put(ports.get(ControlChannel.BRAKE),15); groundOutputsNeutral = false; notifySignals();
            }
            public void clear() { ownSignalsOff(); }
        };
    }

    private boolean parkingHardwareReady() {
        if (!registration.registered() || !bindingValid || view==null || !view.available()
                || !Objects.equals(registration.physicalId(),view.physicalId()) || groundConfiguration==null
                || !groundConfiguration.calibration().liveVerified() || !Objects.equals(groundCalibrationBody,view.physicalId())
                || !groundPortsReady() || !nativeGroundThrottle(target(throttleOffset))
                || !PhysicalVehicleBridge.sameStructure(this,target(throttleOffset)) || groundWheelOffsets.size()<RegisteredAircraftParkingBrake.MINIMUM_WHEELS) return false;
        for (BlockPos offset : groundWheelOffsets) {
            BlockEntity wheel=target(offset);
            if (wheel==null || !PhysicalVehicleBridge.sameStructure(this,wheel)) return false;
        }
        return true;
    }

    private void updateParkingBrake(ServerLevel server, boolean allowNewClaim) {
        var parking = parkingBrake();
        if (transportKind() != Kind.AIR || aircraftClass != AircraftClass.FIXED_WING) { yieldParkingBrake(); return; }
        // Ownership must follow the actual current structure, not the slower five-tick telemetry cache.
        updatePhysicalView(AeronauticsVehicleBridge.resolve(this,facing()).orElse(null));
        boolean hardware = parkingHardwareReady();
        List<Object> actualKey = hardware ? parkingHardwareKey() : null;
        if (parking.held() && !Objects.equals(parkingHardwareKey,actualKey)) { yieldParkingBrake(); return; }
        if (!hardware && !parking.held()) return;
        NativeGroundObservation observation = hardware ? observeGround(server) : NativeGroundObservation.unavailable("ground_hardware_required");
        var authority = groundOwnership(server);
        var terminal = flightTerminalState(server);
        boolean fresh = hardware && observation.available() && groundObservationTick == server.getGameTime()
                && Objects.equals(view.physicalId(), observation.physicalId());
        boolean supported = fresh && RegisteredAircraftParkingBrake.supportedObservation(observation,groundWheelOffsets.size())
                && observation.angularVelocity().length() <= Math.toRadians(2);
        boolean inStand = false;
        if (allowNewClaim && hardware && sourceTerminal != null && server.hasChunkAt(sourceTerminal)
                && server.getBlockEntity(sourceTerminal) instanceof AirportBlockEntity airport)
            inStand = airport.groundLayout().stands().stream().anyMatch(stand -> stand.cargoEnabled()
                    && stand.contains(view.worldBounds(),groundYawDegrees(),view.linearVelocity().length()));
        boolean mayAcquire = allowNewClaim && !active && !paused && !manualTakeover && groundMission == null
                && !groundOutputsOwned && !flightPending && !departureFlightPending && !returnFlightPending
                && !flightRecovering && !recoveryRequired && !invalidGroundMission && !groundStartRequested
                && !groundReleaseRequested && flightTask == null && airportPreflightAttempt == null && manifest.isEmpty();
        parking.update(new RegisteredAircraftParkingBrake.Evidence(view == null ? null : view.physicalId(),fresh,supported,hardware,
                view != null && authority.provesReleased(view.physicalId()),
                authority.available() && (authority.anyActiveLease() || authority.foreignLease())
                        || terminal.available() && (terminal.active() || terminal.enabled()),
                manualTakeover,inStand,mayAcquire,groundConfiguration == null ? -1 : groundConfiguration.calibration().neutralThrottleSignal()),parkingOutputs(server));
        if (parking.held()) parkingHardwareKey = actualKey;
    }

    private NativeGroundObservation observeGround(ServerLevel server) {
        if (groundObservationTick == server.getGameTime()) return groundObservation;
        groundObservationTick = server.getGameTime();
        List<BlockEntity> wheels = new ArrayList<>();
        for (BlockPos offset : groundWheelOffsets) {
            BlockEntity wheel = target(offset);
            if (wheel == null || !PhysicalVehicleBridge.sameStructure(this, wheel))
                return groundObservation = NativeGroundObservation.unavailable("ground_wheels_required");
            wheels.add(wheel);
        }
        return groundObservation = PhysicalVehicleBridge.groundObservation(this, wheels);
    }

    private boolean groundPortsReady() {
        Set<Direction> sides = new HashSet<>();
        for (ControlChannel channel : new ControlChannel[] {ControlChannel.LEFT, ControlChannel.RIGHT, ControlChannel.BRAKE})
            if (ports.get(channel) == null || !sides.add(ports.get(channel))) return false;
        return portsConfirmed;
    }

    private boolean groundHardwareReady(ServerLevel server) {
        if (groundConfiguration == null || !groundConfiguration.calibration().liveVerified() || view == null
                || !Objects.equals(groundCalibrationBody, view.physicalId())) return false;
        if (!groundPortsReady() || !nativeGroundThrottle(target(throttleOffset))
                || !PhysicalVehicleBridge.sameStructure(this, target(throttleOffset)) || groundWheelOffsets.size() < 3) return false;
        NativeGroundObservation observation = observeGround(server);
        return observation.available() && Objects.equals(view.physicalId(), observation.physicalId())
                && observation.wheels().size() == groundWheelOffsets.size()
                && observation.wheels().stream().allMatch(w -> w.tireRadius() > 0);
    }

    private void resetGroundedFlightReference() {
        if (groundedFlightReference != null) groundedFlightReference.reset();
    }

    /** New ground departures only. No height proof is saved or used to rewrite a live native task. */
    private GroundedFlightReference.Measurement measureGroundedFlightReference(ServerLevel server,
            FlightControlBridge.VehicleSnapshot vehicle) {
        reason = "waiting_ground_surface_reference";
        if (view == null || !view.available() || vehicle == null || !groundHardwareReady(server)) {
            resetGroundedFlightReference(); return null;
        }
        NativeGroundObservation ground = observeGround(server);
        if (groundObservationTick != server.getGameTime() || !ground.fullyGrounded()
                || !Objects.equals(view.physicalId(),ground.physicalId())) {
            resetGroundedFlightReference(); return null;
        }
        List<BlockEntity> wheels = new ArrayList<>();
        for (BlockPos offset : groundWheelOffsets) {
            BlockEntity wheel = target(offset);
            if (wheel == null || !PhysicalVehicleBridge.sameStructure(this,wheel)) {
                resetGroundedFlightReference(); return null;
            }
            wheels.add(wheel);
        }
        var surface = PhysicalVehicleBridge.groundSurface(this,wheels);
        if (groundedFlightReference == null) groundedFlightReference = new GroundedFlightReference();
        var configuration = new GroundReferenceConfiguration(airportPreflightAttempt,dimensionId(),flightTerminalOffset,
                groundConfiguration,groundCalibrationBody,view.mass());
        var frame = new GroundedFlightReference.Frame(vehicle.structureId(),vehicle.y(),
                Math.max(vehicle.worldSpeed(),view.linearVelocity().length()),ground.angularVelocity().length(),vehicle.sampledNanos(),
                vehicle.available() && vehicle.fresh() && vehicle.computerWorking() && vehicle.computerMode()==0
                        && "FIXED_WING".equals(vehicle.airframeMode()) && Objects.equals(vehicle.structureId(),view.physicalId()),
                groundWheelOffsets.size(),configuration);
        return groundedFlightReference.observe(surface,frame,server.getGameTime(),System.nanoTime()).orElse(null);
    }

    private static boolean nativeGroundThrottle(BlockEntity actuator) {
        for (Class<?> type = actuator == null ? null : actuator.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals("dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlockEntity")) return true;
        return false;
    }

    private FlightControlBridge.ControlOwnership groundOwnership(ServerLevel server) {
        return FlightControlBridge.inspectControlOwnership(server,
                flightTerminalOffset == null ? null : worldPosition.offset(flightTerminalOffset));
    }

    @Override public double groundYawDegrees() {
        return view == null ? Double.NaN : Math.toDegrees(Math.atan2(-view.worldForward().x, view.worldForward().z));
    }

    @Override public boolean groundArrivalReady() {
        if(railAircraftAutomation()) {
            if(!(level instanceof ServerLevel server)||!physicalArrivalReady()||view==null)return false;
            double angular=RailAircraftMotionBridge.angularSpeed(this).orElse(Double.NaN);
            BlockPos parked=active&&railAircraft.mission()!=null?railAircraft.mission().target():sourceTerminal;
            AirportBlockEntity airport=groundAirport(server,parked);
            if(airport!=null)return airport.railDepartureReady(this,view,groundYawDegrees(),angular);
            return AirportRegistry.get(server).stream().filter(server::hasChunkAt)
                    .map(server::getBlockEntity).filter(AirportBlockEntity.class::isInstance).map(AirportBlockEntity.class::cast)
                    .anyMatch(a->a.railDepartureReady(this,view,groundYawDegrees(),angular));
        }
        if (aircraftClass != AircraftClass.FIXED_WING || !physicalArrivalReady()
                || !(level instanceof ServerLevel server) || !groundHardwareReady(server)) return false;
        NativeGroundObservation observation = observeGround(server);
        return observation.fullyGrounded() && view.linearVelocity().length() <= .25
                && observation.angularVelocity().length() <= Math.toRadians(2)
                && groundOwnership(server).provesReleased(view.physicalId());
    }

    @Override public boolean groundAssignedArrivalReady(BlockPos airportPosition) {
        if(railAircraftAutomation()) {
            if(!(level instanceof ServerLevel server)||view==null)return false;
            AirportBlockEntity airport=groundAirport(server,airportPosition);if(airport==null)return false;
            if(!active)return Objects.equals(sourceTerminal,airportPosition)&&airport.railDepartureReady(this,view,groundYawDegrees(),
                    RailAircraftMotionBridge.angularSpeed(this).orElse(Double.NaN));
            return railAircraft.ready(railContext(server),airport);
        }
        if (!(level instanceof ServerLevel server) || !groundArrivalReady() || invalidGroundMission) return false;
        if (!active) return Objects.equals(sourceTerminal, airportPosition);
        if (groundMission == null || !groundBindingsMatch(server)) return false;
        AirportBlockEntity airport = groundAirport(server, groundMission.targetAirport());
        boolean assigned = airport != null && airport.assignedStandArrival(groundMission.targetAssignment(), this,
                view.linearVelocity().length(), groundYawDegrees());
        return groundMission.deliveryReady(airportPosition, assigned, true, true,
                view.linearVelocity().length(), observeGround(server).angularVelocity().length());
    }

    private AirportBlockEntity groundAirport(ServerLevel server, BlockPos position) {
        return position != null && server.hasChunkAt(position) && server.getBlockEntity(position) instanceof AirportBlockEntity airport ? airport : null;
    }

    private boolean groundBindingsMatch(ServerLevel server) {
        if (groundMission == null || view == null || !Objects.equals(groundMission.plan().physicalId(), view.physicalId())
                || !dimensionId().equals(groundMission.plan().dimensionId())) return false;
        AirportBlockEntity source = groundAirport(server, groundMission.sourceAirport()), target = groundAirport(server, groundMission.targetAirport());
        BlockPos origin = returnTrip ? returnOrigin : sourceTerminal;
        return source != null && target != null && groundMission.matches(origin, source.groundLayoutRevision(), destinationTerminal, target.groundLayoutRevision())
                && source.holdsGroundAssignment(groundMission.sourceAssignment(), getTransportUuid())
                && target.holdsGroundAssignment(groundMission.targetAssignment(), getTransportUuid());
    }

    private boolean prepareGroundFlight(ServerLevel server, AirportBlockEntity source, AirportBlockEntity destination) {
        if (groundMission != null || invalidGroundMission) { reason = "ground_recovery_review"; return false; }
        if (!groundHardwareReady(server)) { resetGroundedFlightReference(); reason = "ground_hardware_required"; return false; }
        if (!groundArrivalReady()) { resetGroundedFlightReference(); reason = "waiting_ground_surface_reference"; return false; }
        var state = flightTerminalState(server);
        if (!state.available() || !state.linked() || state.active() || state.enabled()
                || !groundOwnership(server).provesReleased(view.physicalId())) { reason = "ground_control_conflict"; return false; }
        if (airportPreflightAttempt == null) { airportPreflightAttempt = UUID.randomUUID(); setChanged(); }
        if (nextGroundSourceAssignment != null && (source.groundLayoutRevision() != nextGroundSourceAssignment.layoutRevision()
                || source.groundLayout().stand(nextGroundSourceAssignment.standId()).filter(s -> s.contains(view.worldBounds(), groundYawDegrees(), view.linearVelocity().length())).isEmpty())
                || nextGroundTargetAssignment != null && destination.groundLayoutRevision() != nextGroundTargetAssignment.layoutRevision()) {
            reason = "ground_binding_changed"; return false;
        }
        var vehicle = FlightControlBridge.inspectVehicle(server, worldPosition.offset(flightTerminalOffset));
        var reference = measureGroundedFlightReference(server, vehicle);
        if (reference == null) return false;
        var performance = FlightControlBridge.inspectFixedWingPerformance(server, worldPosition.offset(flightTerminalOffset));
        String performanceFailure = performance.failure(view.physicalId(), server.getGameTime());
        if (!performanceFailure.isEmpty()) { reason = performanceFailure; return false; }
        var calibration = groundConfiguration.calibration();
        var capability = new AirportGroundSelection.Capabilities(view.physicalId(),view.worldBounds(),
                new Vec3(vehicle.x(),vehicle.y(),vehicle.z()),groundYawDegrees(),view.linearVelocity().length(),
                calibration.brakingDeceleration(),calibration.minimumTurnRadius(),calibration.maximumSteeringDegrees(),
                performance.conservativeSpeed(),calibration.liveVerified(),true);
        var identity = new GroundSelectionIdentity(airportPreflightAttempt,view.physicalId(),source.getBlockPos(),source.groundLayoutRevision(),
                destination.getBlockPos(),destination.groundLayoutRevision(),groundConfiguration);
        if (!identity.equals(groundSelectionIdentity)) { groundSelectionIdentity=identity; groundPreparation=new AirportGroundPreparation(); }
        double lowerTaxiSpeed=selectionTaxiSpeedLowerBound(groundConfiguration.taxiSpeed(),source.groundLayout(),destination.groundLayout());
        var sourceGate=groundRouteGate(source.groundLayout(),source.facilities(),lowerTaxiSpeed,true);
        var targetGate=groundRouteGate(destination.groundLayout(),destination.facilities(),lowerTaxiSpeed,false);
        var sources = nextGroundSourceAssignment == null
                ? AirportGroundSelection.search(source.groundLayout(),source.facilities(),AirportTaxiRoute.Direction.OUTBOUND,capability,sourceGate).candidates()
                : AirportGroundSelection.captured(source.groundLayout(),source.facilities(),AirportTaxiRoute.Direction.OUTBOUND,nextGroundSourceAssignment,capability,sourceGate).stream().toList();
        var targets = nextGroundTargetAssignment == null
                ? AirportGroundSelection.search(destination.groundLayout(),destination.facilities(),AirportTaxiRoute.Direction.INBOUND,capability,targetGate).candidates()
                : AirportGroundSelection.captured(destination.groundLayout(),destination.facilities(),AirportTaxiRoute.Direction.INBOUND,nextGroundTargetAssignment,capability,targetGate).stream().toList();
        Map<List<AirportFacility>,Boolean> flightGeometry=new HashMap<>();
        var gate = new AirportGroundSelection.LegGate() {
            public boolean taxi(AirportGroundSelection.Candidate from,AirportGroundSelection.Candidate to) {
                if (!reference.matchesSurface(from.stand().position().y)) return false;
                double diameter=FlightRoutePlanner.requiredHorizontalDiameter(capability.hull(),capability.nativeReference());
                double corridor=(Math.min(from.outbound().width(),to.inbound().width())-diameter)/2;
                double speed=Math.min(groundConfiguration.taxiSpeed(),Math.min(from.outbound().speedLimitMetersPerSecond(),to.inbound().speedLimitMetersPerSecond()));
                return corridor>=.5 && RegisteredAircraftGroundMission.joinsRunwayEnds(from.selectedRunway(),to.selectedRunway(),
                        from.outbound().points().getLast(),to.inbound().points().getFirst(),corridor)
                        && taxiRouteFits(source.groundLayout(),source.facilities(),from.outbound(),capability,speed,corridor,capability.nativeReference(),capability.yawDegrees())
                        && taxiRouteFits(destination.groundLayout(),destination.facilities(),to.inbound(),capability,speed,corridor,null,0);
            }
            public boolean flight(AirportFacility from,AirportFacility to) {
                return flightGeometry.computeIfAbsent(List.of(from,to),pair -> {
                    try {
                        double diameter=FlightRoutePlanner.requiredHorizontalDiameter(capability.hull(),capability.nativeReference());
                        var route=FlightRoutePlanner.plan(aircraftClass,from,to,FlightRoutePlanner.cruiseForLeg(selected().waypoints(),returnTrip),
                                reference.referenceAboveSurface(),diameter,performance.conservativeSpeed());
                        return route.stream().allMatch(p -> p.y<server.getMaxBuildHeight()&&p.y>=server.getMinBuildHeight());
                    } catch(IllegalArgumentException invalid) { return false; }
                });
            }
        };
        var prepared=groundPreparation.advance(server.getGameTime(),sources,targets,gate,
                candidate -> source.prepareGroundCandidate(airportPreflightAttempt,getTransportUuid(),candidate.assignment()),
                candidate -> destination.prepareGroundCandidate(airportPreflightAttempt,getTransportUuid(),candidate.assignment()));
        // Keep the ordinary queued/preflight lifecycle for both bounded scans and all-blocked retry cooldown.
        if (prepared.status()!=AirportGroundPreparation.Status.READY) { reason="airport_unavailable"; return false; }
        var from=prepared.leg().source().assignment();var to=prepared.leg().destination().assignment();
        var origin=prepared.leg().source().selectedRunway();var landing=prepared.leg().destination().selectedRunway();
        var outbound=prepared.leg().source().outbound();var inbound=prepared.leg().destination().inbound();
        var targetStand=prepared.leg().destination().stand();
        if (!reference.matchesSurface(prepared.leg().source().stand().position().y)) {
            resetGroundedFlightReference(); reason = "ground_surface_airport_mismatch"; return false;
        }
        if (!FlightControlBridge.inspectPower(server, worldPosition.offset(flightTerminalOffset)).ready()) { reason = "flight_power_required"; return false; }
        try {
            double diameter = FlightRoutePlanner.requiredHorizontalDiameter(view.worldBounds(), new Vec3(vehicle.x(), vehicle.y(), vehicle.z()));
            if (!outbound.fits(view.worldBounds()) || !inbound.fits(view.worldBounds())) throw new IllegalArgumentException("facility_too_narrow");
            double corridor = (Math.min(outbound.width(), inbound.width()) - diameter) / 2;
            if (corridor < .5) throw new IllegalArgumentException("facility_too_narrow");
            if (!RegisteredAircraftGroundMission.joinsRunwayEnds(origin, landing, outbound.points().getLast(), inbound.points().getFirst(), corridor))
                throw new IllegalArgumentException("ground_route_runway_join");
            double planningSpeed = fixedWingPlanningSpeed(server, origin, landing, vehicle, diameter, performance);
            List<Vec3> flightRoute = FlightRoutePlanner.plan(aircraftClass, origin, landing,
                    FlightRoutePlanner.cruiseForLeg(selected().waypoints(), returnTrip), reference.referenceAboveSurface(), diameter, planningSpeed);
            if (flightRoute.stream().anyMatch(p -> p.y >= server.getMaxBuildHeight() || p.y < server.getMinBuildHeight())) throw new IllegalArgumentException("flight_outside_height");
            UUID missionId = airportPreflightAttempt;
            var plan = new AircraftGroundMission.Plan(missionId, view.physicalId(), dimensionId(), missionId.getMostSignificantBits() & Long.MAX_VALUE,
                    from.runwayId(), to.standId(), missionId.toString(), outbound.points(), outbound.holdPointIndex(), inbound.points(),
                    Math.toDegrees(Math.atan2(-(origin.end().getX() - (double) origin.start().getX()), origin.end().getZ() - (double) origin.start().getZ())),
                    targetStand.yawDegrees(), Math.min(groundConfiguration.taxiSpeed(), Math.min(outbound.speedLimitMetersPerSecond(), inbound.speedLimitMetersPerSecond())),
                    corridor, groundConfiguration.calibration());
            if (!reference.freshAt(server.getGameTime(),System.nanoTime())) {
                resetGroundedFlightReference(); reason = "waiting_ground_surface_reference"; return false;
            }
            if (!source.reserveGroundAssignment(missionId, from, getTransportUuid(), 6000)) { reason = "airport_reserved"; return false; }
            if (!destination.reserveGroundAssignment(missionId, to, getTransportUuid(), 6000)) {
                source.releaseReservation(getTransportUuid()); reason = "airport_reserved"; return false;
            }
            var disabled = FlightControlBridge.requestAction(server, worldPosition.offset(flightTerminalOffset), FlightControlBridge.Action.CANCEL, state.expected());
            if (!disabled.accepted()) { source.releaseReservation(getTransportUuid()); destination.releaseReservation(getTransportUuid()); reason = disabled.reason(); return false; }
            var submitted = FlightControlBridge.submitRoute(server, worldPosition.offset(flightTerminalOffset),
                    new FlightControlBridge.RoutePlan("Sailboat cargo " + getTransportUuid().toString().substring(0, 8),
                            flightRoute.stream().map(p -> new FlightControlBridge.Waypoint(p.x, p.y, p.z)).toList(),
                            FlightControlBridge.Airframe.FIXED_WING, FlightControlBridge.EndBehavior.APPROACH_LANDING, 10, 10), disabled.snapshot().expected());
            if (!submitted.accepted()) { source.releaseReservation(getTransportUuid()); destination.releaseReservation(getTransportUuid()); reason = submitted.reason(); return false; }
            clearGroundNativeLease();
            groundMission = new RegisteredAircraftGroundMission(source.getBlockPos(), destination.getBlockPos(), from, to, plan, AircraftGroundMission.departure(plan));
            groundOutputsOwned = groundStartRequested = groundReleaseRequested = false; groundOutputsNeutral = !parkingBrake().held();
            nextGroundSourceAssignment = nextGroundTargetAssignment = null;
            invalidateTownGeometry(); activeRoute.clear(); activeRoute.addAll(flightRoute); targetIndex = 0;
            departureFacility = from.runwayId(); arrivalFacility = to.runwayId(); flightFingerprint = submitted.submittedProfileFingerprint(); flightTask = null;
            flightPending = false; flightPendingTicks = 0; flightRecovering = recoveryRequired = false; flightPhase = "TAXI_OUT";
            FlightMissionSavedData.get(server).put(FlightMission.start(getTransportUuid(), dimensionId(), worldPosition, aircraftClass,
                    manifest.stream().map(ShipmentManifestEntry::shippingOrderId).filter(id -> !id.isBlank()).toList(), activeRoute, maxSpeed));
            loading.setPinnedEndpoints(List.of(groundMission.sourceAirport(), groundMission.targetAirport()));
            clearAirportPreflight(server); setChanged(); return true;
        } catch (IllegalArgumentException invalid) {
            source.releaseReservation(getTransportUuid()); destination.releaseReservation(getTransportUuid()); reason = invalid.getMessage(); return false;
        }
    }

    /** A real positive lower bound only; the exact pair is assessed again at its actual shared Plan speed. */
    static double selectionTaxiSpeedLowerBound(double configured,AirportGroundLayout source,AirportGroundLayout target) {
        double speed=configured;
        for(var layout:List.of(source,target))for(var route:layout.taxiRoutes())speed=Math.min(speed,route.speedLimitMetersPerSecond());
        return speed;
    }
    /** Only current source OUT candidates may use this aircraft's real pose; every other route is future/remote. */
    static AirportGroundSelection.RouteGate groundRouteGate(AirportGroundLayout layout,List<AirportFacility> facilities,
            double speed,boolean currentSource) {
        return (route,cap,corridor) -> taxiRouteFits(layout,facilities,route,cap,speed,corridor,
                currentSource&&route.direction()==AirportTaxiRoute.Direction.OUTBOUND?cap.nativeReference():null,cap.yawDegrees());
    }
    /** Future/remote routes use their own endpoint poses; only the current source OUT uses the actual native pose. */
    private static boolean taxiRouteFits(AirportGroundLayout layout,List<AirportFacility> facilities,AirportTaxiRoute route,
            AirportGroundSelection.Capabilities cap,double speed,double corridor,Vec3 initial,double initialYaw) {
        var stand=layout.stand(route.standId()).orElse(null);
        var runway=facilities.stream().filter(f -> f.id().equals(route.runwayId())).findFirst().orElse(null);
        if(stand==null||runway==null||corridor<.5)return false;
        double runwayYaw=Math.toDegrees(Math.atan2(-(runway.end().getX()-(double)runway.start().getX()),runway.end().getZ()-(double)runway.start().getZ()));
        double radius=FlightRoutePlanner.requiredHorizontalDiameter(cap.hull(),cap.nativeReference())/2;
        boolean outbound=route.direction()==AirportTaxiRoute.Direction.OUTBOUND;
        var pose=initial==null?route.points().getFirst():initial;
        double yaw=initial==null?(outbound?stand.yawDegrees():runwayYaw):initialYaw;
        var request=new TaxiRouteGeometry.Request(route.points().stream().map(p -> new TaxiRouteGeometry.Point(p.x,p.z)).toList(),
                Math.min(route.width(),2*(radius+corridor)),route.clearanceHeight(),route.holdPointIndex(),
                new TaxiRouteGeometry.Point(pose.x,pose.z),yaw,outbound?runwayYaw:stand.yawDegrees(),
                new TaxiRouteGeometry.Capabilities(cap.liveCalibrated(),radius,cap.hull().getYsize(),cap.minimumTurnRadius(),cap.brakingDeceleration(),speed));
        return TaxiRouteGeometry.assess(request).admitted();
    }

    /** Necessary native speed/stopping checks only; no takeoff acceleration is inferred here. */
    private double fixedWingPlanningSpeed(ServerLevel server, AirportFacility departure, AirportFacility arrival,
            FlightControlBridge.VehicleSnapshot vehicle, double diameter) {
        if (vehicle == null || !vehicle.available() || !vehicle.fresh() || !view.physicalId().equals(vehicle.structureId()))
            throw new IllegalArgumentException("flight_state_stale");
        return fixedWingPlanningSpeed(server,departure,arrival,vehicle,diameter,
                FlightControlBridge.inspectFixedWingPerformance(server,worldPosition.offset(flightTerminalOffset)));
    }
    private double fixedWingPlanningSpeed(ServerLevel server, AirportFacility departure, AirportFacility arrival,
            FlightControlBridge.VehicleSnapshot vehicle, double diameter,FlightControlBridge.FixedWingPerformanceSnapshot performance) {
        if (vehicle == null || !vehicle.available() || !vehicle.fresh() || !view.physicalId().equals(vehicle.structureId()))
            throw new IllegalArgumentException("flight_state_stale");
        String failure = performance.failure(view.physicalId(), server.getGameTime());
        if (!failure.isEmpty()) throw new IllegalArgumentException(failure);
        if (groundConfiguration == null || !groundConfiguration.calibration().liveVerified()
                || !view.physicalId().equals(groundCalibrationBody))
            throw new IllegalArgumentException("ground_calibration_required");
        double speed = performance.conservativeSpeed();
        failure = FixedWingRunwayPolicy.failure(departure.length(), arrival.length(), speed,
                groundConfiguration.calibration().brakingDeceleration(), diameter);
        if (!failure.isEmpty()) throw new IllegalArgumentException(failure);
        return speed;
    }

    private String fixedWingStartFailure(ServerLevel server) {
        if (aircraftClass != AircraftClass.FIXED_WING) return "";
        BlockPos sourcePos = groundMission == null ? (returnTrip ? returnOrigin : sourceTerminal) : groundMission.sourceAirport();
        BlockPos targetPos = groundMission == null ? destinationTerminal : groundMission.targetAirport();
        AirportBlockEntity source = groundAirport(server, sourcePos), target = groundAirport(server, targetPos);
        String sourceId = groundMission == null ? departureFacility : groundMission.sourceAssignment().runwayId();
        String targetId = groundMission == null ? arrivalFacility : groundMission.targetAssignment().runwayId();
        if (source == null || target == null) return "airport_unavailable";
        AirportFacility departure = source.facilities().stream().filter(f -> f.id().equals(sourceId) && f.supports(AircraftClass.FIXED_WING)).findFirst().orElse(null);
        AirportFacility arrival = target.facilities().stream().filter(f -> f.id().equals(targetId) && f.supports(AircraftClass.FIXED_WING)).findFirst().orElse(null);
        if (departure == null || arrival == null) return "airport_binding_changed";
        var vehicle = FlightControlBridge.inspectVehicle(server, worldPosition.offset(flightTerminalOffset));
        try {
            double diameter = FlightRoutePlanner.requiredHorizontalDiameter(view.worldBounds(), new Vec3(vehicle.x(), vehicle.y(), vehicle.z()));
            fixedWingPlanningSpeed(server, departure, arrival, vehicle, diameter);
            return "";
        } catch (IllegalArgumentException failure) { return failure.getMessage(); }
    }

    private boolean groundPathClear(ServerLevel server, Vec3 referencePosition) {
        var state = groundMission.state(); var plan = groundMission.plan();
        List<Vec3> points = switch (state.phase()) {
            case TAXI_IN, STAND_STABILIZING, COMPLETE -> plan.inbound(); default -> plan.outbound();
        };
        Vec3 targetPoint = points.get(Math.min(points.size() - 1, state.pointIndex()));
        boolean chunksReady = loading.update(server, getTransportUuid(), view.physicalId(), view.worldBounds(), targetPoint);
        if (!chunksReady) { groundTerrainWindow.clear(); return false; }
        Vec3 direction = targetPoint.subtract(referencePosition).multiply(1, 0, 1);
        if (direction.lengthSqr() > .01) direction = direction.normalize().scale(Math.min(8, Math.max(2, view.linearVelocity().length() * 2)));
        AABB hull = view.worldBounds();
        AABB area = new AABB(hull.minX, Math.min(hull.maxY, hull.minY + .25), hull.minZ, hull.maxX, hull.maxY, hull.maxZ).expandTowards(direction).deflate(.05);
        terrainResult = groundTerrainWindow.scan(plan.missionId(), view.physicalId(), dimensionId(),
                server.getGameTime(), area, 2048, server::hasChunkAt,
                (at, checkedArea) -> server.getBlockState(at).getCollisionShape(server, at).toAabbs().stream()
                        .anyMatch(box -> box.move(at.getX(), at.getY(), at.getZ()).intersects(checkedArea)));
        return terrainResult == SweptTerrainProbe.Result.CLEAR;
    }

    private boolean physicalRolloutComplete(ServerLevel server, NativeGroundObservation nativeGround) {
        AirportBlockEntity target = groundAirport(server, groundMission.targetAirport());
        boolean freshSupport = groundObservationTick == server.getGameTime() && nativeGround.fullyGrounded()
                && Objects.equals(nativeGround.physicalId(), groundMission.plan().physicalId());
        return target != null && target.facilities().stream().filter(f -> f.id().equals(groundMission.targetAssignment().runwayId()))
                .anyMatch(f -> RegisteredAircraftGroundMission.rolloutReady(f, view.worldBounds(), freshSupport,
                        view.linearVelocity().length(), nativeGround.angularVelocity().length()));
    }

    private void tickGroundAircraft(ServerLevel server) {
        if (!groundBindingsMatch(server)) { groundFailure(server, "ground_binding_changed", false); return; }
        var phase = groundMission.state().phase();
        if (phase == AircraftGroundMission.Phase.FC_ACTIVE) {
            tickFlightAircraft(server, true); // Includes fix18's exact UUID successor acknowledgement.
            if (paused) return;
        }
        var terminal = flightTerminalState(server);
        if (!terminal.available() || !terminal.linked() || !view.physicalId().equals(terminal.structureId())) {
            groundFailure(server, "ground_control_conflict", false); return;
        }
        if (!terminal.profileValid()) {
            if (phase == AircraftGroundMission.Phase.TAXI_OUT && ++flightPendingTicks < 100) { reason = "flight_profile_pending"; return; }
            groundFailure(server, "flight_profile_invalid", false); return;
        }
        if (!flightFingerprint.equals(terminal.expected().profileFingerprint())) { groundFailure(server, "flight_profile_changed", true); return; }
        if (phase != AircraftGroundMission.Phase.FC_ACTIVE && phase != AircraftGroundMission.Phase.WAIT_FC_RELEASE
                && !groundStartRequested && (terminal.active() || terminal.enabled())) {
            groundFailure(server, "ground_control_conflict", true); return;
        }
        var reference = FlightControlBridge.inspectVehicle(server, worldPosition.offset(flightTerminalOffset));
        if (!reference.available() || !reference.fresh() || !view.physicalId().equals(reference.structureId())) {
            if (phase != AircraftGroundMission.Phase.FC_ACTIVE) groundFailure(server, "flight_state_stale", false);
            return;
        }
        Vec3 referencePosition = new Vec3(reference.x(), reference.y(), reference.z());
        var ownership = groundOwnership(server);
        if (groundStartRequested && phase == AircraftGroundMission.Phase.FC_STARTING
                && !ownership.provesFlight(view.physicalId(), flightTask, flightFingerprint)) {
            if (ownership.foreignLease()) { groundFailure(server, "ground_control_conflict", true); return; }
            if (++flightPendingTicks >= 100) { groundFailure(server, "ground_waiting_fc_lease", false); return; }
            reason = "ground_waiting_fc_lease"; status = ControllerStatus.WAITING_TERRAIN;
            updateTrace(server); recordFlightTelemetry(server); return;
        }
        NativeGroundObservation nativeGround = observeGround(server);
        // The air task continues safely when wheel terrain casts are unavailable far above unloaded terrain.
        if (phase == AircraftGroundMission.Phase.FC_ACTIVE && !nativeGround.available()) return;
        if (!nativeGround.available()) { groundFailure(server, nativeGround.reason(), false); return; }
        boolean needsNeutral=phase!=AircraftGroundMission.Phase.FC_ACTIVE&&phase!=AircraftGroundMission.Phase.WAIT_FC_RELEASE
                &&phase!=AircraftGroundMission.Phase.COMPLETE
                &&!(phase==AircraftGroundMission.Phase.FC_STARTING&&groundNativeRelease!=null);
        if(groundOutputsOwned&&needsNeutral&&(!ownership.provesReleased(view.physicalId())||manualTakeover)){
            groundFailure(server,"ground_control_conflict",manualTakeover||ownership.foreignLease());return;
        }
        if(groundOutputsOwned&&needsNeutral&&!maintainGroundNative(nativeGround.fullyGrounded()&&groundHardwareReady(server),groundNeutralPort(server))){
            stopOwnedGroundOutputs(server,false);waitForGroundNative(server,"ground_waiting_native_neutral");return;
        }
        var lease = RegisteredAircraftGroundMission.lease(ownership, view.physicalId(), flightTask, flightFingerprint, groundOutputsOwned);
        boolean complete = RegisteredAircraftGroundMission.exactCompletion(terminal, view.physicalId(), flightTask, flightFingerprint);
        boolean releasedAfterOwnCancel = groundReleaseRequested && ownership.provesReleased(view.physicalId());
        boolean safeRelease = physicalRolloutComplete(server, nativeGround);
        AirportBlockEntity source = groundAirport(server, groundMission.sourceAirport()), target = groundAirport(server, groundMission.targetAirport());
        boolean groundPhase = phase != AircraftGroundMission.Phase.FC_ACTIVE && phase != AircraftGroundMission.Phase.WAIT_FC_RELEASE;
        boolean departurePhase = switch (phase) { case TAXI_OUT, HOLD_LINE, LINE_UP, FC_STARTING -> true; default -> false; };
        boolean permit = !departurePhase || source.preparedGroundAssignment(groundMission.plan().missionId(), getTransportUuid(),
                groundMission.sourceAssignment()).isPresent();
        GroundRunwayReadiness runwayReadiness = departurePhase
                ? source.groundRunwayReadiness(groundMission.sourceAssignment(),this,view.linearVelocity().length(),groundYawDegrees())
                : GroundRunwayReadiness.BLOCKED;
        boolean aligned = runwayReadiness == GroundRunwayReadiness.READY;
        if (phase == AircraftGroundMission.Phase.FC_STARTING && !groundStartRequested
                && runwayReadiness == GroundRunwayReadiness.WAITING) {
            // Reclaim native quiet immediately, before terrain work/stop commands; never reuse a prior normal-frame witness.
            rearmGroundAfterRunwayWait(ownership.provesReleased(view.physicalId())
                    && nativeGround.fullyGrounded() && groundHardwareReady(server),groundNeutralPort(server));
        }
        boolean wholeStand = target.assignedStandArrival(groundMission.targetAssignment(), this, view.linearVelocity().length(), groundYawDegrees())
                && nativeGround.angularVelocity().length() <= Math.toRadians(2);
        if(groundIngressCandidate!=null){
            var ingressPort=groundIngressPort(server,referencePosition,nativeGround);
            if(groundIngressProof!=null){
                if(groundIngressCandidate.plan()!=groundMission.plan()||!groundIngressShapeFits(referencePosition)
                        ||ingressPort.claimRevision()!=groundIngressClaimRevision||!ingressPort.claimsValid()){
                    groundFailure(server,"ground_recovery_review",false);return;
                }
                if(!ingressPort.fullyLoaded()){
                    groundIngressProof=null;groundIngressScan=null;
                    stopOwnedGroundOutputs(server,false);reason="waiting_ground_ingress";status=ControllerStatus.WAITING_TERRAIN;return;
                }
                if(GroundRecoveryIngress.completed(groundIngressProof,referencePosition))clearGroundIngress();
            }else{
                var ingress=advanceGroundIngress(ingressPort);
                if(ingress==GroundIngressStatus.BLOCKED){groundFailure(server,"ground_recovery_review",false);return;}
                if(ingress!=GroundIngressStatus.READY){
                    // A fresh verified HOLD watchdog remains owner throughout this bounded scan wait.
                    stopOwnedGroundOutputs(server,false);reason="waiting_ground_ingress";status=ControllerStatus.WAITING_TERRAIN;return;
                }
            }
        }
        boolean pathClear = !groundPhase || groundPathClear(server, referencePosition);
        var observation = new AircraftGroundMission.Observation(view.physicalId(), dimensionId(), groundMission.plan().layoutRevision(),
                groundMission.plan().reservationId(), server.getGameTime(), server.getGameTime(), referencePosition, view.worldForward(), view.linearVelocity(),
                nativeGround.angularVelocity().length(), nativeGround.fullyGrounded() ? AircraftGroundMission.Support.CONTACT : AircraftGroundMission.Support.AIRBORNE,
                lease, lease != AircraftGroundMission.Lease.FOREIGN && lease != AircraftGroundMission.Lease.UNKNOWN, manualTakeover,
                pathClear, permit, aligned, groundOutputsNeutral, complete || releasedAfterOwnCancel, safeRelease,
                ownership.provesReleased(view.physicalId()), wholeStand);
        var step = AircraftGroundMission.step(groundMission.plan(), groundMission.state(), observation,groundIngressProof,runwayReadiness);
        if (step.action().outputs() != null && !applyGroundOutputs(server, step.action().outputs())) return;
        if (step.state().phase() == AircraftGroundMission.Phase.NEEDS_REVIEW) { groundFailure(server, step.action().reason(), manualTakeover); return; }
        groundMission = groundMission.withState(step.state());
        flightPhase = step.state().phase().name(); reason = step.action().reason();
        status = "waiting_runway_validation".equals(reason) ? ControllerStatus.WAITING_TERRAIN
                : step.action().outputs() != null ? ControllerStatus.RUNNING : ControllerStatus.WAITING_TERRAIN;
        switch (step.action().handshake()) {
            case ACQUIRE_GROUND -> {
                if (tryAcquireGroundOutputs(ownership.provesReleased(view.physicalId()) && nativeGround.fullyGrounded() && groundHardwareReady(server),groundNeutralPort(server))) {
                    // Actual ground control takes over the existing redstone hold without a brake-zero pulse.
                    parkingBrake().toGroundMission(); parkingHardwareKey = null;
                } else {waitForGroundNative(server,"ground_waiting_native_neutral");return;}
            }
            case START_FC -> {
                if (groundStartRequested || !groundOutputsNeutral || !aligned || !permit || !ownership.provesReleased(view.physicalId())) break;
                if(!nativeReadyAfterGroundRelease(groundOutputsNeutral,groundNeutralPort(server))){waitForGroundNative(server,"ground_waiting_native_release");return;}
                String preflightFailure = fixedWingStartFailure(server);
                if (!preflightFailure.isEmpty()) {
                    if ("fixed_wing_reference_unavailable".equals(preflightFailure)||"flight_state_stale".equals(preflightFailure)) waitForGroundNative(server,preflightFailure);
                    else groundFailure(server, preflightFailure, false);
                    return;
                }
                if (!FlightControlBridge.inspectPower(server, worldPosition.offset(flightTerminalOffset)).ready()) { waitForGroundNative(server,"flight_power_required");return; }
                var freshTerminal=flightTerminalState(server);
                if(!freshTerminal.available()||!freshTerminal.linked()||!freshTerminal.profileValid()||freshTerminal.active()||freshTerminal.enabled()
                        ||!Objects.equals(view.physicalId(),freshTerminal.structureId())
                        ||!flightFingerprint.equals(freshTerminal.expected().profileFingerprint())){groundFailure(server,"ground_control_conflict",true);return;}
                var result = FlightControlBridge.requestAction(server, worldPosition.offset(flightTerminalOffset), FlightControlBridge.Action.START, freshTerminal.expected());
                if (!result.accepted()) { groundFailure(server, result.reason(), false); return; }
                flightTask = result.snapshot().taskId(); groundStartRequested = true; groundOutputsOwned = false; flightPendingTicks = 0;
                clearGroundNativeLease();
                FlightControlBridge.claimOwnedTask(server, worldPosition.offset(flightTerminalOffset), getTransportUuid(), view.physicalId(), result.snapshot());
            }
            case RELEASE_FC -> {
                if (!complete || !safeRelease || !ownership.provesFlight(view.physicalId(), flightTask, flightFingerprint)) break;
                if (!requestAircraftCancel(server)) { groundFailure(server, "ground_control_conflict", false); return; }
                groundReleaseRequested = true; groundOutputsOwned = false;
            }
            case RELEASE_GROUND -> { stopOwnedGroundOutputs(server, true); groundOutputsOwned = false; }
            default -> { }
        }
        if(step.state().phase()==AircraftGroundMission.Phase.COMPLETE)clearGroundNativeLease();
        if (step.action().cargoReady() && groundAssignedArrivalReady(groundMission.targetAirport())) { arrive(server, target); return; }
        FlightMission mission = FlightMissionSavedData.get(server).get(getTransportUuid());
        if (mission != null) { mission.physicalObservation(view.worldPosition(), targetIndex, flightPhase, reason); FlightMissionSavedData.get(server).setDirty(); }
        updateTrace(server); recordFlightTelemetry(server); setChanged();
    }

    private GroundNeutralPort groundNeutralPort(ServerLevel server) {
        if(groundNativePort==null)groundNativePort=new GroundNeutralPort(){
            public FlightControlBridge.GroundNeutralSnapshot request(){return FlightControlBridge.requestGroundNeutral(server,RegisteredVehicleControllerBlockEntity.this,
                    getTransportUuid(),groundMission.plan(),worldPosition.offset(flightTerminalOffset),view.physicalId(),flightFingerprint,
                    active&&!paused&&!manualTakeover&&groundObservationTick==server.getGameTime()
                            &&groundObservation!=null&&groundObservation.fullyGrounded()
                            &&Objects.equals(groundObservation.physicalId(),view.physicalId()));}
            public FlightControlBridge.GroundNeutralRelease release(Object handle){
                BlockPos terminal=flightTerminalOffset==null?null:worldPosition.offset(flightTerminalOffset);
                return FlightControlBridge.releaseGroundNeutral(server,terminal,handle);
            }
            public boolean normalAfter(FlightControlBridge.GroundNeutralRelease barrier){return FlightControlBridge.normalFrameAfterGroundRelease(server,worldPosition.offset(flightTerminalOffset),view.physicalId(),flightFingerprint,barrier);}
        };return groundNativePort;
    }
    private boolean groundNativeAcknowledged(boolean eligible,GroundNeutralPort port){
        int waited=groundNativeWaitTicks;
        if(!eligible||port==null){clearGroundDriveLease();groundNativeWaitTicks=waited;return false;}
        groundNativePort=port;
        try{
            var observed=port.request();
            if(observed==null||!observed.available()||observed.handle()==null||observed.generation()<=0){clearGroundDriveLease();groundNativeWaitTicks=waited;return false;}
            if(groundNativeLease!=null&&groundNativeGeneration!=observed.generation())port.release(groundNativeLease);
            groundNativeLease=observed.handle();groundNativeGeneration=observed.generation();
            boolean fresh=observed.acknowledged()&&observed.observations()>=2
                    &&Double.isFinite(observed.physicsTimeSeconds())&&observed.physicsTimeSeconds()>0
                    &&observed.observedAtNanos()>0&&System.nanoTime()>=observed.observedAtNanos()
                    &&System.nanoTime()-observed.observedAtNanos()<=com.monpai.sailboatmod.compat.flightcontrol.NativeGroundNeutral.ACK_NANOS;
            if(fresh)groundNativeWaitTicks=0;return fresh;
        }catch(RuntimeException unavailable){clearGroundDriveLease();groundNativeWaitTicks=waited;return false;}
    }
    boolean tryAcquireGroundOutputs(boolean eligible,GroundNeutralPort port){
        groundDriveAcquiring=true;
        if(!groundNativeAcknowledged(eligible,port))return false;
        clearGroundHoldLease();
        groundNativeRelease=null;groundOutputsOwned=true;return true;
    }
    /** Called on every controlled ground tick and again at the actual native output-write boundary. */
    boolean maintainGroundNative(boolean eligible,GroundNeutralPort port){
        return groundOutputsOwned&&groundNativeAcknowledged(eligible,port);
    }
    boolean groundNativeAllowsOutputs(AircraftGroundMission.Outputs outputs,boolean eligible,GroundNeutralPort port){
        if(!eligible||!groundOutputsOwned||outputs==null||groundConfiguration==null)return false;
        boolean stopping=outputs.throttleSignal()==groundConfiguration.calibration().neutralThrottleSignal()
                &&outputs.left()==0&&outputs.right()==0&&Math.abs(outputs.steeringWheelDegrees())<1e-6
                &&(outputs.brake()==0||outputs.brake()==15);
        return stopping||maintainGroundNative(true,port);
    }
    /** Pending pre-START validation restores only our native quiet lease; its old release proof is unusable. */
    boolean rearmGroundAfterRunwayWait(boolean eligible,GroundNeutralPort port){
        if(groundStartRequested)return false;
        groundNativeRelease=null;groundOutputsNeutral=false;
        return tryAcquireGroundOutputs(eligible,port);
    }
    boolean nativeReadyAfterGroundRelease(boolean wheelNeutral,GroundNeutralPort port){
        if(!wheelNeutral||port==null)return false;
        groundNativePort=port;
        if(groundNativeRelease==null){
            Object handle=groundNativeLease;groundNativeLease=null;groundNativeGeneration=0;
            try{groundNativeRelease=port.release(handle);}
            catch(RuntimeException unavailable){groundNativeRelease=new FlightControlBridge.GroundNeutralRelease(Double.NaN,-1,System.nanoTime());}
            if(groundNativeRelease==null)groundNativeRelease=new FlightControlBridge.GroundNeutralRelease(Double.NaN,-1,System.nanoTime());
            return false;
        }
        if(!Double.isFinite(groundNativeRelease.physicsTimeSeconds())||groundNativeRelease.physicsTimeSeconds()<0
                ||groundNativeRelease.sampledNanos()<0)return false;
        try{return port.normalAfter(groundNativeRelease);}catch(RuntimeException unavailable){return false;}
    }
    void clearGroundNativeLease(){clearGroundDriveLease();clearGroundHoldLease();}
    private void clearGroundDriveLease(){
        groundDriveAcquiring=false;
        Object handle=groundNativeLease;
        try{if(handle!=null&&groundNativePort!=null)groundNativePort.release(handle);}
        catch(RuntimeException unavailable){/* Removing only our registry handle is independent of world availability. */}
        finally{groundNativeLease=null;groundNativeGeneration=0;groundNativeRelease=null;groundNativeWaitTicks=0;groundNativePort=null;}
    }
    private void waitForGroundNative(ServerLevel server,String waitingReason){
        if(++groundNativeWaitTicks>=100){groundFailure(server,waitingReason,false);return;}
        reason=waitingReason;status=ControllerStatus.WAITING_TERRAIN;updateTrace(server);recordFlightTelemetry(server);setChanged();
    }

    private boolean applyGroundOutputs(ServerLevel server, AircraftGroundMission.Outputs outputs) {
        if (!groundOutputsOwned || !groundHardwareReady(server) || !observeGround(server).fullyGrounded()
                || !groundOwnership(server).provesReleased(view.physicalId())) return false;
        if(!groundNativeAllowsOutputs(outputs,true,groundNeutralPort(server))){
            stopOwnedGroundOutputs(server,false);waitForGroundNative(server,"ground_waiting_native_neutral");return false;
        }
        NativeControlResult applied = PhysicalVehicleBridge.nativeThrottle(this, target(throttleOffset), outputs.throttleSignal());
        if (!applied.success()) { groundFailure(server, applied.reason(), "manual_control".equals(applied.reason())); return false; }
        signals.clear();
        signals.put(ports.get(ControlChannel.LEFT), outputs.left()); signals.put(ports.get(ControlChannel.RIGHT), outputs.right());
        signals.put(ports.get(ControlChannel.BRAKE), outputs.brake()); notifySignals();
        groundOutputsNeutral = outputs.throttleSignal() == groundConfiguration.calibration().neutralThrottleSignal()
                && outputs.left() == 0 && outputs.right() == 0 && outputs.brake() == 0;
        return true;
    }

    private boolean stopOwnedGroundOutputs(ServerLevel server, boolean manual) {
        if (!groundOutputsOwned) return false;
        NativeGroundObservation nativeGround = observeGround(server);
        var decision = RegisteredAircraftGroundMission.stopDecision(true, manual,
                groundMission == null ? null : groundMission.plan().physicalId(), nativeGround, groundOwnership(server),
                server.getGameTime(), groundObservationTick, groundConfiguration == null ? null : groundConfiguration.calibration());
        if (decision.mode() == RegisteredAircraftGroundMission.StopMode.YIELD) {
            // Clear only our redstone values; native FC or a pilot owns the throttle now.
            ownSignalsOff(); groundOutputsOwned = false; groundOutputsNeutral = true; return false;
        }
        if (decision.mode() != RegisteredAircraftGroundMission.StopMode.HOLD) return false;
        var outputs = decision.outputs();
        NativeControlResult result = PhysicalVehicleBridge.nativeThrottle(this, target(throttleOffset), outputs.throttleSignal());
        if (result.success()) {
            signals.clear();
            signals.put(ports.get(ControlChannel.LEFT), 0); signals.put(ports.get(ControlChannel.RIGHT), 0);
            signals.put(ports.get(ControlChannel.BRAKE), outputs.brake()); notifySignals();
            // A paused mission keeps this owned hold; the watchdog revalidates support/authority every tick.
            groundOutputsNeutral = false;
            return true;
        } else if ("manual_control".equals(result.reason())) {
            ownSignalsOff(); groundOutputsOwned = false; groundOutputsNeutral = true; manualTakeover = true;
        }
        return false;
    }

    private void releaseStoppedGroundOutputs(ServerLevel server, boolean manual) {
        boolean held = stopOwnedGroundOutputs(server, manual);
        if (!groundOutputsOwned || !held || view == null) return;
        NativeGroundObservation nativeGround = observeGround(server);
        if (groundObservationTick == server.getGameTime() && nativeGround.fullyGrounded() && groundMission != null
                && Objects.equals(nativeGround.physicalId(), groundMission.plan().physicalId())
                && groundOwnership(server).provesReleased(groundMission.plan().physicalId())
                && view.linearVelocity().length() <= .25 && nativeGround.angularVelocity().length() <= Math.toRadians(2)) {
            // The verified stopped ground hold becomes independent of the soon-to-be-cleared mission.
            parkingBrake().adopt(view.physicalId()); parkingHardwareKey = parkingHardwareKey();
            groundOutputsOwned = false; groundOutputsNeutral = false;
        }
    }

    private void groundFailure(ServerLevel server, String failure, boolean manual) {
        clearGroundIngress();
        groundTerrainWindow.clear();
        clearAirportLoading();
        stopOwnedGroundOutputs(server, manual);
        clearGroundNativeLease();
        manualTakeover |= manual; paused = true;
        status = manual ? ControllerStatus.MANUAL : ControllerStatus.FAULT;
        reason = failure; flightPhase = "GROUND_REVIEW";
        if(!manual)updateGroundHold(server);
        // Retain the known phase/index, reservations and cargo. Never pause/cancel a foreign or airborne task.
        setChanged(); remember(server);
    }

    enum GroundIngressStatus { NONE, WAITING, READY, BLOCKED }
    interface GroundIngressPort {
        boolean boundAndFreshStopped();
        boolean fullyLoaded();
        boolean claimsValid();
        long claimRevision();
        AirportGroundValidation.Result advance(AirportGroundValidation.Scan scan,int budget);
    }
    private GroundRecoveryIngress.Candidate groundIngressCandidate;
    private GroundRecoveryIngress.Proof groundIngressProof;
    private AirportGroundValidation.Scan groundIngressScan;
    private long groundIngressClaimRevision=-1;
    private GroundIngressStatus advanceGroundIngress(GroundIngressPort port) {
        if(groundIngressCandidate==null)return GroundIngressStatus.NONE;
        try{
            // Missing FULL is a wait; preserve the exact candidate and incremental scan identity.
            if(!port.fullyLoaded()){
                if(groundIngressProof!=null){groundIngressProof=null;groundIngressScan=null;}
                return GroundIngressStatus.WAITING;
            }
            long revision=port.claimRevision();
            if(groundIngressClaimRevision<0)groundIngressClaimRevision=revision;
            if(revision<0||revision!=groundIngressClaimRevision||!port.boundAndFreshStopped()||!port.claimsValid())return GroundIngressStatus.BLOCKED;
            if(groundIngressScan==null)groundIngressScan=AirportGroundValidation.ingressScan(groundIngressCandidate.scanStart(),
                    groundIngressCandidate.scanEnd(),groundIngressCandidate.width(),groundIngressCandidate.clearanceHeight());
            var result=port.advance(groundIngressScan,2048);
            if(port.claimRevision()!=groundIngressClaimRevision)return GroundIngressStatus.BLOCKED;
            if(result==AirportGroundValidation.Result.WAITING)return GroundIngressStatus.WAITING;
            if(result==AirportGroundValidation.Result.UNLOADED){groundIngressScan=null;return GroundIngressStatus.WAITING;}
            if(result!=AirportGroundValidation.Result.VALID)return GroundIngressStatus.BLOCKED;
            groundIngressProof=GroundRecoveryIngress.authorize(groundIngressCandidate,new GroundRecoveryIngress.Evidence(
                    port.boundAndFreshStopped(),true,true,port.claimsValid()&&port.claimRevision()==groundIngressClaimRevision,port.fullyLoaded()));
            return groundIngressProof==null?GroundIngressStatus.BLOCKED:GroundIngressStatus.READY;
        }catch(RuntimeException invalid){groundIngressProof=null;return GroundIngressStatus.BLOCKED;}
    }

    private void clearGroundIngress() {
        groundIngressCandidate=null;groundIngressProof=null;groundIngressScan=null;groundIngressClaimRevision=-1;
    }
    private boolean prepareGroundIngress(ServerLevel server) {
        clearGroundIngress();
        if(groundMission==null||groundMission.state().phase()!=AircraftGroundMission.Phase.TAXI_OUT||groundMission.state().pointIndex()!=1)return true;
        var p=groundMission.plan();var ref=FlightControlBridge.inspectVehicle(server,worldPosition.offset(flightTerminalOffset));
        if(!ref.available()||!ref.fresh()||!Objects.equals(p.physicalId(),ref.structureId()))return false;
        Vec3 position=new Vec3(ref.x(),ref.y(),ref.z()),a=p.outbound().getFirst(),delta=p.outbound().get(1).subtract(a).multiply(1,0,1);
        double projection=Math.max(0,Math.min(1,position.subtract(a).multiply(1,0,1).dot(delta)/delta.lengthSqr()));
        if(position.subtract(a.add(delta.scale(projection))).horizontalDistanceSqr()<=p.corridorHalfWidth()*p.corridorHalfWidth())return true;
        AirportBlockEntity source=groundAirport(server,groundMission.sourceAirport());
        var route=source==null?null:source.groundLayout().taxiRoutes().stream()
                .filter(r->r.id().equals(groundMission.sourceAssignment().outboundRouteId())).findFirst().orElse(null);
        if(route==null)return false;
        // A fixed conservative circular envelope fits the original source route, not a wider corridor.
        double envelope=route.width()/2-p.corridorHalfWidth();
        double actualRadius=FlightRoutePlanner.requiredHorizontalDiameter(view.worldBounds(),position)/2;
        double actualHeight=view.worldBounds().maxY-a.y;
        if(!Double.isFinite(actualRadius)||actualRadius>envelope||view.worldBounds().minY<a.y-.25
                ||!Double.isFinite(actualHeight)||actualHeight>route.clearanceHeight())return false;
        groundIngressCandidate=GroundRecoveryIngress.candidate(p,groundMission.state(),position,view.worldForward(),envelope,route.clearanceHeight());
        return groundIngressCandidate!=null&&groundIngressCandidate.width()<=route.width()+1e-7;
    }
    private boolean groundIngressShapeFits(Vec3 reference) {
        if(groundIngressCandidate==null||groundMission==null||groundIngressCandidate.plan()!=groundMission.plan())return false;
        double radius=FlightRoutePlanner.requiredHorizontalDiameter(view.worldBounds(),reference)/2;
        return Double.isFinite(radius)&&radius<=groundIngressCandidate.validationRequest().capabilities().hullRadius()+1e-7
                &&view.worldBounds().minY>=groundIngressCandidate.scanStart().y-.25
                &&view.worldBounds().maxY<=groundIngressCandidate.scanStart().y+groundIngressCandidate.clearanceHeight();
    }
    private GroundIngressPort groundIngressPort(ServerLevel server,Vec3 reference,NativeGroundObservation ground) {
        var authority=com.monpai.sailboatmod.nation.data.NationSavedData.get(server);
        return new GroundIngressPort(){
            public boolean boundAndFreshStopped(){
                if(!groundBindingsMatch(server)||!groundIngressShapeFits(reference)||!ground.available()||!ground.fullyGrounded()
                        ||groundObservationTick!=server.getGameTime()||!groundHardwareReady(server)
                        ||view.linearVelocity().length()>.25||ground.angularVelocity().length()>Math.toRadians(2)
                        ||!groundOwnership(server).provesReleased(view.physicalId())||manualTakeover)return false;
                var initial=groundIngressCandidate.validationRequest().initialPosition();
                return Math.hypot(reference.x-initial.x(),reference.z-initial.z())<=.25;
            }
            public boolean fullyLoaded(){
                var source=groundAirport(server,groundMission.sourceAirport());var target=groundAirport(server,groundMission.targetAirport());
                if(source==null||target==null||!configureAirportLoading(server,source,target))return false;
                loading.update(server,getTransportUuid(),view.physicalId(),view.worldBounds(),groundIngressCandidate.scanEnd());
                return groundIngressCandidate.footprint().stream().allMatch(c->server.getChunkSource().getChunkNow(c.x,c.z)!=null);
            }
            public boolean claimsValid(){
                var source=groundAirport(server,groundMission.sourceAirport());ServerPlayer player;
                try{player=server.getServer().getPlayerList().getPlayer(UUID.fromString(getOwnerUuid()));}catch(IllegalArgumentException invalid){return false;}
                if(player==null||player.serverLevel()!=server||source==null)return false;
                for(var c:groundIngressCandidate.footprint()){
                    var town=com.monpai.sailboatmod.nation.service.TownService.getManagedTownAt(player,
                            new BlockPos(c.getMinBlockX(),(int)groundIngressCandidate.scanStart().y,c.getMinBlockZ()));
                    if(town==null||!town.townId().equals(source.getTownId()))return false;
                }
                return true;
            }
            public long claimRevision(){return authority.claimRevision();}
            public AirportGroundValidation.Result advance(AirportGroundValidation.Scan scan,int budget){
                return scan.advance(budget,p->!server.isOutsideBuildHeight(p)&&server.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)!=null,
                        p->server.getFluidState(p).isEmpty()&&server.getBlockState(p).getCollisionShape(server,p).isEmpty(),
                        p->server.getBlockState(p).isFaceSturdy(server,p,Direction.UP));
            }
        };
    }

    private void resumeGroundMission(ServerLevel server) {
        clearGroundDriveLease();
        groundTerrainWindow.clear();
        if (flightRecovering) blockReloadReplay(server);
        if (flightRecovering || !groundBindingsMatch(server) || !groundArrivalReady()) { reason = "ground_recovery_review"; setChanged(); return; }
        var phase = groundMission.state().phase();
        if (phase == AircraftGroundMission.Phase.FC_ACTIVE || phase == AircraftGroundMission.Phase.WAIT_FC_RELEASE
                || phase == AircraftGroundMission.Phase.COMPLETE || phase == AircraftGroundMission.Phase.NEEDS_REVIEW) {
            // FC cannot restore route progress; explicit landing review never replays its old full card.
            reason = "ground_recovery_review"; setChanged(); return;
        }
        if(!prepareGroundIngress(server)){reason="ground_recovery_review";setChanged();return;}
        var s = groundMission.state();
        groundMission = groundMission.withState(new AircraftGroundMission.State(s.missionId(),s.physicalId(),s.dimensionId(),s.layoutRevision(),
                s.runwayId(),s.standId(),s.reservationId(),s.phase(),s.pointIndex(),0,-1,false));
        // Keep verified stop-HOLD/BRAKE15 through ingress validation and the new DRIVE acknowledgement.
        groundStartRequested = groundReleaseRequested = false; groundOutputsOwned = false; groundOutputsNeutral = !parkingBrake().held();
        flightRecovering = recoveryRequired = manualTakeover = paused = false; active = true;
        flightPhase = s.phase().name(); status = ControllerStatus.RUNNING; reason = ""; setChanged();
    }

    private void invalidateGroundCalibration() {
        resetGroundedFlightReference();
        clearGroundIngress();
        yieldParkingBrake();
        if (groundConfiguration != null) groundConfiguration = RegisteredAircraftGroundMission.Configuration.parse(groundConfiguration.text());
        groundCalibrationBody = null; groundObservationTick = -1;
    }

    private void recordFlightTelemetry(ServerLevel server) {
        if (view == null) return;
        if (manifest.isEmpty()) ShippingTraceService.recordFlightTelemetry(server,getTransportUuid(),"",view.worldPosition().y,flightPhase,"PHYSICAL");
        else for (ShipmentManifestEntry entry:manifest) ShippingTraceService.recordFlightTelemetry(server,getTransportUuid(),entry.shippingOrderId(),view.worldPosition().y,flightPhase,"PHYSICAL");
    }

    private boolean clearFlightTerrain(ServerLevel server) {
        Vec3 sweep = view.linearVelocity().scale(2);
        if (sweep.length()>64) sweep=sweep.normalize().scale(64);
        // The lower skin may touch a runway. Diagnose forward/upper volume without counting its deck.
        AABB hull=view.worldBounds();
        AABB area=new AABB(hull.minX,hull.minY+.2,hull.minZ,hull.maxX,hull.maxY,hull.maxZ).expandTowards(sweep);
        terrainResult=terrainProbe.scan(area,2048,server::hasChunkAt,
                at->server.getBlockState(at).getCollisionShape(server,at).toAabbs().stream()
                        .anyMatch(box->box.move(at.getX(),at.getY(),at.getZ()).intersects(area)));
        return terrainResult==SweptTerrainProbe.Result.CLEAR;
    }

    private void releaseAirportReservation(ServerLevel server) {
        if(railAircraftAutomation()) {
            var rail=railAircraft.mission();
            for(BlockPos pos:new BlockPos[]{sourceTerminal,returnOrigin,destinationTerminal,rail==null?null:rail.source(),rail==null?null:rail.target()})
                if(pos!=null&&server.hasChunkAt(pos)&&server.getBlockEntity(pos) instanceof AirportBlockEntity a)a.releaseReservation(getTransportUuid());
        }
        if (groundMission != null) {
            for (BlockPos position : new BlockPos[] { groundMission.sourceAirport(), groundMission.targetAirport() })
                if (server.hasChunkAt(position) && server.getBlockEntity(position) instanceof AirportBlockEntity airport)
                    airport.releaseReservation(getTransportUuid());
        }
        if(destinationTerminal!=null && server.hasChunkAt(destinationTerminal)
                && server.getBlockEntity(destinationTerminal) instanceof AirportBlockEntity airport)
            airport.releaseReservation(getTransportUuid());
    }

    private boolean clearSweptTerrain(ServerLevel server, Vec3 waypoint) {
        Vec3 toward = waypoint.subtract(view.worldPosition()).multiply(1, 0, 1);
        Vec3 direction =
                (toward.lengthSqr() > 0.01D ? toward.normalize() : view.worldForward())
                        .scale(Math.min(12, Math.max(2, view.linearVelocity().length() * 2)));
        AABB area = view.worldBounds().expandTowards(direction).deflate(0.1D);
        terrainResult =
                navigationTerrain.scan(
                        waypoint.equals(activeRoute.getLast()),
                        area,
                        8192,
                        server::hasChunkAt,
                        at ->
                                server
                                        .getBlockState(at)
                                        .getCollisionShape(server, at)
                                        .toAabbs()
                                        .stream()
                                        .anyMatch(
                                                shape ->
                                                        shape.move(at.getX(), at.getY(), at.getZ())
                                                                .intersects(area)));
        return terrainResult == SweptTerrainProbe.Result.CLEAR;
    }

    private boolean applyCommand(NativeNavigationDriver.Command command) {
        if (throttleOffset != null) {
            BlockEntity target = target(throttleOffset);
            NativeControlResult result =
                    AeronauticsVehicleBridge.nativeThrottle(
                            this,
                            target,
                            NativeNavigationDriver.transmissionSignal(
                                    command.throttle(), transmissionInverted));
            if (!result.success()) {
                fault(result.reason(), "manual_control".equals(result.reason()));
                return false;
            }
        }
        if (steeringOffset != null) {
            NativeControlResult result =
                    AeronauticsVehicleBridge.nativeSteering(
                            this,
                            target(steeringOffset),
                            (command.right() - command.left()) * 2.0F);
            if (!result.success()) {
                fault(result.reason(), "manual_control".equals(result.reason()));
                return false;
            }
        }
        int[] values = {
            command.throttle(),
            command.brake(),
            command.left(),
            command.right(),
            command.forwardGear(),
            command.reverseGear()
        };
        signals.clear();
        for (ControlChannel channel : ControlChannel.values()) {
            Direction side = ports.get(channel);
            if (side != null) signals.merge(side, values[channel.ordinal()], Math::max);
        }
        notifySignals();
        return true;
    }

    private void ownSignalsOff() {
        clearGroundHoldLease();
        parkingBrake().forget(); parkingHardwareKey = null;
        signals.clear();
        notifySignals();
    }

    private void notifySignals() {
        if (level != null && !level.isClientSide)
            level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
    }

    public int signal(Direction queriedSide) {
        return signals.getOrDefault(queriedSide.getOpposite(), 0);
    }

    private void holdNative(boolean manual) {
        manual |= manualTakeover;
        if(manual)clearGroundNativeLease();
        if (transportKind() == Kind.AIR) {
            if (manual || aircraftClass != AircraftClass.FIXED_WING) ownSignalsOff();
            return; // Explicit fixed-wing parking/ground/flight transactions own the output handoff.
        }
        ownSignalsOff();
        if (!manual) {
            NativeControlResult stopped =
                    throttleOffset == null
                            ? NativeControlResult.applied()
                            : AeronauticsVehicleBridge.nativeThrottle(
                                    this,
                                    target(throttleOffset),
                                    NativeNavigationDriver.transmissionSignal(
                                            0, transmissionInverted));
            if ("manual_control".equals(stopped.reason())) {
                manualTakeover = true;
                return;
            }
            if (steeringOffset != null) {
                NativeControlResult steering =
                        AeronauticsVehicleBridge.nativeSteering(this, target(steeringOffset), 0.0F);
                if ("manual_control".equals(steering.reason())) {
                    manualTakeover = true;
                    return;
                }
            }
            if (ControllerRegistration.validControlPorts(ports))
                signals.put(ports.get(ControlChannel.BRAKE), 15);
            notifySignals();
        }
    }

    private void fault(String why, boolean manual) {
        if(railAircraftAutomation()){
            manualTakeover|=manual;paused=true;flightPhase=manualTakeover?"MANUAL":"PAUSED";
            status=manualTakeover?ControllerStatus.MANUAL:ControllerStatus.FAULT;reason=why;
            if(level instanceof ServerLevel server){
                if(view!=null){if(manualTakeover)railAircraft.manualRelease(railContext(server));else railAircraft.park(railContext(server));}
                remember(server);
            }
            setChanged();return;
        }
        clearGroundIngress();
        clearAirportLoading();
        if (groundMission != null && level instanceof ServerLevel groundServer) {
            groundFailure(groundServer, why, manual || manualTakeover); return;
        }
        manual |= manualTakeover;
        if (transportKind() == Kind.AIR && level instanceof ServerLevel server && !manual) {
            if (!requestAircraftPause(server)) manual = true;
        }
        manualTakeover |= manual;
        paused = true;
        flightPhase = manual ? "MANUAL" : "PAUSED";
        status = manual ? ControllerStatus.MANUAL : ControllerStatus.FAULT;
        reason = why;
        if (transportKind() == Kind.AIR && level instanceof ServerLevel server && flightTerminalOffset != null)
            FlightControlBridge.clearOwnedTask(server, worldPosition.offset(flightTerminalOffset), getTransportUuid());
        holdNative(manual);
        if (transportKind() != Kind.AIR) loading.close();
        if (level instanceof ServerLevel server) remember(server);
        setChanged();
    }

    private Direction facing() {
        return getBlockState().getValue(RegisteredVehicleControllerBlock.FACING);
    }

    private BlockEntity target(BlockPos offset) {
        if (level == null || offset == null) return null;
        BlockPos at = worldPosition.offset(offset);
        return level.hasChunkAt(at) ? level.getBlockEntity(at) : null;
    }

    private SelectedCargoInventory cargo() {
        return new SelectedCargoInventory(cargoHandlers());
    }

    private List<IItemHandler> cargoHandlers() {
        List<IItemHandler> handlers = new ArrayList<>();
        Set<IItemHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (!(level instanceof ServerLevel server)) {
            if (!cargoOffsets.isEmpty()) throw new IllegalStateException("Cargo level unavailable");
            return handlers;
        }
        for (BlockPos offset : cargoOffsets) {
            BlockEntity target = target(offset);
            if (target == null || !AeronauticsVehicleBridge.sameStructure(this, target))
                throw new IllegalStateException("Selected cargo is outside the live structure");
            IItemHandler handler =
                    server.getCapability(
                            Capabilities.ItemHandler.BLOCK, target.getBlockPos(), null);
            if (handler == null)
                throw new IllegalStateException("Selected cargo handler unavailable");
            if (seen.add(handler)) handlers.add(handler);
        }
        return handlers;
    }

    private record CargoSnapshot(IItemHandlerModifiable handler, int slot, ItemStack stack) {}

    private static List<CargoSnapshot> snapshotCargo(List<IItemHandler> handlers) {
        List<CargoSnapshot> snapshots = new ArrayList<>();
        for (IItemHandler handler : handlers) {
            if (!(handler instanceof IItemHandlerModifiable mutable))
                throw new IllegalArgumentException(
                        "Selected cargo cannot restore a failed operation");
            int slots = handler.getSlots();
            if (slots < 0 || slots > 4096 || snapshots.size() + slots > 4096)
                throw new IllegalArgumentException("Invalid selected cargo inventory");
            for (int slot = 0; slot < slots; slot++)
                snapshots.add(
                        new CargoSnapshot(mutable, slot, handler.getStackInSlot(slot).copy()));
        }
        return snapshots;
    }

    private void restoreCargo(List<CargoSnapshot> snapshots) {
        RuntimeException failed = null;
        for (CargoSnapshot snapshot : snapshots) {
            try {
                snapshot.handler().setStackInSlot(snapshot.slot(), snapshot.stack().copy());
            } catch (RuntimeException failure) {
                if (failed == null) failed = failure;
            }
        }
        if (failed != null) cargoFailure(failed);
    }

    private void cargoFailure(RuntimeException failure) {
        if (!cargoFaultReported)
            LOGGER.warn(
                    "[PhysicalTransport] Selected cargo unavailable for {}: {}",
                    getTransportUuid(),
                    failure.toString());
        cargoFaultReported = true;
        fault("cargo_unavailable", status == ControllerStatus.MANUAL);
    }

    private List<ItemStack> cargoContents() {
        try {
            List<ItemStack> contents = cargo().contents();
            cargoFaultReported = false;
            return contents;
        } catch (RuntimeException failure) {
            cargoFailure(failure);
            return List.of();
        }
    }

    private CargoLoadReceipt insertCargo(List<ItemStack> values) {
        if (cargoOffsets.isEmpty()) return null;
        List<CargoSnapshot> before = List.of();
        try {
            List<IItemHandler> handlers = cargoHandlers();
            SelectedCargoInventory inventory = new SelectedCargoInventory(handlers);
            before = snapshotCargo(handlers);
            CargoLoadReceipt inserted = inventory.load(values);
            if (inserted == null) return null;
            List<CargoSnapshot> rollback = before;
            cargoFaultReported = false;
            return new CargoLoadReceipt() {
                private boolean closed;

                @Override
                public void commit() {
                    if (!closed) {
                        inserted.commit();
                        closed = true;
                    }
                }

                @Override
                public void rollback() {
                    if (closed) return;
                    closed = true;
                    try {
                        inserted.rollback();
                    } catch (RuntimeException failure) {
                        restoreCargo(rollback);
                        cargoFailure(failure);
                    }
                }
            };
        } catch (RuntimeException failure) {
            restoreCargo(before);
            cargoFailure(failure);
            return null;
        }
    }

    private boolean controlsReady() {
        if(railAircraftAutomation())return view!=null&&view.available()&&registration.registered()&&RailAircraftMotionBridge.available(this);
        if (transportKind() == Kind.AIR) {
            if (!(level instanceof ServerLevel server) || flightTerminalOffset == null || view == null) return false;
            BlockEntity terminal = target(flightTerminalOffset);
            if (terminal == null || !AeronauticsVehicleBridge.sameStructure(this, terminal)) return false;
            var state = flightTerminalState(server);
            return state.available() && state.linked() && view.physicalId().equals(state.structureId());
        }
        return portsConfirmed && ControllerRegistration.validControlPorts(ports);
    }

    /** Published coordinates may be known before the destination BE is loaded. Never probe/guess another terminal. */
    private void resolveAirportDestinationPosition(ServerLevel server) {
        if (transportKind() != Kind.AIR || destinationTerminal != null || routeCatalog.isEmpty()) return;
        String destinationName = returnTrip ? selected().startDockName() : selected().endDockName();
        if (destinationName == null || destinationName.isBlank()) return;
        var matches = AirportSavedData.get(server).airports(dimensionId()).stream()
                .filter(airport -> airport.name().equalsIgnoreCase(destinationName)).toList();
        if (matches.size() == 1) destinationTerminal = matches.getFirst().pos();
    }

    private DockBlockEntity resolveDestination(ServerLevel server) {
        if (!returnTrip && !manifest.isEmpty()) {
            ShippingOrder order =
                    MarketSavedData.get(server)
                            .getShippingOrder(manifest.getFirst().shippingOrderId());
            if (order != null) destinationTerminal = order.targetDockPos();
        }
        if (transportKind() == Kind.AIR) {
            resolveAirportDestinationPosition(server);
            // Air facilities can be hundreds of blocks from the registry; never guess a neighbour.
            if (destinationTerminal == null) return null;
        }
        if (destinationTerminal == null && !activeRoute.isEmpty()) {
            Vec3 end = activeRoute.getLast();
            Collection<BlockPos> terminals =
                    transportKind() == Kind.AIR ? AirportRegistry.get(server)
                            : transportKind() == Kind.LAND ? PostStationRegistry.get(server) : DockRegistry.get(server);
            destinationTerminal =
                    terminals.stream()
                            .filter(server::hasChunkAt)
                            .filter(pos -> server.getBlockEntity(pos) instanceof DockBlockEntity)
                            .filter(pos -> Vec3.atCenterOf(pos).distanceToSqr(end) < 64 * 64)
                            .min(
                                    Comparator.comparingDouble(
                                            pos -> Vec3.atCenterOf(pos).distanceToSqr(end)))
                            .orElse(null);
        }
        return destinationTerminal != null
                        && server.hasChunkAt(destinationTerminal)
                        && server.getBlockEntity(destinationTerminal)
                                instanceof DockBlockEntity dock
                ? dock
                : null;
    }

    private void arrive(ServerLevel server, DockBlockEntity dock) {
        RegisteredAircraftGroundMission completedGroundMission = groundMission;
        var completedRailMission=railAircraft.mission();
        if (transportKind() == Kind.AIR) {
            if (!(dock instanceof AirportBlockEntity airport) || !airport.isCarrierInsideDockZone(this)) return;
            if(railAircraftAutomation()) {
                if(!railAircraft.ready(railContext(server),airport))return;
            } else if (groundMission != null) {
                if (!groundAssignedArrivalReady(airport.getBlockPos())) return;
                releaseStoppedGroundOutputs(server, false);
                if (groundOutputsOwned) return;
            } else if (!requestAircraftCancel(server)) { status=ControllerStatus.FAULT; flightPhase="PARKED_CONTROL_CONFLICT"; return; }
        }
        if(!railAircraftAutomation())holdNative(false);
        boolean shouldReturn = autoReturn || !manifest.isEmpty();
        List<ShipmentManifestEntry> arriving = List.copyOf(manifest);
        if (dock != null && (!manifest.isEmpty() || autoUnload)) {
            CarrierArrivalService.DeliveryResult delivered;
            try {
                delivered =
                        CarrierArrivalService.deliverAtDock(
                                server,
                                dock,
                                this,
                                getAutopilotRouteName(),
                                pendingShipper,
                                selected().startDockName(),
                                selected().endDockName(),
                                departureMillis,
                                missionDistance,
                                List.copyOf(manifest));
            } catch (RuntimeException failure) {
                cargoFailure(failure);
                return;
            }
            if (!manifest.isEmpty() && !delivered.delivered()) {
                fault("cargo_missing", false);
                return;
            }
            manifest.clear();
            manifest.addAll(delivered.remainingManifest());
            for (ShipmentManifestEntry entry : arriving)
                if (!manifest.contains(entry) && !entry.shippingOrderId().isBlank())
                    ShippingTraceService.removeTrace(server, entry.shippingOrderId());
        }
        if (transportKind() == Kind.AIR) {
            if(railAircraftAutomation()&&!railAircraft.complete())return;
            FlightMission mission = FlightMissionSavedData.get(server).get(getTransportUuid());
            if (mission != null) { mission.finishAtAirport(true); FlightMissionSavedData.get(server).setDirty(); }
            releaseAirportReservation(server); flightTask=null; flightPhase="PARKED";
            groundMission = null; groundOutputsOwned = groundStartRequested = groundReleaseRequested = false;
            groundOutputsNeutral = !parkingBrake().held(); invalidGroundMission = false;
            clearAirportPreflight(server);
            flightPending = returnFlightPending = departureFlightPending = false;
            flightRecovering = recoveryRequired = false;
        }
        finishTownVoyage();
        active = false;
        paused = false;
        arrivalTicks = 0;
        clearAirportLoading();
        loading.close();
        if (!manifest.isEmpty()) {
            fault("next_leg_required", false);
            return;
        }
        if (dock != null && shouldReturn && !returnTrip && sourceTerminal != null) {
            if (transportKind() == Kind.AIR) {
                invalidateTownGeometry(); activeRoute.clear(); activeRoute.addAll(FlightRoutePlanner.cruiseForLeg(selected().waypoints(), true));
                returnOrigin = dock.getBlockPos(); returnFlightPending = true; flightPending = false;
                if(railAircraftAutomation()&&completedRailMission!=null) {
                    var from=groundAirport(server,completedRailMission.target());var to=groundAirport(server,completedRailMission.source());
                    var source=from==null?Optional.<GroundAssignment>empty():completedRailMission.targetAssignment().forDirection(from.groundLayout(),from.facilities(),AirportTaxiRoute.Direction.OUTBOUND);
                    var target=to==null?Optional.<GroundAssignment>empty():completedRailMission.sourceAssignment().forDirection(to.groundLayout(),to.facilities(),AirportTaxiRoute.Direction.INBOUND);
                    if(source.isEmpty()||target.isEmpty()){fault("ground_binding_changed",false);return;}
                    nextGroundSourceAssignment=source.orElseThrow();nextGroundTargetAssignment=target.orElseThrow();railAircraft.resetPreparation();
                }
                if (completedGroundMission != null) {
                    AirportBlockEntity originalSource = groundAirport(server, completedGroundMission.sourceAirport());
                    AirportBlockEntity originalTarget = groundAirport(server, completedGroundMission.targetAirport());
                    var returnSource = originalTarget == null ? Optional.<GroundAssignment>empty() : completedGroundMission.targetAssignment()
                            .forDirection(originalTarget.groundLayout(), originalTarget.facilities(), com.monpai.sailboatmod.transport.air.airport.AirportTaxiRoute.Direction.OUTBOUND);
                    var returnTarget = originalSource == null ? Optional.<GroundAssignment>empty() : completedGroundMission.sourceAssignment()
                            .forDirection(originalSource.groundLayout(), originalSource.facilities(), com.monpai.sailboatmod.transport.air.airport.AirportTaxiRoute.Direction.INBOUND);
                    if (returnSource.isEmpty() || returnTarget.isEmpty()) { fault("ground_binding_changed", false); return; }
                    nextGroundSourceAssignment = returnSource.orElseThrow();
                    nextGroundTargetAssignment = returnTarget.orElseThrow();
                }
            } else { invalidateTownGeometry(); Collections.reverse(activeRoute); }
            destinationTerminal = sourceTerminal;
            returnTrip = true;
            targetIndex = 0;
            active = true;
            startTownVoyage(server, dock.getBlockPos(), destinationTerminal);
            status = ControllerStatus.RUNNING;
            setChanged();
            return;
        }
        status = ControllerStatus.IDLE;
        // A completed deadhead now belongs to the airport it actually reached. The idle tick
        // retries pickup after bounded airport validation, including after a server restart.
        if (transportKind() == Kind.AIR && dock != null) sourceTerminal = dock.getBlockPos();
        ShippingTraceService.removeTrace(
                server, ShippingTraceService.manualTraceId(getTransportUuid()));
        remember(server);
        setChanged();
    }

    private void updateTrace(ServerLevel server) {
        double progress =
                activeRoute.size() < 2 ? 0 : targetIndex / (double) (activeRoute.size() - 1);
        if (manifest.isEmpty()) {
            String id = ShippingTraceService.manualTraceId(getTransportUuid());
            if (com.monpai.sailboatmod.market.logistics.ShippingTraceSavedData.get(server)
                            .getTrace(id)
                    == null)
                ShippingTraceService.createOrUpdateManualTrace(
                        server,
                        getTransportUuid(),
                        activeRoute,
                        getOwnerUuid(),
                        "",
                        transportMode(),
                        transportKind() == Kind.AIR ? "IN_TRANSIT" : "SAILING",
                        selected().startDockName(),
                        selected().endDockName(),
                        view.worldPosition().x,
                        view.worldPosition().z,
                        ShippingTraceService.cargoFromItems(cargoContents()));
            ShippingTraceService.updateLivePositionProgressAndSpeed(
                    server,
                    id,
                    view.worldPosition().x,
                    view.worldPosition().z,
                    targetIndex,
                    progress,
                    view.linearVelocity().length() / 20);
        } else
            for (ShipmentManifestEntry entry : manifest) {
                ShippingTraceService.updateStatus(
                        server,
                        entry.shippingOrderId(),
                        status == ControllerStatus.WAITING_TERRAIN || status == ControllerStatus.FAULT || paused ? "STUCK"
                                : transportKind() == Kind.AIR ? "IN_TRANSIT" : "SAILING");
                ShippingTraceService.updateLivePositionProgressAndSpeed(
                        server,
                        entry.shippingOrderId(),
                        view.worldPosition().x,
                        view.worldPosition().z,
                        targetIndex,
                        progress,
                        view.linearVelocity().length() / 20);
            }
    }

    private RouteDefinition selected() {
        return routeCatalog.isEmpty()
                ? new RouteDefinition("", List.of())
                : routeCatalog.get(Math.max(0, Math.min(selectedRoute, routeCatalog.size() - 1)));
    }

    private void remember(ServerLevel server) {
        if (!registration.registered()) return;
        RegisteredTransportSavedData.get(server)
                .put(
                        new RegisteredTransportSavedData.Entry(
                                getTransportUuid(),
                                dimensionId(),
                                worldPosition,
                                view == null ? registration.physicalId() : view.physicalId(),
                                getOwnerUuid(),
                                active
                                        && (!paused || transportKind() == Kind.AIR)
                                        && bindingValid
                                        && view != null
                                        && view.available()));
    }

    @Override
    public String providerId() {
        return "aeronautics";
    }

    @Override
    public String dimensionId() {
        return level == null ? "minecraft:overworld" : level.dimension().location().toString();
    }

    @Override
    public Kind transportKind() {
        return registration.kind();
    }

    @Override
    public int getTransportId() {
        return runtimeId;
    }

    @Override
    public UUID getTransportUuid() {
        return registration.registrationId();
    }

    @Override
    public Component getTransportName() {
        return Component.literal(
                vehicleName.isBlank()
                        ? (transportKind() == Kind.AIR ? "Aircraft"
                                : transportKind() == Kind.LAND
                                ? "Registered vehicle"
                                : "Registered vessel")
                        : vehicleName);
    }

    @Override
    public Vec3 transportPosition() {
        return view == null ? Vec3.atCenterOf(worldPosition) : view.worldPosition();
    }

    private void updatePhysicalView(PhysicalVehicleView resolved) {
        view = resolved;
        if (view != null && view.available() && "physical_unavailable".equals(reason)) {
            reason = "";
        }
    }

    @Override
    public AABB transportBounds() {
        return view == null ? LogisticsCarrier.super.transportBounds() : view.worldBounds();
    }

    @Override
    public boolean isTransportAlive() {
        return !isRemoved()
                && bindingValid
                && registration.registered()
                && view != null
                && view.available();
    }

    @Override
    public boolean isAutopilotActive() {
        return active;
    }

    @Override
    public boolean isAutopilotPaused() {
        return paused;
    }

    @Override
    public String getSelectedRouteName() {
        return selected().name();
    }

    @Override
    public String getAutopilotRouteName() {
        return selected().name();
    }

    @Override
    public String getOwnerName() {
        return ownerName;
    }

    @Override
    public String getOwnerUuid() {
        return registration.ownerUuid();
    }

    @Override
    public int getRentalPrice() {
        return rentalPrice;
    }

    @Override
    public void setRentalPrice(int price) {
        rentalPrice = Math.max(-1, Math.min(1000000, price));
        setChanged();
    }

    @Override
    public boolean isAvailableForRent() {
        return rentalPrice >= 0;
    }

    @Override
    public boolean isOwnedBy(Player player) {
        return player != null && player.getUUID().toString().equals(getOwnerUuid());
    }

    @Override
    public void setPendingShipper(String name) {
        pendingShipper = name == null ? ownerName : name;
    }

    @Override
    public void setPendingMarketDelivery(
            String name, String uuid, String purchase, String shipping) {
        setPendingShipmentManifest(
                List.of(
                        new ShipmentManifestEntry(
                                "", ItemStack.EMPTY, purchase, shipping, uuid, name, 0)));
    }

    @Override
    public void clearPendingMarketDelivery() {
        manifest.clear();
        setChanged();
    }

    @Override
    public void setPendingShipmentManifest(List<ShipmentManifestEntry> values) {
        manifest.clear();
        if (values != null) manifest.addAll(values);
        autoReturn = false;
        autoUnload = false;
        setChanged();
    }

    @Override
    public List<ShipmentManifestEntry> getPendingShipmentManifest() {
        return List.copyOf(manifest);
    }

    @Override
    public void setAllowNonOrderAutoReturn(boolean value) {
        autoReturn = value;
        setChanged();
    }

    @Override
    public void setAllowNonOrderAutoUnload(boolean value) {
        autoUnload = value;
        setChanged();
    }

    @Override
    public boolean hasCargo() {
        try {
            boolean present = !cargo().contents().isEmpty();
            cargoFaultReported = false;
            return present;
        } catch (RuntimeException failure) {
            cargoFailure(failure);
            return true; // Unknown inventory must never be treated as an empty hold.
        }
    }

    @Override
    public boolean canLoadCargo(List<ItemStack> values) {
        if (!manifest.isEmpty() || cargoOffsets.isEmpty()) return false;
        try {
            return cargo().canLoad(values);
        } catch (RuntimeException failure) {
            cargoFailure(failure);
            return false;
        }
    }

    @Override
    public boolean loadCargo(List<ItemStack> values) {
        CargoLoadReceipt receipt = insertCargo(values);
        if (receipt == null) return false;
        receipt.commit();
        return true;
    }

    @Override
    public CargoLoadReceipt loadCargoForDispatch(List<ItemStack> values) {
        return manifest.isEmpty() ? insertCargo(values) : null;
    }

    @Override
    public List<ItemStack> unloadAllCargo() {
        if (cargoOffsets.isEmpty()) return List.of();
        List<CargoSnapshot> before = List.of();
        try {
            List<IItemHandler> handlers = cargoHandlers();
            SelectedCargoInventory inventory = new SelectedCargoInventory(handlers);
            before = snapshotCargo(handlers);
            List<ItemStack> removed = inventory.drain();
            cargoFaultReported = false;
            return removed;
        } catch (RuntimeException failure) {
            restoreCargo(before);
            cargoFailure(failure);
            return List.of();
        }
    }

    @Override public void setDispatchDestination(BlockPos terminal) { dispatchDestination = terminal == null ? null : terminal.immutable(); }

    @Override
    public void setRouteCatalog(List<RouteDefinition> routes, int preferred, BlockPos dockPos) {
        routeCatalog.clear();
        invalidateTownGeometry(); activeRoute.clear();
        if (routes != null) {
            for (RouteDefinition route : routes) {
                if (route == null || route.waypoints().size() < 2) {
                    routeCatalog.clear();
                    selectedRoute = 0;
                    sourceTerminal = dockPos;
                    destinationTerminal = null;
                    fault("missing_route", false);
                    return;
                }
                // The server validated this route. Keep every waypoint and catalog index intact.
                routeCatalog.add(route.copy());
            }
        }
        selectedRoute = Math.max(0, Math.min(preferred, routeCatalog.size() - 1));
        sourceTerminal = dockPos;
        destinationTerminal = null;
        setChanged();
    }

    @Override
    public void setLandTransportTask(
            LandTransportNetworkService.LandRoutePlan plan,
            boolean shouldReturn,
            CarriageEntity.TransportTaskKind kind) {
        if (plan != null) {
            destinationTerminal = plan.targetStationPos();
            autoReturn = shouldReturn;
        }
    }

    @Override
    public boolean startAutopilotFromRouteStart() {
        if (!(level instanceof ServerLevel server)
                || !isTransportAlive()
                || !controlsReady()
                || routeCatalog.isEmpty()
                || active
                || paused) return false;
        if (sourceTerminal == null
                || !server.hasChunkAt(sourceTerminal)
                || !(server.getBlockEntity(sourceTerminal) instanceof DockBlockEntity dock)) return false;
        if (transportKind() == Kind.AIR) {
            // Geometry can prove that this request starts at a parked aircraft while the bounded
            // world scan is still pending. Complete that scan before motion or native profile submission.
            if (!(dock instanceof AirportBlockEntity airport)) return false;
            if(railAircraftAutomation()) {
                if(!airport.railDepartureGeometry(this,view,groundYawDegrees(),
                        RailAircraftMotionBridge.angularSpeed(this).orElse(Double.NaN)))return false;
            } else if (aircraftClass == AircraftClass.FIXED_WING && hasGroundLayout(airport)) {
                if (!airport.isCarrierInsideDockZone(this)) return false;
            } else if (airport.facilities().stream().noneMatch(facility ->
                    facility.supports(aircraftClass) && facility.isParked(view.worldBounds(), view.linearVelocity().length()))) return false;
        } else if (!dock.isCarrierInsideDockZone(this)) return false;
        if (transportKind() == Kind.AIR) {
            if(railAircraftAutomation()){railAircraft.resetPreparation();railAircraft.allowResume();}
            else {parkingBrake().rearm();updateParkingBrake(server,true);updateGroundHold(server);}
            clearAirportPreflight(server);
        }
        invalidateTownGeometry(); activeRoute.clear();
        activeRoute.addAll(selected().waypoints());
        targetIndex = 0;
        destinationTerminal = dispatchDestination;
        dispatchDestination = null;
        returnTrip = false;
        returnFlightPending = departureFlightPending = false;
        if (transportKind() == Kind.AIR && !prepareAircraftFlight(server, dock)) {
            // Returning true commits the accepted dispatch, including its manifest. A caller that
            // rolls cargo back after false can never leave an asynchronous empty flight behind.
            if (!queueAirportDeparture(server)) return false;
        }
        manualTakeover = false;
        paused = false;
        active = true;
        departureMillis = System.currentTimeMillis();
        missionDistance = selected().routeLengthMeters();
        loading.update(
                server,
                getTransportUuid(),
                view.physicalId(),
                view.worldBounds(),
                activeRoute.getFirst());
        status = departureFlightPending ? ControllerStatus.WAITING_TERRAIN : ControllerStatus.RUNNING;
        if (!departureFlightPending) reason = "";
        arrivalTicks = stalledTicks = 0;
        lastProgressPosition = null;
        startTownVoyage(server, sourceTerminal, destinationTerminal);
        remember(server);
        setChanged();
        return true;
    }

    @Override
    public void controlAutopilot(Player player, SailboatEntity.AutopilotControlAction action) {
        if (!canManage(player)) return;
        switch (action) {
            case START -> startAutopilotFromRouteStart();
            case PAUSE -> pause(false);
            case RESUME -> resume();
            case STOP -> stop();
            default -> {}
        }
    }

    private boolean requestAircraftPause(ServerLevel server) {
        if (!ownsInstalledFlightProfile(server)) { reason = "flight_control_conflict"; return false; }
        var state = flightTerminalState(server);
        if (!state.available() || (!flightPending && !flightRecovering && flightTask != null && !Objects.equals(flightTask,state.taskId()))) {
            reason = "flight_control_conflict"; return false;
        }
        var action = state.active() || "PAUSED".equals(state.phase()) ? FlightControlBridge.Action.PAUSE : FlightControlBridge.Action.CANCEL;
        var result = FlightControlBridge.requestAction(server, worldPosition.offset(flightTerminalOffset),action,state.expected());
        if (!result.accepted()) { reason = result.reason(); return false; }
        FlightControlBridge.clearOwnedTask(server, worldPosition.offset(flightTerminalOffset), getTransportUuid());
        return true;
    }

    private void pause(boolean manual) {
        if (level instanceof ServerLevel) { townVoyage().clearMotion(); townVoyageDirty = true; }
        if(railAircraftAutomation()) {
            manualTakeover|=manual;paused=true;
            if(level instanceof ServerLevel server&&view!=null){
                if(manualTakeover)airSurfaces.neutral(surfaceContext());else airSurfaces.pause(surfaceContext());
                if(manualTakeover)railAircraft.manualRelease(railContext(server));
                else if(!railAircraft.hasMission())railAircraft.park(railContext(server));
                remember(server);
            }
            status=manualTakeover?ControllerStatus.MANUAL:ControllerStatus.PAUSED;flightPhase=manualTakeover?"MANUAL":"PAUSED";setChanged();return;
        }
        resetGroundedFlightReference();
        NativeTakeoffAssist.suppress(this);
        if(groundMission==null)clearGroundNativeLease();
        clearAirportLoading();
        if (groundMission != null && level instanceof ServerLevel groundServer) {
            groundFailure(groundServer, manual ? "manual_control" : "ground_recovery_review", manual);
            return;
        }
        if (transportKind() == Kind.AIR && level instanceof ServerLevel server && flightTerminalOffset != null)
            FlightControlBridge.clearOwnedTask(server, worldPosition.offset(flightTerminalOffset), getTransportUuid());
        if (transportKind() == Kind.AIR && (flightRecovering || recoveryRequired)) {
            // Recovery already relinquished (or is still proving) the terminal. A pause button
            // must not bypass that proof and cancel a stranger's unvalidated replacement card.
            setChanged(); return;
        }
        manual |= manualTakeover || status == ControllerStatus.MANUAL;
        if (transportKind() == Kind.AIR && !manual && !airportPreflightPending()
                && level instanceof ServerLevel server && !requestAircraftPause(server)) {
            setChanged(); return;
        }
        manualTakeover |= manual;
        paused = true;
        status = manual ? ControllerStatus.MANUAL : ControllerStatus.PAUSED;
        if (transportKind() == Kind.AIR) flightPhase = manual ? "MANUAL" : "PAUSED";
        holdNative(manual);
        if (transportKind() != Kind.AIR) loading.close();
        if (level instanceof ServerLevel server) remember(server);
        setChanged();
    }

    private void resume() {
        if (level instanceof ServerLevel) { townVoyage().clearMotion(); townVoyageDirty = true; }
        if(railAircraftAutomation()) {
            if(!railAircraft.canResume(airportPreflightPending())){
                reason="rail_recovery_review";setChanged();return;
            }
            if(level instanceof ServerLevel server&&railAircraft.mission()!=null&&!railAircraft.mission().completed()&&!airportPreflightPending()) {
                var fuel=checkCurrentAircraftFuel(server);if(!fuel.success()){fuelFailure(server,fuel.reason());return;}
            }
            railAircraft.allowResume();manualTakeover=false;active=true;paused=false;recoveryRequired=flightRecovering=false;
            status=ControllerStatus.RUNNING;reason="";setChanged();return;
        }
        if (groundMission != null && level instanceof ServerLevel groundServer) { resumeGroundMission(groundServer); return; }
        if (transportKind() == Kind.AIR && level instanceof ServerLevel server && (flightRecovering || recoveryRequired)) {
            resumeRecoveredFlight(server);
            return;
        }
        if (!activeRoute.isEmpty() && isTransportAlive() && controlsReady()) {
            if (transportKind() == Kind.AIR && !airportPreflightPending() && level instanceof ServerLevel server) {
                var state = flightTerminalState(server);
                if (state.profileValid() && !flightFingerprint.equals(state.expected().profileFingerprint())) {
                    reason="flight_profile_changed"; setChanged(); return;
                }
                if (!state.profileValid() || "READY".equals(state.phase())) {
                    flightPending=true; flightPendingTicks=0;
                } else {
                    FlightControlBridge.Action action = FlightResumePolicy.action(state.phase());
                    if (action == null) { reason="flight_control_conflict"; setChanged(); return; }
                    var result = FlightControlBridge.requestAction(server, worldPosition.offset(flightTerminalOffset),action,state.expected());
                    if (!result.accepted()) { reason=result.reason(); setChanged(); return; }
                    flightTask=result.snapshot().taskId(); flightPending=false; flightRecovering=false;
                    FlightControlBridge.claimOwnedTask(server, worldPosition.offset(flightTerminalOffset),
                            getTransportUuid(), view.physicalId(), result.snapshot());
                }
            }
            manualTakeover = false;
            active = true;
            paused = false;
            stalledTicks = 0;
            lastProgressPosition = null;
            status = ControllerStatus.RUNNING;
            reason = "";
            setChanged();
        }
    }

    private void resumeRecoveredFlight(ServerLevel server) {
        if (flightRecovering) blockReloadReplay(server);
        if (flightRecovering || !isTransportAlive()) return;
        FlightMission mission = FlightMissionSavedData.get(server).get(getTransportUuid());
        if (mission == null || !mission.active() || mission.representation() != FlightMission.Representation.PHYSICAL
                || !mission.controller().equals(worldPosition)) {
            reason = "flight_recovery_identity_conflict"; setChanged(); return;
        }
        DockBlockEntity targetAirport = resolveDestination(server);
        BlockPos origin = returnTrip ? returnOrigin : sourceTerminal;
        AirportBlockEntity departure = origin != null && server.hasChunkAt(origin)
                && server.getBlockEntity(origin) instanceof AirportBlockEntity airport ? airport : null;
        boolean atDestination = targetAirport instanceof AirportBlockEntity airport && airport.isCarrierInsideDockZone(this);
        boolean atDeparture = departure != null && departure.isCarrierInsideDockZone(this);
        switch (FlightRecoveryPolicy.resume(atDeparture, atDestination)) {
            case REQUIRE_LANDING -> { reason = "flight_recovery_land_at_airport"; setChanged(); }
            case ARRIVE -> {
                // Keep the recovery flag until arrival/cargo delivery has actually succeeded.
                arrive(server, targetAirport);
            }
            case PREFLIGHT -> {
                recoveryRequired = false;
                manualTakeover = false;
                paused = false;
                if (returnTrip) returnFlightPending = true;
                else departureFlightPending = true;
                flightPending = false;
                flightPhase = "PREFLIGHT";
                reason = "airport_unavailable";
                status = ControllerStatus.WAITING_TERRAIN;
                setChanged();
            }
        }
    }

    private boolean requestAircraftCancel(ServerLevel server) {
        if (!ownsInstalledFlightProfile(server)) { reason="flight_control_conflict"; return false; }
        var state=flightTerminalState(server);
        if (!state.available() || (!flightPending && state.profileValid() && !flightFingerprint.equals(state.expected().profileFingerprint()))
                || (flightTask!=null && state.taskId()!=null && !Objects.equals(flightTask,state.taskId()) && !flightRecovering)) {
            reason="flight_control_conflict"; return false;
        }
        var result=FlightControlBridge.requestAction(server,worldPosition.offset(flightTerminalOffset),FlightControlBridge.Action.CANCEL,state.expected());
        if (!result.accepted()) { reason=result.reason(); return false; }
        FlightControlBridge.clearOwnedTask(server, worldPosition.offset(flightTerminalOffset), getTransportUuid());
        return true;
    }

    private boolean ownsInstalledFlightProfile(ServerLevel server) {
        if (flightTerminalOffset == null) return false;
        BlockPos terminal = worldPosition.offset(flightTerminalOffset);
        var installed = FlightControlBridge.inspectInstalledProfile(server, terminal);
        return FlightRecoveryPolicy.guard(FlightControlBridge.sameStructure(server, worldPosition, terminal),
                installed.available(), flightFingerprint, installed.fingerprint()) == FlightRecoveryPolicy.Guard.CANCEL_REPLAY;
    }

    private void stop() {
        if(railAircraftAutomation()) {
            if(level instanceof ServerLevel server) {
                airSurfaces.neutral(surfaceContext());
                if(view!=null){railAircraft.cancel(railContext(server));if(manualTakeover)railAircraft.manualRelease(railContext(server));}
                releaseAirportReservation(server);clearAirportPreflight(server);
                var journal=FlightMissionSavedData.get(server).get(getTransportUuid());
                if(journal!=null){journal.cancelPhysical();FlightMissionSavedData.get(server).setDirty();}
            }
            clearAirportLoading();clearGroundNativeLease();yieldParkingBrake();
            finishTownVoyage();
            active=paused=false;flightPending=departureFlightPending=returnFlightPending=false;
            flightRecovering=recoveryRequired=false;flightTask=null;groundMission=null;invalidGroundMission=false;
            nextGroundSourceAssignment=nextGroundTargetAssignment=null;
            groundOutputsOwned=groundStartRequested=groundReleaseRequested=false;groundOutputsNeutral=true;
            status=manualTakeover?ControllerStatus.MANUAL:ControllerStatus.IDLE;flightPhase="IDLE";reason="";loading.close();setChanged();return;
        }
        resetGroundedFlightReference();
        NativeTakeoffAssist.suppress(this);
        clearGroundIngress();
        clearAirportLoading();
        boolean manual = status == ControllerStatus.MANUAL;
        if (groundMission != null && level instanceof ServerLevel groundServer) {
            releaseStoppedGroundOutputs(groundServer, manual);
            if (groundOutputsOwned) { groundFailure(groundServer, "ground_contact_not_proven", false); return; }
        }
        clearGroundNativeLease();
        if (transportKind() == Kind.AIR && level instanceof ServerLevel server) {
            if (flightTerminalOffset != null)
                FlightControlBridge.clearOwnedTask(server, worldPosition.offset(flightTerminalOffset), getTransportUuid());
            if (flightRecovering) {
                blockReloadReplay(server);
                if (flightRecovering) { setChanged(); return; }
                manual = false;
            }
            if (!manual && !airportPreflightPending() && (active || flightPending) && !requestAircraftCancel(server)) {
                if (parkingBrake().held()) { paused=true; status=ControllerStatus.FAULT; }
                setChanged(); return;
            }
            FlightMission mission = FlightMissionSavedData.get(server).get(getTransportUuid());
            if (mission != null) { mission.cancelPhysical(); FlightMissionSavedData.get(server).setDirty(); }
            clearAirportPreflight(server);
            releaseAirportReservation(server); flightPending = returnFlightPending = departureFlightPending = false;
            flightRecovering = recoveryRequired = false; flightTask = null; flightPhase = "IDLE";
            groundMission = null; invalidGroundMission = false; groundStartRequested = groundReleaseRequested = false;
            nextGroundSourceAssignment = nextGroundTargetAssignment = null;
        }
        finishTownVoyage();
        active = false;
        paused = false;
        status = ControllerStatus.IDLE;
        holdNative(manual);
        if (level instanceof ServerLevel parkedServer && !manual) updateParkingBrake(parkedServer,true);
        loading.close();
        if (level instanceof ServerLevel server) remember(server);
        setChanged();
    }

    public boolean canManage(Player player) {
        return player != null
                && registration.canManage(
                        player.getUUID(),
                        player.hasPermissions(2),
                        player.position().distanceToSqr(transportPosition()));
    }

    @Override
    public void openStorage(Player player) {
        if (level instanceof ServerLevel)
            updatePhysicalView(AeronauticsVehicleBridge.resolve(this, facing()).orElse(null));
        if (player instanceof ServerPlayer serverPlayer && canManage(player))
            ModNetwork.CHANNEL.send(
                    serverPlayer, new OpenRegisteredVehicleControllerPacket(screenData()));
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server && registration.registered()) {
            TransportRegistry.remember(server, getTransportUuid());
            if (flightRecovering) blockReloadReplay(server);
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel server) {
            TownVoyagePublisher.markUnloaded(server, townCarrierKey());
            TownVoyagePublisher.releaseGroundGeometry(server, townCarrierKey());
        }
        townVoyage().invalidateGeometry();
        NativeTakeoffAssist.remove(this);
        clearGroundIngress();
        clearGroundNativeLease();
        yieldParkingBrake();
        clearAirportLoading();
        if (level instanceof ServerLevel server) {
            if (transportKind() == Kind.AIR && flightTerminalOffset != null)
                FlightControlBridge.clearOwnedTask(server, worldPosition.offset(flightTerminalOffset), getTransportUuid());
            TransportRegistry.unregister(server, getTransportUuid());
            if (!ControllerRegistration.preserveLoadingOnRemoval(
                    RegisteredTransportSavedData.isServerStopping(server.getServer()),
                    active,
                    paused && transportKind() != Kind.AIR)) loading.close();
        }
        super.setRemoved();
    }

    /** Called only by actual block replacement, never ordinary chunk unload or server shutdown. */
    public void onPermanentRemoval() {
        finishTownVoyage();
        clearAirportLoading();
        if (!(level instanceof ServerLevel server)) return;
        if(railAircraftAutomation()&&view!=null){airSurfaces.neutral(surfaceContext());railAircraft.permanentRelease(railContext(server));active=false;}
        if (transportKind() == Kind.AIR && flightTerminalOffset != null)
            FlightControlBridge.clearOwnedTask(server, worldPosition.offset(flightTerminalOffset), getTransportUuid());
        boolean manual = status == ControllerStatus.MANUAL;
        if (transportKind() == Kind.AIR) {
            stopOwnedGroundOutputs(server, manual);
            clearAirportPreflight(server);
            boolean released = !active || (!manual && requestAircraftCancel(server));
            FlightMission mission = FlightMissionSavedData.get(server).get(getTransportUuid());
            if (mission != null) {
                if (released) mission.cancelPhysical();
                else mission.requireRecovery("controller_removed:" + reason);
                FlightMissionSavedData.get(server).setDirty();
            }
            if (!released) LOGGER.warn("Aircraft {} controller removed while external flight control remains: {}",getTransportUuid(),reason);
            releaseAirportReservation(server);
        }
        active = false;
        paused = false;
        bindingValid = false;
        try {
            RegisteredTransportSavedData.get(server)
                    .removeController(
                            getTransportUuid(),
                            () -> {
                                try {
                                    holdNative(manual);
                                } finally {
                                    ownSignalsOff();
                                }
                            },
                            loading::close);
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "[PhysicalTransport] Controller removal cleanup failed for {}: {}",
                    getTransportUuid(),
                    failure.toString());
        } finally {
            TransportRegistry.forget(server, getTransportUuid());
        }
    }

    public boolean handleAction(
            ServerPlayer player, ControllerAction action, String value, int index) {
        updatePhysicalView(AeronauticsVehicleBridge.resolve(this, facing()).orElse(null));
        if (!(level instanceof ServerLevel server)||player==null||player.level()!=level) return false;
        boolean nearby=canManage(player);
        if(!nearby&&(action!=ControllerAction.BIND_AIR_SURFACE||!isOwnedBy(player)||!canPickAirSurface(player,value)))return false;
        try {
            switch (action) {
                case REFRESH -> {}
                case BIND_AIR_SURFACE -> {
                    if(!canPickAirSurface(player,value)||active&&!paused)return false;
                    var binding=AircraftSurfaceActionRequest.bind(value,worldPosition);railAircraft.park(railContext(server));
                    var result=airSurfaces.bind(surfaceContext(),binding);
                    if(!result.success()){reason=result.reason();setChanged();return false;}
                }
                case REMOVE_AIR_SURFACE -> {
                    if(!railAircraftAutomation()||view==null||active&&!paused)return false;
                    var key=AircraftSurfaceActionRequest.key(value,worldPosition);railAircraft.park(railContext(server));
                    var result=airSurfaces.hold(surfaceContext(),key.role(),key.localOffset());
                    if(!result.success()){reason=result.reason();setChanged();return false;}
                }
                case CLEAR_AIR_SURFACE_BINDING -> {
                    if(!railSurfaceClearReady(server))return false;
                    var key=AircraftSurfaceActionRequest.key(value,worldPosition);railAircraft.park(railContext(server));
                    var result=airSurfaces.clear(surfaceContext(),key.role(),key.localOffset());
                    if(!result.success()){reason=result.reason();setChanged();return false;}
                }
                case SET_AIRCRAFT_CLASS -> {
                    if (transportKind() != Kind.AIR || active || !manifest.isEmpty()) return false;
                    var nextClass=AircraftClass.valueOf(value);
                    if(railAircraftAutomation()&&nextClass!=aircraftClass&&view!=null){airSurfaces.neutral(surfaceContext());railAircraft.permanentRelease(railContext(server));}
                    aircraftClass = nextClass; invalidateGroundCalibration();
                }
                case SET_FLIGHT_TERMINAL -> {
                    if (transportKind() != Kind.AIR || active) return false;
                    BlockPos offset = parseOffset(value);
                    if (target(offset) == null || !AeronauticsVehicleBridge.sameStructure(this, target(offset))) return false;
                    var terminalState = FlightControlBridge.inspect(server, worldPosition.offset(offset));
                    if (!terminalState.available() || !terminalState.linked()) { reason = terminalState.reason(); return false; }
                    flightTerminalOffset = offset; invalidateGroundCalibration();
                }
                case ADD_GROUND_WHEEL -> {
                    if (transportKind() != Kind.AIR || active || !manifest.isEmpty()) return false;
                    BlockPos offset = parseOffset(value); BlockEntity wheel = target(offset);
                    if (groundWheelOffsets.size() >= 32 || wheel == null || !PhysicalVehicleBridge.sameStructure(this, wheel)) return false;
                    NativeGroundObservation observation = PhysicalVehicleBridge.groundObservation(this, List.of(wheel));
                    if (!observation.available() || observation.wheels().size() != 1 || observation.wheels().getFirst().tireRadius() <= 0) return false;
                    if (!groundWheelOffsets.contains(offset)) { groundWheelOffsets.add(offset); invalidateGroundCalibration(); }
                }
                case REMOVE_GROUND_WHEEL -> {
                    if (transportKind() != Kind.AIR || active || !manifest.isEmpty()) return false;
                    if (groundWheelOffsets.remove(parseOffset(value))) invalidateGroundCalibration();
                }
                case SET_GROUND_CALIBRATION -> {
                    if (transportKind() != Kind.AIR || active || !manifest.isEmpty()) return false;
                    groundConfiguration = RegisteredAircraftGroundMission.Configuration.parse(value); groundCalibrationBody = null;
                    resetGroundedFlightReference();
                }
                case CONFIRM_GROUND_CALIBRATION -> {
                    if (transportKind() != Kind.AIR || active || groundConfiguration == null || view == null || !view.available()
                            || !groundPortsReady() || !nativeGroundThrottle(target(throttleOffset)) || !PhysicalVehicleBridge.sameStructure(this, target(throttleOffset))
                            || groundWheelOffsets.size() < 3 || !observeGround(server).fullyGrounded()) return false;
                    groundConfiguration = groundConfiguration.confirmed(); groundCalibrationBody = view.physicalId();
                    resetGroundedFlightReference();
                }
                case CLEAR_GROUND_CALIBRATION -> {
                    if (transportKind() != Kind.AIR || active) return false;
                    invalidateGroundCalibration();
                }
                case REGISTER -> {
                    updatePhysicalView(AeronauticsVehicleBridge.resolve(this, facing()).orElse(null));
                    LogisticsCarrier duplicate =
                            TransportRegistry.resolve(server, getTransportUuid());
                    if (duplicate != null && duplicate != this) return false;
                    if (view == null
                            || !view.available()
                            || !RegisteredTransportSavedData.get(server)
                                    .claim(dimensionId(), view.physicalId(), getTransportUuid())
                            || !registration.register(view.physicalId(), view.mass(), true)) {
                        reason = "registration_failed";
                        return false;
                    }
                    bindingValid = true;
                    status = active ? ControllerStatus.PAUSED : ControllerStatus.IDLE;
                    TransportRegistry.register(server, this);
                    remember(server);
                }
                case UNREGISTER -> {
                    if (active || !manifest.isEmpty() || hasCargo()) return false;
                    stop();
                    if(railAircraftAutomation()&&view!=null){airSurfaces.neutral(surfaceContext());railAircraft.permanentRelease(railContext(server));}
                    registration.unregister();
                    TransportRegistry.forget(server, getTransportUuid());
                    RegisteredTransportSavedData.get(server).forget(getTransportUuid());
                    ownSignalsOff();
                    status = ControllerStatus.UNREGISTERED;
                }
                case RENAME ->
                        vehicleName =
                                value.strip().substring(0, Math.min(64, value.strip().length()));
                case ADD_CARGO -> {
                    if (active || !manifest.isEmpty() || hasCargo()) return false;
                    BlockPos offset = parseOffset(value);
                    BlockEntity target = target(offset);
                    if (cargoOffsets.size() >= 32
                            || target == null
                            || (view != null
                                    && !AeronauticsVehicleBridge.sameStructure(this, target))
                            || server.getCapability(
                                            Capabilities.ItemHandler.BLOCK,
                                            target.getBlockPos(),
                                            null)
                                    == null) return false;
                    if (!cargoOffsets.contains(offset)) cargoOffsets.add(offset);
                }
                case REMOVE_CARGO -> {
                    if (active || !manifest.isEmpty() || hasCargo()) return false;
                    cargoOffsets.remove(parseOffset(value));
                }
                case SET_THROTTLE_TARGET, SET_STEERING_TARGET -> {
                    if (active) return false;
                    BlockPos offset = parseOffset(value);
                    if (target(offset) == null
                            || (view != null
                                    && !AeronauticsVehicleBridge.sameStructure(
                                            this, target(offset)))) return false;
                    if (action == ControllerAction.SET_THROTTLE_TARGET) throttleOffset = offset;
                    else steeringOffset = offset;
                    invalidateGroundCalibration();
                }
                case CLEAR_NATIVE_TARGETS -> {
                    if (active) return false;
                    throttleOffset = null;
                    steeringOffset = null; invalidateGroundCalibration();
                }
                case SET_PORT -> {
                    if (active) return false;
                    String[] parts = value.split(":");
                    ports.put(ControlChannel.valueOf(parts[0]), Direction.valueOf(parts[1]));
                    portsConfirmed = false; invalidateGroundCalibration();
                    ownSignalsOff();
                }
                case CONFIRM_PORTS -> {
                    if (active) return false;
                    if (transportKind() == Kind.AIR) {
                        portsConfirmed = true;
                        portsConfirmed = groundPortsReady();
                    } else portsConfirmed = ControllerRegistration.validControlPorts(ports);
                    if (!portsConfirmed) {
                        ownSignalsOff();
                        reason = "configure_power";
                        setChanged();
                        return false;
                    }
                }
                case TOGGLE_INVERTED -> {
                    if (active) return false;
                    transmissionInverted = !transmissionInverted;
                }
                case SET_SPEED -> {
                    if (active) return false;
                    double speed = Double.parseDouble(value);
                    if (!Double.isFinite(speed) || speed < 0.1 || speed > 30) return false;
                    maxSpeed = speed;
                }
                case TOGGLE_TAKEOFF_ASSIST -> {
                    if (transportKind() != Kind.AIR || aircraftClass != AircraftClass.FIXED_WING) return false;
                    takeoffAssistConfig = takeoffAssistConfig().withEnabled(!takeoffAssistConfig().enabled());
                    updateTakeoffAssist();
                    if (takeoffAssistConfig.enabled()) NativeTakeoffAssist.arm(this);
                    else NativeTakeoffAssist.suppress(this);
                }
                case SET_TAKEOFF_ASSIST_ACCELERATION -> {
                    if (transportKind() != Kind.AIR || aircraftClass != AircraftClass.FIXED_WING) return false;
                    double acceleration = Double.parseDouble(value);
                    if (!Double.isFinite(acceleration) || acceleration < 0 || acceleration > 20) return false;
                    takeoffAssistConfig = takeoffAssistConfig().withAcceleration(acceleration);
                    updateTakeoffAssist();
                }
                case TOGGLE_REVERSE -> allowReverse = !allowReverse;
                case BIND_NEAREST_TERMINAL -> {
                    if (active) return false;
                    if (transportKind() == Kind.AIR && aircraftClass == AircraftClass.FIXED_WING) {
                        if (!bindParkedStandAirport(server)) return false;
                        break;
                    }
                    Collection<BlockPos> terminals =
                            transportKind() == Kind.AIR ? AirportRegistry.get(server)
                                    : transportKind() == Kind.LAND ? PostStationRegistry.get(server) : DockRegistry.get(server);
                    sourceTerminal =
                            terminals.stream()
                                    .filter(server::hasChunkAt)
                                    .filter(
                                            pos ->
                                                    Vec3.atCenterOf(pos)
                                                                    .distanceToSqr(
                                                                            transportPosition())
                                                            < 64 * 64)
                                    .min(
                                            Comparator.comparingDouble(
                                                    pos ->
                                                            Vec3.atCenterOf(pos)
                                                                    .distanceToSqr(
                                                                            transportPosition())))
                                    .orElse(null);
                }
                case IMPORT_ROUTES -> {
                    if (active) return false;
                    ItemStack book = player.getMainHandItem();
                    if (!(book.getItem() instanceof TransportRouteBook))
                        book = player.getOffhandItem();
                    if (!(book.getItem() instanceof TransportRouteBook)) return false;
                    setRouteCatalog(RouteBookItem.getRoutes(book), 0, sourceTerminal);
                }
                case SELECT_ROUTE -> {
                    if (active) return false;
                    selectedRoute = Math.max(0, Math.min(index, routeCatalog.size() - 1));
                }
                case START -> {
                    if (!startAutopilotFromRouteStart()) {
                        if(!railAircraftAutomation()||reason.isBlank())reason = "start_not_ready";
                        return false;
                    }
                }
                case PAUSE -> pause(false);
                case RESUME -> resume();
                case STOP -> stop();
                case TOGGLE_PARKING_BRAKE -> {
                    if (transportKind()!=Kind.AIR || aircraftClass!=AircraftClass.FIXED_WING || active || groundMission!=null) return false;
                    if (!parkingBrake().released()) parkingBrake().ownerRelease(parkingOutputs(server));
                    else {
                        boolean wasManual=manualTakeover;
                        manualTakeover=false; parkingBrake().rearm(); updateParkingBrake(server,true); updateGroundHold(server);
                        if (!parkingBrake().held()) manualTakeover |= wasManual;
                    }
                }
                case TOGGLE_AUTO_RETURN -> {
                    if(railAircraftAutomation()&&(active||paused))return false;
                    autoReturn = !autoReturn;
                }
                case TOGGLE_AUTO_UNLOAD -> autoUnload = !autoUnload;
            }
            if(railAircraftAutomation()&&EnumSet.of(ControllerAction.SET_SPEED,ControllerAction.SELECT_ROUTE,
                    ControllerAction.IMPORT_ROUTES,ControllerAction.BIND_NEAREST_TERMINAL,ControllerAction.TOGGLE_AUTO_RETURN).contains(action)) {
                aircraftFuel.invalidate();fuelReturnKey="";fuelReturnPath=null;railAircraft.resetPreparation();
            }
            setChanged();
            return true;
        } catch (IllegalArgumentException | IndexOutOfBoundsException invalid) {
            reason = "invalid_setting";
            return false;
        }
    }

    private static BlockPos parseOffset(String value) {
        String[] parts = value.strip().split("[ ,]+");
        if (parts.length != 3) throw new IllegalArgumentException();
        int x = Integer.parseInt(parts[0]),
                y = Integer.parseInt(parts[1]),
                z = Integer.parseInt(parts[2]);
        if (Math.abs((long) x) > 64 || Math.abs((long) y) > 64 || Math.abs((long) z) > 64)
            throw new IllegalArgumentException();
        return new BlockPos(x, y, z);
    }

    /** The pick is near the real bearing on the owner's current body, even when the wing is far from the controller. */
    private boolean canPickAirSurface(ServerPlayer player,String value) {
        if(!railAircraftAutomation()||player==null||player.level()!=level||view==null||!view.available()
                ||!bindingValid||!registration.registered()||(!isOwnedBy(player)&&!player.hasPermissions(2)))return false;
        try {
            var binding=AircraftSurfaceActionRequest.bind(value,worldPosition);BlockPos pos=worldPosition.offset(binding.localOffset());
            if(!level.hasChunkAt(pos))return false;var bearing=level.getBlockEntity(pos);
            if(bearing==null||!AeronauticsVehicleBridge.sameStructure(this,bearing))return false;
            // Editing an existing row is managed at the controller; a new selection must be made near its bearing.
            if(canManage(player)&&airSurfaces.bindings().stream().anyMatch(b->b.role()==binding.role()&&b.localOffset().equals(binding.localOffset())))return true;
            var actual=AeronauticsVehicleBridge.resolve(bearing,facing());
            return actual.isPresent()&&actual.orElseThrow().physicalId().equals(view.physicalId())
                    &&player.position().distanceToSqr(actual.orElseThrow().worldPosition())<=64;
        }catch(IllegalArgumentException failure){return false;}
    }
    private boolean railSurfaceClearReady(ServerLevel server) {
        if(!railAircraftAutomation()||active||paused||!manifest.isEmpty()||view==null||!view.available())return false;
        var context=railContext(server);
        for(BlockPos pos:new BlockPos[]{sourceTerminal,destinationTerminal}) {
            var airport=pos==null?null:groundAirport(server,pos);
            if(airport!=null&&airport.railDepartureGeometry(this,view,context.yaw(),context.angularSpeed()))return true;
        }
        reason="surface_park_before_clear";return false;
    }

    public ControllerScreenData screenData() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", getTransportName().getString());
        tag.putString("Owner", ownerName);
        tag.putString("Kind", transportKind().name());
        tag.putString("AircraftClass", aircraftClass.name());
        tag.putBoolean("AirRail",railAircraftAutomation());
        tag.put("AirSurfaces",airSurfaces.screenData(getBlockPos(),surfaceContext()));
        tag.put("AirFuel",aircraftFuel.cachedScreenData());
        tag.putString("AirRailForward",facing().getName());
        tag.putBoolean("TakeoffAssistEnabled", takeoffAssistConfig().enabled());
        tag.putDouble("TakeoffAssistAcceleration", takeoffAssistConfig().acceleration());
        tag.putString("TakeoffAssistStatus", NativeTakeoffAssist.status(this));
        tag.putString("FlightPhase", flightPhase);
        tag.putString("FlightTerminal", flightTerminalOffset == null ? "-" : flightTerminalOffset.toShortString());
        tag.putString("ExecutionMode", "PHYSICAL");
        tag.putString("HybridStatus", "sable_custody_transaction_missing");
        ListTag groundWheels = new ListTag();
        for (BlockPos offset : groundWheelOffsets) groundWheels.add(StringTag.valueOf(offset.toShortString()));
        tag.put("GroundWheels", groundWheels);
        tag.putString("GroundCalibration", groundConfiguration == null ? "" : groundConfiguration.text());
        tag.putBoolean("GroundCalibrated", groundConfiguration != null && groundConfiguration.calibration().liveVerified()
                && view != null && Objects.equals(groundCalibrationBody, view.physicalId()));
        tag.putString("GroundCapability", groundConfiguration == null || !groundConfiguration.calibration().liveVerified()
                ? "ground_not_calibrated" : groundWheelOffsets.size() < 3 ? "ground_wheels_required"
                : level instanceof ServerLevel groundServer && groundHardwareReady(groundServer) ? "" : "ground_hardware_required");
        tag.putString("ParkingBrake", parkingBrake().held() ? "HOLDING" : parkingBrake().released() ? "RELEASED" : "WAITING");
        tag.putBoolean("ParkingBrakeReleased", parkingBrake().released());
        tag.putString("GroundPhase", groundMission == null ? "" : groundMission.state().phase().name());
        tag.putInt("GroundPoint", groundMission == null ? 0 : groundMission.state().pointIndex());
        tag.putString("SourceStand", groundMission == null ? "" : groundMission.sourceAssignment().standId());
        tag.putString("TargetStand", groundMission == null ? "" : groundMission.targetAssignment().standId());
        if (transportKind() == Kind.AIR && level instanceof ServerLevel server && flightTerminalOffset != null) {
            var flight = FlightControlBridge.inspectVehicle(server,worldPosition.offset(flightTerminalOffset));
            tag.putString("FlightComputerMode",flight.airframeMode());
            tag.putBoolean("FlightStateFresh",flight.fresh());
            tag.putBoolean("FlightComputerWorking",flight.computerWorking());
        }
        tag.putString("Status", status.name());
        tag.putString("Reason", reason);
        tag.putBoolean("Registered", registration.registered());
        tag.putString("AircraftClass", aircraftClass.name());
        tag.putString("FlightPhase", flightPhase);
        tag.putString("FlightFingerprint", flightFingerprint);
        tag.putString("DepartureFacility", departureFacility); tag.putString("ArrivalFacility", arrivalFacility);
        tag.putBoolean("FlightPending", flightPending);
        tag.putBoolean("ReturnFlightPending", returnFlightPending);
        tag.putBoolean("DepartureFlightPending", departureFlightPending);
        tag.putBoolean("RecoveryRequired", recoveryRequired);
        if (airportPreflightAttempt != null) tag.putUUID("AirportPreflightAttempt", airportPreflightAttempt);
        if (returnOrigin != null) tag.putLong("ReturnOrigin", returnOrigin.asLong());
        if (flightTask != null) tag.putUUID("FlightTask", flightTask);
        if (flightTerminalOffset != null) tag.putLong("FlightTerminalOffset", flightTerminalOffset.asLong());
        tag.putBoolean("Active", active);
        tag.putBoolean("Paused", paused);
        tag.putBoolean("ManualTakeover", manualTakeover);
        tag.putBoolean("PortsConfirmed", portsConfirmed);
        tag.putBoolean("Inverted", transmissionInverted);
        tag.putBoolean("AllowReverse", allowReverse);
        tag.putBoolean("AutoReturn", autoReturn);
        tag.putBoolean("AutoUnload", autoUnload);
        tag.putDouble("MaxSpeed", maxSpeed);
        tag.putInt("Target", targetIndex);
        tag.putInt("PointCount", activeRoute.size());
        tag.putInt("SelectedRoute", selectedRoute);
        tag.putString(
                "Position",
                String.format(
                        java.util.Locale.ROOT,
                        "%.1f, %.1f, %.1f",
                        transportPosition().x,
                        transportPosition().y,
                        transportPosition().z));
        if (view != null) {
            tag.putString("PhysicalId", view.physicalId().toString());
            tag.putDouble("Mass", view.mass());
            tag.putString(
                    "Size",
                    String.format(
                            java.util.Locale.ROOT,
                            "%.1f × %.1f × %.1f",
                            view.worldBounds().getXsize(),
                            view.worldBounds().getYsize(),
                            view.worldBounds().getZsize()));
        }
        tag.putString("Terminal", sourceTerminal == null ? "-" : sourceTerminal.toShortString());
        tag.putString(
                "ThrottleTarget", throttleOffset == null ? "-" : throttleOffset.toShortString());
        tag.putString(
                "SteeringTarget", steeringOffset == null ? "-" : steeringOffset.toShortString());
        ListTag cargo = new ListTag();
        for (BlockPos offset : cargoOffsets) cargo.add(StringTag.valueOf(offset.toShortString()));
        tag.put("Cargo", cargo);
        ListTag routes = new ListTag();
        for (RouteDefinition route : routeCatalog) routes.add(StringTag.valueOf(route.name()));
        tag.put("Routes", routes);
        CompoundTag portTag = new CompoundTag();
        ports.forEach((channel, side) -> portTag.putString(channel.name(), side.name()));
        tag.put("Ports", portTag);
        tag.putInt("ManifestCount", manifest.size());
        return new ControllerScreenData(worldPosition, getTransportUuid(), tag);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        townVoyage().writeTo(tag);
        tag.put("RailAircraft",railAircraft.save());
        tag.put("AirSurfaces",airSurfaces.save());
        tag.put("Registration", registration.save());
        tag.putBoolean("TakeoffAssistEnabled", takeoffAssistConfig().enabled());
        tag.putDouble("TakeoffAssistAcceleration", takeoffAssistConfig().acceleration());
        tag.putBoolean("ParkingBrakeReleased",parkingBrake().released());
        tag.putLongArray("GroundWheelOffsets", groundWheelOffsets.stream().mapToLong(BlockPos::asLong).toArray());
        if (groundConfiguration != null) {
            tag.putString("GroundCalibrationValues", groundConfiguration.text());
            tag.putBoolean("GroundCalibrationConfirmed", groundConfiguration.calibration().liveVerified());
        }
        if (groundCalibrationBody != null) tag.putUUID("GroundCalibrationBody", groundCalibrationBody);
        if (groundMission != null) tag.put("AircraftGroundMission", groundMission.save());
        if (nextGroundSourceAssignment != null) tag.put("NextGroundSourceAssignment", nextGroundSourceAssignment.save());
        if (nextGroundTargetAssignment != null) tag.put("NextGroundTargetAssignment", nextGroundTargetAssignment.save());
        tag.putBoolean("InvalidGroundMission", invalidGroundMission);
        tag.putString("Name", vehicleName);
        tag.putString("OwnerName", ownerName);
        tag.putBoolean("PortsConfirmed", portsConfirmed);
        tag.putBoolean("Inverted", transmissionInverted);
        tag.putBoolean("AllowReverse", allowReverse);
        tag.putBoolean("AutoReturn", autoReturn);
        tag.putBoolean("AutoUnload", autoUnload);
        tag.putString("AircraftClass", aircraftClass.name());
        tag.putString("FlightPhase", flightPhase);
        tag.putString("FlightFingerprint", flightFingerprint);
        tag.putString("DepartureFacility", departureFacility); tag.putString("ArrivalFacility", arrivalFacility);
        tag.putBoolean("FlightPending", flightPending);
        tag.putBoolean("ReturnFlightPending", returnFlightPending);
        tag.putBoolean("DepartureFlightPending", departureFlightPending);
        tag.putBoolean("RecoveryRequired", recoveryRequired);
        if (airportPreflightAttempt != null) tag.putUUID("AirportPreflightAttempt", airportPreflightAttempt);
        if (returnOrigin != null) tag.putLong("ReturnOrigin", returnOrigin.asLong());
        if (flightTask != null) tag.putUUID("FlightTask", flightTask);
        if (flightTerminalOffset != null) tag.putLong("FlightTerminalOffset", flightTerminalOffset.asLong());
        tag.putBoolean("Active", active);
        tag.putBoolean("Paused", paused);
        tag.putBoolean("ReturnTrip", returnTrip);
        tag.putBoolean("ManualTakeover", manualTakeover);
        tag.putString("Status", status.name());
        tag.putString("Reason", reason);
        tag.putDouble("MaxSpeed", maxSpeed);
        tag.putInt("RentalPrice", rentalPrice);
        tag.putInt("SelectedRoute", selectedRoute);
        tag.putInt("Target", targetIndex);
        tag.putLong("Departure", departureMillis);
        tag.putDouble("Distance", missionDistance);
        tag.putString("Shipper", pendingShipper);
        if (sourceTerminal != null) tag.putLong("Source", sourceTerminal.asLong());
        if (destinationTerminal != null) tag.putLong("Destination", destinationTerminal.asLong());
        if (throttleOffset != null) tag.putLong("ThrottleOffset", throttleOffset.asLong());
        if (steeringOffset != null) tag.putLong("SteeringOffset", steeringOffset.asLong());
        tag.putLongArray(
                "CargoOffsets", cargoOffsets.stream().mapToLong(BlockPos::asLong).toArray());
        CompoundTag portTag = new CompoundTag();
        ports.forEach((channel, side) -> portTag.putString(channel.name(), side.name()));
        tag.put("Ports", portTag);
        RouteNbtUtil.writeRoutes(tag, "Routes", routeCatalog);
        RouteNbtUtil.writeRoutes(
                tag,
                "ActiveRoute",
                activeRoute.isEmpty() ? List.of() : List.of(new RouteDefinition("", activeRoute)));
        ListTag entries = new ListTag();
        for (ShipmentManifestEntry entry : manifest) entries.add(entry.save());
        tag.put("Manifest", entries);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        townVoyage().readFrom(tag);
        townVoyageDirty = true;
        resetGroundedFlightReference();
        NativeTakeoffAssist.remove(this);
        takeoffAssistConfig = new TakeoffAssistConfig(tag.getBoolean("TakeoffAssistEnabled"),
                tag.contains("TakeoffAssistAcceleration", Tag.TAG_ANY_NUMERIC)
                        ? tag.getDouble("TakeoffAssistAcceleration") : TakeoffAssistConfig.DEFAULT.acceleration());
        clearGroundIngress();
        clearGroundNativeLease();
        if (transportKind() == Kind.AIR && level instanceof ServerLevel server && flightTerminalOffset != null)
            FlightControlBridge.clearOwnedTask(server, worldPosition.offset(flightTerminalOffset), getTransportUuid());
        super.loadAdditional(tag, provider);
        groundWheelOffsets.clear();
        for (long raw : tag.getLongArray("GroundWheelOffsets")) {
            BlockPos offset = BlockPos.of(raw);
            if (groundWheelOffsets.size() < 32 && Math.abs((long) offset.getX()) <= 64 && Math.abs((long) offset.getY()) <= 64
                    && Math.abs((long) offset.getZ()) <= 64 && !groundWheelOffsets.contains(offset)) groundWheelOffsets.add(offset);
        }
        groundConfiguration = null;
        try {
            if (tag.contains("GroundCalibrationValues", Tag.TAG_STRING)) {
                groundConfiguration = RegisteredAircraftGroundMission.Configuration.parse(tag.getString("GroundCalibrationValues"));
                if (tag.getBoolean("GroundCalibrationConfirmed")) groundConfiguration = groundConfiguration.confirmed();
            }
        } catch (IllegalArgumentException invalid) { groundConfiguration = null; }
        groundCalibrationBody = tag.hasUUID("GroundCalibrationBody") ? tag.getUUID("GroundCalibrationBody") : null;
        groundMission = tag.contains("AircraftGroundMission", Tag.TAG_COMPOUND)
                ? RegisteredAircraftGroundMission.loadOptional(tag.getCompound("AircraftGroundMission")).orElse(null) : null;
        invalidGroundMission = tag.getBoolean("InvalidGroundMission") || tag.contains("AircraftGroundMission") && groundMission == null;
        nextGroundSourceAssignment = nextGroundTargetAssignment = null;
        try {
            if (tag.contains("NextGroundSourceAssignment") || tag.contains("NextGroundTargetAssignment")) {
                nextGroundSourceAssignment = GroundAssignment.load(tag.getCompound("NextGroundSourceAssignment"));
                nextGroundTargetAssignment = GroundAssignment.load(tag.getCompound("NextGroundTargetAssignment"));
            }
        } catch (IllegalArgumentException invalid) { invalidGroundMission = true; }
        groundOutputsOwned = groundStartRequested = groundReleaseRequested = false; groundOutputsNeutral = true;
        parkingBrake().restoreReleased(tag.getBoolean("ParkingBrakeReleased")); parkingHardwareKey = null;
        groundObservationTick = -1;
        if (tag.contains("Registration"))
            registration = ControllerRegistration.load(tag.getCompound("Registration"));
        try { aircraftClass = AircraftClass.valueOf(tag.getString("AircraftClass")); }
        catch (IllegalArgumentException ignored) { aircraftClass = AircraftClass.AIRSHIP; }
        flightPhase = tag.getString("FlightPhase"); flightFingerprint = tag.getString("FlightFingerprint");
        departureFacility = tag.getString("DepartureFacility"); arrivalFacility = tag.getString("ArrivalFacility"); flightPending = tag.getBoolean("FlightPending");
        // FC task UUIDs are runtime leases and change after chunk/server reload.
        flightTask = null;
        returnFlightPending = tag.getBoolean("ReturnFlightPending");
        departureFlightPending = tag.getBoolean("DepartureFlightPending");
        recoveryRequired = tag.getBoolean("RecoveryRequired");
        airportPreflightAttempt = tag.hasUUID("AirportPreflightAttempt") ? tag.getUUID("AirportPreflightAttempt") : null;
        returnOrigin = tag.contains("ReturnOrigin") ? BlockPos.of(tag.getLong("ReturnOrigin")) : null;
        railAircraft=tag.contains("RailAircraft",Tag.TAG_COMPOUND)?RegisteredAircraftRailController.load(tag.getCompound("RailAircraft")):
                RegisteredAircraftRailController.load(new CompoundTag());
        airSurfaces=RegisteredAircraftSurfaces.load(tag.contains("AirSurfaces",Tag.TAG_COMPOUND)?tag.getCompound("AirSurfaces"):new CompoundTag());
        aircraftFuel=new RegisteredAircraftFuelController();fuelReturnKey="";fuelReturnPath=null;fuelHealthTick=Long.MIN_VALUE;
        if(tag.contains("AirSurfaces")&&!tag.contains("AirSurfaces",Tag.TAG_COMPOUND)) {
            var malformed=new CompoundTag();malformed.putBoolean("Invalid",true);malformed.put("Raw",tag.get("AirSurfaces").copy());
            airSurfaces=RegisteredAircraftSurfaces.load(malformed);
        }
        flightRecovering = transportKind() == Kind.AIR && !railAircraftAutomation() && !knownGroundIntent() && FlightRecoveryPolicy.needsReloadGuard(
                tag.getBoolean("Active"), departureFlightPending, returnFlightPending);
        flightTerminalOffset = tag.contains("FlightTerminalOffset") ? BlockPos.of(tag.getLong("FlightTerminalOffset")) : null;
        vehicleName = tag.getString("Name");
        ownerName = tag.getString("OwnerName");
        portsConfirmed = tag.getBoolean("PortsConfirmed");
        transmissionInverted = tag.getBoolean("Inverted");
        allowReverse = tag.getBoolean("AllowReverse");
        autoReturn = tag.getBoolean("AutoReturn");
        autoUnload = tag.getBoolean("AutoUnload");
        active = tag.getBoolean("Active");
        paused = tag.getBoolean("Paused");
        returnTrip = tag.getBoolean("ReturnTrip");
        try {
            status = ControllerStatus.valueOf(tag.getString("Status"));
        } catch (IllegalArgumentException ignored) {
            status = ControllerStatus.UNLOADED;
        }
        manualTakeover = tag.getBoolean("ManualTakeover") || status == ControllerStatus.MANUAL;
        reason = tag.getString("Reason");
        double speed = tag.getDouble("MaxSpeed");
        maxSpeed = Double.isFinite(speed) && speed >= 0.1 && speed <= 30 ? speed : 4;
        rentalPrice = tag.contains("RentalPrice") ? tag.getInt("RentalPrice") : -1;
        selectedRoute = tag.getInt("SelectedRoute");
        targetIndex = tag.getInt("Target");
        departureMillis = tag.getLong("Departure");
        missionDistance = tag.getDouble("Distance");
        pendingShipper = tag.getString("Shipper");
        sourceTerminal = tag.contains("Source") ? BlockPos.of(tag.getLong("Source")) : null;
        destinationTerminal =
                tag.contains("Destination") ? BlockPos.of(tag.getLong("Destination")) : null;
        throttleOffset =
                tag.contains("ThrottleOffset") ? BlockPos.of(tag.getLong("ThrottleOffset")) : null;
        steeringOffset =
                tag.contains("SteeringOffset") ? BlockPos.of(tag.getLong("SteeringOffset")) : null;
        cargoOffsets.clear();
        for (long raw : tag.getLongArray("CargoOffsets"))
            if (cargoOffsets.size() < 32) cargoOffsets.add(BlockPos.of(raw));
        CompoundTag portTag = tag.getCompound("Ports");
        ports.clear();
        for (ControlChannel channel : ControlChannel.values())
            try {
                ports.put(channel, Direction.valueOf(portTag.getString(channel.name())));
            } catch (IllegalArgumentException ignored) {
            }
        if (transportKind() != Kind.AIR && !ControllerRegistration.validControlPorts(ports)) {
            portsConfirmed = false;
            paused = true;
            status = ControllerStatus.FAULT;
            reason = "configure_power";
        }
        routeCatalog.clear();
        routeCatalog.addAll(RouteNbtUtil.readRoutes(tag, "Routes"));
        invalidateTownGeometry(); activeRoute.clear();
        List<RouteDefinition> savedRoute = RouteNbtUtil.readRoutes(tag, "ActiveRoute");
        if (!savedRoute.isEmpty()) activeRoute.addAll(savedRoute.getFirst().waypoints());
        if (!railAircraftAutomation() && (groundMission != null || invalidGroundMission) && active) {
            paused = true; recoveryRequired = true; status = ControllerStatus.FAULT; reason = "ground_recovery_review";
        }
        if(railAircraftAutomation()) {
            flightRecovering=false;
            if(railAircraft.invalid()||active&&!railAircraft.hasMission()&&!airportPreflightPending()){
                paused=true;recoveryRequired=true;status=ControllerStatus.FAULT;reason="rail_recovery_review";
            } else if(railAircraft.hasMission())recoveryRequired=false;
        }
        manifest.clear();
        for (Tag raw : tag.getList("Manifest", Tag.TAG_COMPOUND))
            manifest.add(ShipmentManifestEntry.load((CompoundTag) raw));
    }
    /** Optional observation state is initialized independently of native controller state. */
    private TownCarrierVoyageTracker townVoyage() {
        if (townVoyage == null) townVoyage = new TownCarrierVoyageTracker();
        return townVoyage;
    }

    private CarrierKey townCarrierKey() {
        return new CarrierKey("registered", dimensionId(), getTransportUuid());
    }

    private void invalidateTownGeometry() {
        townVoyage().invalidateGeometry();
        if (level instanceof ServerLevel server) TownVoyagePublisher.releaseGroundGeometry(server, townCarrierKey());
    }

    private void startTownVoyage(ServerLevel server, BlockPos source, BlockPos target) {
        TownVoyagePublisher.retireCurrent(server, townCarrierKey());
        TownVoyagePublisher.releaseGroundGeometry(server, townCarrierKey());
        townVoyage().started(true, TownVoyagePublisher.endpoint(server, source, "Unknown"),
                TownVoyagePublisher.endpoint(server, target, "Unknown"));
        townVoyageDirty = true;
    }

    private void finishTownVoyage() {
        if (level instanceof ServerLevel server) {
            TownVoyagePublisher.retireCurrent(server, townCarrierKey());
            TownVoyagePublisher.releaseGroundGeometry(server, townCarrierKey());
        }
        townVoyage().finish(); townVoyageDirty = true;
    }

    /** Outer server-tick publication sees all blocked, paused, fuel and recovery early returns. */
    private void publishTownVoyage(ServerLevel server) {
        boolean settlingAtDestination = active && transportKind() != Kind.AIR
                && targetIndex == activeRoute.size() - 1 && arrivalTicks > 0;
        if (!townVoyageDirty && townVoyageSettlingAtDestination == settlingAtDestination && server.getGameTime() % 20 != 0) return;
        townVoyageSettlingAtDestination = settlingAtDestination;
        townVoyageDirty = false;
        if (!active) { finishTownVoyage(); townVoyageDirty = false; return; }
        var source = townGroundSource(returnTrip, townVoyage().current(),
                TownVoyagePublisher.endpoint(server, sourceTerminal, "Unknown"));
        if (transportKind() != Kind.AIR && returnTrip && source.dimensionId().equals(dimensionId()))
            source = TownVoyagePublisher.endpoint(server, BlockPos.of(source.terminalPos()), source.displayName());
        var target = TownVoyagePublisher.endpoint(server, destinationTerminal, "Unknown");
        var mission = railAircraftAutomation() ? railAircraft.mission() : null;
        TownRailIntent rail = railAircraftAutomation() ? townRailIntent(active, airportPreflightPending(), returnTrip,
                railAircraft.preparationId(), mission, sourceTerminal, destinationTerminal, returnOrigin) : null;
        if (railAircraftAutomation()) {
            if (rail == null) { finishTownVoyage(); townVoyageDirty = false; return; }
            source = TownVoyagePublisher.endpoint(server, rail.source(), "Unknown");
            target = TownVoyagePublisher.endpoint(server, rail.target(), "Unknown");
        } else if (transportKind() == Kind.AIR && returnTrip) {
            source = TownVoyagePublisher.endpoint(server, returnOrigin, "Unknown");
        }
        var leg = townVoyage().observe(true, false, source, target).orElseThrow();
        UUID legId = townVoyageLegId(leg.legId(), rail, transportKind() == Kind.AIR ? flightTask : null);
        boolean loaded = view != null && view.available();
        State state = townObservationState(loaded, flightRecovering || recoveryRequired, paused, status, reason, settlingAtDestination);
        if (!loaded) invalidateTownGeometry();
        if (state == State.MOVING && mission != null) state = TownVoyagePublisher.state(mission.paused(), mission.waitReason());
        long tick = server.getServer().overworld().getGameTime();
        OptionalLong eta = OptionalLong.empty();
        if (transportKind() != Kind.AIR && loaded) eta = townVoyage().ground(activeRoute, targetIndex, view.worldPosition(),
                tick, state == State.MOVING,
                (geometry, points) -> TownVoyagePublisher.reserveGroundGeometry(server, townCarrierKey(), geometry, points));
        else townVoyage().clearMotion();
        if (townVoyage().geometryId().isEmpty()) TownVoyagePublisher.releaseGroundGeometry(server, townCarrierKey());
        var progress = townRailProgress(rail != null && rail.timed() && state == State.MOVING, mission);
        var observation = new Observation(new Key(townCarrierKey(), legId), transportKind(), getTransportName().getString(),
                getAutopilotRouteName(), source, target, transportKind() == Kind.AIR ? flightPhase : paused ? "PAUSED" : "MOVING",
                state, loaded, tick, 20, eta, eta.isPresent() ? tick : -1,
                progress == null ? townVoyage().geometryId() : progress.path().fingerprint());
        if (progress == null) TownVoyagePublisher.observe(server, observation);
        else TownVoyagePublisher.observeAir(server, observation, progress.path(), progress.confirmedDistance(), progress.actualStandTicks());
    }

}
