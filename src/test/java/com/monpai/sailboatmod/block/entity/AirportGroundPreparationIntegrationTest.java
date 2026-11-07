package com.monpai.sailboatmod.block.entity;

import static org.junit.jupiter.api.Assertions.*;
import com.monpai.sailboatmod.transport.TransportRegistry;
import com.monpai.sailboatmod.transport.air.airport.*;
import com.monpai.sailboatmod.transport.air.airport.AirportGroundPreparation.*;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.*;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
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

/** Exercises actual AirportBE incremental runway/ground scans, not a fake terrain verdict. */
class AirportGroundPreparationIntegrationTest {
    @TempDir Path directory;
    TestLevel level;AirportBlockEntity airport;UUID aircraft=UUID.randomUUID(),attempt=UUID.randomUUID();
    AirportGroundSelection.Candidate bad,good,wide,arrivalGood;AirportGroundLayout layout;
    final AirportGroundSelection.LegGate compatible=new AirportGroundSelection.LegGate(){
        public boolean taxi(AirportGroundSelection.Candidate a,AirportGroundSelection.Candidate b){return true;}
        public boolean flight(AirportFacility a,AirportFacility b){return true;}
    };
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @BeforeEach void setup()throws Exception{
        level=allocate(TestLevel.class);level.server=allocate(DedicatedServer.class);level.time=100;
        level.blocked=new HashSet<>();level.unloaded=new HashSet<>();
        set(MinecraftServer.class,level.server,"levels",Map.of(Level.OVERWORLD,level));
        level.storage=new DimensionDataStorage(directory.toFile(),null,level.registryAccess());
        airport=allocate(AirportBlockEntity.class);set(BlockEntity.class,airport,"worldPosition",BlockPos.ZERO);
        airport.setLevel(level);set(BlockEntity.class,airport,"blockState",Blocks.AIR.defaultBlockState());
        set(DockBlockEntity.class,airport,"routes",new ArrayList<>());
        for(String f:List.of("dockName","ownerName","ownerUuid","townId","nationId"))set(DockBlockEntity.class,airport,f,"");
        for(Field f:AirportBlockEntity.class.getDeclaredFields())if(Map.class.isAssignableFrom(f.getType())){f.setAccessible(true);f.set(airport,new LinkedHashMap<>());}
        var main=new AirportFacility("east",AirportFacility.Type.RUNWAY,new BlockPos(0,64,0),new BlockPos(128,64,0),12,8);
        var reverse=new AirportFacility("west",AirportFacility.Type.RUNWAY,main.end(),main.start(),24,8);
        var fs=new LinkedHashMap<String,AirportFacility>();fs.put(main.id(),main);fs.put(reverse.id(),reverse);
        set(AirportBlockEntity.class,airport,"facilities",fs);set(AirportBlockEntity.class,airport,"reservations",new AirportReservationBook());
        set(AirportBlockEntity.class,airport,"budgetTick",Long.MIN_VALUE);
        var stand=new AirportParkingStand("cargo",new Vec3(-36.5,64,.5),0,32,32,8,true);
        var out=new AirportTaxiRoute("out-east",AirportTaxiRoute.Direction.OUTBOUND,"east","cargo",List.of(stand.position(),new Vec3(-20.5,64,.5),new Vec3(.5,64,.5)),12,8,1,1);
        var inBad=new AirportTaxiRoute("in-east",AirportTaxiRoute.Direction.INBOUND,"east","cargo",List.of(new Vec3(128.5,64,.5),new Vec3(128.5,64,40.5),new Vec3(-36.5,64,40.5),stand.position()),12,8,1,-1);
        var inClear=new AirportTaxiRoute("in-west",AirportTaxiRoute.Direction.INBOUND,"west","cargo",List.of(new Vec3(.5,64,.5),stand.position()),24,8,1,-1);
        var outWide=new AirportTaxiRoute("out-west",AirportTaxiRoute.Direction.OUTBOUND,"west","cargo",List.of(stand.position(),new Vec3(-36.5,64,-40.5),new Vec3(160.5,64,-40.5),new Vec3(160.5,64,.5),new Vec3(128.5,64,.5)),24,8,1,1);
        layout=new AirportGroundLayout(9,List.of(stand),List.of(out,inBad,inClear,outWide));
        assertEquals(AirportGroundValidation.Result.VALID,AirportGroundValidation.references(layout,List.copyOf(fs.values())),"positive fixtures must satisfy real schema references");
        set(AirportBlockEntity.class,airport,"groundLayout",layout);
        bad=new AirportGroundSelection.Candidate(new GroundAssignment(9,"east","cargo",out.id(),inBad.id()),stand,main,out,inBad);
        good=new AirportGroundSelection.Candidate(new GroundAssignment(9,"east","cargo",out.id(),inClear.id()),stand,main,out,inClear);
        wide=new AirportGroundSelection.Candidate(new GroundAssignment(9,"west","cargo",outWide.id(),inClear.id()),stand,reverse,outWide,inClear);
        arrivalGood=new AirportGroundSelection.Candidate(good.assignment().forDirection(layout,airport.facilities(),AirportTaxiRoute.Direction.INBOUND).orElseThrow(),stand,reverse,out,inClear);
        assertEquals(arrivalGood.assignment(),arrivalGood.assignment().forDirection(layout,airport.facilities(),AirportTaxiRoute.Direction.INBOUND).orElseThrow());
        assertTrue(AirportGroundSelection.leg(List.of(good),List.of(arrivalGood),compatible).selected().isPresent(),"positive source OUT / target IN roles must reach preparation");
    }
    @AfterEach void cleanup(){TransportRegistry.clear(level.server);}
    private Check prepare(AirportGroundSelection.Candidate c){return airport.prepareGroundCandidate(attempt,aircraft,c.assignment());}
    private Object pending()throws Exception{var f=AirportBlockEntity.class.getDeclaredField("groundPreflights");f.setAccessible(true);return((Map<?,?>)f.get(airport)).get(attempt);}

    @Test void blockedExactOppositePairFallsThroughThenWaitingScanKeepsItsIdentity()throws Exception{
        level.blocked.add(new BlockPos(-20,65,40)); // Only in-east; neither selected runway nor clear pair.
        var cursor=new AirportGroundPreparation();boolean[] rejected={false};int[] badAfterReject={0},goodWaits={0},sourceCalls={0};Object[] firstPending={null};
        Outcome result=null;
        for(int tick=0;tick<600;tick++,level.time+=5){
            result=cursor.advance(List.of(bad,good),List.of(arrivalGood),compatible,c->{
                sourceCalls[0]++;if(c.assignment().equals(bad.assignment())&&rejected[0])badAfterReject[0]++;
                Check check=prepare(c);
                if(c.assignment().equals(bad.assignment())&&check.status()==Status.BLOCKED)rejected[0]=true;
                if(c.assignment().equals(good.assignment())&&check.status()==Status.WAITING){
                    goodWaits[0]++;try{Object p=pending();if(p!=null){if(firstPending[0]==null)firstPending[0]=p;else assertSame(firstPending[0],p,"WAITING must advance the same real Scan");}}catch(ReflectiveOperationException e){throw new AssertionError(e);}catch(Exception e){throw new AssertionError(e);}
                }return check;
            },c->new Check(Status.READY,""));
            if(result.status()==Status.READY)break;
        }
        assertTrue(sourceCalls[0]>0,"must actually enter AirportBE preparation rather than fail candidate role validation");
        assertTrue(rejected[0],"actual selected bad pair must surface terminal terrain failure");
        assertEquals(Status.READY,result.status(),"must continue past blocked exact pair sharing the same active OUT");
        assertEquals(good.assignment(),result.leg().source().assignment());assertEquals(0,badAfterReject[0]);
        assertTrue(goodWaits[0]>1,"fixture must exercise multi-callback incremental preparation");assertNotNull(firstPending[0]);
    }

    @Test void selectedPairTerrainDoesNotScanUnusedConfiguredRoute(){
        level.blocked.add(new BlockPos(-20,65,40));Check result=null;
        for(int i=0;i<400;i++,level.time+=5){result=prepare(good);if(result.status()!=Status.WAITING)break;}
        assertEquals(Status.READY,result.status(),"an unselected inbound obstacle cannot invalidate selected stand + OUT + IN terrain");
        assertTrue(airport.reserveGroundAssignment(attempt,good.assignment(),aircraft,100),"permit consumes exact selected scan");
    }

    @Test void capturedPairCapabilityChangeDoesNotSubstituteAnotherUsableConfiguredPair(){
        var small=capabilities(new AABB(-38,65,-1,-35,67,2));
        var large=capabilities(new AABB(-39,65,-8,-34,67,9));
        var allow=(AirportGroundSelection.RouteGate)(route,cap,corridor)->true;
        assertTrue(AirportGroundSelection.captured(layout,airport.facilities(),AirportTaxiRoute.Direction.OUTBOUND,bad.assignment(),small,allow).isPresent());
        assertTrue(AirportGroundSelection.search(layout,airport.facilities(),AirportTaxiRoute.Direction.OUTBOUND,large,allow).candidates().stream().anyMatch(c->c.assignment().equals(wide.assignment())),"larger aircraft has a different compatible pair, but captured IDs may not change");
        var exact=AirportGroundSelection.captured(layout,airport.facilities(),AirportTaxiRoute.Direction.OUTBOUND,bad.assignment(),large,allow);
        assertTrue(exact.isEmpty());var cursor=new AirportGroundPreparation();int[] calls={0};
        var result=cursor.advance(exact.stream().toList(),List.of(arrivalGood),compatible,c->{calls[0]++;return prepare(c);},c->{calls[0]++;return new Check(Status.READY,"");});
        assertEquals(Status.BLOCKED,result.status());assertNull(result.leg());assertEquals(0,calls[0]);
        assertTrue(airport.groundReservations().isEmpty());
    }
    @Test void allBlockedRetriesAfterTwentyTicksButWaitingScanIsNeverRestarted()throws Exception{
        level.blocked.add(new BlockPos(-20,65,40));var cursor=new AirportGroundPreparation();int[] calls={0};
        AirportGroundPreparation.Provider provider=c->{calls[0]++;return prepare(c);};Outcome result=null;
        for(int i=0;i<400;i++,level.time+=5){
            result=cursor.advance(level.time,List.of(bad),List.of(arrivalGood),compatible,provider,c->new Check(Status.READY,""));
            if(result.status()==Status.BLOCKED)break;
        }
        assertEquals(Status.BLOCKED,result.status(),"actual terrain failure must reach terminal state before testing retry");
        long failedAt=level.time;int atFailure=calls[0];level.blocked.clear();
        for(int elapsed=1;elapsed<20;elapsed++){
            level.time=failedAt+elapsed;
            assertEquals(Status.BLOCKED,cursor.advance(level.time,List.of(bad),List.of(arrivalGood),compatible,provider,c->new Check(Status.READY,"")).status());
        }
        assertEquals(atFailure,calls[0],"terminal cooldown may not eagerly restart scans");
        Object sameScan=null;int waitingCalls=0;
        for(int i=0;i<400;i++){
            level.time=failedAt+20+i*5;
            result=cursor.advance(level.time,List.of(bad),List.of(arrivalGood),compatible,provider,c->new Check(Status.READY,""));
            if(result.status()==Status.READY)break;
            assertEquals(Status.WAITING,result.status());Object current=pending();
            if(current!=null){if(sameScan==null)sameScan=current;else assertSame(sameScan,current,"elapsed time must not reset a still-WAITING Scan");waitingCalls++;}
        }
        assertEquals(Status.READY,result.status());assertTrue(waitingCalls>4,"WAITING must continue beyond the twenty-tick retry interval");
    }
    private AirportGroundSelection.Capabilities capabilities(AABB hull){return new AirportGroundSelection.Capabilities(aircraft,hull,new Vec3(-36.5,65,.5),0,0,1,10,30,5,true,true);}
    static final class TestLevel extends ServerLevel{
        DedicatedServer server;DimensionDataStorage storage;Set<ChunkPos> unloaded;Set<BlockPos> blocked;long time;
        private TestLevel(){super(null,command->{},null,null,null,null,null,false,0L,List.of(),false,null);}
        @Override public MinecraftServer getServer(){return server;}@Override public DimensionDataStorage getDataStorage(){return storage;}
        @Override public RegistryAccess registryAccess(){return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);}
        @Override public void blockEntityChanged(BlockPos pos){}@Override public ResourceKey<Level> dimension(){return Level.OVERWORLD;}
        @Override public boolean hasChunkAt(BlockPos p){return !unloaded.contains(new ChunkPos(p));}@Override public boolean hasChunk(int x,int z){return !unloaded.contains(new ChunkPos(x,z));}
        @Override public int getMinBuildHeight(){return 0;}@Override public int getHeight(){return 256;}@Override public long getGameTime(){return time;}
        @Override public <T extends Entity>List<T> getEntitiesOfClass(Class<T> t,AABB b,Predicate<? super T> f){return List.of();}
        @Override public BlockState getBlockState(BlockPos p){assertTrue(hasChunkAt(p));return(p.getY()==63||blocked.contains(p)?Blocks.STONE:Blocks.AIR).defaultBlockState();}
        @Override public FluidState getFluidState(BlockPos p){return Fluids.EMPTY.defaultFluidState();}
    }
    static void set(Class<?> t,Object target,String name,Object value)throws Exception{Field f=t.getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    @SuppressWarnings("unchecked")static <T>T allocate(Class<T>t)throws Exception{Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);return(T)((Unsafe)f.get(null)).allocateInstance(t);}
}
