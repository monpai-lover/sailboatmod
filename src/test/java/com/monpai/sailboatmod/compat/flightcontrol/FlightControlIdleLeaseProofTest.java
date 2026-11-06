package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native-free contract for the read-only broker observation used by both handoff predicates. */
class FlightControlIdleLeaseProofTest {
    private static final UUID BODY=UUID.randomUUID(), TASK=UUID.randomUUID();
    private final Object terminal=new Object(), computer=new Object(), broker=new Object(), frame=new Object(), physicalBody=new Object();
    private Class<?> type(String name) { return assertDoesNotThrow(() -> Class.forName(FlightControlLeaseProof.class.getName()+"$"+name)); }
    private Object record(String name,Map<String,Object> values) {
        Class<?> type=type(name); var fields=type.getRecordComponents();
        Class<?>[] types=Arrays.stream(fields).map(RecordComponent::getType).toArray(Class<?>[]::new);
        Object[] args=Arrays.stream(fields).map(f->values.get(f.getName())).toArray();
        return assertDoesNotThrow(()->type.getDeclaredConstructor(types).newInstance(args));
    }
    private Object activity(boolean idle) {
        Map<String,Object> values=new HashMap<>();
        for(String key:List.of("identity","token","route","computer"))values.put(key,idle?null:new Object());
        return record("Activity",values);
    }
    private Object observation(Map<String,Object> changes) {
        Map<String,Object> values=new HashMap<>();
        values.put("terminal",terminal);values.put("computer",computer);values.put("broker",broker);values.put("frame",frame);
        values.put("physicalBody",physicalBody);values.put("actualBody",BODY);
        values.put("body",BODY);values.put("dimension","minecraft:overworld");values.put("terminalPosition",42L);values.put("computerPosition",43L);
        values.put("topology",1L);values.put("sessionEpoch",1L);values.put("configRevision",0L);values.put("slotGeneration",1L);values.put("taskRevision",1L);
        values.put("task",null);values.put("fingerprint","");values.put("available",true);values.put("linked",true);values.put("activity",activity(true));
        values.putAll(changes);return record("AuthorityObservation",values);
    }
    private Object occupancy(int bundles,int leases,int claims,int commands) {
        return record("NativeOccupancy",Map.of("bundles",bundles,"leases",leases,"claims",claims,"commands",commands));
    }
    private FlightControlBridge.ControlOwnership proof(Object before,Object after,Object occupancy,boolean oldFlightConsistent,boolean oldOwned,boolean foreign) {
        return assertDoesNotThrow(()->(FlightControlBridge.ControlOwnership)FlightControlLeaseProof.class.getDeclaredMethod("control",type("AuthorityObservation"),type("AuthorityObservation"),type("NativeOccupancy"),boolean.class,boolean.class,boolean.class).invoke(null,before,after,occupancy,oldFlightConsistent,oldOwned,foreign));
    }
    private FlightControlBridge.ControlOwnership idle(Object occupancy) {
        Object observation=observation(Map.of());return proof(observation,observation,occupancy,false,false,false);
    }
    @Test void offComputerWithBlankProfileAndNoTaskCanProveActuallyEmptyNativeAuthority() {
        var proof=idle(occupancy(0,0,0,0));
        assertTrue(proof.available());assertTrue(proof.provesReleased(BODY));
        assertFalse(proof.provesReleased(UUID.randomUUID()));assertFalse(proof.provesFlight(BODY,TASK,"profile"));
    }
    @Test void acquiredBundleBeforeExplicitPublishStillOccupiesNativeDomains() {
        var proof=idle(occupancy(1,1,2,2));
        assertTrue(proof.anyActiveLease());assertFalse(proof.provesReleased(BODY));
    }
    @Test void bundleAndClaimsBlockReleaseEvenIfNoCommandWasPublished() {
        var proof=idle(occupancy(1,1,2,0));
        assertFalse(proof.provesReleased(BODY));assertFalse(proof.provesFlight(BODY,TASK,"profile"));
    }
    @Test void expiredCommandDeadlinesCannotDiscardTheStillPopulatedNativeLeaseTables() {
        // Counts contain the raw native frame, including entries whose deadlines have passed.
        assertFalse(idle(occupancy(1,1,2,2)).provesReleased(BODY));
        assertFalse(idle(occupancy(0,0,0,2)).provesReleased(BODY));
    }
    @Test void foreignAndUnknownBrokerObservationsNeverProveRelease() {
        Object observation=observation(Map.of());
        assertFalse(proof(observation,observation,occupancy(1,1,2,2),false,false,true).provesReleased(BODY));
        assertFalse(idle(null).provesReleased(BODY));
        for(Object invalid:List.of(occupancy(-1,0,0,0),occupancy(0,1,0,0),occupancy(0,0,1,0),occupancy(129,129,129,129)))
            assertFalse(idle(invalid).provesReleased(BODY));
    }
    @Test void retainedNativeIdentityOrTokenWithoutBrokerOccupancyIsUnknownRatherThanReleased() {
        Object observation=observation(Map.of("activity",activity(false)));
        var proof=proof(observation,observation,occupancy(0,0,0,0),false,false,false);
        assertFalse(proof.available());assertFalse(proof.provesReleased(BODY));
    }
    @Test void cachedVehicleIdentityCannotOverrideTheActualLiveSableBody() {
        Object changedBody=observation(Map.of("actualBody",UUID.randomUUID()));
        var proof=proof(changedBody,changedBody,occupancy(0,0,0,0),false,false,false);
        assertFalse(proof.available());assertFalse(proof.provesReleased(BODY));
    }
    @Test void actualFlightRetainsTheOldReadyAndCompleteConsistencyGates() {
        Object observation=observation(Map.of("task",TASK,"fingerprint","profile","activity",activity(false)));
        Object occupied=occupancy(1,1,2,2);
        assertTrue(proof(observation,observation,occupied,true,true,false).provesFlight(BODY,TASK,"profile"));
        assertFalse(proof(observation,observation,occupied,false,true,false).provesFlight(BODY,TASK,"profile"));
        assertFalse(proof(observation,observation,occupied,true,false,false).provesFlight(BODY,TASK,"profile"));
        assertFalse(proof(observation,observation,occupied,true,true,true).provesFlight(BODY,TASK,"profile"));
        assertFalse(proof(observation,observation,occupancy(0,0,0,0),true,true,false).provesFlight(BODY,TASK,"profile"));
    }
    @Test void changedNativeObjectsBindingOrSnapshotsNeverEstablishEitherHandoff() {
        Object before=observation(Map.of());
        List<Map<String,Object>> changes=new ArrayList<>();
        for(String key:List.of("terminal","computer","broker","frame","physicalBody"))changes.add(Map.of(key,new Object()));
        changes.addAll(List.of(Map.of("body",UUID.randomUUID()),Map.of("dimension","minecraft:the_nether"),Map.of("terminalPosition",99L),Map.of("computerPosition",99L),Map.of("topology",2L),Map.of("sessionEpoch",2L),Map.of("configRevision",2L),Map.of("slotGeneration",2L),Map.of("taskRevision",2L),Map.of("task",TASK),Map.of("fingerprint","changed"),Map.of("available",false),Map.of("linked",false),Map.of("activity",activity(false))));
        for(var change:changes){var proof=proof(before,observation(change),occupancy(0,0,0,0),false,false,false);assertFalse(proof.available(),change.toString());assertFalse(proof.provesReleased(BODY));assertFalse(proof.provesFlight(BODY,TASK,"profile"));}
    }
}
