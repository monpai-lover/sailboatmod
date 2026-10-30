package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.registry.ModBlockEntities;
import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.transport.TransportRegistry;
import com.monpai.sailboatmod.transport.air.AircraftClass;
import com.monpai.sailboatmod.transport.air.airport.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Reuses warehouse/escrow delivery, but admits only physically parked compatible aircraft. */
public final class AirportBlockEntity extends DockBlockEntity {
    private final Map<String, AirportFacility> facilities = new LinkedHashMap<>();
    private AirportReservationBook reservations = new AirportReservationBook();
    private final Map<String, ValidationState> validation = new HashMap<>();
    private final Map<String, UUID> reservationChecks = new HashMap<>();
    private final Map<UUID, String> arrivalChecks = new HashMap<>();
    private final Map<UUID, PreflightState> preflights = new HashMap<>();
    private long budgetTick = Long.MIN_VALUE;
    private int remainingBudget;
    private static final int SCAN_BUDGET = 2048;
    // Two maximum scans, even if serialized, retried every five ticks. This is a preparation
    // deadline, not the ordinary cache TTL. Completion is a bounded snapshot, not an atomic world scan.
    public static final long PREFLIGHT_MAX_TICKS = 2L * 5 * ((AirportValidation.MAX_SCAN_STEPS + SCAN_BUDGET - 1) / SCAN_BUDGET);
    private static final int MAX_PREFLIGHTS = 16;
    private static final class PreflightState {
        final UUID aircraftId;
        final ValidationState validation;
        final long startedAt;
        PreflightState(UUID aircraftId, AirportFacility facility, long now) {
            this.aircraftId = aircraftId; this.validation = new ValidationState(facility); this.startedAt = now;
        }
    }
    private static final class ValidationState {
        final AirportFacility facility;
        final AirportValidation.Scan scan;
        AirportValidation.Result result = AirportValidation.Result.WAITING;
        long checkedAt;
        ValidationState(AirportFacility facility) { this.facility = facility; scan = new AirportValidation.Scan(facility); }
    }
    public AirportBlockEntity(BlockPos pos, BlockState state) { super(ModBlockEntities.AIRPORT_BLOCK_ENTITY.get(), pos, state); }
    @Override protected void registerFacility(Level level, BlockPos pos) { AirportRegistry.register(level, pos); }
    @Override protected void unregisterFacility(Level level, BlockPos pos) { AirportRegistry.unregister(level, pos); }
    @Override protected String defaultFacilityName() { return "Airport"; }
    @Override protected String defaultFacilityNameSuffix() { return "'s Airport"; }
    @Override protected boolean isValidFacilityZone(Level level, int minX, int maxX, int minZ, int maxZ) { return false; }
    @Override protected boolean supportsLogisticsCarrier(LogisticsCarrier carrier) { return carrier != null && carrier.transportKind() == LogisticsCarrier.Kind.AIR; }
    @Override protected net.minecraft.world.phys.AABB carrierSearchBounds() {
        net.minecraft.world.phys.AABB area = new net.minecraft.world.phys.AABB(worldPosition).inflate(8);
        for (AirportFacility facility : facilities.values()) area = area.minmax(facility.bounds());
        return area;
    }
    @Override protected void syncFacilityMarkers() {
        if (level instanceof ServerLevel server) AirportSavedData.get(server).put(new AirportSavedData.AirportSnapshot(level.dimension().location().toString(), worldPosition, getDockName(), getTownId(), getOwnerUuid(), facilities()));
    }
    public List<AirportFacility> facilities() { return List.copyOf(facilities.values()); }
    public AirportValidation.Result registerFacility(AirportFacility facility) {
        if (!(level instanceof ServerLevel) || facility == null || (!facilities.containsKey(facility.id()) && facilities.size() >= 16)
                || facility.start().distSqr(worldPosition) > 512D * 512 || facility.end().distSqr(worldPosition) > 512D * 512) return AirportValidation.Result.INVALID_GEOMETRY;
        // An occupied resource cannot be moved out from beneath an approaching aircraft.
        if (reservations.active(level.getGameTime()).stream().anyMatch(value -> value.facilityId().equals(facility.id()))) return AirportValidation.Result.OBSTRUCTED;
        AirportValidation.Result result = validate(facility);
        if (result == AirportValidation.Result.VALID) {
            facilities.put(facility.id(), facility); validation.remove(facility.id()); reservationChecks.remove(facility.id());
            arrivalChecks.values().removeIf(facility.id()::equals);
            preflights.values().removeIf(value -> value.validation.facility.id().equals(facility.id())); setChanged();
        }
        return result;
    }
    public boolean removeFacility(String id) {
        if (!(level instanceof ServerLevel) || reservations.active(level.getGameTime()).stream().anyMatch(value -> value.facilityId().equals(id))) return false;
        if (facilities.remove(id) == null) return false;
        reservations.removeFacility(id); validation.remove(id); reservationChecks.remove(id); arrivalChecks.values().removeIf(id::equals);
        preflights.values().removeIf(value -> value.validation.facility.id().equals(id)); setChanged(); return true;
    }
    public AirportValidation.Result validateFacility(String id) { return validate(facilities.get(id)); }
    private AirportValidation.Result validate(AirportFacility facility) {
        if (!(level instanceof ServerLevel server)) return AirportValidation.Result.UNLOADED;
        return AirportValidation.validate(facility,
                pos -> !server.isOutsideBuildHeight(pos) && server.hasChunkAt(pos),
                pos -> server.hasChunkAt(pos) && server.getFluidState(pos).isEmpty() && server.getBlockState(pos).getCollisionShape(server, pos).isEmpty(),
                pos -> server.hasChunkAt(pos) && server.getBlockState(pos).isFaceSturdy(server, pos, Direction.UP));
    }
    private boolean chunksPresent(ServerLevel server, AirportFacility facility) {
        var bounds = facility.clearanceBounds();
        if (server.isOutsideBuildHeight(facility.start().below()) || server.isOutsideBuildHeight(BlockPos.containing(bounds.maxX, bounds.maxY - 1, bounds.maxZ))) return false;
        for (int x = ((int) Math.floor(bounds.minX)) >> 4; x <= ((int) Math.floor(bounds.maxX)) >> 4; x++)
            for (int z = ((int) Math.floor(bounds.minZ)) >> 4; z <= ((int) Math.floor(bounds.maxZ)) >> 4; z++)
                if (!server.hasChunk(x, z)) return false;
        return true;
    }
    /** At most 2,048 geometry cells per airport per tick; successful snapshots expire after 20 ticks. */
    private AirportValidation.Result cachedValidation(AirportFacility facility) {
        if (!(level instanceof ServerLevel server) || !chunksPresent(server, facility)) {
            validation.remove(facility.id()); return AirportValidation.Result.UNLOADED;
        }
        long now = level.getGameTime();
        ValidationState state = validation.get(facility.id());
        if (state == null || !state.facility.equals(facility)
                || state.result != AirportValidation.Result.WAITING && now - state.checkedAt >= 20) {
            state = new ValidationState(facility); validation.put(facility.id(), state);
        }
        advanceValidation(server, state);
        return state.result;
    }
    private void advanceValidation(ServerLevel server, ValidationState state) {
        long now = server.getGameTime();
        if (budgetTick != now) { budgetTick = now; remainingBudget = SCAN_BUDGET; }
        if (state.result == AirportValidation.Result.WAITING && remainingBudget > 0) {
            state.result = state.scan.advance(remainingBudget,
                    pos -> !server.isOutsideBuildHeight(pos) && server.hasChunkAt(pos),
                    pos -> server.hasChunkAt(pos) && server.getFluidState(pos).isEmpty() && server.getBlockState(pos).getCollisionShape(server, pos).isEmpty(),
                    pos -> server.hasChunkAt(pos) && server.getBlockState(pos).isFaceSturdy(server, pos, Direction.UP));
            remainingBudget -= state.scan.stepsUsed();
            if (state.result != AirportValidation.Result.WAITING) state.checkedAt = now;
        }
    }
    /** A fresh per-intent source scan; successful results stay pinned until handshake or deadline. */
    public Optional<AirportFacility> preparedDeparture(UUID attemptId, LogisticsCarrier carrier, AircraftClass airframe, double speedMetersPerSecond) {
        if (!(level instanceof ServerLevel) || attemptId == null || !supportsLogisticsCarrier(carrier)
                || !carrier.isTransportAlive() || !level.dimension().location().toString().equals(carrier.dimensionId())
                || !(carrier instanceof AirportApproachState approach) || !approach.physicalArrivalReady()) {
            clearPreflight(attemptId); return Optional.empty();
        }
        for (AirportFacility facility : facilities.values()) {
            if (!facility.supports(airframe) || !facility.isParked(carrier.transportBounds(), speedMetersPerSecond)
                    || !reservationAvailable(facility, carrier.getTransportUuid()) || occupiedByOtherAircraft(facility, carrier.getTransportUuid())) continue;
            return preparedValidation(attemptId, carrier.getTransportUuid(), facility) == AirportValidation.Result.VALID
                    ? Optional.of(facility) : Optional.empty();
        }
        clearPreflight(attemptId); return Optional.empty();
    }
    /** Pin one compatible, currently unoccupied destination while this intent's scan completes. */
    public Optional<AirportFacility> preparedLanding(UUID attemptId, UUID aircraftId, AircraftClass airframe) {
        if (!(level instanceof ServerLevel) || attemptId == null || aircraftId == null) return Optional.empty();
        PreflightState pinned = preflights.get(attemptId);
        AirportFacility selected = pinned == null ? null : facilities.get(pinned.validation.facility.id());
        if (selected != null && selected.supports(airframe) && reservationAvailable(selected, aircraftId) && !occupiedByOtherAircraft(selected, aircraftId)) {
            AirportValidation.Result result = preparedValidation(attemptId, aircraftId, selected);
            if (result == AirportValidation.Result.VALID) return Optional.of(selected);
            if (result == AirportValidation.Result.WAITING) return Optional.empty();
        }
        for (AirportFacility facility : facilities.values()) {
            if (facility.equals(selected) || !facility.supports(airframe) || !reservationAvailable(facility, aircraftId) || occupiedByOtherAircraft(facility, aircraftId)) continue;
            AirportValidation.Result result = preparedValidation(attemptId, aircraftId, facility);
            if (result == AirportValidation.Result.VALID) return Optional.of(facility);
            if (result == AirportValidation.Result.WAITING) return Optional.empty();
        }
        return Optional.empty();
    }
    private AirportValidation.Result preparedValidation(UUID attemptId, UUID aircraftId, AirportFacility facility) {
        if (!(level instanceof ServerLevel server) || !chunksPresent(server, facility)) {
            clearPreflight(attemptId); return AirportValidation.Result.UNLOADED;
        }
        long now = server.getGameTime();
        preflights.values().removeIf(state -> now < state.startedAt || now - state.startedAt >= PREFLIGHT_MAX_TICKS);
        PreflightState state = preflights.get(attemptId);
        if (state == null || !state.aircraftId.equals(aircraftId) || !state.validation.facility.equals(facility)
                || state.validation.result != AirportValidation.Result.WAITING && state.validation.result != AirportValidation.Result.VALID
                && now - state.validation.checkedAt >= 20) {
            if (state == null && preflights.size() >= MAX_PREFLIGHTS) return AirportValidation.Result.WAITING;
            state = new PreflightState(aircraftId, facility, now); preflights.put(attemptId, state);
        }
        advanceValidation(server, state.validation);
        return state.validation.result;
    }
    /** Consume the destination preparation without starting another out-of-phase validation. */
    public boolean reservePreparedFacility(UUID attemptId, String facilityId, UUID aircraftId, long ttlTicks) {
        if (!(level instanceof ServerLevel server) || attemptId == null || aircraftId == null) return false;
        PreflightState state = preflights.get(attemptId);
        AirportFacility facility = facilities.get(facilityId);
        long now = server.getGameTime();
        if (state == null || facility == null || !state.aircraftId.equals(aircraftId) || !state.validation.facility.equals(facility)
                || state.validation.result != AirportValidation.Result.VALID || now < state.startedAt || now - state.startedAt >= PREFLIGHT_MAX_TICKS
                || !chunksPresent(server, facility) || !reservationAvailable(facility, aircraftId) || occupiedByOtherAircraft(facility, aircraftId)) return false;
        if (!reservations.reserve(facilityId, aircraftId, now, ttlTicks)) return false;
        reservationChecks.put(facilityId, aircraftId);
        validation.put(facilityId, state.validation);
        setChanged(); return true;
    }
    /** Flight acceptance/cancellation releases scan state; the separately owned landing lease remains. */
    public void clearPreflight(UUID attemptId) { if (attemptId != null) preflights.remove(attemptId); }
    public Optional<AirportFacility> availableFacility(AircraftClass airframe) { return availableFacility(airframe, null); }
    public Optional<AirportFacility> availableFacility(AircraftClass airframe, UUID aircraftId) {
        if (!(level instanceof ServerLevel)) return Optional.empty();
        return facilities.values().stream().filter(value -> value.supports(airframe))
                .filter(value -> reservationAvailable(value, aircraftId))
                .filter(value -> !occupiedByOtherAircraft(value, aircraftId))
                .filter(value -> cachedValidation(value) == AirportValidation.Result.VALID).findFirst();
    }
    public boolean reserveFacility(String facilityId, UUID aircraftId, long ttlTicks) {
        AirportFacility facility = facilities.get(facilityId);
        if (!(level instanceof ServerLevel) || facility == null || aircraftId == null || !reservationAvailable(facility, aircraftId)
                || occupiedByOtherAircraft(facility, aircraftId)) return false;
        // Hold the resource while a fresh bounded scan runs, otherwise two requesters can
        // repeatedly invalidate one another's pending scan without either gaining a lease.
        if (!reservations.reserve(facilityId, aircraftId, level.getGameTime(), ttlTicks)) return false;
        if (!aircraftId.equals(reservationChecks.get(facilityId))) {
            reservationChecks.put(facilityId, aircraftId); validation.remove(facilityId);
        }
        AirportValidation.Result result = cachedValidation(facility);
        if (result != AirportValidation.Result.VALID && result != AirportValidation.Result.WAITING) {
            reservations.removeFacility(facilityId); reservationChecks.remove(facilityId);
        }
        setChanged();
        return result == AirportValidation.Result.VALID;
    }
    private boolean reservationAvailable(AirportFacility target, UUID aircraftId) {
        for (AirportReservationBook.Reservation occupied : reservations.active(level.getGameTime())) {
            AirportFacility other = facilities.get(occupied.facilityId());
            if (!occupied.aircraftId().equals(aircraftId) && other != null && other.bounds().intersects(target.bounds())) return false;
        }
        return true;
    }
    private boolean occupiedByOtherAircraft(AirportFacility target, UUID aircraftId) {
        if (!(level instanceof ServerLevel server)) return true;
        return TransportRegistry.nearby(server, target.bounds()).stream().anyMatch(carrier -> !carrier.getTransportUuid().equals(aircraftId)
                && carrier.transportKind() == LogisticsCarrier.Kind.AIR && carrier.isTransportAlive());
    }
    public void releaseReservation(UUID aircraftId) {
        if (level instanceof ServerLevel) {
            reservations.release(aircraftId); reservationChecks.values().removeIf(aircraftId::equals);
            arrivalChecks.remove(aircraftId); preflights.values().removeIf(state -> state.aircraftId.equals(aircraftId)); setChanged();
        }
    }
    public List<AirportReservationBook.Reservation> reservations() { return level == null ? List.of() : reservations.active(level.getGameTime()); }
    public boolean isParked(LogisticsCarrier carrier, AircraftClass airframe, double speedMetersPerSecond) {
        return parkedFacility(carrier, airframe, speedMetersPerSecond).isPresent();
    }
    public Optional<AirportFacility> parkedFacility(LogisticsCarrier carrier, AircraftClass airframe, double speedMetersPerSecond) {
        if (!supportsLogisticsCarrier(carrier)) return Optional.empty();
        for (AirportFacility value : facilities.values()) {
            if (!value.supports(airframe) || !value.isParked(carrier.transportBounds(), speedMetersPerSecond)) continue;
            if (!value.id().equals(arrivalChecks.get(carrier.getTransportUuid()))) {
                arrivalChecks.put(carrier.getTransportUuid(), value.id()); validation.remove(value.id());
            }
            return cachedValidation(value) == AirportValidation.Result.VALID ? Optional.of(value) : Optional.empty();
        }
        arrivalChecks.remove(carrier.getTransportUuid());
        return Optional.empty();
    }
    @Override public boolean isCarrierInsideDockZone(LogisticsCarrier carrier) {
        if (!(carrier instanceof AirportApproachState state) || !state.physicalArrivalReady() || state.velocityMetersPerSecond() == null) {
            if (carrier != null) arrivalChecks.remove(carrier.getTransportUuid());
            return false;
        }
        return isParked(carrier, state.aircraftClass(), state.velocityMetersPerSecond().length());
    }
    @Override public boolean isInsideDockZone(net.minecraft.world.phys.Vec3 point) { return false; }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider); ListTag values = new ListTag(); for (AirportFacility facility : facilities.values()) values.add(facility.save());
        tag.put("AirportFacilities", values); tag.put("AirportReservations", reservations.save());
    }
    @Override public void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider); facilities.clear();
        for (Tag raw : tag.getList("AirportFacilities", Tag.TAG_COMPOUND)) {
            if (facilities.size() >= 16) break;
            try { AirportFacility facility = AirportFacility.load((CompoundTag) raw); facilities.put(facility.id(), facility); } catch (IllegalArgumentException ignored) { }
        }
        reservations = AirportReservationBook.load(tag.getCompound("AirportReservations"));
        validation.clear(); reservationChecks.clear(); arrivalChecks.clear(); preflights.clear();
    }
}
