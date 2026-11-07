package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.dock.TownWarehouseRegistry;
import com.monpai.sailboatmod.market.terminal.TerminalNetworkSavedData;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.rail.RailTerminalActionPacket;
import com.monpai.sailboatmod.network.packet.rail.RailTerminalSnapshotPacket;
import com.monpai.sailboatmod.registry.ModBlockEntities;
import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.transport.rail.*;
import com.monpai.sailboatmod.transport.rail.ui.RailTerminalRegistry;
import com.monpai.sailboatmod.transport.rail.ui.RailTerminalStopovers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.FakePlayer;
import java.util.*;

public final class RailFreightTerminalBlockEntity extends DockBlockEntity implements RailFreightTerminalAccess {
    private UUID terminalId = UUID.randomUUID();
    private long revision;
    private RailStationRef bound;
    private final Map<BlockPos, RailTerminalStopovers.Plan> stopoverPlans = new LinkedHashMap<>();
    private Tag unreadableStopovers;
    public RailFreightTerminalBlockEntity(BlockPos pos, BlockState state) { super(ModBlockEntities.RAIL_FREIGHT_TERMINAL_BLOCK_ENTITY.get(), pos, state); }
    public UUID terminalId() { return terminalId; }
    public long revision() { return revision; }
    @Override public Optional<RailStationRef> stationRef() {
        if (!(level instanceof ServerLevel server) || !validTownBinding()) return Optional.empty();
        return RailFreightService.inspectStation(server, bound.stationPos()).filter(bound::equals);
    }
    @Override public List<RailStationRef> railStopovers(BlockPos targetTerminal) {
        var plan = stopoverPlans.get(targetTerminal);
        return plan == null ? List.of() : plan.stops().stream().map(RailTerminalStopovers.Choice::station).toList();
    }
    @Override public List<Integer> railStopoverSeconds(BlockPos targetTerminal) {
        var plan = stopoverPlans.get(targetTerminal);
        return plan == null ? List.of() : plan.dwellSeconds();
    }
    @Override public boolean railStopoversMatch(BlockPos targetTerminal, RailStationRef targetStation) {
        if (unreadableStopovers != null) return false;
        var plan = stopoverPlans.get(targetTerminal);
        return plan == null || level instanceof ServerLevel server && plan.destination().station().equals(targetStation)
                && matchesStopoverChoice(server, plan.destination());
    }
    private boolean validTownBinding() {
        if (level == null || bound == null || !bound.dimensionId().equals(level.dimension().location().toString())
                || bound.stationPos().distSqr(worldPosition) > 64) return false;
        var terminalTown = TownService.getTownAt(level, worldPosition);
        var stationTown = TownService.getTownAt(level, bound.stationPos());
        return terminalTown != null && stationTown != null && terminalTown.townId().equals(getTownId())
                && terminalTown.townId().equals(stationTown.townId());
    }
    private boolean stationObservationPending(ServerLevel server) {
        if (!validTownBinding()) return false;
        if (!server.hasChunkAt(bound.stationPos())) return true;
        return RailFreightService.inspectStation(server, bound.stationPos()).isEmpty()
                && net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(server.getBlockState(bound.stationPos()).getBlock())
                .equals(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", "track_station"));
    }
    @Override protected void registerFacility(Level level, BlockPos pos) { RailTerminalRegistry.register(level, pos); }
    @Override protected void unregisterFacility(Level level, BlockPos pos) { RailTerminalRegistry.unregister(level, pos); }
    @Override protected void syncFacilityMarkers() { }
    @Override protected String defaultFacilityName() { return "Rail Freight Terminal"; }
    @Override protected String defaultFacilityNameSuffix() { return "'s Rail Freight Terminal"; }
    @Override protected boolean supportsLogisticsCarrier(LogisticsCarrier carrier) { return carrier != null && "create-train".equals(carrier.providerId()); }
    @Override protected boolean isValidFacilityZone(Level level, int minX, int maxX, int minZ, int maxZ) { return false; }
    @Override public List<LogisticsCarrier> getAvailableSailboatsForDispatch(Player player) {
        if (!(level instanceof ServerLevel server)) return List.of();
        return stationRef().map(s -> RailFreightService.readyCarriers(server, s)).orElse(List.of());
    }
    @Override public List<LogisticsCarrier> getAssignableSailboats(Player player) { return getAvailableSailboatsForDispatch(player); }
    @Override public boolean isCarrierInsideDockZone(LogisticsCarrier carrier) {
        return level instanceof ServerLevel server && carrier != null
                && stationRef().map(station -> RailFreightService.isDocked(server, carrier, station)).orElse(false);
    }
    public boolean canManage(ServerPlayer player) {
        if (player == null || player instanceof FakePlayer || isRemoved() || player.serverLevel() != level
                || !level.hasChunkAt(worldPosition) || !canManageDock(player)) return false;
        double distance = player.distanceToSqr(worldPosition.getCenter());
        if (!Double.isFinite(distance) || distance < 0 || distance > 64) return false;
        var town = TownService.getManagedTownAt(player, worldPosition);
        return town != null && town.townId().equals(getTownId());
    }
    public String apply(ServerPlayer player, RailTerminalActionPacket packet) {
        if (!canManage(player)) return "forbidden";
        if (!terminalId.equals(packet.terminalId()) || revision != packet.revision() || revision >= Long.MAX_VALUE - 1) return "stale";
        if (!(level instanceof ServerLevel server)) return "unavailable";
        if (packet.action() == RailTerminalActionPacket.Action.REFRESH) return "";
        boolean changed;
        switch (packet.action()) {
            case RENAME -> { setDockName(packet.name()); changed = true; }
            case BIND -> {
                if (bound != null && !RailFreightService.registrationsAt(server, bound).isEmpty()) return "registered_station";
                var selected = RailFreightService.inspectStation(server, packet.stationPos()).orElse(null);
                var town = TownService.getManagedTownAt(player, packet.stationPos());
                if (selected == null || !selected.stationId().equals(packet.trainId())
                        || selected.stationPos().distSqr(worldPosition) > 64 || town == null || !town.townId().equals(getTownId())) return "invalid_station";
                for (var pos : RailTerminalRegistry.get(level)) {
                    if (!pos.equals(worldPosition) && level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof RailFreightTerminalBlockEntity other
                            && selected.equals(other.bound)) return "station_in_use";
                }
                if (!selected.equals(bound)) {
                    stopoverPlans.clear();
                    unreadableStopovers = null;
                }
                bound = selected;
                var network = TerminalNetworkSavedData.get(level);
                var previous = network.getEntry(level.dimension().location().toString(), worldPosition.asLong());
                if (previous != null) network.putEntry(previous.withReachableTownIds(Set.of()));
                changed = true;
            }
            case REGISTER -> {
                var station = stationRef().orElse(null);
                var train = station == null ? null : RailFreightService.inspectTrain(server, station).orElse(null);
                changed = train != null && train.trainId().equals(packet.trainId()) && RailFreightService.register(player, station);
            }
            case UNREGISTER, ENABLE, DISABLE -> {
                if (bound == null || RailFreightService.registrationsAt(server, bound).stream().noneMatch(r -> r.trainId().equals(packet.trainId()))) return "invalid_train";
                changed = packet.action() == RailTerminalActionPacket.Action.UNREGISTER
                        ? RailFreightService.unregister(player, packet.trainId())
                        : RailFreightService.setEnabled(player, packet.trainId(), packet.action() == RailTerminalActionPacket.Action.ENABLE);
            }
            case SET_STOPOVERS -> {
                var requested = packet.stopovers();
                var current = stopoverPlans.get(requested.destination().terminalPos());
                return saveStopovers(server, legacyStopoverEdit(requested, current));
            }
            case SET_TIMED_STOPOVERS -> { return saveStopovers(server, packet.stopovers()); }
            default -> changed = false;
        }
        if (!changed) return "rejected";
        revision++; setChanged(); return "saved";
    }
    static RailTerminalStopovers.Plan legacyStopoverEdit(RailTerminalStopovers.Plan requested,
                                                         RailTerminalStopovers.Plan current) {
        if (current == null || !current.destination().equals(requested.destination()))
            return new RailTerminalStopovers.Plan(requested.destination(), requested.stops());
        var seconds = new ArrayList<Integer>(requested.stops().size());
        for (var stop : requested.stops()) {
            int previous = current.stops().indexOf(stop);
            seconds.add(previous < 0 ? RailRoute.DEFAULT_STOPOVER_SECONDS : current.dwellSeconds().get(previous));
        }
        return new RailTerminalStopovers.Plan(requested.destination(), requested.stops(), seconds);
    }
    private String saveStopovers(ServerLevel server, RailTerminalStopovers.Plan plan) {
        if (unreadableStopovers != null) return "invalid_stopover_data";
        var target = plan.destination();
        var current = stopoverPlans.get(target.terminalPos());
        // A saved route can be cleared even if its remote terminal was removed or unloaded.
        if (plan.stops().isEmpty() && current != null && current.destination().equals(target)) {
            stopoverPlans.remove(target.terminalPos());
        } else {
            var source = stationRef().orElse(null);
            if (!matchesStopoverChoice(server, target)) return "invalid_stopover_target";
            if (!plan.validFrom(worldPosition, source)) return "invalid_stopover_station";
            if (plan.stops().isEmpty()) return "saved";
            if (current == null && stopoverPlans.size() >= RailTerminalStopovers.MAX_ROUTES) return "stopover_limit";
            RailStationRef previous = source;
            for (var stop : plan.stops()) {
                if (!matchesStopoverChoice(server, stop)) return "invalid_stopover_station";
                if (!RailFreightService.stationsConnected(server, previous, stop.station())) return "invalid_stopover_connection";
                previous = stop.station();
            }
            if (!RailFreightService.stationsConnected(server, previous, target.station())) return "invalid_stopover_connection";
            if (plan.equals(current)) return "saved";
            stopoverPlans.put(target.terminalPos(), plan);
        }
        revision++;
        setChanged();
        return "saved";
    }
    private static boolean matchesStopoverChoice(ServerLevel server, RailTerminalStopovers.Choice choice) {
        BlockPos pos = choice.terminalPos();
        return server.hasChunkAt(pos) && server.getBlockEntity(pos) instanceof RailFreightTerminalBlockEntity terminal
                && terminal.terminalId.equals(choice.terminalId()) && terminal.stationRef().filter(choice.station()::equals).isPresent();
    }
    public CompoundTag snapshot(ServerPlayer player, String result) {
        CompoundTag data = new CompoundTag();
        data.putString("Dimension", level.dimension().location().toString()); data.putLong("Pos", worldPosition.asLong());
        data.putUUID("TerminalId", terminalId); data.putLong("Revision", revision); data.putString("Name", getDockName()); data.putString("CustomName", getCustomDockName());
        data.putString("Result", result); data.putString("Town", getTownId());
        var warehouse = TownWarehouseRegistry.get(level, getTownId());
        data.putString("Warehouse", warehouse == null ? "" : warehouse.toShortString());
        if (!(level instanceof ServerLevel server)) return data;
        if (bound != null) { data.putString("Station", bound.stationPos().toShortString()); data.putUUID("StationId", bound.stationId()); }
        data.putBoolean("StationValid", stationRef().isPresent());
        ListTag stations = new ListTag();
        for (var station : RailFreightService.nearbyStations(server, worldPosition, 8).stream().limit(16).toList()) {
            var town = TownService.getManagedTownAt(player, station.stationPos());
            if (town == null || !town.townId().equals(getTownId())) continue;
            CompoundTag entry = new CompoundTag(); entry.putLong("Pos", station.stationPos().asLong()); entry.putUUID("Id", station.stationId());
            stations.add(entry);
        }
        data.put("Stations", stations);
        ListTag trains = new ListTag();
        var registered = bound == null ? List.<RailFreightService.RegistrationSnapshot>of() : RailFreightService.registrationsAt(server, bound);
        var candidates = new ArrayList<>(registered);
        stationRef().flatMap(s -> RailFreightService.inspectTrain(server, s)).ifPresent(current -> {
            if (candidates.stream().noneMatch(r -> r.trainId().equals(current.trainId()))) candidates.add(current);
        });
        for (var train : candidates.stream().limit(32).toList()) {
            CompoundTag entry = new CompoundTag(); entry.putUUID("Id", train.trainId()); entry.putString("Name", train.trainName());
            entry.putString("Owner", train.ownerId() == null ? "" : train.ownerId().toString());
            entry.putBoolean("Owned", player.getUUID().equals(train.ownerId())); entry.putBoolean("Registered", train.registered());
            entry.putBoolean("Enabled", train.enabled()); entry.putString("Status", train.status());
            if (!train.blockedFrom().isBlank() && !train.blockedTo().isBlank()) {
                entry.putString("BlockedFrom", train.blockedFrom());
                entry.putString("BlockedTo", train.blockedTo());
            }
            entry.putInt("Slots", train.cargoSlots()); entry.putInt("Used", train.cargoUsed()); entry.putInt("FuelSafeSlots", train.fuelSafeSlots());
            entry.putString("Order", train.purchaseOrderId() == null ? "" : train.purchaseOrderId());
            if (train.source() != null) entry.putString("Source", train.source().stationPos().toShortString());
            if (train.target() != null) entry.putString("Target", train.target().stationPos().toShortString());
            trains.add(entry);
        }
        data.put("Trains", trains);
        data.putBoolean("TimedStopovers", true);
        data.putBoolean("StopoversUnreadable", unreadableStopovers != null);
        ListTag plans = new ListTag();
        stopoverPlans.values().forEach(plan -> plans.add(plan.save()));
        data.put("StopoverPlans", plans);
        ListTag choices = new ListTag();
        RailStationRef stopoverSource = stationRef().orElse(null);
        if (stopoverSource != null) {
            RailTerminalRegistry.get(server).stream().filter(pos -> !pos.equals(worldPosition))
                    .sorted(Comparator.<BlockPos>comparingDouble(pos -> pos.distSqr(worldPosition)).thenComparingLong(BlockPos::asLong))
                    .limit(64).forEach(pos -> {
                        if (!server.hasChunkAt(pos) || !(server.getBlockEntity(pos) instanceof RailFreightTerminalBlockEntity other)) return;
                        other.stationRef().filter(station -> RailFreightService.stationsConnected(server, stopoverSource, station)).ifPresent(station -> {
                            CompoundTag entry = new RailTerminalStopovers.Choice(pos, other.terminalId, station).save();
                            entry.putString("Name", other.getDockName());
                            choices.add(entry);
                        });
                    });
        }
        data.put("StopoverChoices", choices);
        return data;
    }
    public void reply(ServerPlayer player, String result, boolean open) {
        reply(player, result, open, null);
    }
    public void reply(ServerPlayer player, String result, boolean open, RailTerminalActionPacket.Action action) {
        if (!canManage(player)) return;
        CompoundTag data = snapshot(player, result);
        if (action != null && !result.isBlank()) data.putString("ReplyAction", action.name());
        ModNetwork.CHANNEL.send(player, new RailTerminalSnapshotPacket(data, open));
    }
    @Override protected void refreshTerminalNetworkEntry() {
        if (!(level instanceof ServerLevel server)) return;
        var network = TerminalNetworkSavedData.get(level);
        var old = network.getEntry(level.dimension().location().toString(), worldPosition.asLong());
        network.putEntry(new TerminalNetworkSavedData.TerminalEntry(level.dimension().location().toString(), worldPosition.asLong(),
                TerminalNetworkSavedData.Kind.RAIL, getDockName(), getTownId(), getNationId(), List.of(), old == null ? Set.of() : old.reachableTownIds()));
        com.monpai.sailboatmod.transport.board.TownVoyagePublisher.observeEndpoint(server, worldPosition, getTownId(), getDockName());
    }
    public static void serverTick(Level level, BlockPos pos, BlockState state, RailFreightTerminalBlockEntity terminal) {
        if (!(level instanceof ServerLevel server) || Math.floorMod(server.getGameTime() + pos.asLong(), 100) != 0) return;
        // An unloaded endpoint is unknown, not evidence that its rail connection disappeared.
        var network = TerminalNetworkSavedData.get(level);
        var previous = network.getEntry(level.dimension().location().toString(), pos.asLong());
        Set<String> reachable = new LinkedHashSet<>(previous == null ? Set.of() : previous.reachableTownIds());
        if (terminal.stationObservationPending(server)) return;
        var source = terminal.stationRef().orElse(null);
        Map<String, Boolean> observed = new HashMap<>();
        Set<String> unknown = new HashSet<>();
        var endpoints = network.all().stream().filter(e -> e.kind() == TerminalNetworkSavedData.Kind.RAIL
                && e.dimId().equals(level.dimension().location().toString())).toList();
        boolean completeScan = endpoints.size() <= 256;
        if (source == null) reachable.clear();
        else for (var candidate : endpoints.stream().limit(256).toList()) {
            if (candidate.townId().isBlank()) continue;
            if (!level.hasChunkAt(candidate.pos())) { unknown.add(candidate.townId()); continue; }
            if (!(level.getBlockEntity(candidate.pos()) instanceof RailFreightTerminalBlockEntity target)) {
                observed.merge(candidate.townId(), false, Boolean::logicalOr); continue;
            }
            if (target.stationObservationPending(server)) { unknown.add(candidate.townId()); continue; }
            boolean connected = target.stationRef().map(s -> RailFreightService.stationsConnected(server, source, s)).orElse(false);
            observed.merge(candidate.townId(), connected, Boolean::logicalOr);
        }
        if (completeScan) reachable.retainAll(endpoints.stream().map(TerminalNetworkSavedData.TerminalEntry::townId).collect(java.util.stream.Collectors.toSet()));
        observed.forEach((town, connected) -> { if (connected) reachable.add(town); else if (completeScan && !unknown.contains(town)) reachable.remove(town); });
        network.putEntry(new TerminalNetworkSavedData.TerminalEntry(level.dimension().location().toString(), pos.asLong(),
                TerminalNetworkSavedData.Kind.RAIL, terminal.getDockName(), terminal.getTownId(), terminal.getNationId(), List.of(), reachable));
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider); tag.putUUID("RailTerminalId", terminalId); tag.putLong("RailRevision", revision);
        if (bound != null) { tag.putString("RailDimension", bound.dimensionId()); tag.putLong("RailStationPos", bound.stationPos().asLong()); tag.putUUID("RailStationId", bound.stationId()); }
        if (unreadableStopovers != null) tag.put("RailStopovers", unreadableStopovers.copy());
        else if (!stopoverPlans.isEmpty()) {
            ListTag plans = new ListTag();
            stopoverPlans.values().forEach(plan -> plans.add(plan.save()));
            tag.put("RailStopovers", plans);
        }
    }
    @Override public void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        if (tag.hasUUID("RailTerminalId")) terminalId = tag.getUUID("RailTerminalId");
        revision = Math.max(0, tag.getLong("RailRevision"));
        bound = null;
        var dimension = net.minecraft.resources.ResourceLocation.tryParse(tag.getString("RailDimension"));
        if (tag.hasUUID("RailStationId") && dimension != null && dimension.toString().equals(tag.getString("RailDimension")))
            bound = new RailStationRef(tag.getString("RailDimension"), BlockPos.of(tag.getLong("RailStationPos")), tag.getUUID("RailStationId"));
        stopoverPlans.clear();
        unreadableStopovers = null;
        Tag raw = tag.get("RailStopovers");
        if (raw == null) return;
        try {
            if (!(raw instanceof ListTag plans) || plans.size() > RailTerminalStopovers.MAX_ROUTES
                    || !plans.isEmpty() && plans.getElementType() != Tag.TAG_COMPOUND)
                throw new IllegalArgumentException("Invalid rail stopover catalog");
            for (Tag value : plans) {
                var plan = RailTerminalStopovers.Plan.load((CompoundTag) value);
                if (plan.stops().isEmpty() || !plan.validFrom(worldPosition, bound)
                        || stopoverPlans.putIfAbsent(plan.destination().terminalPos(), plan) != null)
                    throw new IllegalArgumentException("Invalid or duplicate rail stopover route");
            }
        } catch (IllegalArgumentException failure) {
            stopoverPlans.clear();
            unreadableStopovers = raw.copy();
        }
    }
}
