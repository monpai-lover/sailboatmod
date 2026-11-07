package com.monpai.sailboatmod.block.entity;

import static org.junit.jupiter.api.Assertions.*;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.network.packet.airport.AirportSnapshotPacket;
import com.monpai.sailboatmod.transport.*;
import com.monpai.sailboatmod.transport.air.AircraftClass;
import com.monpai.sailboatmod.transport.air.airport.*;
import io.netty.buffer.Unpooled;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.*;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.*;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

class AirportGroundOperationsTest {
    @TempDir Path directory;
    TestLevel level;AirportBlockEntity airport;Aircraft aircraft;UUID attempt=UUID.randomUUID();
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @BeforeEach void setup()throws Exception{
        level=allocate(TestLevel.class);level.server=allocate(DedicatedServer.class);level.time=100;level.unloaded=new HashSet<>();level.blocked=new HashSet<>();
        set(MinecraftServer.class,level.server,"levels",Map.of(Level.OVERWORLD,level));level.storage=new DimensionDataStorage(directory.toFile(),null,level.registryAccess());
        airport=allocate(AirportBlockEntity.class);set(BlockEntity.class,airport,"worldPosition",BlockPos.ZERO);airport.setLevel(level);set(BlockEntity.class,airport,"blockState",Blocks.AIR.defaultBlockState());
        set(DockBlockEntity.class,airport,"routes",new ArrayList<>());for(String field:List.of("dockName","ownerName","ownerUuid","townId","nationId"))set(DockBlockEntity.class,airport,field,"");
        for(Field field:AirportBlockEntity.class.getDeclaredFields())if(Map.class.isAssignableFrom(field.getType())){field.setAccessible(true);field.set(airport,new LinkedHashMap<>());}
        var runway=new AirportFacility("main",AirportFacility.Type.RUNWAY,new BlockPos(0,64,0),new BlockPos(48,64,0),7,8);
        set(AirportBlockEntity.class,airport,"facilities",new LinkedHashMap<>(Map.of("main",runway)));set(AirportBlockEntity.class,airport,"reservations",new AirportReservationBook());
        set(AirportBlockEntity.class,airport,"budgetTick",Long.MIN_VALUE);
        var stand=new AirportParkingStand("a",new Vec3(-20.5,64,.5),0,12,8,8,true);
        var out=new AirportTaxiRoute("out",AirportTaxiRoute.Direction.OUTBOUND,"main","a",List.of(stand.position(),new Vec3(-10,64,.5),new Vec3(.5,64,.5)),8,8,2,1);
        var in=new AirportTaxiRoute("in",AirportTaxiRoute.Direction.INBOUND,"main","a",List.of(new Vec3(40.5,64,.5),stand.position()),8,8,2,-1);
        set(AirportBlockEntity.class,airport,"groundLayout",new AirportGroundLayout(2,List.of(stand),List.of(out,in)));aircraft=new Aircraft();
    }
    @AfterEach void clear(){TransportRegistry.clear(level.server);}
    @Test void airportOwnerCanRenameWhileAnAircraftHoldsAnAssignmentOutsideTheTownFootprint() throws Exception {
        TestPlayer owner=airportOwner();
        var assignment=prepare();
        assertTrue(airport.reserveGroundAssignment(attempt,assignment,aircraft.id,100));
        var layout=airport.groundLayout();
        var facilities=airport.facilities();
        var reservations=airport.groundReservations();
        assertTrue(airport.configurationOccupied(facilities,layout));

        var result=rename(owner,2,"West Field");

        assertTrue(result.success(),result.code());
        assertEquals(3,result.revision());
        assertEquals("West Field",airport.getDockName());
        assertSame(layout,airport.groundLayout(),"Renaming must not replace or revise the geometry");
        assertEquals(facilities,airport.facilities());
        assertEquals(reservations,airport.groundReservations());
        assertTrue(airport.assignedStandArrival(assignment,aircraft.proxy,0,0),"Previously held assignment stays valid");

        var snapshot=AirportScreenData.capture(owner,airport,"ok");
        var buffer=new FriendlyByteBuf(Unpooled.buffer());
        try {
            AirportSnapshotPacket.encode(new AirportSnapshotPacket(snapshot,false),buffer);
            var readback=AirportSnapshotPacket.decode(buffer).data();
            assertEquals(3,readback.revision());
            assertEquals(layout.withRevision(3),readback.groundLayout());
            assertEquals("West Field",readback.customName());
            assertSame(layout,airport.groundLayout());
        } finally {buffer.release();}
    }
    @Test void airportRenameUsesTheEditorRevisionAndRetainsOwnerAndTownChecks() throws Exception {
        TestPlayer owner=airportOwner();
        assertTrue(rename(owner,2,"West Field").success());
        assertEquals(3,AirportScreenData.capture(owner,airport,"").revision());
        level.time+=5;
        assertEquals("stale_revision",rename(owner,2,"Old Draft").code());

        set(DockBlockEntity.class,airport,"ownerUuid",UUID.randomUUID().toString());
        assertEquals("forbidden",rename(owner,3,"Foreign Owner").code());
        set(DockBlockEntity.class,airport,"ownerUuid",owner.getUUID().toString());
        set(DockBlockEntity.class,airport,"townId","different-town");
        assertEquals("forbidden",rename(owner,3,"Foreign Town").code());
        set(DockBlockEntity.class,airport,"townId","airport-town");

        assertTrue(rename(owner,3,"").success());
        assertEquals("Air Town",airport.getDockName());
        assertEquals(2,airport.groundLayoutRevision());
    }
    private TestPlayer airportOwner() throws Exception {
        level.airport=airport;
        TestPlayer owner=allocate(TestPlayer.class);
        owner.level=level;
        owner.uuid=UUID.randomUUID();
        set(Entity.class,owner,"position",BlockPos.ZERO.getCenter());
        set(DockBlockEntity.class,airport,"ownerUuid",owner.uuid.toString());
        set(DockBlockEntity.class,airport,"townId","airport-town");
        NationSavedData.get(level).putTown(new TownRecord("airport-town","","Air Town",owner.uuid,1L,
                "minecraft:overworld",BlockPos.ZERO.asLong(),"","european"));
        return owner;
    }
    private AirportManagementService.Result rename(TestPlayer owner,long revision,String name){
        CompoundTag action=new CompoundTag();
        action.putString("Action","rename");
        action.putString("Name",name);
        return AirportManagementService.apply(owner,BlockPos.ZERO,revision,action);
    }
    private GroundAssignment prepare(){for(int i=0;i<200;i++,level.time+=5){var value=airport.preparedGroundDeparture(attempt,aircraft.proxy,0,0);if(value.isPresent())return value.get();}return fail("bounded ground preparation did not finish");}
    private void oppositeRunways()throws Exception{
        var main=airport.facilities().getFirst();
        var reverse=new AirportFacility("reverse",AirportFacility.Type.RUNWAY,main.end(),main.start(),main.width(),main.clearanceHeight());
        var facilities=new LinkedHashMap<String,AirportFacility>();facilities.put("main",main);facilities.put("reverse",reverse);
        set(AirportBlockEntity.class,airport,"facilities",facilities);
        var layout=airport.groundLayout();var out=layout.taxiRoutes().getFirst();
        var in=new AirportTaxiRoute("in",AirportTaxiRoute.Direction.INBOUND,"reverse","a",List.of(new Vec3(.5,64,.5),layout.stands().getFirst().position()),8,8,2,-1);
        set(AirportBlockEntity.class,airport,"groundLayout",new AirportGroundLayout(2,layout.stands(),List.of(out,in)));
    }
    @Test void independentlyAssignedOppositeRunwaysPrepareDepartureAndArrivalAtTheSameStand()throws Exception{
        oppositeRunways();
        var departure=prepare();assertEquals("main",departure.runwayId());
        assertEquals("out",departure.outboundRouteId());assertEquals("in",departure.inboundRouteId());
        UUID landingAttempt=UUID.randomUUID();Optional<GroundAssignment> landing=Optional.empty();
        for(int i=0;i<200&&landing.isEmpty();i++,level.time+=5)landing=airport.preparedGroundLanding(landingAttempt,aircraft.id,null,null);
        assertTrue(landing.isPresent(),"arrival must select the runway assigned to the stored inbound route");
        assertEquals("reverse",landing.orElseThrow().runwayId());assertEquals(departure.standId(),landing.orElseThrow().standId());
        assertEquals(departure.outboundRouteId(),landing.orElseThrow().outboundRouteId());assertEquals(departure.inboundRouteId(),landing.orElseThrow().inboundRouteId());
    }
    @Test void reverseFacilityCannotAcquireASecondLeaseOnTheSamePhysicalRunway()throws Exception{
        oppositeRunways();var departure=prepare();assertTrue(airport.reserveGroundAssignment(attempt,departure,aircraft.id,100));
        assertFalse(airport.reserveFacility("reverse",UUID.randomUUID(),100));
        assertTrue(airport.preparedGroundAssignment(attempt,aircraft.id,departure).isPresent(),"departure permit must refresh its captured OUT without requiring IN on that runway ID");
        assertFalse(airport.assignedStandArrival(new GroundAssignment(2,"reverse","a","out","in"),aircraft.proxy,0,0),"an opposite-direction assignment cannot borrow another exact reservation");
    }
    @Test void exactReturnKeepsStoredRouteIdsAndNeverSelectsAReplacement()throws Exception{
        oppositeRunways();var departure=prepare();
        var arrival=departure.forDirection(airport.groundLayout(),airport.facilities(),AirportTaxiRoute.Direction.INBOUND).orElseThrow();
        UUID arrivalAttempt=UUID.randomUUID();Optional<GroundAssignment> ready=Optional.empty();
        for(int i=0;i<200&&ready.isEmpty();i++,level.time+=5)ready=airport.preparedGroundLanding(arrivalAttempt,aircraft.id,arrival);
        assertEquals(arrival,ready.orElseThrow());
        var nextDeparture=arrival.forDirection(airport.groundLayout(),airport.facilities(),AirportTaxiRoute.Direction.OUTBOUND).orElseThrow();
        assertEquals(departure,nextDeparture);
        UUID returnAttempt=UUID.randomUUID();Optional<GroundAssignment> returned=Optional.empty();
        for(int i=0;i<200&&returned.isEmpty();i++,level.time+=5)returned=airport.preparedGroundDeparture(returnAttempt,aircraft.proxy,0,0,nextDeparture);
        assertEquals(departure,returned.orElseThrow());
        assertTrue(airport.preparedGroundLanding(UUID.randomUUID(),aircraft.id,new GroundAssignment(2,"reverse","a","missing","in")).isEmpty());
        assertTrue(airport.preparedGroundDeparture(UUID.randomUUID(),aircraft.proxy,0,0,new GroundAssignment(3,"main","a","out","in")).isEmpty());
        assertTrue(airport.preparedGroundDeparture(UUID.randomUUID(),aircraft.proxy,0,0,arrival).isEmpty(),"arrival role may not become a departure permit without explicit conversion");
    }
    @Test void standLoadingAndRunwayReadyAreSeparateAndDeliveryRequiresExactClaim(){
        assertFalse(airport.assignedStandArrival(new GroundAssignment(2,"main","a","out","in"),aircraft.proxy,0,0));
        var assignment=prepare();assertTrue(airport.reserveGroundAssignment(attempt,assignment,aircraft.id,100));
        assertTrue(airport.assignedStandArrival(assignment,aircraft.proxy,0,0));
        assertFalse(airport.groundRunwayReady(assignment,aircraft.proxy,0,0));
        aircraft.hull=aircraft.hull.move(30,0,0);
        assertFalse(airport.assignedStandArrival(assignment,aircraft.proxy,0,0));
        boolean ready=false;for(int i=0;i<100&&!ready;i++,level.time+=5)ready=airport.groundRunwayReady(assignment,aircraft.proxy,0,-90);
        assertTrue(ready);
    }
    @Test void genericDockZoneNeedsMissionSpecificAirportProofAndNeverFallsBackToRunway(){
        assertFalse(airport.isCarrierInsideDockZone(aircraft.proxy));
        aircraft.assignedReady=true;assertTrue(airport.isCarrierInsideDockZone(aircraft.proxy));
        aircraft.hull=aircraft.hull.move(30,0,0);assertFalse(airport.isCarrierInsideDockZone(aircraft.proxy));
    }
    @Test void obstacleBuiltDuringTaxiCannotReuseOldRunwayPreflightForFcHandoff(){
        var assignment=prepare();assertTrue(airport.reserveGroundAssignment(attempt,assignment,aircraft.id,1000));
        aircraft.hull=aircraft.hull.move(30,0,0);level.time+=25;level.blocked.add(new BlockPos(10,66,0));
        for(int i=0;i<100;i++,level.time++)assertFalse(airport.groundRunwayReady(assignment,aircraft.proxy,0,-90));
    }
    @Test void geometryAndLowSpeedNeverSubstituteForNativeGroundProof(){
        var assignment=prepare();assertTrue(airport.reserveGroundAssignment(attempt,assignment,aircraft.id,100));
        aircraft.grounded=false;assertFalse(airport.assignedStandArrival(assignment,aircraft.proxy,0,0));
        assertTrue(airport.preparedGroundDeparture(UUID.randomUUID(),aircraft.proxy,0,0).isEmpty());
    }
    @Test void leaseExpiryStaysStickyAndRevisionMismatchCannotDeliver(){
        var assignment=prepare();assertTrue(airport.reserveGroundAssignment(attempt,assignment,aircraft.id,100));level.time+=101;
        assertTrue(airport.groundReserved());assertEquals("review",airport.standStatus("a"));
        assertTrue(airport.configurationOccupied(airport.facilities(),airport.groundLayout()));
        assertFalse(airport.assignedStandArrival(new GroundAssignment(3,"main","a","out","in"),aircraft.proxy,0,0));
        airport.releaseReservation(aircraft.id);assertFalse(airport.groundReserved());
    }
    @Test void expiredStickyGroundClaimBlocksLegacyRotorcraftAdmission(){
        var assignment=prepare();assertTrue(airport.reserveGroundAssignment(attempt,assignment,aircraft.id,100));
        level.time+=101;UUID rotorcraft=UUID.randomUUID(),rotorAttempt=UUID.randomUUID();
        for(int retry=0;retry<100;retry++,level.time+=5){
            assertTrue(airport.preparedLanding(rotorAttempt,rotorcraft,AircraftClass.ROTORCRAFT).isEmpty());
            assertFalse(airport.reserveFacility("main",rotorcraft,100));
            assertTrue(airport.availableFacility(AircraftClass.ROTORCRAFT,rotorcraft).isEmpty());
            assertTrue(airport.reservations().stream().noneMatch(r->r.aircraftId().equals(rotorcraft)),"pending scans must not create a foreign runway permit under a sticky ground claim");
        }
        airport.releaseReservation(aircraft.id);
        Optional<AirportFacility> landing=Optional.empty();
        for(int retry=0;retry<100&&landing.isEmpty();retry++,level.time+=5)landing=airport.preparedLanding(rotorAttempt,rotorcraft,AircraftClass.ROTORCRAFT);
        assertTrue(landing.isPresent());
        assertTrue(airport.reservePreparedFacility(rotorAttempt,"main",rotorcraft,100));
    }
    @Test void runwayHandoffRenewsItsOwnExpiredPermit(){
        var assignment=prepare();assertTrue(airport.reserveGroundAssignment(attempt,assignment,aircraft.id,100));
        aircraft.hull=aircraft.hull.move(30,0,0);level.time+=101;
        boolean ready=false;for(int retry=0;retry<100&&!ready;retry++,level.time+=5)ready=airport.groundRunwayReady(assignment,aircraft.proxy,0,-90);
        assertTrue(ready);
        assertTrue(airport.reservations().stream().anyMatch(r->r.facilityId().equals("main")&&r.aircraftId().equals(aircraft.id)&&r.expiresAtTick()>level.time),"FC handoff needs a current runway permit owned by this aircraft");
    }
    @Test void runwayHandoffCannotOverwriteAForeignCurrentPermit()throws Exception{
        var assignment=prepare();assertTrue(airport.reserveGroundAssignment(attempt,assignment,aircraft.id,100));
        aircraft.hull=aircraft.hull.move(30,0,0);level.time+=101;UUID foreign=UUID.randomUUID();
        var conflicting=new AirportReservationBook();assertTrue(conflicting.reserve("main",foreign,level.time,1000));
        set(AirportBlockEntity.class,airport,"reservations",conflicting);
        for(int retry=0;retry<100;retry++,level.time++)assertFalse(airport.groundRunwayReady(assignment,aircraft.proxy,0,-90));
        assertEquals(foreign,airport.reservations().getFirst().aircraftId());
    }
    @Test void unknownGroundChunkInvalidatesArrivalAndOldNoLayoutHasNoGroundDeparture()throws Exception{
        var assignment=prepare();assertTrue(airport.reserveGroundAssignment(attempt,assignment,aircraft.id,100));
        level.unloaded.add(new ChunkPos(-2,0));assertFalse(airport.assignedStandArrival(assignment,aircraft.proxy,0,0));
        level.unloaded.clear();set(AirportBlockEntity.class,airport,"groundLayout",null);
        assertEquals(AirportGroundLayout.empty(),airport.groundLayout());assertTrue(airport.preparedGroundDeparture(attempt,aircraft.proxy,0,0).isEmpty());
    }
    static final class Aircraft implements InvocationHandler{
        final UUID id=UUID.randomUUID();AABB hull=new AABB(-22,65,-1,-19,67,2);boolean grounded=true,assignedReady=false;
        final LogisticsCarrier proxy=(LogisticsCarrier)Proxy.newProxyInstance(LogisticsCarrier.class.getClassLoader(),new Class<?>[]{LogisticsCarrier.class,AirportApproachState.class},this);
        public Object invoke(Object proxy,Method method,Object[] args)throws Throwable{return switch(method.getName()){
            case "getTransportUuid"->id;case "transportKind"->LogisticsCarrier.Kind.AIR;case "dimensionId"->"minecraft:overworld";
            case "isTransportAlive"->true;case "transportBounds"->hull;case "transportPosition"->hull.getCenter();
            case "groundAssignedArrivalReady"->assignedReady&&BlockPos.ZERO.equals(args[0]);case "groundYawDegrees"->0D;case "physicalArrivalReady"->true;case "groundArrivalReady"->grounded;case "aircraftClass"->AircraftClass.FIXED_WING;case "velocityMetersPerSecond"->Vec3.ZERO;
            default->method.isDefault()?InvocationHandler.invokeDefault(proxy,method,args):null;};}
    }
    static final class TestLevel extends ServerLevel{
        DedicatedServer server;DimensionDataStorage storage;Set<ChunkPos> unloaded;Set<BlockPos> blocked;long time;AirportBlockEntity airport;
        private TestLevel(){super(null,command->{},null,null,null,null,null,false,0L,List.of(),false,null);}
        @Override public MinecraftServer getServer(){return server;}@Override public DimensionDataStorage getDataStorage(){return storage;}
        @Override public RegistryAccess registryAccess(){return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);}
        @Override public void blockEntityChanged(BlockPos pos){}@Override public ResourceKey<Level> dimension(){return Level.OVERWORLD;}
        @Override public BlockEntity getBlockEntity(BlockPos pos){return BlockPos.ZERO.equals(pos)?airport:null;}
        @Override public void sendBlockUpdated(BlockPos pos,BlockState oldState,BlockState newState,int flags){}
        @Override public boolean hasChunkAt(BlockPos pos){return !unloaded.contains(new ChunkPos(pos));}@Override public boolean hasChunk(int x,int z){return !unloaded.contains(new ChunkPos(x,z));}
        @Override public int getMinBuildHeight(){return 0;}@Override public int getHeight(){return 256;}@Override public long getGameTime(){return time;}
        @Override public <T extends Entity>List<T> getEntitiesOfClass(Class<T> type,AABB bounds,Predicate<? super T> filter){return List.of();}
        @Override public BlockState getBlockState(BlockPos pos){assertTrue(hasChunkAt(pos));return(pos.getY()==63||blocked.contains(pos)?Blocks.STONE:Blocks.AIR).defaultBlockState();}
        @Override public FluidState getFluidState(BlockPos pos){return Fluids.EMPTY.defaultFluidState();}
    }
    private static final class TestPlayer extends ServerPlayer {
        TestLevel level;
        UUID uuid;
        private TestPlayer(){super(null,null,null,null);}
        @Override public Level level(){return level;}
        @Override public ServerLevel serverLevel(){return level;}
        @Override public UUID getUUID(){return uuid;}
    }
    static void set(Class<?> type,Object target,String name,Object value)throws Exception{Field field=type.getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    @SuppressWarnings("unchecked")static <T>T allocate(Class<T>type)throws Exception{Field field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);return(T)((Unsafe)field.get(null)).allocateInstance(type);}
}
