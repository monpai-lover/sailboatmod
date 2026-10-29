package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.block.LandVehicleRegistrationControllerBlock;
import com.monpai.sailboatmod.block.RegisteredVehicleControllerBlock;
import com.monpai.sailboatmod.compat.aeronautics.AeronauticsVehicleBridge;
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
        implements LogisticsCarrier {
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
    private int rentalPrice = -1, selectedRoute, targetIndex, arrivalTicks, stalledTicks;
    private long departureMillis;
    private double missionDistance;
    private final List<RouteDefinition> routeCatalog = new ArrayList<>();
    private final List<Vec3> activeRoute = new ArrayList<>();
    private final List<ShipmentManifestEntry> manifest = new ArrayList<>();
    private PhysicalVehicleView view;
    private boolean bindingValid;
    private final int runtimeId = TransportRegistry.allocateRuntimeId();
    private final TransportLoadingWindow loading = new TransportLoadingWindow();
    private final SweptTerrainProbe terrainProbe = new SweptTerrainProbe();
    private SweptTerrainProbe.Result terrainResult = SweptTerrainProbe.Result.WAITING;
    private boolean cargoFaultReported;
    private Vec3 lastProgressPosition;

    public RegisteredVehicleControllerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.REGISTERED_VEHICLE_CONTROLLER_BLOCK_ENTITY.get(), pos, state);
        Kind kind =
                state.getBlock() instanceof LandVehicleRegistrationControllerBlock
                        ? Kind.LAND
                        : Kind.WATER;
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
        maxSpeed = kind == Kind.LAND ? 6.0D : 4.0D;
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
        if (level instanceof ServerLevel server && server.getGameTime() % 5 == 0)
            core.tickServer(server);
    }

    private void tickServer(ServerLevel server) {
        view = AeronauticsVehicleBridge.resolve(this, facing()).orElse(null);
        bindingValid = false;
        if (view == null || !view.available()) {
            TransportRegistry.unregister(server, getTransportUuid());
            ownSignalsOff();
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
            status = ControllerStatus.UNREGISTERED;
            return;
        }
        LogisticsCarrier duplicate = TransportRegistry.resolve(server, getTransportUuid());
        if (duplicate != null && duplicate != this) {
            fault("duplicate_controller", false);
            return;
        }
        RegisteredTransportSavedData saved = RegisteredTransportSavedData.get(server);
        if (!saved.claim(dimensionId(), view.physicalId(), getTransportUuid())) {
            fault("duplicate_controller", false);
            return;
        }
        registration.register(view.physicalId(), view.mass(), true);
        bindingValid = true;
        TransportRegistry.register(server, this);
        remember(server);
        if (!ControllerRegistration.validControlPorts(ports)) {
            portsConfirmed = false;
            fault("configure_power", false);
            return;
        }
        if (!active || paused) {
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
            fault("configure_power", false);
            return;
        }
        if (targetIndex < 0 || targetIndex >= activeRoute.size()) {
            fault("missing_route", false);
            return;
        }
        Vec3 target = activeRoute.get(targetIndex);
        boolean chunksReady =
                loading.update(
                        server, getTransportUuid(), view.physicalId(), view.worldBounds(), target);
        boolean terrainReady = chunksReady && clearSweptTerrain(server, target);
        double radius =
                Math.max(
                        2.0D,
                        Math.max(view.worldBounds().getXsize(), view.worldBounds().getZsize())
                                        * 0.5D
                                + 1.0D);
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
                                allowReverse));
        if (!applyCommand(command)) return;
        status = command.waiting() ? ControllerStatus.WAITING_TERRAIN : ControllerStatus.RUNNING;
        reason =
                !chunksReady
                        ? loading.reason()
                        : terrainReady
                                ? command.reason()
                                : terrainResult == SweptTerrainProbe.Result.BLOCKED
                                        ? "obstacle"
                                        : "waiting_terrain";
        if (command.arrived() && targetIndex + 1 < activeRoute.size()) {
            targetIndex++;
            arrivalTicks = 0;
            setChanged();
        }
        DockBlockEntity destination = resolveDestination(server);
        boolean finalLeg = targetIndex >= activeRoute.size() - 1;
        boolean slow = view.linearVelocity().horizontalDistanceSqr() <= 0.25D * 0.25D;
        boolean atDock = destination != null && destination.isCarrierInsideDockZone(this);
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

    private boolean clearSweptTerrain(ServerLevel server, Vec3 waypoint) {
        Vec3 toward = waypoint.subtract(view.worldPosition()).multiply(1, 0, 1);
        Vec3 direction =
                (toward.lengthSqr() > 0.01D ? toward.normalize() : view.worldForward())
                        .scale(Math.min(12, Math.max(2, view.linearVelocity().length() * 2)));
        AABB area = view.worldBounds().expandTowards(direction).deflate(0.1D);
        terrainResult =
                terrainProbe.scan(
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
        manual |= manualTakeover;
        manualTakeover |= manual;
        paused = true;
        status = manual ? ControllerStatus.MANUAL : ControllerStatus.FAULT;
        reason = why;
        holdNative(manual);
        loading.close();
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
        return portsConfirmed && ControllerRegistration.validControlPorts(ports);
    }

    private DockBlockEntity resolveDestination(ServerLevel server) {
        if (!returnTrip && !manifest.isEmpty()) {
            ShippingOrder order =
                    MarketSavedData.get(server)
                            .getShippingOrder(manifest.getFirst().shippingOrderId());
            if (order != null) destinationTerminal = order.targetDockPos();
        }
        if (destinationTerminal == null && !activeRoute.isEmpty()) {
            Vec3 end = activeRoute.getLast();
            Collection<BlockPos> terminals =
                    transportKind() == Kind.LAND
                            ? PostStationRegistry.get(server)
                            : DockRegistry.get(server);
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
        holdNative(false);
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
        active = false;
        paused = false;
        arrivalTicks = 0;
        loading.close();
        if (!manifest.isEmpty()) {
            fault("next_leg_required", false);
            return;
        }
        if (dock != null && shouldReturn && !returnTrip && sourceTerminal != null) {
            Collections.reverse(activeRoute);
            destinationTerminal = sourceTerminal;
            returnTrip = true;
            targetIndex = 0;
            active = true;
            status = ControllerStatus.RUNNING;
            setChanged();
            return;
        }
        status = ControllerStatus.IDLE;
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
                        transportKind() == Kind.LAND ? "LAND" : "PORT",
                        "SAILING",
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
                        status == ControllerStatus.WAITING_TERRAIN ? "STUCK" : "SAILING");
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
                                        && !paused
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
                        ? (transportKind() == Kind.LAND
                                ? "Registered vehicle"
                                : "Registered vessel")
                        : vehicleName);
    }

    @Override
    public Vec3 transportPosition() {
        return view == null ? Vec3.atCenterOf(worldPosition) : view.worldPosition();
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

    @Override
    public void setRouteCatalog(List<RouteDefinition> routes, int preferred, BlockPos dockPos) {
        routeCatalog.clear();
        activeRoute.clear();
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
                || !(server.getBlockEntity(sourceTerminal) instanceof DockBlockEntity dock)
                || !dock.isCarrierInsideDockZone(this)) return false;
        activeRoute.clear();
        activeRoute.addAll(selected().waypoints());
        targetIndex = 0;
        manualTakeover = false;
        paused = false;
        active = true;
        returnTrip = false;
        departureMillis = System.currentTimeMillis();
        missionDistance = selected().routeLengthMeters();
        loading.update(
                server,
                getTransportUuid(),
                view.physicalId(),
                view.worldBounds(),
                activeRoute.getFirst());
        status = ControllerStatus.RUNNING;
        reason = "";
        arrivalTicks = stalledTicks = 0;
        lastProgressPosition = null;
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

    private void pause(boolean manual) {
        manual |= manualTakeover || status == ControllerStatus.MANUAL;
        manualTakeover |= manual;
        paused = true;
        status = manual ? ControllerStatus.MANUAL : ControllerStatus.PAUSED;
        holdNative(manual);
        loading.close();
        if (level instanceof ServerLevel server) remember(server);
        setChanged();
    }

    private void resume() {
        if (!activeRoute.isEmpty() && isTransportAlive() && controlsReady()) {
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

    private void stop() {
        boolean manual = status == ControllerStatus.MANUAL;
        active = false;
        paused = false;
        status = ControllerStatus.IDLE;
        holdNative(manual);
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
            view = AeronauticsVehicleBridge.resolve(this, facing()).orElse(null);
        if (player instanceof ServerPlayer serverPlayer && canManage(player))
            ModNetwork.CHANNEL.send(
                    serverPlayer, new OpenRegisteredVehicleControllerPacket(screenData()));
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server && registration.registered())
            TransportRegistry.remember(server, getTransportUuid());
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel server) {
            TransportRegistry.unregister(server, getTransportUuid());
            if (!ControllerRegistration.preserveLoadingOnRemoval(
                    RegisteredTransportSavedData.isServerStopping(server.getServer()),
                    active,
                    paused)) loading.close();
        }
        super.setRemoved();
    }

    /** Called only by actual block replacement, never ordinary chunk unload or server shutdown. */
    public void onPermanentRemoval() {
        if (!(level instanceof ServerLevel server)) return;
        boolean manual = status == ControllerStatus.MANUAL;
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
        view = AeronauticsVehicleBridge.resolve(this, facing()).orElse(null);
        if (!canManage(player) || !(level instanceof ServerLevel server)) return false;
        try {
            switch (action) {
                case REFRESH -> {}
                case REGISTER -> {
                    view = AeronauticsVehicleBridge.resolve(this, facing()).orElse(null);
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
                }
                case CLEAR_NATIVE_TARGETS -> {
                    if (active) return false;
                    throttleOffset = null;
                    steeringOffset = null;
                }
                case SET_PORT -> {
                    if (active) return false;
                    String[] parts = value.split(":");
                    ports.put(ControlChannel.valueOf(parts[0]), Direction.valueOf(parts[1]));
                    portsConfirmed = false;
                    ownSignalsOff();
                }
                case CONFIRM_PORTS -> {
                    if (active) return false;
                    portsConfirmed = ControllerRegistration.validControlPorts(ports);
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
                case TOGGLE_REVERSE -> allowReverse = !allowReverse;
                case BIND_NEAREST_TERMINAL -> {
                    if (active) return false;
                    Collection<BlockPos> terminals =
                            transportKind() == Kind.LAND
                                    ? PostStationRegistry.get(server)
                                    : DockRegistry.get(server);
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
                        reason = "start_not_ready";
                        return false;
                    }
                }
                case PAUSE -> pause(false);
                case RESUME -> resume();
                case STOP -> stop();
                case TOGGLE_AUTO_RETURN -> autoReturn = !autoReturn;
                case TOGGLE_AUTO_UNLOAD -> autoUnload = !autoUnload;
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

    public ControllerScreenData screenData() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", getTransportName().getString());
        tag.putString("Owner", ownerName);
        tag.putString("Kind", transportKind().name());
        tag.putString("Status", status.name());
        tag.putString("Reason", reason);
        tag.putBoolean("Registered", registration.registered());
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
        tag.put("Registration", registration.save());
        tag.putString("Name", vehicleName);
        tag.putString("OwnerName", ownerName);
        tag.putBoolean("PortsConfirmed", portsConfirmed);
        tag.putBoolean("Inverted", transmissionInverted);
        tag.putBoolean("AllowReverse", allowReverse);
        tag.putBoolean("AutoReturn", autoReturn);
        tag.putBoolean("AutoUnload", autoUnload);
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
        super.loadAdditional(tag, provider);
        if (tag.contains("Registration"))
            registration = ControllerRegistration.load(tag.getCompound("Registration"));
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
        if (!ControllerRegistration.validControlPorts(ports)) {
            portsConfirmed = false;
            paused = true;
            status = ControllerStatus.FAULT;
            reason = "configure_power";
        }
        routeCatalog.clear();
        routeCatalog.addAll(RouteNbtUtil.readRoutes(tag, "Routes"));
        activeRoute.clear();
        List<RouteDefinition> savedRoute = RouteNbtUtil.readRoutes(tag, "ActiveRoute");
        if (!savedRoute.isEmpty()) activeRoute.addAll(savedRoute.getFirst().waypoints());
        manifest.clear();
        for (Tag raw : tag.getList("Manifest", Tag.TAG_COMPOUND))
            manifest.add(ShipmentManifestEntry.load((CompoundTag) raw));
    }
}
