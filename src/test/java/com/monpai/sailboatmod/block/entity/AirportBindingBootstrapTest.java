package com.monpai.sailboatmod.block.entity;

import static org.junit.jupiter.api.Assertions.*;
import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.transport.air.AircraftClass;
import com.monpai.sailboatmod.transport.air.FlightMission;
import com.monpai.sailboatmod.transport.air.FlightMissionSavedData;
import com.monpai.sailboatmod.transport.air.airport.*;
import com.monpai.sailboatmod.transport.control.*;
import com.monpai.sailboatmod.transport.physical.PhysicalVehicleView;
import com.monpai.sailboatmod.market.ShipmentManifestEntry;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.*;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

/** Initial source discovery is geometry only; it never substitutes for rail terrain/body/arrival proof. */
class AirportBindingBootstrapTest {
    @TempDir Path directory;
    TestLevel level; RegisteredVehicleControllerBlockEntity controller; AirportBlockEntity c,old;
    final UUID physical=UUID.randomUUID();
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @BeforeEach void setup()throws Exception{
        level=allocate(TestLevel.class);level.server=allocate(DedicatedServer.class);level.entities=new HashMap<>();level.unloaded=new HashSet<>();
        set(MinecraftServer.class,level.server,"levels",Map.of(Level.OVERWORLD,level));level.storage=new DimensionDataStorage(directory.toFile(),null,level.registryAccess());
        controller=allocate(RegisteredVehicleControllerBlockEntity.class);controller.setLevel(level);
        var registration=new ControllerRegistration(UUID.randomUUID(),"owner",LogisticsCarrier.Kind.AIR);assertTrue(registration.register(physical,86.75,true));
        set(controller,"registration",registration);set(controller,"aircraftClass",AircraftClass.FIXED_WING);set(controller,"status",ControllerStatus.IDLE);set(controller,"bindingValid",true);
        set(controller,"railAircraft",new RegisteredAircraftRailController());
        set(controller,"airSurfaces",new RegisteredAircraftSurfaces());
        set(controller,"aircraftFuel",new RegisteredAircraftFuelController());
        set(controller,"groundOutputsNeutral",true);set(controller,"manifest",new ArrayList<>());set(controller,"view",view(new AABB(77,81,507,94,88,524),Vec3.ZERO));
        c=airport(new BlockPos(-32,81,560),true);old=airport(new BlockPos(136,81,496),false);
    }
    @AfterEach void clear(){AirportRegistry.clear(level.server);}
    @Test void sourceDiscoveryFindsTheFarActualStandWithoutGrantingUnscannedCargoAdmission()throws Exception{
        assertTrue(controller.transportPosition().distanceTo(Vec3.atCenterOf(c.getBlockPos()))>64);
        assertTrue(controller.transportPosition().distanceTo(Vec3.atCenterOf(old.getBlockPos()))<64);
        assertTrue(bind());assertEquals(c.getBlockPos(),get(controller,"sourceTerminal"));
        assertFalse(c.isCarrierInsideDockZone(controller),"A stand-only airport with no directed taxi/runway or native angular observation is not departure proof");
        assertFalse(old.isCarrierInsideDockZone(controller));
        assertFalse((boolean)get(controller,"active"));assertTrue(controller.getPendingShipmentManifest().isEmpty());
    }
    @Test void overlappingAirportStandsAreAmbiguousEvenWhenOneBlockIsCloser()throws Exception{
        var duplicate=airport(new BlockPos(80,81,550),true);
        assertFalse(bind());assertNull(get(controller,"sourceTerminal"));
        level.unloaded.add(new ChunkPos(duplicate.getBlockPos()));
        assertTrue(bind(),"Only actual loaded airports may participate; do not load a contender");assertEquals(c.getBlockPos(),get(controller,"sourceTerminal"));
    }
    @Test void theBindingActionSelectorWritesOnlyTheUniqueParkedAirport()throws Exception{
        assertTrue(bind());assertEquals(c.getBlockPos(),get(controller,"sourceTerminal"));
        set(controller,"sourceTerminal",null);airport(new BlockPos(80,81,550),true);
        assertFalse(bind());assertNull(get(controller,"sourceTerminal"));assertEquals("airport_stand_ambiguous",get(controller,"reason"));
        set(controller,"paused",true);assertFalse(bind());assertNull(get(controller,"sourceTerminal"));assertEquals("airport_binding_busy",get(controller,"reason"));
    }
    @Test void wholeHullYawCargoAndActualSpeedAreRequiredForInitialCandidate()throws Exception{
        set(controller,"view",view(new AABB(68,81,507,94,88,524),Vec3.ZERO));assertFalse(bind());
        set(controller,"view",view(new AABB(77,81,507,94,88,524),new Vec3(.251,0,0)));assertFalse(bind());
        var normal=view(new AABB(77,81,507,94,88,524),Vec3.ZERO);
        set(controller,"view",new PhysicalVehicleView(physical,"minecraft:overworld",normal.worldPosition(),new Vec3(0,0,1),Vec3.ZERO,normal.worldBounds(),86.75,true));assertFalse(bind());
        set(controller,"view",normal);assertTrue(bind());set(controller,"sourceTerminal",null);
        set(c,"groundLayout",new AirportGroundLayout(2,List.of(new AirportParkingStand("stand",new Vec3(85,81,515.5),90,32,30,8,false)),List.of()));assertFalse(bind());
    }
    @Test void activePausedManualAndManifestAircraftCannotRebindTheirSource()throws Exception{
        for(String flag:List.of("active","paused","manualTakeover")){
            set(controller,flag,true);assertFalse(bind(),flag);set(controller,flag,false);
        }
        set(controller,"manifest",new ArrayList<>(List.of(new ShipmentManifestEntry("listing",ItemStack.EMPTY,"purchase","shipment","buyer","Buyer",1))));assertFalse(bind());set(controller,"manifest",new ArrayList<>());
        set(controller,"sourceTerminal",old.getBlockPos());assertTrue(bind());assertEquals(c.getBlockPos(),get(controller,"sourceTerminal"));
        assertFalse(c.isCarrierInsideDockZone(controller),"A recorded source still needs full rail departure proof before loading");
    }
    @Test void registrationAndSameValidPhysicalBodyRemainMandatory()throws Exception{
        ((ControllerRegistration)get(controller,"registration")).unregister();assertFalse(bind());
        ((ControllerRegistration)get(controller,"registration")).register(UUID.randomUUID(),86.75,true);assertFalse(bind());
        ((ControllerRegistration)get(controller,"registration")).register(physical,86.75,true);set(controller,"bindingValid",false);assertFalse(bind());
    }
    @Test void anOldNativeJournalIsNotReplayedOrPromotedToRailCargoArrivalProof()throws Exception{
        var mission=FlightMission.start(controller.getTransportUuid(),"minecraft:overworld",BlockPos.ZERO,AircraftClass.FIXED_WING,List.of(),List.of(Vec3.ZERO,new Vec3(1,1,1)),1);
        FlightMissionSavedData.get(level).put(mission);assertFalse(c.isCarrierInsideDockZone(controller));
        mission.cancelPhysical();mission.requireRecovery("unproven old shutdown");assertFalse(c.isCarrierInsideDockZone(controller));
        mission.cancelPhysical();assertFalse(c.isCarrierInsideDockZone(controller),"A cancelled old journal still cannot invent missing rail terrain/control proof");
        assertTrue(bind());assertFalse((boolean)get(controller,"active"));assertNull(get(controller,"flightTask"));
    }
    @Test void geometryBindingNeedsNeitherLegacyWheelCalibrationNorNativeTaskCaches()throws Exception{
        set(controller,"groundMission",allocate(RegisteredAircraftGroundMission.class));
        var assignment=new GroundAssignment(2,"r","stand","out","in");
        set(controller,"nextGroundSourceAssignment",assignment);set(controller,"nextGroundTargetAssignment",assignment);
        set(controller,"groundOutputsNeutral",false);set(controller,"flightTask",UUID.randomUUID());
        set(controller,"flightRecovering",true);set(controller,"recoveryRequired",true);
        assertTrue(bind(),"Source metadata discovery is independent of retired native calibration/cache fields");
        assertFalse((boolean)get(controller,"active"));assertFalse(c.isCarrierInsideDockZone(controller));
    }
    private boolean bind()throws Exception{Method m=RegisteredVehicleControllerBlockEntity.class.getDeclaredMethod("bindParkedStandAirport",ServerLevel.class);m.setAccessible(true);return(boolean)m.invoke(controller,level);}
    private PhysicalVehicleView view(AABB hull,Vec3 speed){return new PhysicalVehicleView(physical,"minecraft:overworld",new Vec3(84.94,83,515.58),new Vec3(-1,0,0),speed,hull,86.75,true);}
    private AirportBlockEntity airport(BlockPos p,boolean stand)throws Exception{
        var airport=allocate(AirportBlockEntity.class);set(BlockEntity.class,airport,"worldPosition",p);airport.setLevel(level);
        for(Field f:AirportBlockEntity.class.getDeclaredFields())if(Map.class.isAssignableFrom(f.getType())){f.setAccessible(true);f.set(airport,new LinkedHashMap<>());}
        set(airport,"groundLayout",new AirportGroundLayout(2,stand?List.of(new AirportParkingStand("stand",new Vec3(85,81,515.5),90,32,30,8,true)):List.of(),List.of()));
        set(airport,"reservations",new AirportReservationBook());level.entities.put(p,airport);AirportRegistry.register(level,p);return airport;
    }
    private static final class TestLevel extends ServerLevel{
        DedicatedServer server;DimensionDataStorage storage;Map<BlockPos,BlockEntity> entities;Set<ChunkPos>unloaded;
        private TestLevel(){super(null,r->{},null,null,null,null,null,false,0,List.of(),false,null);}
        @Override public MinecraftServer getServer(){return server;}@Override public DimensionDataStorage getDataStorage(){return storage;}
        @Override public RegistryAccess registryAccess(){return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);}@Override public ResourceKey<Level> dimension(){return Level.OVERWORLD;}
        @Override public boolean hasChunkAt(BlockPos p){return !unloaded.contains(new ChunkPos(p));}@Override public long getGameTime(){return 100;}
        @Override public BlockEntity getBlockEntity(BlockPos p){assertTrue(hasChunkAt(p),"Never load unloaded metadata");return entities.get(p);}
        @Override public void blockEntityChanged(BlockPos p){}
    }
    static Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String n,Object v)throws Exception{set(o.getClass(),o,n,v);}static void set(Class<?>t,Object o,String n,Object v)throws Exception{Field f=t.getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
    @SuppressWarnings("unchecked")static<T>T allocate(Class<T>t)throws Exception{Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);return(T)((Unsafe)f.get(null)).allocateInstance(t);}
}
