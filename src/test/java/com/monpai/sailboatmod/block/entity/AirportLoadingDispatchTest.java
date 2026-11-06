package com.monpai.sailboatmod.block.entity;

import static org.junit.jupiter.api.Assertions.*;
import com.monpai.sailboatmod.market.ShipmentManifestEntry;
import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.transport.air.airport.*;
import com.monpai.sailboatmod.transport.control.ControllerRegistration;
import com.monpai.sailboatmod.transport.control.ControllerStatus;
import com.monpai.sailboatmod.transport.physical.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import com.monpai.sailboatmod.route.RouteDefinition;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.*;
import sun.misc.Unsafe;

/** Exercises the real dispatch preparation/queue transition with actual airport BEs and a cold fake chunk backend. */
class AirportLoadingDispatchTest {
    @TempDir static Path directory;
    private static final BlockPos SOURCE=new BlockPos(0,81,0),TARGET=new BlockPos(10000,81,0);
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @Test void unloadedMetadataQueuesTheDepartureAndKeepsCargoBeforeTerrainIsPrepared()throws Exception {
        var f=fixture();f.level.loaded.add(new ChunkPos(SOURCE));
        assertFalse(prepare(f));assertEquals("waiting_airport_metadata",get(f.controller,"reason"));
        assertTrue(((Set<?>)get(f.window,"airportPins")).isEmpty(),"no inferred airport footprint before actual metadata exists");
        assertTrue((boolean)invoke(f.controller,"queueAirportDeparture",new Class[]{ServerLevel.class},f.level));
        assertTrue((boolean)get(f.controller,"departureFlightPending"));assertFalse((boolean)get(f.controller,"flightPending"));
        assertSame(f.cargo,get(f.controller,"manifest"));assertEquals(1,f.cargo.size());
        f.level.loaded.add(new ChunkPos(TARGET));
        assertFalse(prepare(f));assertEquals("waiting_airport_terrain",get(f.controller,"reason"));
        assertFalse(((Set<?>)get(f.window,"airportPins")).isEmpty());
        assertTrue(((Map<?,?>)get(f.source,"preflights")).isEmpty(),"loading precedes scans, not the other way around");
        f.level.allLoaded=true;assertTrue(prepare(f));assertSame(f.cargo,get(f.controller,"manifest"));
        invoke(f.controller,"clearAirportLoading",new Class[]{});assertTrue(((Set<?>)get(f.window,"airportPins")).isEmpty());assertTrue(f.backend.body);
    }
    @Test void aLoadedNonAirportTargetIsAHardRejectionAndDropsTheOldAirportLease()throws Exception {
        var f=fixture();f.level.allLoaded=true;assertTrue(prepare(f));
        f.level.entities.remove(TARGET);assertFalse(prepare(f));assertEquals("destination_airport_required",get(f.controller,"reason"));
        assertTrue(((Set<?>)get(f.window,"airportPins")).isEmpty());assertSame(f.cargo,get(f.controller,"manifest"));
        assertFalse((boolean)invoke(f.controller,"queueAirportDeparture",new Class[]{ServerLevel.class},f.level));
    }
    @Test void aFaultedAircraftDoesNotReacquireAirportTerrainWhileItsBodyStaysRetained()throws Exception {
        var f=fixture();f.level.allLoaded=true;assertTrue(prepare(f));set(f.controller,"active",true);set(f.controller,"status",ControllerStatus.FAULT);
        invoke(f.controller,"refreshAirportLoading",new Class[]{ServerLevel.class},f.level);
        assertTrue(((Set<?>)get(f.window,"airportPins")).isEmpty());assertTrue(f.backend.body);
        set(f.controller,"status",ControllerStatus.RUNNING);invoke(f.controller,"refreshAirportLoading",new Class[]{ServerLevel.class},f.level);
        assertFalse(((Set<?>)get(f.window,"airportPins")).isEmpty());
        set(f.controller,"paused",true);invoke(f.controller,"refreshAirportLoading",new Class[]{ServerLevel.class},f.level);
        assertTrue(((Set<?>)get(f.window,"airportPins")).isEmpty());assertTrue(f.backend.body);
    }
    @Test void normalNamedRouteResolvesCoordinatesBeforeWaitingForUnloadedAirportMetadata()throws Exception {
        var f=fixture();f.level.loaded.add(new ChunkPos(SOURCE));set(f.controller,"destinationTerminal",null);
        set(f.controller,"routeCatalog",new ArrayList<>(List.of(new RouteDefinition("C to D",List.of(new Vec3(0,81,0),new Vec3(10000,81,0)),"Owner","owner",1,10000,"C","D"))));
        AirportSavedData.get(f.level).put(new AirportSavedData.AirportSnapshot("minecraft:overworld",TARGET,"D","town","owner",List.of()));
        assertFalse(prepare(f));assertEquals(TARGET,get(f.controller,"destinationTerminal"));
        assertEquals("waiting_airport_metadata",get(f.controller,"reason"));
        assertTrue((boolean)invoke(f.controller,"queueAirportDeparture",new Class[]{ServerLevel.class},f.level));
        assertTrue((boolean)get(f.controller,"departureFlightPending"));
    }
    @Test void ambiguousNamedDestinationDoesNotGuessAnAirportByDistance()throws Exception {
        var f=fixture();f.level.loaded.add(new ChunkPos(SOURCE));set(f.controller,"destinationTerminal",null);
        set(f.controller,"routeCatalog",new ArrayList<>(List.of(new RouteDefinition("C to D",List.of(new Vec3(0,81,0),new Vec3(10000,81,0)),"Owner","owner",1,10000,"C","D"))));
        var data=AirportSavedData.get(f.level);data.put(new AirportSavedData.AirportSnapshot("minecraft:overworld",TARGET,"D","town","owner",List.of()));
        data.put(new AirportSavedData.AirportSnapshot("minecraft:overworld",TARGET.offset(16,0,0),"d","town","owner",List.of()));
        assertFalse(prepare(f));assertNull(get(f.controller,"destinationTerminal"));assertEquals("destination_airport_required",get(f.controller,"reason"));
        assertFalse((boolean)invoke(f.controller,"queueAirportDeparture",new Class[]{ServerLevel.class},f.level));
    }
    private static boolean prepare(Fixture f)throws Exception{return (boolean)invoke(f.controller,"prepareAirportLoading",new Class[]{ServerLevel.class,AirportBlockEntity.class},f.level,f.source);}
    private record Fixture(RegisteredVehicleControllerBlockEntity controller,AirportBlockEntity source,TestLevel level,Backend backend,TransportLoadingWindow window,List<ShipmentManifestEntry> cargo){}
    private static Fixture fixture()throws Exception {
        var level=allocate(TestLevel.class);level.server=allocate(DedicatedServer.class);set(level.server,MinecraftServer.class,"levels",Map.of(Level.OVERWORLD,level));level.storage=new DimensionDataStorage(directory.toFile(),null,level.registryAccess());level.entities=new HashMap<>();level.loaded=new HashSet<>();
        var source=airport(level,SOURCE);var target=airport(level,TARGET);level.entities.put(SOURCE,source);level.entities.put(TARGET,target);
        var backend=new Backend(level);var window=new TransportLoadingWindow(backend);var c=allocate(RegisteredVehicleControllerBlockEntity.class);
        set(c,"registration",new ControllerRegistration(UUID.randomUUID(),"owner",LogisticsCarrier.Kind.AIR));set(c,"loading",window);
        set(c,"view",new PhysicalVehicleView(UUID.randomUUID(),"minecraft:overworld",new Vec3(0,81,0),new Vec3(1,0,0),Vec3.ZERO,new AABB(-4,81,-4,4,87,4),80,true));
        set(c,"destinationTerminal",TARGET);set(c,"sourceTerminal",SOURCE);set(c,"reason","");
        List<ShipmentManifestEntry> cargo=new ArrayList<>(List.of(new ShipmentManifestEntry("listing",ItemStack.EMPTY,"purchase","order","buyer","Buyer",1)));
        set(c,"manifest",cargo);return new Fixture(c,source,level,backend,window,cargo);
    }
    private static AirportBlockEntity airport(TestLevel level,BlockPos pos)throws Exception {
        var a=allocate(AirportBlockEntity.class);set(a,BlockEntity.class,"worldPosition",pos);set(a,BlockEntity.class,"level",level);
        var runway=new AirportFacility("r",AirportFacility.Type.RUNWAY,pos,pos.offset(2048,0,0),28,12);
        set(a,"facilities",new LinkedHashMap<>(Map.of("r",runway)));set(a,"groundLayout",AirportGroundLayout.empty());set(a,"preflights",new HashMap<>());return a;
    }
    private static final class Backend implements TransportLoadingWindow.Backend {
        final TestLevel level;boolean body;Backend(TestLevel l){level=l;}
        final Set<ChunkPos> temporary=new HashSet<>();
        public long tick(){return 100;}public boolean isLoaded(ChunkPos p){return level.allLoaded||level.loaded.contains(p);}
        public CompletableFuture<Boolean> request(ChunkPos p){return new CompletableFuture<>();}
        public void ticket(UUID o,ChunkPos p,boolean add){}public void airportTicket(UUID o,ChunkPos p,boolean add){if(add)temporary.add(p);else temporary.remove(p);}
        public boolean structure(UUID p,UUID o,boolean add){body=add;return true;}
    }
    private static final class TestLevel extends ServerLevel {
        DedicatedServer server;DimensionDataStorage storage;
        Map<BlockPos,BlockEntity> entities;Set<ChunkPos> loaded;boolean allLoaded;
        private TestLevel(){super(null,r->{},null,null,null,null,null,false,0,List.of(),false,null);}
        @Override public MinecraftServer getServer(){return server;}@Override public DimensionDataStorage getDataStorage(){return storage;}
        @Override public RegistryAccess registryAccess(){return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);}@Override public ResourceKey<Level> dimension(){return Level.OVERWORLD;}
        @Override public boolean hasChunkAt(BlockPos p){return allLoaded||loaded.contains(new ChunkPos(p));}
        @Override public BlockEntity getBlockEntity(BlockPos p){assertTrue(hasChunkAt(p));return entities.get(p);}
    }
    private static Object invoke(Object o,String n,Class<?>[] p,Object... args)throws Exception {Method m=o.getClass().getDeclaredMethod(n,p);m.setAccessible(true);return m.invoke(o,args);}
    private static Object get(Object o,String n)throws Exception {Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String n,Object v)throws Exception {set(o,o.getClass(),n,v);}
    private static void set(Object o,Class<?> t,String n,Object v)throws Exception {Field f=t.getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
    @SuppressWarnings("unchecked") private static <T>T allocate(Class<T> t)throws Exception {Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);return (T)((Unsafe)f.get(null)).allocateInstance(t);}
}
