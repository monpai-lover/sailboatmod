package com.monpai.sailboatmod.transport.rail;

import com.monpai.sailboatmod.block.entity.TownWarehouseBlockEntity;
import com.monpai.sailboatmod.compat.create.RailFreightNativeBridge;
import com.monpai.sailboatmod.market.ShipmentManifestEntry;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.transport.CargoLoadReceipt;
import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.transport.SelectedCargoInventory;
import com.monpai.sailboatmod.transport.TransportRegistry;
import com.monpai.sailboatmod.transport.rail.RailFreightNative.TrainView;
import com.monpai.sailboatmod.transport.rail.RailFreightSavedData.Job;
import com.monpai.sailboatmod.transport.rail.RailFreightSavedData.Phase;
import com.monpai.sailboatmod.transport.rail.RailFreightSavedData.Registration;
import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/** The only market entry point for opted-in Create trains. A job survives the carrier's loaded lifetime. */
public final class RailFreightService {
    public static final String PROVIDER_ID = "create-train";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<MinecraftServer, Map<UUID, RailFreightCarrier>> LIVE = new WeakHashMap<>();
    private static boolean incompatibleCreate;

    private RailFreightService() {}

    public record RegistrationSnapshot(UUID trainId, String trainName, @Nullable UUID ownerId, boolean registered,
                                       boolean enabled, String status, int cargoSlots, int cargoUsed,
                                       String purchaseOrderId, @Nullable RailStationRef source,
                                       @Nullable RailStationRef target, int fuelSafeSlots) {}

    public static boolean available() { return nativeApi() != null; }

    /** A staged or interrupted rail job keeps this purchase exclusive across all transport modes. */
    public static boolean isOrderClaimed(ServerLevel level, String purchaseOrderId) {
        return serverThread(level) && purchaseOrderId != null && !purchaseOrderId.isBlank()
                && RailFreightSavedData.get(level).claimsPurchase(purchaseOrderId);
    }

    public static Optional<RailStationRef> inspectStation(ServerLevel level, BlockPos pos) {
        return read(level, Optional.empty(), api -> api.station(level, pos));
    }

    public static List<RailStationRef> nearbyStations(ServerLevel level, BlockPos center, int radius) {
        return read(level, List.of(), api -> api.nearbyStations(level, center, radius));
    }

    public static boolean stationsConnected(ServerLevel level, RailStationRef source, RailStationRef target) {
        if (source == null || target == null) return false;
        return read(level, false, api -> api.connected(level, source, target));
    }

    public static Optional<RegistrationSnapshot> inspectTrain(ServerLevel level, RailStationRef station) {
        if (station == null) return Optional.empty();
        return read(level, Optional.empty(), api -> api.atStation(level, station).map(view -> {
            RailFreightSavedData data = RailFreightSavedData.get(level);
            return snapshot(data.registration(view.id()), view, data.job(view.id()));
        }));
    }

    public static List<RegistrationSnapshot> registrationsAt(ServerLevel level, RailStationRef station) {
        if (!serverThread(level) || station == null) return List.of();
        RailFreightSavedData data = RailFreightSavedData.get(level);
        return data.registrations().stream().filter(registration -> station.equals(registration.home()))
                .map(registration -> snapshot(registration, read(level, null,
                        api -> api.train(level, registration.trainId()).orElse(null)), data.job(registration.trainId())))
                .toList();
    }

    public static boolean register(ServerPlayer player, RailStationRef station) {
        if (!canManage(player, station)) return false;
        ServerLevel level = player.serverLevel();
        return read(level, false, api -> {
            TrainView view = api.atStation(level, station).orElse(null);
            if (view == null || !player.getUUID().equals(view.owner()) || !view.parkedAt(station)
                    || view.cargoSlots() <= 0 || view.position() == null) return false;
            RailFreightSavedData data = RailFreightSavedData.get(level);
            if (TransportRegistry.resolve(level, view.id()) != null) return false;
            if (!data.register(new Registration(view.id(), view.owner(), station, view.name(), true))) return false;
            publish(level, view, null);
            return true;
        });
    }

    public static boolean unregister(ServerPlayer player, UUID trainId) {
        if (player == null || trainId == null || !serverThread(player.serverLevel())) return false;
        ServerLevel level = player.serverLevel();
        RailFreightSavedData data = RailFreightSavedData.get(level);
        Registration registration = data.registration(trainId);
        if (!ownedBy(player, registration) || !data.unregister(trainId)) return false;
        unpublish(level.getServer(), trainId);
        TransportRegistry.forget(level, trainId);
        return true;
    }

    /** Pausing the registration stops new orders. A committed trip still finishes its safe stop and return. */
    public static boolean setEnabled(ServerPlayer player, UUID trainId, boolean enabled) {
        if (player == null || trainId == null || !serverThread(player.serverLevel())) return false;
        RailFreightSavedData data = RailFreightSavedData.get(player.serverLevel());
        return ownedBy(player, data.registration(trainId)) && data.setEnabled(trainId, enabled);
    }

    public static List<LogisticsCarrier> readyCarriers(ServerLevel level, RailStationRef station) {
        if (station == null) return List.of();
        return read(level, List.of(), api -> {
            TrainView view = api.atStation(level, station).orElse(null);
            if (view == null || !ready(RailFreightSavedData.get(level), station, view)) return List.of();
            RailFreightCarrier carrier = publish(level, view, null);
            return carrier == null ? List.of() : List.of(carrier);
        });
    }

    public static boolean isDocked(ServerLevel level, LogisticsCarrier carrier, RailStationRef station) {
        if (!(carrier instanceof RailFreightCarrier rail) || rail.level() != level || station == null) return false;
        return read(level, false, api -> api.atStation(level, station).filter(view -> view.id().equals(rail.getTransportUuid())
                && rail.ownerId().equals(view.owner()) && view.position() != null && view.parkedAt(station)).isPresent());
    }

    public static Optional<RailRoute> plan(ServerLevel level, LogisticsCarrier carrier, RailStationRef source,
                                           RailStationRef target, BlockPos sourceTerminal, BlockPos targetTerminal,
                                           BlockPos buyerWarehouse) {
        if (!(carrier instanceof RailFreightCarrier rail) || rail.level() != level || source == null || target == null
                || !serverThread(level) || !binding(level, sourceTerminal, source) || !binding(level, targetTerminal, target)
                || warehouse(level, buyerWarehouse, target) == null) return Optional.empty();
        return read(level, Optional.empty(), api -> {
            TrainView view = api.atStation(level, source).orElse(null);
            if (view == null || !view.id().equals(rail.getTransportUuid()) || !rail.ownerId().equals(view.owner())
                    || !ready(RailFreightSavedData.get(level), source, view)) return Optional.empty();
            return api.plan(level, view.id(), view.owner(), source, target).map(path -> new RailRoute(source, target,
                    sourceTerminal, targetTerminal, buyerWarehouse, path.distanceMeters(), path.sourceName() + " → " + path.targetName()));
        });
    }

    public static @Nullable RailDispatchReceipt prepareDispatch(ServerLevel level, LogisticsCarrier carrier,
                                                                 RailRoute route, List<ShipmentManifestEntry> manifest) {
        if (!(carrier instanceof RailFreightCarrier rail) || rail.level() != level || route == null || !serverThread(level)
                || level.getServer().tickRateManager().isFrozen()) return null;
        Optional<RailRoute> fresh = plan(level, rail, route.source(), route.target(), route.sourceTerminal(),
                route.targetTerminal(), route.buyerWarehouse());
        if (fresh.isEmpty() || !fresh.get().routeName().equals(route.routeName())) return null;
        RailFreightNative api = nativeApi();
        if (api == null) return null;
        RailFreightSavedData data = RailFreightSavedData.get(level);
        Job prepared;
        List<ItemStack> cargo;
        try {
            prepared = new Job(UUID.randomUUID(), rail.getTransportUuid(), fresh.get(), manifest,
                    new CompoundTag(), Phase.PREPARED, "loading");
            if (!RailFreightDelivery.validWaitingOrders(level, prepared)) return null;
            cargo = RailFreightDelivery.markedCargo(prepared);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return null;
        }
        if (cargo.isEmpty()) return null;
        SelectedCargoInventory inventory = inventory(api, rail);
        if (inventory == null || !inventory.contents().isEmpty() || !inventory.canLoad(cargo) || !data.begin(prepared)) return null;
        try {
            CargoLoadReceipt receipt = inventory.load(cargo);
            if (receipt == null) {
                api.markDirty();
                data.complete(prepared.trainId(), prepared.jobId());
                return null;
            }
            api.markDirty();
            return new Dispatch(level, api, data, rail, prepared, receipt);
        } catch (RuntimeException | LinkageError failure) {
            status(data, prepared, "recovery_required");
            reportFailure(failure);
            return null;
        }
    }

    public static void tick(MinecraftServer server) {
        if (server == null || !server.isSameThread() || server.getTickCount() % 20 != 0) return;
        RailFreightSavedData data = RailFreightSavedData.get(server.overworld());
        RailFreightNative api = nativeApi();
        for (Registration registration : data.registrations()) {
            ServerLevel level = level(server, registration.home().dimensionId());
            if (level == null) continue;
            TransportRegistry.remember(level, registration.trainId());
            Job job = data.job(registration.trainId());
            TrainView view = api == null ? null : read(level, null, bridge -> bridge.train(level, registration.trainId()).orElse(null));
            if (view == null || !registration.ownerId().equals(view.owner()) || view.position() == null) {
                unpublish(server, registration.trainId());
            } else {
                publish(level, view, job);
            }
            if (job == null || server.tickRateManager().isFrozen()) continue;
            try {
                advance(level, api, data, registration, job, view);
            } catch (RuntimeException | LinkageError failure) {
                status(data, job, "native_error");
                reportFailure(failure);
            }
        }
    }

    public static void stop(MinecraftServer server) {
        Map<UUID, RailFreightCarrier> carriers = LIVE.remove(server);
        if (carriers == null) return;
        for (RailFreightCarrier carrier : carriers.values()) {
            if (TransportRegistry.resolve(carrier.level(), carrier.getTransportUuid()) == carrier) {
                TransportRegistry.unregister(carrier.level(), carrier.getTransportUuid());
            }
        }
    }

    static boolean canLoad(RailFreightCarrier carrier, List<ItemStack> cargo) {
        if (cargo == null || cargo.isEmpty()) return false;
        return read(carrier.level(), false, api -> {
            SelectedCargoInventory inventory = inventory(api, carrier);
            return inventory != null && inventory.canLoad(cargo);
        });
    }

    static void openStorage(ServerPlayer player, UUID trainId) {
        if (RailFreightSavedData.get(player.serverLevel()).job(trainId) == null) {
            read(player.serverLevel(), false, api -> api.openStorage(player, trainId));
        }
    }

    private static void advance(ServerLevel level, @Nullable RailFreightNative api, RailFreightSavedData data,
                                Registration registration, Job job, @Nullable TrainView train) {
        if (api == null) { status(data, job, "create_unavailable"); return; }
        if (train == null || !train.usable()) { status(data, job, "train_unavailable"); return; }
        if (!registration.ownerId().equals(train.owner())) { status(data, job, "owner_changed"); return; }
        if (job.phase() == Phase.PREPARED) {
            recoverPrepared(level, api, data, job, train);
            return;
        }
        RailRoute route = job.route();
        RailStationRef destination = job.phase() == Phase.RETURN ? route.source() : route.target();
        if (train.manual() || !api.targetMatchesSchedule(level, train.id(), destination, job.ownedSchedule())
                || train.destination() != null && !destination.equals(train.destination())) {
            status(data, job, "schedule_changed");
            return;
        }
        if (train.paused() && !train.completed()) { status(data, job, "native_paused"); return; }
        if (!train.parkedAt(destination) || !train.completed()) {
            if (job.phase() == Phase.OUTBOUND) RailFreightDelivery.recordPosition(level, job, train);
            status(data, job, job.phase() == Phase.RETURN ? "returning" : "in_transit");
            return;
        }
        BlockPos terminal = job.phase() == Phase.RETURN ? route.sourceTerminal() : route.targetTerminal();
        if (!binding(level, terminal, destination) || api.atStation(level, destination).filter(v -> v.id().equals(train.id())).isEmpty()) {
            status(data, job, "terminal_unavailable");
            return;
        }
        var cargo = api.cargo(level, train.id(), train.owner());
        if (cargo == null) { status(data, job, "cargo_unavailable"); return; }
        if (job.phase() == Phase.RETURN) {
            if (!RailFreightDelivery.empty(cargo)) { status(data, job, "return_cargo_present"); return; }
            if (api.clearOwnedSchedule(level, train.id(), train.owner(), route.source(), job.ownedSchedule(), true)) {
                data.complete(job.trainId(), job.jobId());
            }
            return;
        }
        TownWarehouseBlockEntity warehouse = warehouse(level, route.buyerWarehouse(), route.target());
        if (warehouse == null) { status(data, job, "warehouse_unavailable"); return; }
        RailFreightDelivery.recordPosition(level, job, train);
        String waiting = RailFreightDelivery.deliver(level, api, job, warehouse, cargo);
        if (waiting != null) { status(data, job, waiting); return; }
        if (!RailFreightDelivery.empty(cargo)) { status(data, job, "return_cargo_present"); return; }
        if (!binding(level, route.sourceTerminal(), route.source())) { status(data, job, "return_unavailable"); return; }
        CompoundTag returnSchedule = api.start(level, train.id(), train.owner(), route.target(), route.source(),
                job.jobId(), job.ownedSchedule());
        if (returnSchedule == null) { status(data, job, "return_unavailable"); return; }
        data.replace(new Job(job.jobId(), job.trainId(), route, job.manifest(), returnSchedule, Phase.RETURN, "returning"));
    }

    private static void recoverPrepared(ServerLevel level, RailFreightNative api, RailFreightSavedData data, Job job, TrainView train) {
        RailRoute route = job.route();
        if (!train.hasSchedule() && train.parkedAt(route.source()) && binding(level, route.sourceTerminal(), route.source())
                && RailFreightDelivery.validWaitingOrders(level, job)) {
            var cargo = api.cargo(level, train.id(), train.owner());
            if (cargo != null && RailFreightDelivery.rollbackPrepared(api, job, cargo)) {
                data.complete(job.trainId(), job.jobId());
                return;
            }
        }
        status(data, job, "recovery_required");
    }

    private static final class Dispatch implements RailDispatchReceipt {
        private final ServerLevel level;
        private final RailFreightNative api;
        private final RailFreightSavedData data;
        private final RailFreightCarrier carrier;
        private final Job prepared;
        private final CargoLoadReceipt cargo;
        private @Nullable CompoundTag schedule;
        private boolean closed;

        Dispatch(ServerLevel level, RailFreightNative api, RailFreightSavedData data, RailFreightCarrier carrier,
                 Job prepared, CargoLoadReceipt cargo) {
            this.level = level;
            this.api = api;
            this.data = data;
            this.carrier = carrier;
            this.prepared = prepared;
            this.cargo = cargo;
        }

        @Override
        public boolean start() {
            if (closed || schedule != null || !serverThread(level) || level.getServer().tickRateManager().isFrozen()
                    || data.job(prepared.trainId()) != prepared || !RailFreightDelivery.validWaitingOrders(level, prepared)) return false;
            RailRoute route = prepared.route();
            if (!binding(level, route.sourceTerminal(), route.source()) || !binding(level, route.targetTerminal(), route.target())) return false;
            try {
                schedule = api.start(level, prepared.trainId(), carrier.ownerId(), route.source(), route.target(), prepared.jobId(), null);
                return schedule != null && data.replace(new Job(prepared.jobId(), prepared.trainId(), route,
                        prepared.manifest(), schedule, Phase.OUTBOUND, "waiting_market_commit"));
            } catch (RuntimeException | LinkageError failure) {
                reportFailure(failure);
                return false;
            }
        }

        @Override
        public void commit() {
            if (closed || !serverThread(level) || schedule == null) return;
            closed = true;
            cargo.commit();
            Job current = data.job(prepared.trainId());
            if (current != null && current.jobId().equals(prepared.jobId())) status(data, current, "in_transit");
        }

        @Override
        public void rollback() {
            if (closed || !serverThread(level)) return;
            closed = true;
            Job current = data.job(prepared.trainId());
            if (current == null || !current.jobId().equals(prepared.jobId())) return;
            try {
                if (!RailFreightDelivery.validWaitingOrders(level, prepared) || !scheduleReleased()) {
                    status(data, current, "recovery_required");
                    return;
                }
                cargo.rollback();
                api.markDirty();
                data.complete(prepared.trainId(), prepared.jobId());
            } catch (RuntimeException | LinkageError failure) {
                status(data, current, "recovery_required");
                reportFailure(failure);
            }
        }

        private boolean scheduleReleased() {
            if (schedule != null) {
                return api.clearOwnedSchedule(level, prepared.trainId(), carrier.ownerId(), prepared.route().source(), schedule, false);
            }
            return api.train(level, prepared.trainId()).filter(view -> carrier.ownerId().equals(view.owner())
                    && view.parkedAt(prepared.route().source()) && !view.hasSchedule()).isPresent();
        }
    }

    private static boolean ready(RailFreightSavedData data, RailStationRef station, TrainView view) {
        Registration registration = data.registration(view.id());
        return registration != null && registration.enabled() && station.equals(registration.home())
                && registration.ownerId().equals(view.owner()) && data.job(view.id()) == null && view.parkedAt(station)
                && view.canDriveBothWays() && !view.hasSchedule() && view.position() != null
                && view.cargoSlots() > 0 && view.cargoUsed() == 0;
    }

    private static boolean ownedBy(ServerPlayer player, @Nullable Registration registration) {
        return registration != null && registration.ownerId().equals(player.getUUID()) && canManage(player, registration.home());
    }

    private static boolean canManage(@Nullable ServerPlayer player, @Nullable RailStationRef station) {
        if (player == null || station == null) return false;
        ServerLevel level = player.serverLevel();
        if (!serverThread(level) || level.getServer().getPlayerList().getPlayer(player.getUUID()) != player
                || !dimension(level).equals(station.dimensionId()) || player.distanceToSqr(Vec3.atCenterOf(station.stationPos())) > 64) return false;
        TownRecord town = TownService.getTownAt(level, station.stationPos());
        return town != null && TownService.canManageTown(player, NationSavedData.get(level), town);
    }

    private static boolean binding(ServerLevel level, BlockPos pos, RailStationRef station) {
        BlockEntity entity = loadedBlockEntity(level, pos);
        return entity instanceof RailFreightTerminalAccess terminal && terminal.stationRef().filter(station::equals).isPresent()
                && sameTown(level, pos, station.stationPos());
    }

    private static @Nullable TownWarehouseBlockEntity warehouse(ServerLevel level, BlockPos pos, RailStationRef station) {
        BlockEntity entity = loadedBlockEntity(level, pos);
        return entity instanceof TownWarehouseBlockEntity warehouse && sameTown(level, pos, station.stationPos()) ? warehouse : null;
    }

    static boolean sameTown(ServerLevel level, BlockPos first, BlockPos second) {
        TownRecord a = TownService.getTownAt(level, first);
        TownRecord b = TownService.getTownAt(level, second);
        return a != null && b != null && a.townId().equals(b.townId());
    }

    static @Nullable BlockEntity loadedBlockEntity(ServerLevel level, @Nullable BlockPos pos) {
        if (pos == null) return null;
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        BlockEntity entity = chunk == null ? null : chunk.getBlockEntities().get(pos);
        return entity == null || entity.isRemoved() ? null : entity;
    }

    private static @Nullable SelectedCargoInventory inventory(RailFreightNative api, RailFreightCarrier carrier) {
        var handlers = api.cargo(carrier.level(), carrier.getTransportUuid(), carrier.ownerId());
        return handlers == null ? null : new SelectedCargoInventory(List.copyOf(handlers));
    }

    private static RegistrationSnapshot snapshot(@Nullable Registration registration, @Nullable TrainView view, @Nullable Job job) {
        UUID id = view == null ? registration.trainId() : view.id();
        String name = view == null ? registration.trainName() : view.name();
        UUID owner = registration == null ? view.owner() : registration.ownerId();
        boolean enabled = registration != null && registration.enabled();
        String status;
        if (job != null) status = job.status();
        else if (registration == null) status = "unregistered";
        else if (view == null || !view.usable()) status = "train_unavailable";
        else if (!registration.ownerId().equals(view.owner())) status = "owner_changed";
        else if (!enabled) status = "disabled";
        else if (view.hasSchedule()) status = "schedule_present";
        else if (view.cargoSlots() == 0) status = "cargo_unavailable";
        else if (view.cargoUsed() != 0) status = "cargo_present";
        else if (!view.canDriveBothWays()) status = "driver_unavailable";
        else status = view.parkedAt(registration.home()) ? "ready" : "not_at_home";
        return new RegistrationSnapshot(id, name, owner, registration != null, enabled, status,
                view == null ? 0 : view.cargoSlots(), view == null ? 0 : view.cargoUsed(),
                job == null ? "" : job.manifest().getFirst().purchaseOrderId(),
                job == null ? registration == null ? null : registration.home() : job.route().source(),
                job == null ? null : job.route().target(), view == null ? 0 : view.fuelSafeSlots());
    }

    private static void status(RailFreightSavedData data, Job job, String status) {
        if (!job.status().equals(status)) {
            data.replace(new Job(job.jobId(), job.trainId(), job.route(), job.manifest(), job.ownedSchedule(), job.phase(), status));
        }
    }

    private static @Nullable RailFreightCarrier publish(ServerLevel level, TrainView view, @Nullable Job job) {
        Map<UUID, RailFreightCarrier> carriers = LIVE.computeIfAbsent(level.getServer(), ignored -> new HashMap<>());
        RailFreightCarrier current = carriers.get(view.id());
        LogisticsCarrier already = TransportRegistry.resolve(level, view.id());
        if (already != null && already != current) return null;
        if (current == null) {
            current = new RailFreightCarrier(level, view, job);
            carriers.put(view.id(), current);
        } else {
            current.update(view, job);
        }
        TransportRegistry.register(level, current);
        return current;
    }

    private static void unpublish(MinecraftServer server, UUID trainId) {
        Map<UUID, RailFreightCarrier> carriers = LIVE.get(server);
        if (carriers == null) return;
        RailFreightCarrier removed = carriers.remove(trainId);
        if (removed != null && TransportRegistry.resolve(removed.level(), trainId) == removed) {
            TransportRegistry.unregister(removed.level(), trainId);
        }
    }

    private static @Nullable ServerLevel level(MinecraftServer server, String id) {
        for (ServerLevel level : server.getAllLevels()) if (dimension(level).equals(id)) return level;
        return null;
    }

    private static String dimension(ServerLevel level) { return level.dimension().location().toString(); }
    private static boolean serverThread(@Nullable ServerLevel level) { return level != null && level.getServer().isSameThread(); }

    private static <T> T read(ServerLevel level, T unavailable, Function<RailFreightNative, T> operation) {
        if (!serverThread(level)) return unavailable;
        RailFreightNative api = nativeApi();
        if (api == null) return unavailable;
        try {
            return operation.apply(api);
        } catch (RuntimeException | LinkageError failure) {
            reportFailure(failure);
            return unavailable;
        }
    }

    private static @Nullable RailFreightNative nativeApi() {
        if (incompatibleCreate || ModList.get() == null || !ModList.get().isLoaded("create")) return null;
        try {
            return NativeHolder.INSTANCE;
        } catch (LinkageError failure) {
            reportFailure(failure);
            return null;
        }
    }

    private static void reportFailure(Throwable failure) {
        if (failure instanceof LinkageError) incompatibleCreate = true;
        LOGGER.warn("Create rail freight operation stopped with its custody preserved", failure);
    }

    private static final class NativeHolder {
        private static final RailFreightNative INSTANCE = new RailFreightNativeBridge();
    }
}
