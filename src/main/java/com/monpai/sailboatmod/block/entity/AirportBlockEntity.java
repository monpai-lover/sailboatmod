package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.registry.ModBlockEntities;
import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.transport.TransportRegistry;
import com.monpai.sailboatmod.transport.air.AircraftClass;
import com.monpai.sailboatmod.transport.air.ground.GroundRunwayReadiness;
import com.monpai.sailboatmod.transport.air.airport.*;
import com.monpai.sailboatmod.transport.air.rail.RailAirportSelection;
import com.monpai.sailboatmod.transport.physical.PhysicalVehicleView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

/** Reuses warehouse/escrow delivery, but admits only physically parked compatible aircraft. */
public final class AirportBlockEntity extends DockBlockEntity {
    private final Map<String, AirportFacility> facilities = new LinkedHashMap<>();
    private AirportReservationBook reservations = new AirportReservationBook();
    private AirportGroundLayout groundLayout = AirportGroundLayout.empty();
    private long editorRevision;
    private final Map<UUID, GroundReservation> groundReservations = new LinkedHashMap<>();
    private final Map<UUID, GroundPreparation> groundPreflights = new HashMap<>();
    private final Map<String, ValidationState> validation = new HashMap<>();
    private final Map<String, UUID> reservationChecks = new HashMap<>();
    private final Map<UUID, String> arrivalChecks = new HashMap<>();
    private final Map<UUID, PreflightState> preflights = new HashMap<>();
    private long budgetTick = Long.MIN_VALUE;
    private int remainingBudget;
    private static final int SCAN_BUDGET = AirportEditorSession.PER_TICK_BUDGET;
    private AirportEditorSession editorSession;
    public AirportEditorSession editorSession() { return editorSession; }
    public boolean queueEditor(AirportEditorSession next) {
        if (editorSession != null) {
            if (!editorSession.owner.equals(next.owner)) return false;
            if (editorSession.baseRevision == next.baseRevision && editorSession.proposal.equals(next.proposal)) return true;
            editorSession.cancel();
        }
        editorSession = next; return true;
    }
    public void clearEditor(AirportEditorSession expected) {
        if (editorSession == expected) { editorSession.cancel(); editorSession = null; }
    }
    public static void serverTick(Level level, BlockPos pos, BlockState state, AirportBlockEntity airport) {
        if (!(level instanceof ServerLevel server) || airport.editorSession == null) return;
        long now = server.getGameTime();
        if (airport.budgetTick != now) { airport.budgetTick = now; airport.remainingBudget = SCAN_BUDGET; }
        // Snapshot replies may inspect facility status recursively. Reserve this work first,
        // so those reads cannot spend the same terrain budget a second time.
        int available = airport.remainingBudget;
        airport.remainingBudget = 0;
        int used = AirportManagementService.advanceEditor(airport, available);
        airport.remainingBudget += available - used;
    }
    @Override public void setRemoved() {
        if (editorSession != null) { editorSession.cancel(); editorSession = null; }
        super.setRemoved();
    }
    public void editorValidatedFacility(AirportFacility facility) {
        if (level == null || !facilities.containsValue(facility)) return;
        ValidationState state = new ValidationState(facility);
        state.result = AirportValidation.Result.VALID; state.checkedAt = level.getGameTime();
        validation.put(facility.id(), state);
    }
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
        for (AirportParkingStand stand : groundLayout().stands()) area=area.minmax(stand.bounds());
        return area;
    }
    @Override protected void syncFacilityMarkers() {
        if (level instanceof ServerLevel server) AirportSavedData.get(server).put(new AirportSavedData.AirportSnapshot(level.dimension().location().toString(), worldPosition, getDockName(), getTownId(), getOwnerUuid(), facilities()));
    }
    /** Ground data is separate from legacy runway/helipad/mooring geometry. */
    public AirportGroundLayout groundLayout() { return groundLayout == null ? AirportGroundLayout.empty() : groundLayout; }
    public long groundLayoutRevision() { return groundLayout().revision(); }
    /** Editing names must not invalidate an aircraft's geometry assignment or reservation. */
    public long editorRevision() { return Math.max(editorRevision, groundLayoutRevision()); }
    public boolean renameFromEditor(long expectedRevision, String name) {
        if (!(level instanceof ServerLevel) || expectedRevision != editorRevision() || expectedRevision >= Long.MAX_VALUE - 1) return false;
        if (!getCustomDockName().equals(name)) {
            editorRevision = expectedRevision + 1;
            setDockName(name);
        }
        return true;
    }
    /** Called after both route endpoints have been checked on the server tick. */
    public void publishRoutesFromEditor(List<com.monpai.sailboatmod.route.RouteDefinition> routes) {
        editorRevision = editorRevision() + 1;
        setRoutes(routes, routes.size() - 1);
    }
    private void updateGroundLayout(AirportGroundLayout layout) {
        editorRevision = editorRevision() + 1;
        groundLayout = layout;
    }
    public AirportValidation.Result facilityStatus(String id) {
        ValidationState state=validation.get(id);
        if(state==null||level==null||level.getGameTime()<state.checkedAt||level.getGameTime()-state.checkedAt>=20)return AirportValidation.Result.WAITING;
        return state.result;
    }
    public record GroundReservation(GroundAssignment assignment,UUID aircraftId,long expiresAtTick) {}
    private static final class GroundPreparation {
        final UUID aircraftId;final GroundAssignment assignment;final AirportGroundValidation.Scan scan;final long startedAt;
        AirportGroundValidation.Result result=AirportGroundValidation.Result.WAITING;
        GroundPreparation(UUID aircraftId,GroundAssignment assignment,AirportGroundLayout layout,List<AirportFacility> facilities,long now){
            this.aircraftId=aircraftId;this.assignment=assignment;this.scan=new AirportGroundValidation.Scan(selectedGroundLayout(layout,assignment),facilities);this.startedAt=now;
        }
    }
    /** Sticky safety claims: an elapsed TTL requests review; it does not prove the tarmac empty. */
    public boolean groundReserved(){return groundReservations!=null&&!groundReservations.isEmpty();}
    public List<GroundReservation> groundReservations(){return groundReservations==null?List.of():List.copyOf(groundReservations.values());}
    public boolean holdsGroundAssignment(GroundAssignment assignment,UUID aircraftId){
        return assignment!=null&&aircraftId!=null&&groundReservations().stream().anyMatch(r->r.aircraftId().equals(aircraftId)&&r.assignment().equals(assignment));
    }
    public String standStatus(String id){var stand=groundLayout().stand(id).orElse(null);return stand==null?"missing":groundResourceStatus(stand.bounds());}
    public String taxiStatus(String id){var route=groundLayout().taxiRoutes().stream().filter(r->r.id().equals(id)).findFirst().orElse(null);return route==null?"missing":groundResourceStatus(route.bounds());}
    private String groundResourceStatus(net.minecraft.world.phys.AABB bounds){
        if(!(level instanceof ServerLevel server)||!loadedBounds(server,bounds))return "unloaded";
        if(groundReserved())return groundReservations().stream().anyMatch(r->r.expiresAtTick()<=server.getGameTime())?"review":"reserved";
        return occupiedBounds(bounds,null)?"occupied":"waiting";
    }
    private boolean loadedBounds(ServerLevel server,net.minecraft.world.phys.AABB bounds){
        if(server.isOutsideBuildHeight(BlockPos.containing(bounds.minX,bounds.minY-1,bounds.minZ))||server.isOutsideBuildHeight(BlockPos.containing(bounds.maxX,bounds.maxY-1,bounds.maxZ)))return false;
        for(int x=((int)Math.floor(bounds.minX))>>4;x<=((int)Math.floor(bounds.maxX))>>4;x++)for(int z=((int)Math.floor(bounds.minZ))>>4;z<=((int)Math.floor(bounds.maxZ))>>4;z++)if(!server.hasChunk(x,z))return false;return true;
    }
    private boolean occupiedBounds(net.minecraft.world.phys.AABB bounds,UUID aircraftId){
        if(!(level instanceof ServerLevel server))return true;
        return TransportRegistry.nearby(server,bounds).stream().anyMatch(c->!c.getTransportUuid().equals(aircraftId)&&c.transportKind()==LogisticsCarrier.Kind.AIR&&c.isTransportAlive()&&c.transportBounds().intersects(bounds));
    }
    /** Whole-airport locking deliberately rejects geometry edits while any aircraft uses its resources. */
    public boolean configurationOccupied(List<AirportFacility> proposed,AirportGroundLayout candidate){
        if(!(level instanceof ServerLevel)||groundReserved()||!reservations.active(level.getGameTime()).isEmpty())return true;
        for(var f:facilities.values())if(occupiedBounds(f.bounds(),null))return true;
        for(var f:proposed)if(occupiedBounds(f.bounds(),null))return true;
        for(var s:groundLayout().stands())if(occupiedBounds(s.bounds(),null))return true;
        for(var r:groundLayout().taxiRoutes())if(occupiedBounds(r.bounds(),null))return true;
        for(var s:candidate.stands())if(occupiedBounds(s.bounds(),null))return true;
        for(var r:candidate.taxiRoutes())if(occupiedBounds(r.bounds(),null))return true;return false;
    }
    public boolean replaceGroundConfiguration(long expectedRevision,List<AirportFacility> proposed,AirportGroundLayout candidate){
        if(candidate==null||expectedRevision!=groundLayoutRevision()||editorRevision()>=Long.MAX_VALUE-1||candidate.revision()!=expectedRevision+1
                ||proposed==null||proposed.size()>16||AirportGroundValidation.references(candidate,proposed)!=AirportGroundValidation.Result.VALID
                ||configurationOccupied(proposed,candidate))return false;
        Set<String> ids=new HashSet<>();for(var facility:proposed)if(facility==null||!facility.validGeometry()||!ids.add(facility.id()))return false;
        facilities.clear();for(var facility:proposed)facilities.put(facility.id(),facility);updateGroundLayout(candidate);
        validation.clear();reservationChecks.clear();arrivalChecks.clear();preflights.clear();groundPreflights.clear();setChanged();return true;
    }
    private GroundAssignment assignment(String runwayId,String standId,AirportTaxiRoute.Direction direction){
        return GroundAssignment.select(groundLayout(),facilities(),runwayId,standId,direction).orElse(null);
    }
    private boolean currentAssignment(GroundAssignment assignment){
        return assignment!=null&&(assignment.forDirection(groundLayout(),facilities(),AirportTaxiRoute.Direction.OUTBOUND).filter(assignment::equals).isPresent()
                ||assignment.forDirection(groundLayout(),facilities(),AirportTaxiRoute.Direction.INBOUND).filter(assignment::equals).isPresent());
    }
    private boolean groundAvailable(UUID aircraftId){
        if(!(level instanceof ServerLevel))return false;
        return groundReservations().stream().allMatch(r->r.aircraftId().equals(aircraftId))
                &&reservations.active(level.getGameTime()).stream().allMatch(r->r.aircraftId().equals(aircraftId));
    }
    private boolean groundOccupiedByOther(UUID aircraftId){
        for(var f:facilities.values())if(occupiedBounds(f.bounds(),aircraftId))return true;
        for(var s:groundLayout().stands())if(occupiedBounds(s.bounds(),aircraftId))return true;
        for(var r:groundLayout().taxiRoutes())if(occupiedBounds(r.bounds(),aircraftId))return true;return false;
    }
    private boolean railGroundOccupiedByOther(GroundAssignment assignment, UUID aircraftId) {
        if (!(level instanceof ServerLevel server)) return true;
        AABB facility = facilities.get(assignment.runwayId()).bounds();
        if (railResourceOccupied(server, facility, aircraftId, facility::intersects)) return true;
        if (assignment.direct()) return false;
        var selected = selectedGroundLayout(groundLayout(), assignment);
        AABB stand = selected.stands().getFirst().bounds();
        if (railResourceOccupied(server, stand, aircraftId, stand::intersects)) return true;
        return selected.taxiRoutes().stream().anyMatch(route ->
                railResourceOccupied(server, route.bounds(), aircraftId, route::intersectsHull));
    }
    private static boolean railResourceOccupied(ServerLevel server, AABB bounds, UUID aircraftId, Predicate<AABB> overlaps) {
        return TransportRegistry.nearby(server, bounds).stream().anyMatch(carrier ->
                !carrier.getTransportUuid().equals(aircraftId) && carrier.isTransportAlive() && overlaps.test(carrier.transportBounds()));
    }
    /** Stand loading is independent of the later runway/alignment gate. */
    public Optional<GroundAssignment> preparedGroundDeparture(UUID attemptId,LogisticsCarrier carrier,double speed,double yaw){
        return preparedGroundDeparture(attemptId,carrier,speed,yaw,null);
    }
    /** A return leg must keep the exact previously confirmed stand and both route IDs. */
    public Optional<GroundAssignment> preparedGroundDeparture(UUID attemptId,LogisticsCarrier carrier,double speed,double yaw,GroundAssignment exact){
        if(!(level instanceof ServerLevel)||attemptId==null||!supportsLogisticsCarrier(carrier)||!carrier.isTransportAlive()
                ||!level.dimension().location().toString().equals(carrier.dimensionId())||!(carrier instanceof AirportApproachState approach)
                ||approach.aircraftClass()!=AircraftClass.FIXED_WING||!approach.groundArrivalReady()||!groundAvailable(carrier.getTransportUuid())||groundOccupiedByOther(carrier.getTransportUuid()))return Optional.empty();
        var layout=groundLayout();
        if(exact!=null){
            if(exact.forDirection(layout,facilities(),AirportTaxiRoute.Direction.OUTBOUND).filter(exact::equals).isEmpty()
                    ||layout.stand(exact.standId()).filter(s->s.contains(carrier.transportBounds(),yaw,speed)).isEmpty()
                    ||!assignmentFits(exact,carrier.transportBounds()))return Optional.empty();
            return preparedGroundAssignment(attemptId,carrier.getTransportUuid(),exact);
        }
        for(var stand:layout.stands()){
            if(!stand.cargoEnabled()||!stand.contains(carrier.transportBounds(),yaw,speed))continue;
            for(var runway:facilities.values()){
                GroundAssignment assignment=assignment(runway.id(),stand.id(),AirportTaxiRoute.Direction.OUTBOUND);if(assignment==null)continue;
                if(!assignmentFits(assignment,carrier.transportBounds()))continue;
                return prepareGround(attemptId,carrier.getTransportUuid(),assignment)?Optional.of(assignment):Optional.empty();
            }
        }return Optional.empty();
    }
    private boolean assignmentFits(GroundAssignment assignment,net.minecraft.world.phys.AABB hull){
        return groundLayout().taxiRoutes().stream().filter(r->r.id().equals(assignment.outboundRouteId())||r.id().equals(assignment.inboundRouteId())).allMatch(r->r.fits(hull));
    }
    /** Explicit IDs pin the selected destination; null IDs select once from configured same-stand pairs. */
    public Optional<GroundAssignment> preparedGroundLanding(UUID attemptId,UUID aircraftId,String runwayId,String standId){
        if(!(level instanceof ServerLevel)||attemptId==null||aircraftId==null||!groundAvailable(aircraftId)||groundOccupiedByOther(aircraftId))return Optional.empty();
        GroundPreparation pinned=groundPreflights.get(attemptId);
        if(pinned!=null&&pinned.assignment.forDirection(groundLayout(),facilities(),AirportTaxiRoute.Direction.INBOUND).filter(pinned.assignment::equals).isPresent()
                &&(runwayId==null||runwayId.equals(pinned.assignment.runwayId()))&&(standId==null||standId.equals(pinned.assignment.standId())))
            return prepareGround(attemptId,aircraftId,pinned.assignment)?Optional.of(pinned.assignment):Optional.empty();
        for(var stand:groundLayout().stands())for(var runway:facilities.values()){
            if((standId!=null&&!standId.equals(stand.id()))||(runwayId!=null&&!runwayId.equals(runway.id())))continue;
            GroundAssignment assignment=assignment(runway.id(),stand.id(),AirportTaxiRoute.Direction.INBOUND);if(assignment!=null)return prepareGround(attemptId,aircraftId,assignment)?Optional.of(assignment):Optional.empty();
        }return Optional.empty();
    }
    public Optional<GroundAssignment> preparedGroundLanding(UUID attemptId,UUID aircraftId,GroundAssignment exact){
        if(exact==null||exact.forDirection(groundLayout(),facilities(),AirportTaxiRoute.Direction.INBOUND).filter(exact::equals).isEmpty())return Optional.empty();
        return preparedGroundAssignment(attemptId,aircraftId,exact);
    }
    /** Refresh an already captured permit without choosing any replacement route or stand. */
    public Optional<GroundAssignment> preparedGroundAssignment(UUID attemptId,UUID aircraftId,GroundAssignment exact){
        if(!(level instanceof ServerLevel)||attemptId==null||aircraftId==null||!currentAssignment(exact)
                ||!groundAvailable(aircraftId)||groundOccupiedByOther(aircraftId))return Optional.empty();
        return prepareGround(attemptId,aircraftId,exact)?Optional.of(exact):Optional.empty();
    }
    /** Distinguishes a progressing scan from a hard candidate rejection without reserving anything. */
    public AirportGroundPreparation.Check prepareGroundCandidate(UUID attemptId,UUID aircraftId,GroundAssignment assignment){
        return prepareGroundCandidate(attemptId, aircraftId, assignment, false);
    }
    public AirportGroundPreparation.Check prepareRailGroundCandidate(UUID attemptId, UUID aircraftId, GroundAssignment assignment) {
        return prepareGroundCandidate(attemptId, aircraftId, assignment, true);
    }
    private AirportGroundPreparation.Check prepareGroundCandidate(UUID attemptId, UUID aircraftId, GroundAssignment assignment, boolean rail) {
        var ready=AirportGroundPreparation.Status.READY;var waiting=AirportGroundPreparation.Status.WAITING;var blocked=AirportGroundPreparation.Status.BLOCKED;
        if(!(level instanceof ServerLevel server)||attemptId==null||aircraftId==null||!currentAssignment(assignment))
            return new AirportGroundPreparation.Check(blocked,"ground_binding_changed");
        if (!assignment.direct()) {
            var references=AirportGroundValidation.references(groundLayout(),facilities());
            if(references!=AirportGroundValidation.Result.VALID)return new AirportGroundPreparation.Check(blocked,references.name().toLowerCase(Locale.ROOT));
        }
        // Active leases are still exclusive across the airport, even for physically separate assignments.
        if(!groundAvailable(aircraftId)||(rail ? railGroundOccupiedByOther(assignment, aircraftId) : groundOccupiedByOther(aircraftId)))
            return new AirportGroundPreparation.Check(waiting,"airport_reserved");
        long now=server.getGameTime();groundPreflights.values().removeIf(p->now<p.startedAt||now-p.startedAt>=PREFLIGHT_MAX_TICKS);
        var facility=preparedValidation(attemptId,aircraftId,facilities.get(assignment.runwayId()));
        if(facility!=AirportValidation.Result.VALID)return new AirportGroundPreparation.Check(
                facility==AirportValidation.Result.WAITING||facility==AirportValidation.Result.UNLOADED?waiting:blocked,
                facility.name().toLowerCase(Locale.ROOT));
        if (assignment.direct()) return new AirportGroundPreparation.Check(ready,"");
        GroundPreparation prepared=groundPreflights.get(attemptId);
        if(prepared==null||!prepared.aircraftId.equals(aircraftId)||!prepared.assignment.equals(assignment)){
            if(prepared==null&&groundPreflights.size()>=MAX_PREFLIGHTS)return new AirportGroundPreparation.Check(waiting,"airport_unavailable");
            prepared=new GroundPreparation(aircraftId,assignment,groundLayout(),facilities(),now);groundPreflights.put(attemptId,prepared);
        }
        if(prepared.result!=AirportGroundValidation.Result.WAITING&&prepared.result!=AirportGroundValidation.Result.VALID&&now-prepared.startedAt>=20){
            prepared=new GroundPreparation(aircraftId,assignment,groundLayout(),facilities(),now);groundPreflights.put(attemptId,prepared);
        }
        if(budgetTick!=now){budgetTick=now;remainingBudget=SCAN_BUDGET;}
        if(prepared.result==AirportGroundValidation.Result.WAITING&&remainingBudget>0){
            prepared.result=prepared.scan.advance(remainingBudget,p->!server.isOutsideBuildHeight(p)&&server.hasChunkAt(p),
                    p->server.hasChunkAt(p)&&server.getFluidState(p).isEmpty()&&server.getBlockState(p).getCollisionShape(server,p).isEmpty(),
                    p->server.hasChunkAt(p)&&server.getBlockState(p).isFaceSturdy(server,p,Direction.UP));remainingBudget-=prepared.scan.stepsUsed();
        }
        if(prepared.result==AirportGroundValidation.Result.VALID)return new AirportGroundPreparation.Check(
                groundAssignmentLoaded(server,assignment)?ready:waiting,"airport_unavailable");
        return new AirportGroundPreparation.Check(prepared.result==AirportGroundValidation.Result.WAITING||prepared.result==AirportGroundValidation.Result.UNLOADED?waiting:blocked,
                prepared.result.name().toLowerCase(Locale.ROOT));
    }
    private boolean prepareGround(UUID attemptId,UUID aircraftId,GroundAssignment assignment){
        return prepareGroundCandidate(attemptId,aircraftId,assignment).status()==AirportGroundPreparation.Status.READY;
    }
    private static AirportGroundLayout selectedGroundLayout(AirportGroundLayout layout,GroundAssignment assignment){
        var stand=layout.stand(assignment.standId()).orElseThrow();
        var routes=layout.taxiRoutes().stream().filter(r->r.id().equals(assignment.outboundRouteId())||r.id().equals(assignment.inboundRouteId())).toList();
        return new AirportGroundLayout(layout.revision(),List.of(stand),routes);
    }
    private boolean groundAssignmentLoaded(ServerLevel server,GroundAssignment assignment){
        if(!currentAssignment(assignment))return false;
        if(assignment.direct())return chunksPresent(server,facilities.get(assignment.runwayId()));
        var selected=selectedGroundLayout(groundLayout(),assignment);
        for(var stand:selected.stands())if(!loadedBounds(server,stand.bounds()))return false;
        for(var route:selected.taxiRoutes())if(!loadedBounds(server,route.bounds()))return false;return true;
    }
    public boolean reserveGroundAssignment(UUID attemptId,GroundAssignment assignment,UUID aircraftId,long ttlTicks){
        return reserveGroundAssignment(attemptId, assignment, aircraftId, ttlTicks, false);
    }
    public boolean reserveRailGroundAssignment(UUID attemptId, GroundAssignment assignment, UUID aircraftId, long ttlTicks) {
        return reserveGroundAssignment(attemptId, assignment, aircraftId, ttlTicks, true);
    }
    private boolean reserveGroundAssignment(UUID attemptId, GroundAssignment assignment, UUID aircraftId, long ttlTicks, boolean rail) {
        if(!(level instanceof ServerLevel server)||!currentAssignment(assignment)||!groundAvailable(aircraftId)
                ||(rail ? railGroundOccupiedByOther(assignment, aircraftId) : groundOccupiedByOther(aircraftId)))return false;
        var prepared=groundPreflights.get(attemptId);long now=server.getGameTime();
        if(!assignment.direct()&&(prepared==null||!prepared.aircraftId.equals(aircraftId)||!prepared.assignment.equals(assignment)
                ||prepared.result!=AirportGroundValidation.Result.VALID||now<prepared.startedAt||now-prepared.startedAt>=PREFLIGHT_MAX_TICKS
                ||!groundAssignmentLoaded(server,assignment)))return false;
        GroundReservation existing=groundReservations.get(aircraftId);
        if(existing!=null&&!existing.assignment().equals(assignment))return false;
        if(!reservePreparedFacility(attemptId,assignment.runwayId(),aircraftId,ttlTicks))return false;
        groundReservations.put(aircraftId,new GroundReservation(assignment,aircraftId,now+ttlTicks));setChanged();return true;
    }
    /** Legacy callers retain their fail-closed boolean contract. */
    public boolean groundRunwayReady(GroundAssignment assignment,LogisticsCarrier carrier,double speed,double yaw){
        return groundRunwayReadiness(assignment,carrier,speed,yaw)==GroundRunwayReadiness.READY;
    }
    /** Waiting for bounded terrain evidence is distinct from a failed physical/ownership gate. */
    public GroundRunwayReadiness groundRunwayReadiness(GroundAssignment assignment,LogisticsCarrier carrier,double speed,double yaw){
        if(!groundCarrierBound(assignment,carrier)||!Double.isFinite(speed)||speed<0||speed>.25||!Double.isFinite(yaw))
            return GroundRunwayReadiness.BLOCKED;
        AirportFacility runway=facilities.get(assignment.runwayId());var hull=carrier.transportBounds();
        UUID aircraftId=carrier.getTransportUuid();
        if(runway==null||hull==null||!groundAvailable(aircraftId)||!reservationAvailable(runway,aircraftId))
            return GroundRunwayReadiness.BLOCKED;
        double heading=Math.toDegrees(Math.atan2(-(runway.end().getX()-(double)runway.start().getX()),runway.end().getZ()-(double)runway.start().getZ()));
        boolean aligned=AirportGroundLayout.angleDifference(yaw,heading)<=10&&hull.minY>=runway.start().getY()-.5&&hull.maxY<=runway.start().getY()+runway.clearanceHeight()
                &&runway.containsHorizontal(hull.minX,hull.minZ,0)&&runway.containsHorizontal(hull.minX,hull.maxZ,0)&&runway.containsHorizontal(hull.maxX,hull.minZ,0)&&runway.containsHorizontal(hull.maxX,hull.maxZ,0);
        if(!aligned)return GroundRunwayReadiness.BLOCKED;
        if(!groundAssignmentLoaded((ServerLevel)level,assignment))return GroundRunwayReadiness.WAITING;
        AirportValidation.Result validation=cachedValidation(runway);
        if(validation==AirportValidation.Result.WAITING||validation==AirportValidation.Result.UNLOADED)return GroundRunwayReadiness.WAITING;
        if(validation!=AirportValidation.Result.VALID)return GroundRunwayReadiness.BLOCKED;
        // A sticky claim prevents reassignment after expiry, but FC also needs a current runway permit.
        // Renew through the ordinary exclusive book only after the real scan is VALID.
        long now=level.getGameTime();
        if(!reservations.reserve(runway.id(),aircraftId,now,AirportReservationBook.MAX_TTL_TICKS))return GroundRunwayReadiness.BLOCKED;
        groundReservations.put(aircraftId,new GroundReservation(assignment,aircraftId,now+AirportReservationBook.MAX_TTL_TICKS));
        reservationChecks.put(runway.id(),aircraftId);setChanged();return GroundRunwayReadiness.READY;
    }
    public boolean assignedStandArrival(GroundAssignment assignment,LogisticsCarrier carrier,double speed,double yaw){
        return groundCarrierReady(assignment,carrier)&&groundLayout().stand(assignment.standId()).orElseThrow().contains(carrier.transportBounds(),yaw,speed);
    }
    /** Pure queue eligibility; does not advance terrain preparation, reserve resources or authorize cargo. */
    public boolean railDepartureGeometry(LogisticsCarrier carrier,PhysicalVehicleView actual,double yaw,double angularSpeed){
        if(!railViewMatches(carrier,actual==null?null:actual.physicalId(),actual,yaw))return false;
        var aircraft=new RailAirportSelection.Aircraft(actual.physicalId(),actual.worldBounds(),actual.worldPosition(),yaw);
        var airframe=((AirportApproachState)carrier).aircraftClass();
        if (airframe!=AircraftClass.FIXED_WING) return RailAirportSelection.directCandidates(airframe,groundLayout(),facilities(),null).stream()
                .anyMatch(candidate -> RailAirportSelection.directParked(aircraft,candidate.runway(),actual.linearVelocity().length(),angularSpeed));
        return RailAirportSelection.select(aircraft,groundLayout(),facilities(),null,groundLayout(),facilities(),null,
                selection->RailAirportSelection.parked(aircraft,selection.source().stand(),actual.linearVelocity().length(),angularSpeed))
                .selection().isPresent();
    }
    /** Real parked geometry and the ordinary bounded terrain scan; no flight controller or wheel proof. */
    public boolean railDepartureReady(LogisticsCarrier carrier,PhysicalVehicleView actual,double yaw,double angularSpeed){
        if(!railViewMatches(carrier,actual==null?null:actual.physicalId(),actual,yaw))return false;
        var aircraft=new RailAirportSelection.Aircraft(actual.physicalId(),actual.worldBounds(),actual.worldPosition(),yaw);
        var airframe=((AirportApproachState)carrier).aircraftClass();
        if (airframe!=AircraftClass.FIXED_WING) return RailAirportSelection.directCandidates(airframe,groundLayout(),facilities(),null).stream()
                .filter(candidate -> RailAirportSelection.directParked(aircraft,candidate.runway(),actual.linearVelocity().length(),angularSpeed))
                .anyMatch(candidate -> railDeparturePreparation(carrier,actual,candidate.assignment()));
        Map<GroundAssignment,Boolean> observed=new HashMap<>();
        return RailAirportSelection.select(aircraft,groundLayout(),facilities(),null,groundLayout(),facilities(),null,selection->{
            GroundAssignment assignment=selection.source().assignment();
            return observed.computeIfAbsent(assignment,key->{
                if(!RailAirportSelection.parked(aircraft,selection.source().stand(),actual.linearVelocity().length(),angularSpeed))return false;
                return railDeparturePreparation(carrier,actual,key);
            });
        }).selection().isPresent();
    }
    private boolean railDeparturePreparation(LogisticsCarrier carrier,PhysicalVehicleView actual,GroundAssignment assignment) {
        UUID attempt=UUID.nameUUIDFromBytes(("rail-stand:"+carrier.getTransportUuid()+":"+actual.physicalId()+":"+assignment)
                .getBytes(StandardCharsets.UTF_8));
        return prepareRailGroundCandidate(attempt,carrier.getTransportUuid(),assignment).status()==AirportGroundPreparation.Status.READY;
    }
    /** A fresh geometric observation for the caller's consecutive-tick dwell counter; never asks it for arrival proof. */
    public boolean railStandContains(GroundAssignment assignment,LogisticsCarrier carrier,UUID expectedBody,
                                     PhysicalVehicleView actual,double yaw,double angularSpeed,long sampledTick){
        if(!(level instanceof ServerLevel server)||sampledTick!=server.getGameTime()
                ||!railViewMatches(carrier,expectedBody,actual,yaw)||!currentAssignment(assignment)
                ||!groundAvailable(carrier.getTransportUuid())||railGroundOccupiedByOther(assignment,carrier.getTransportUuid())
                ||!groundAssignmentLoaded(server,assignment))return false;
        long now=server.getGameTime();UUID owner=carrier.getTransportUuid();
        boolean currentClaim=groundReservations().stream().anyMatch(r->r.aircraftId().equals(owner)&&r.assignment().equals(assignment)&&r.expiresAtTick()>now);
        boolean currentRunway=reservations.active(now).stream().anyMatch(r->r.aircraftId().equals(owner)&&r.facilityId().equals(assignment.runwayId())&&r.expiresAtTick()>now);
        if(!currentClaim||!currentRunway)return false;
        var aircraft=new RailAirportSelection.Aircraft(expectedBody,actual.worldBounds(),actual.worldPosition(),yaw);
        var airframe=((AirportApproachState)carrier).aircraftClass();
        if (assignment.direct()) return airframe!=AircraftClass.FIXED_WING&&assignment.directType()==RailAirportSelection.directType(airframe)
                &&RailAirportSelection.directParked(aircraft,facilities.get(assignment.runwayId()),actual.linearVelocity().length(),angularSpeed);
        if (airframe!=AircraftClass.FIXED_WING) return false;
        var stand=groundLayout().stand(assignment.standId()).orElse(null);
        return stand!=null&&AirportGroundLayout.angleDifference(yaw,stand.yawDegrees())<=5
                &&RailAirportSelection.parked(aircraft,stand,actual.linearVelocity().length(),angularSpeed);
    }
    /** The counter must represent advancing actual observations, persisted/reset by the rail mission owner. */
    public boolean railAssignedStandArrival(GroundAssignment assignment,LogisticsCarrier carrier,UUID expectedBody,
                                            PhysicalVehicleView actual,double yaw,double angularSpeed,int stableTicks,long sampledTick){
        return stableTicks>=40&&railStandContains(assignment,carrier,expectedBody,actual,yaw,angularSpeed,sampledTick);
    }
    private boolean railViewMatches(LogisticsCarrier carrier,UUID expectedBody,PhysicalVehicleView actual,double yaw){
        if(!(level instanceof ServerLevel)||carrier==null||!supportsLogisticsCarrier(carrier)||!carrier.isTransportAlive()
                ||!(carrier instanceof AirportApproachState state)||!state.railAircraftAutomation()
                ||state.aircraftClass()==null||expectedBody==null||actual==null||!actual.available()
                ||!expectedBody.equals(actual.physicalId())||!level.dimension().location().toString().equals(actual.dimensionId())
                ||!actual.dimensionId().equals(carrier.dimensionId())||actual.linearVelocity()==null||actual.worldForward()==null
                ||!Double.isFinite(actual.mass())||actual.mass()<=0||!Double.isFinite(yaw))return false;
        var aircraft=new RailAirportSelection.Aircraft(actual.physicalId(),actual.worldBounds(),actual.worldPosition(),yaw);
        if(!RailAirportSelection.validAircraft(aircraft))return false;
        var carrierPosition=carrier.transportPosition();
        double positionError=carrierPosition==null?Double.NaN:carrierPosition.distanceToSqr(actual.worldPosition());
        if(!actual.worldBounds().equals(carrier.transportBounds())
                ||!Double.isFinite(positionError)||positionError>1e-10)return false;
        var forward=actual.worldForward();var velocity=actual.linearVelocity();
        return Double.isFinite(forward.x)&&Double.isFinite(forward.y)&&Double.isFinite(forward.z)&&forward.horizontalDistanceSqr()>1e-8
                &&Double.isFinite(velocity.x)&&Double.isFinite(velocity.y)&&Double.isFinite(velocity.z)
                &&AirportGroundLayout.angleDifference(yaw,Math.toDegrees(Math.atan2(-forward.x,forward.z)))<=1e-6;
    }
    private boolean groundCarrierReady(GroundAssignment assignment,LogisticsCarrier carrier){
        return groundCarrierBound(assignment,carrier)&&groundAssignmentLoaded((ServerLevel)level,assignment);
    }
    private boolean groundCarrierBound(GroundAssignment assignment,LogisticsCarrier carrier){
        return level instanceof ServerLevel&&assignment!=null&&!assignment.direct()&&currentAssignment(assignment)
                &&supportsLogisticsCarrier(carrier)&&carrier.isTransportAlive()
                &&level.dimension().location().toString().equals(carrier.dimensionId())&&carrier instanceof AirportApproachState approach
                &&approach.aircraftClass()==AircraftClass.FIXED_WING&&approach.groundArrivalReady()&&holdsGroundAssignment(assignment,carrier.getTransportUuid())
                &&!groundOccupiedByOther(carrier.getTransportUuid());
    }

    public List<AirportFacility> facilities() { return List.copyOf(facilities.values()); }
    public AirportValidation.Result registerFacility(AirportFacility facility) {
        if (!(level instanceof ServerLevel) || facility == null || (!facilities.containsKey(facility.id()) && facilities.size() >= 16)
                || facility.start().distSqr(worldPosition) > (double) facility.managementDistance() * facility.managementDistance()
                || facility.end().distSqr(worldPosition) > (double) facility.managementDistance() * facility.managementDistance()) return AirportValidation.Result.INVALID_GEOMETRY;
        // An occupied resource cannot be moved out from beneath an approaching aircraft.
        List<AirportFacility> proposed=new ArrayList<>(facilities());proposed.removeIf(f->f.id().equals(facility.id()));proposed.add(facility);
        if (configurationOccupied(proposed,groundLayout())||editorRevision()>=Long.MAX_VALUE-1||AirportGroundValidation.references(groundLayout(),proposed)!=AirportGroundValidation.Result.VALID) return AirportValidation.Result.OBSTRUCTED;
        AirportValidation.Result result = cachedValidation(facility);
        if (result == AirportValidation.Result.VALID) {
            facilities.put(facility.id(), facility); updateGroundLayout(groundLayout().withRevision(groundLayoutRevision()+1)); validation.remove(facility.id()); reservationChecks.remove(facility.id());
            arrivalChecks.values().removeIf(facility.id()::equals);
            preflights.values().removeIf(value -> value.validation.facility.id().equals(facility.id())); setChanged();
        }
        return result;
    }
    public boolean removeFacility(String id) {
        if (!(level instanceof ServerLevel)||configurationOccupied(facilities(),groundLayout())||editorRevision()>=Long.MAX_VALUE-1) return false;
        if(groundLayout().taxiRoutes().stream().anyMatch(r->r.runwayId().equals(id)))return false;
        if (facilities.remove(id) == null) return false;updateGroundLayout(groundLayout().withRevision(groundLayoutRevision()+1));
        reservations.removeFacility(id); validation.remove(id); reservationChecks.remove(id); arrivalChecks.values().removeIf(id::equals);
        preflights.values().removeIf(value -> value.validation.facility.id().equals(id)); setChanged(); return true;
    }
    public AirportValidation.Result validateFacility(String id) {
        AirportFacility facility = facilities.get(id);
        return facility == null ? AirportValidation.Result.INVALID_GEOMETRY : cachedValidation(facility);
    }
    private boolean chunksPresent(ServerLevel server, AirportFacility facility) {
        if (facility == null || !facility.validGeometry()) return false;
        var bounds = facility.clearanceBounds();
        if (server.isOutsideBuildHeight(facility.start().below()) || server.isOutsideBuildHeight(BlockPos.containing(bounds.maxX, bounds.maxY - 1, bounds.maxZ))) return false;
        var chunks = facility.clearanceChunks();
        if (chunks.isEmpty()) return false;
        for (long chunk : chunks.get())
            if (!server.hasChunk(net.minecraft.world.level.ChunkPos.getX(chunk), net.minecraft.world.level.ChunkPos.getZ(chunk))) return false;
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
    public void clearPreflight(UUID attemptId) { if (attemptId != null) { preflights.remove(attemptId); if(groundPreflights!=null) groundPreflights.remove(attemptId); } }
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
        // The ground layout currently has a conservative whole-airport conflict zone. Its persisted
        // safety claim survives the short runway TTL and applies equally to legacy admission paths.
        if(groundReservations().stream().anyMatch(claim->!claim.aircraftId().equals(aircraftId)))return false;
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
            reservations.release(aircraftId); if(groundReservations!=null)groundReservations.remove(aircraftId);if(groundPreflights!=null)groundPreflights.values().removeIf(p->p.aircraftId.equals(aircraftId)); reservationChecks.values().removeIf(aircraftId::equals);
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
    @Override protected boolean canAssignCarrierToRoute(LogisticsCarrier carrier, boolean allowCargo) {
        // Market dispatch stages the manifest before assigning the route. A first departure
        // needs the same parked-airport proof, while delivery still needs its captured arrival proof.
        return super.canAssignCarrierToRoute(carrier, allowCargo)
                || allowCargo && carrier instanceof RegisteredVehicleControllerBlockEntity controller
                    && controller.canBootstrapLoadedAtAirport(this);
    }

    @Override public boolean isCarrierInsideDockZone(LogisticsCarrier carrier) {
        if (!(carrier instanceof AirportApproachState state) || !state.physicalArrivalReady() || state.velocityMetersPerSecond() == null) {
            if (carrier != null) arrivalChecks.remove(carrier.getTransportUuid());
            return false;
        }
        if (state.railAircraftAutomation()&&state.aircraftClass()!=null&&state.aircraftClass()!=AircraftClass.FIXED_WING) {
            if (carrier instanceof RegisteredVehicleControllerBlockEntity controller&&controller.canBootstrapAtAirport(this)) return true;
            if (!(level instanceof ServerLevel server)||!state.groundAssignedArrivalReady(worldPosition)) return false;
            double speed=state.velocityMetersPerSecond().length();
            var claim=groundReservations.get(carrier.getTransportUuid());
            if (claim!=null) {
                var assignment=claim.assignment();
                var facility=facilities.get(assignment.runwayId());
                return claim.expiresAtTick()>server.getGameTime()&&assignment.directType()==RailAirportSelection.directType(state.aircraftClass())
                        &&groundAssignmentLoaded(server,assignment)&&facility.isParked(carrier.transportBounds(),speed);
            }
            return facilities.values().stream().anyMatch(facility->facility.type()==RailAirportSelection.directType(state.aircraftClass())
                    &&facility.isParked(carrier.transportBounds(),speed));
        }
        if(state.aircraftClass()==AircraftClass.FIXED_WING&&(!groundLayout().stands().isEmpty()||!groundLayout().taxiRoutes().isEmpty())) {
            if(carrier instanceof RegisteredVehicleControllerBlockEntity controller && controller.canBootstrapAtAirport(this))return true;
            if((!state.railAircraftAutomation()&&!state.groundArrivalReady())||!state.groundAssignedArrivalReady(worldPosition))return false;
            double speed=state.velocityMetersPerSecond().length(),yaw=state.groundYawDegrees();
            Optional<GroundReservation> claim=groundReservations().stream().filter(r->r.aircraftId().equals(carrier.getTransportUuid())).findFirst();
            if(state.railAircraftAutomation()&&claim.isPresent()){
                var selected=claim.get();
                return selected.expiresAtTick()>level.getGameTime()&&currentAssignment(selected.assignment())
                        &&groundAssignmentLoaded((ServerLevel)level,selected.assignment())
                        &&groundLayout().stand(selected.assignment().standId()).orElseThrow().contains(carrier.transportBounds(),yaw,speed);
            }
            if(claim.isPresent())return assignedStandArrival(claim.get().assignment(),carrier,speed,yaw);
            return groundLayout().stands().stream().anyMatch(s->s.cargoEnabled()&&s.contains(carrier.transportBounds(),yaw,speed));
        }
        return isParked(carrier, state.aircraftClass(), state.velocityMetersPerSecond().length());
    }
    @Override public boolean isInsideDockZone(net.minecraft.world.phys.Vec3 point) { return false; }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider); ListTag values = new ListTag(); for (AirportFacility facility : facilities.values()) values.add(facility.save());
        tag.put("AirportFacilities", values); tag.put("AirportReservations", reservations.save());
        tag.put("AirportGroundLayout",groundLayout().save());tag.putLong("AirportEditorRevision",editorRevision());ListTag groundClaims=new ListTag();
        for(var claim:groundReservations()){CompoundTag item=claim.assignment().save();item.putUUID("Aircraft",claim.aircraftId());item.putLong("Expires",claim.expiresAtTick());groundClaims.add(item);}tag.put("AirportGroundReservations",groundClaims);
    }
    @Override public void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider); facilities.clear();
        for (Tag raw : tag.getList("AirportFacilities", Tag.TAG_COMPOUND)) {
            if (facilities.size() >= 16) break;
            try { AirportFacility facility = AirportFacility.load((CompoundTag) raw); facilities.put(facility.id(), facility); } catch (IllegalArgumentException ignored) { }
        }
        reservations = AirportReservationBook.load(tag.getCompound("AirportReservations"));
        try { groundLayout=AirportGroundLayout.load(tag.getCompound("AirportGroundLayout")); } catch(IllegalArgumentException invalid){groundLayout=AirportGroundLayout.empty();}
        editorRevision = Math.min(tag.getLong("AirportEditorRevision"), Long.MAX_VALUE - 1);
        groundReservations.clear();groundPreflights.clear();
        for(Tag raw:tag.getList("AirportGroundReservations",Tag.TAG_COMPOUND)) { if(groundReservations.size()>=16)break;try {CompoundTag item=(CompoundTag)raw;GroundAssignment assignment=GroundAssignment.load(item);if(item.hasUUID("Aircraft")) {UUID aircraft=item.getUUID("Aircraft");groundReservations.put(aircraft,new GroundReservation(assignment,aircraft,item.getLong("Expires")));}}catch(IllegalArgumentException ignored){} }
        validation.clear(); reservationChecks.clear(); arrivalChecks.clear(); preflights.clear();
    }
}
