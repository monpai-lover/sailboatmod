package com.monpai.sailboatmod.transport.air.ground;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static com.monpai.sailboatmod.transport.air.ground.AircraftGroundMission.*;
import static org.junit.jupiter.api.Assertions.*;
class AircraftGroundMissionNbtTest {
    private final Plan p=new Plan(UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld",5,"runway","stand","reservation",
            List.of(Vec3.ZERO,new Vec3(10,0,0),new Vec3(20,0,0)),1,List.of(new Vec3(20,0,0),Vec3.ZERO),-90,90,2,3,
            new Calibration(false,.8,5,30,15,0));
    private State state(Phase phase) { return new State(p.missionId(),p.physicalId(),p.dimensionId(),5,"runway","stand","reservation",phase,1,12,123,false); }
    @Test void allPlanCalibrationAndProgressFieldsRoundTripAndLoadedStateRequiresRecoveryProof() {
        var value=AircraftGroundMissionNbt.loadOptional(AircraftGroundMissionNbt.save(p,state(Phase.TAXI_OUT))).orElseThrow();
        assertEquals(p,value.plan());assertEquals(recovered(state(Phase.TAXI_OUT)),value.state());
        assertFalse(value.plan().calibration().liveVerified());
        assertEquals(1,value.state().pointIndex());assertTrue(value.state().recovered());assertEquals(0,value.state().stableTicks());
    }
    @Test void invalidOrForeignPersistenceDoesNotCreateAReplayableMission() {
        assertTrue(AircraftGroundMissionNbt.loadOptional(new CompoundTag()).isEmpty());
        CompoundTag tag=AircraftGroundMissionNbt.save(p,state(Phase.FC_ACTIVE));
        tag.getCompound("State").putUUID("PhysicalId",UUID.randomUUID());
        assertTrue(AircraftGroundMissionNbt.loadOptional(tag).isEmpty());
        tag=AircraftGroundMissionNbt.save(p,state(Phase.FC_ACTIVE));tag.getCompound("Plan").putDouble("SpeedLimit",Double.NaN);
        assertTrue(AircraftGroundMissionNbt.loadOptional(tag).isEmpty());
        tag=AircraftGroundMissionNbt.save(p,state(Phase.FC_ACTIVE));tag.getCompound("State").putString("Phase","INVENTED");
        assertTrue(AircraftGroundMissionNbt.loadOptional(tag).isEmpty());
        tag=AircraftGroundMissionNbt.save(p,state(Phase.FC_ACTIVE));tag.getCompound("State").putInt("PointIndex",99999);
        assertTrue(AircraftGroundMissionNbt.loadOptional(tag).isEmpty());
        tag=AircraftGroundMissionNbt.save(p,state(Phase.FC_ACTIVE));tag.getCompound("Plan").getCompound("Calibration").remove("LiveVerified");
        assertTrue(AircraftGroundMissionNbt.loadOptional(tag).isEmpty());
    }
    @Test void throttleLatchIsPersistedAndInvalidNativeSignalsAreRejected() {
        CompoundTag saved=AircraftGroundMissionNbt.save(p,state(Phase.TAXI_OUT));
        assertTrue(saved.getCompound("State").contains("LastThrottleSignal"));
        assertEquals(-1,saved.getCompound("State").getInt("LastThrottleSignal"));
        State powered=new State(p.missionId(),p.physicalId(),p.dimensionId(),5,"runway","stand","reservation",Phase.TAXI_OUT,1,0,123,false,0);
        CompoundTag active=AircraftGroundMissionNbt.save(p,powered);
        assertEquals(0,active.getCompound("State").getInt("LastThrottleSignal"));
        assertEquals(-1,AircraftGroundMissionNbt.loadOptional(active).orElseThrow().state().lastThrottleSignal(),"recovery never replays powered latch");
        saved.getCompound("State").putInt("LastThrottleSignal",16);
        assertTrue(AircraftGroundMissionNbt.loadOptional(saved).isEmpty());
    }
    @Test void loadedCompletionCannotSettleCargoFromSavedDwell() {
        State complete=new State(p.missionId(),p.physicalId(),p.dimensionId(),5,"runway","stand","reservation",Phase.COMPLETE,1,40,123,false);
        var loaded=AircraftGroundMissionNbt.loadOptional(AircraftGroundMissionNbt.save(p,complete)).orElseThrow();
        assertTrue(loaded.state().recovered());assertEquals(0,loaded.state().stableTicks());
    }
}
