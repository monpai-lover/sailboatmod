package com.monpai.sailboatmod.compat.create;

import com.monpai.sailboatmod.transport.rail.RailFreightCargoHold;
import com.monpai.sailboatmod.transport.rail.RailFreightNative;
import com.monpai.sailboatmod.transport.rail.RailStationRef;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.condition.ScheduledDelay;
import com.simibubi.create.content.trains.schedule.condition.TimedWaitCondition;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.station.GlobalStation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.Nullable;

/** Server-thread adapter for the installed Create railway registry, schedules and mounted item storage. */
public final class RailFreightNativeBridge implements RailFreightNative {
    private static final int MAX_CARRIAGES = 64;
    private static final int MAX_SLOTS = 4096;
    private static final int MAX_STATIONS_IN_GRAPH = 8192;
    private static final String SCHEDULE_JOB = "SailboatFreightJob";
    private static final StationAccess STATIONS = StationAccess.bind();

    @Override
    public Optional<RailStationRef> station(ServerLevel level, BlockPos pos) {
        return Optional.ofNullable(ref(stationAt(level, pos)));
    }

    @Override
    public List<RailStationRef> nearbyStations(ServerLevel level, BlockPos center, int radius) {
        int range = Math.max(0, Math.min(radius, 16));
        List<RailStationRef> candidates = new ArrayList<>();
        for (int x = (center.getX() - range) >> 4; x <= (center.getX() + range) >> 4; x++) {
            for (int z = (center.getZ() - range) >> 4; z <= (center.getZ() + range) >> 4; z++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    BlockPos pos = entity.getBlockPos();
                    if (STATIONS.type().isInstance(entity) && pos.distSqr(center) <= (double) range * range) {
                        station(level, pos).ifPresent(candidates::add);
                    }
                }
            }
        }
        return candidates.stream().sorted(Comparator.comparingDouble(s -> s.stationPos().distSqr(center))).limit(64).toList();
    }

    @Override
    public boolean connected(ServerLevel level, RailStationRef source, RailStationRef target) {
        GlobalStation from = station(level, source);
        GlobalStation to = station(level, target);
        if (from == null || to == null || from == to || from.assembling || to.assembling) return false;
        var graphs = Create.RAILWAYS.trackNetworks.values();
        if (graphs.size() > MAX_STATIONS_IN_GRAPH) return false;
        for (TrackGraph graph : graphs) {
            if (graph.getPoint(EdgePointType.STATION, source.stationId()) == from
                    && graph.getPoint(EdgePointType.STATION, target.stationId()) == to) return true;
        }
        return false;
    }

    @Override
    public Optional<TrainView> atStation(ServerLevel level, RailStationRef station) {
        GlobalStation nativeStation = station(level, station);
        if (nativeStation == null || nativeStation.assembling) return Optional.empty();
        Train train = nativeStation.getPresentTrain();
        if (train == null || nativeTrain(train.id) != train || train.getCurrentStation() != nativeStation) return Optional.empty();
        return Optional.of(snapshot(level, train));
    }

    @Override
    public Optional<TrainView> train(ServerLevel level, UUID trainId) {
        Train train = nativeTrain(trainId);
        return train == null ? Optional.empty() : Optional.of(snapshot(level, train));
    }

    @Override
    public Optional<Path> plan(ServerLevel level, UUID trainId, UUID owner, RailStationRef source, RailStationRef target) {
        Train train = ownedTrain(trainId, owner);
        GlobalStation from = station(level, source);
        GlobalStation to = station(level, target);
        if (!readyToMove(train, from) || !uniqueTarget(train, from) || !uniqueTarget(train, to) || from == to) {
            return Optional.empty();
        }
        var path = train.navigation.findPathTo(to, Double.MAX_VALUE);
        double distance = path == null ? 0 : Math.abs(path.distance);
        if (path == null || path.destination != to || !Double.isFinite(distance) || distance <= 0 || distance > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        return Optional.of(new Path(Math.max(1, (int) Math.round(distance)), from.name, to.name));
    }

    @Override
    public @Nullable List<IItemHandlerModifiable> cargo(ServerLevel level, UUID trainId, UUID owner) {
        Train train = ownedTrain(trainId, owner);
        if (!valid(train)) return null;
        List<IItemHandlerModifiable> handlers = new ArrayList<>();
        int slots = 0;
        for (Carriage carriage : train.carriages) {
            var entity = carriage.anyAvailableEntity();
            if (entity == null || !entity.isAlive() || entity.level() != level || entity.getCarriage() != carriage
                    || carriage.storage == null || carriage.storage.getMountedItems() == null) return null;
            var fuel = carriage.storage.getFuelItems();
            // The mounted wrapper excludes forwarded external storage. Its physical slots may also be fuel slots.
            for (var entry : carriage.storage.getMountedItems().storages.entrySet()) {
                IItemHandlerModifiable mounted = entry.getValue();
                if (mounted.getSlots() < 0 || mounted.getSlots() > MAX_SLOTS - slots) return null;
                if (mounted.getSlots() > 0) {
                    boolean fuelAccessible = fuel != null && fuel.storages.containsKey(entry.getKey());
                    handlers.add(new RailFreightCargoHold(mounted, fuelAccessible));
                }
                slots += mounted.getSlots();
            }
        }
        return handlers.isEmpty() ? null : List.copyOf(handlers);
    }

    @Override
    public boolean openStorage(ServerPlayer player, UUID trainId) {
        Train train = ownedTrain(trainId, player.getUUID());
        if (!valid(train)) return false;
        for (Carriage carriage : train.carriages) {
            var entity = carriage.anyAvailableEntity();
            if (entity == null || !entity.isAlive() || entity.level() != player.serverLevel() || carriage.storage == null) continue;
            for (var entry : carriage.storage.getAllItemStorages().entrySet()) {
                Vec3 center = entity.toGlobalVector(Vec3.atCenterOf(entry.getKey()), 1.0F);
                if (player.distanceToSqr(center) <= 64 && carriage.storage.handlePlayerStorageInteraction(entity.getContraption(), player, entry.getKey())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public @Nullable CompoundTag start(ServerLevel level, UUID trainId, UUID owner, RailStationRef source,
                                       RailStationRef target, UUID jobId, @Nullable CompoundTag completedSchedule) {
        Train train = ownedTrain(trainId, owner);
        if (!valid(train) || plan(level, trainId, owner, source, target).isEmpty()) return null;
        Schedule existing = train.runtime.getSchedule();
        if (completedSchedule == null ? existing != null
                : existing == null || !train.runtime.isAutoSchedule || !train.runtime.completed
                        || !completedSchedule.equals(existing.write(level.registryAccess()))) return null;
        GlobalStation to = station(level, target);
        if (!uniqueTarget(train, to)) return null;
        DestinationInstruction destination = instruction(to.name);
        destination.getData().putUUID(SCHEDULE_JOB, jobId);
        ScheduledDelay wait = new ScheduledDelay();
        wait.getData().putInt("Value", 1);
        wait.getData().putInt("TimeUnit", TimedWaitCondition.TimeUnit.SECONDS.ordinal());
        Schedule schedule = new Schedule(new ArrayList<>(List.of(new ScheduleEntry(destination, List.of(List.of(wait))))), false, 0);
        CompoundTag serialized = schedule.write(level.registryAccess());
        // This is the normal Create automatic schedule entry point. Native ticks do all movement and stopping.
        train.runtime.setSchedule(schedule, true);
        markDirty();
        return train.runtime.getSchedule() == schedule ? serialized : null;
    }

    @Override
    public boolean clearOwnedSchedule(ServerLevel level, UUID trainId, UUID owner, RailStationRef station,
                                      CompoundTag expected, boolean requireCompleted) {
        Train train = ownedTrain(trainId, owner);
        GlobalStation nativeStation = station(level, station);
        if (!parked(train, nativeStation) || (requireCompleted && !train.runtime.completed)) return false;
        Schedule existing = train.runtime.getSchedule();
        if (existing == null || !train.runtime.isAutoSchedule || !expected.equals(existing.write(level.registryAccess()))) return false;
        train.runtime.discardSchedule();
        markDirty();
        return train.runtime.getSchedule() == null;
    }

    @Override
    public boolean targetMatchesSchedule(ServerLevel level, UUID trainId, RailStationRef target, CompoundTag expected) {
        Train train = nativeTrain(trainId);
        if (!valid(train) || train.runtime.getSchedule() == null || !train.runtime.isAutoSchedule) return false;
        Schedule schedule = train.runtime.getSchedule();
        if (!expected.equals(schedule.write(level.registryAccess())) || schedule.entries.size() != 1
                || !(schedule.entries.getFirst().instruction instanceof DestinationInstruction destination)) return false;
        GlobalStation nativeStation = train.graph.getPoint(EdgePointType.STATION, target.stationId());
        return target.equals(ref(nativeStation)) && uniqueTarget(train, nativeStation)
                && destination.getData().getString("Text").equals(nativeStation.name);
    }

    @Override
    public void markDirty() {
        Create.RAILWAYS.markTracksDirty();
    }

    private TrainView snapshot(ServerLevel level, Train train) {
        boolean valid = valid(train);
        GlobalStation station = valid ? train.getCurrentStation() : null;
        if (station != null && (station.getPresentTrain() != train || station.assembling)) station = null;
        Vec3 position = null;
        AABB bounds = null;
        if (valid) {
            for (Carriage carriage : train.carriages) {
                var entity = carriage.anyAvailableEntity();
                if (entity == null || !entity.isAlive() || entity.level() != level || entity.getCarriage() != carriage) continue;
                if (position == null) position = entity.position();
                bounds = bounds == null ? entity.getBoundingBox() : bounds.minmax(entity.getBoundingBox());
            }
        }
        int slots = 0;
        int occupied = 0;
        int fuelSafeSlots = 0;
        List<IItemHandlerModifiable> handlers = valid && train.owner != null ? cargo(level, train.id, train.owner) : null;
        if (handlers != null) {
            for (IItemHandlerModifiable handler : handlers) {
                slots += handler.getSlots();
                if (handler instanceof RailFreightCargoHold hold && hold.fuelSafe()) fuelSafeSlots += hold.getSlots();
                for (int slot = 0; slot < handler.getSlots(); slot++) if (!handler.getStackInSlot(slot).isEmpty()) occupied++;
            }
        }
        Schedule schedule = valid ? train.runtime.getSchedule() : null;
        return new TrainView(train.id, train.owner, train.name == null ? "" : train.name.getString(), ref(station),
                valid ? ref(train.navigation.destination) : null, position, bounds, train.speed,
                valid ? train.navigation.distanceToDestination : Double.NaN, valid,
                valid && drivers(train), valid && (train.manualTick || train.countPlayerPassengers() != 0),
                valid && train.runtime.paused, valid && train.runtime.completed, schedule != null,
                schedule != null && train.runtime.isAutoSchedule ? schedule.write(level.registryAccess()) : null,
                slots, occupied, fuelSafeSlots);
    }

    private static boolean readyToMove(@Nullable Train train, @Nullable GlobalStation source) {
        return parked(train, source) && drivers(train);
    }

    private static boolean parked(@Nullable Train train, @Nullable GlobalStation station) {
        return valid(train) && station != null && !station.assembling && station.getPresentTrain() == train
                && train.getCurrentStation() == station && train.navigation.destination == null
                && Double.isFinite(train.speed) && Math.abs(train.speed) < 1e-6
                && !train.manualTick && train.countPlayerPassengers() == 0;
    }

    private static boolean drivers(Train train) {
        return train.doubleEnded && train.hasForwardConductor() && train.hasBackwardConductor();
    }

    private static boolean valid(@Nullable Train train) {
        return train != null && !train.invalid && !train.derailed && train.graph != null && train.navigation != null
                && train.runtime != null && train.carriages != null && !train.carriages.isEmpty() && train.carriages.size() <= MAX_CARRIAGES;
    }

    private static @Nullable Train nativeTrain(UUID id) {
        Train train = Create.RAILWAYS.trains.get(id);
        return train != null && id.equals(train.id) ? train : null;
    }

    private static @Nullable Train ownedTrain(UUID id, UUID owner) {
        Train train = nativeTrain(id);
        return train != null && owner.equals(train.owner) ? train : null;
    }

    private static @Nullable GlobalStation stationAt(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        BlockEntity blockEntity = chunk == null ? null : chunk.getBlockEntities().get(pos);
        if (blockEntity == null || blockEntity.isRemoved() || !STATIONS.type().isInstance(blockEntity)) return null;
        GlobalStation station;
        try {
            BlockPos target = (BlockPos) STATIONS.trackPosition().invokeExact(blockEntity);
            if (!level.hasChunkAt(target)) return null;
            station = (GlobalStation) STATIONS.station().invokeExact(blockEntity);
        } catch (Throwable failure) {
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("Cannot read the native Create station", failure);
        }
        return station != null && level.dimension().equals(station.getBlockEntityDimension())
                && pos.equals(station.getBlockEntityPos()) ? station : null;
    }

    private static @Nullable GlobalStation station(ServerLevel level, RailStationRef reference) {
        if (!level.dimension().location().toString().equals(reference.dimensionId())) return null;
        GlobalStation actual = stationAt(level, reference.stationPos());
        return actual != null && reference.stationId().equals(actual.getId()) ? actual : null;
    }

    private static @Nullable RailStationRef ref(@Nullable GlobalStation station) {
        if (station == null || station.getId() == null || station.getBlockEntityDimension() == null || station.getBlockEntityPos() == null) return null;
        return new RailStationRef(station.getBlockEntityDimension().location().toString(), station.getBlockEntityPos(), station.getId());
    }

    private static DestinationInstruction instruction(String name) {
        DestinationInstruction instruction = new DestinationInstruction();
        instruction.getData().putString("Text", name);
        return instruction;
    }

    private static boolean uniqueTarget(Train train, @Nullable GlobalStation target) {
        if (target == null || target.assembling || target.name == null || target.name.isBlank() || target.name.length() > 128
                || target.name.chars().anyMatch(ch -> ch == '*' || ch == '?' || ch == '[' || ch == ']' || ch == '\\' || Character.isISOControl(ch))
                || train.graph.getPoint(EdgePointType.STATION, target.getId()) != target) return false;
        var stations = train.graph.getPoints(EdgePointType.STATION);
        if (stations.size() > MAX_STATIONS_IN_GRAPH) return false;
        Pattern pattern = Pattern.compile(instruction(target.name).getFilterForRegex());
        int count = 0;
        for (GlobalStation station : stations) {
            if (!pattern.matcher(station.name).matches()) continue;
            if (station != target || ++count != 1) return false;
        }
        return count == 1;
    }

    /** StationBlockEntity's public hierarchy includes Ponder, which is available only when Create loads. */
    private record StationAccess(Class<?> type, MethodHandle station, MethodHandle trackPosition) {
        static StationAccess bind() {
            try {
                var loader = RailFreightNativeBridge.class.getClassLoader();
                Class<?> type = Class.forName("com.simibubi.create.content.trains.station.StationBlockEntity", false, loader);
                Class<?> target = Class.forName("com.simibubi.create.content.trains.track.TrackTargetingBehaviour", false, loader);
                var lookup = MethodHandles.publicLookup();
                MethodHandle station = lookup.findVirtual(type, "getStation", MethodType.methodType(GlobalStation.class))
                        .asType(MethodType.methodType(GlobalStation.class, BlockEntity.class));
                MethodHandle behavior = lookup.findGetter(type, "edgePoint", target);
                MethodHandle position = lookup.findVirtual(target, "getGlobalPosition", MethodType.methodType(BlockPos.class));
                return new StationAccess(type, station, MethodHandles.filterReturnValue(behavior, position)
                        .asType(MethodType.methodType(BlockPos.class, BlockEntity.class)));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Incompatible Create station API", failure);
            }
        }
    }
}
