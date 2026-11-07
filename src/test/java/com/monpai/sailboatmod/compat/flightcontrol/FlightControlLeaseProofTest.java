package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static com.monpai.sailboatmod.compat.flightcontrol.FlightControlLeaseProof.*;
import static org.junit.jupiter.api.Assertions.*;

class FlightControlLeaseProofTest {
    final UUID body=UUID.randomUUID(), task=UUID.randomUUID();
    final Identity active=new Identity(body,task,42,UUID.randomUUID());
    Evidence check(Identity id, Long generation, Set<?> domains, List<Entry> frame) {
        return inspect(id,generation,domains,frame,body,task,42,true,100);
    }
    @Test void everyActualRouteDomainMustHaveTheCurrentIdentityGenerationAndLiveDeadline() {
        var entries=List.of(new Entry("velocity",active,7,200),new Entry("angular",active,7,200));
        assertEquals(new Evidence(true,false),check(active,7L,Set.of("velocity","angular"),entries));
        assertFalse(check(active,8L,Set.of("velocity","angular"),entries).owned());
        assertFalse(check(active,7L,Set.of("missing"),entries).owned());
        assertFalse(check(active,7L,Set.of(),entries).owned());
        assertFalse(check(null,7L,Set.of("velocity"),entries).owned());
        assertFalse(check(new Identity(null,null,42,null),7L,Set.of("velocity"),entries).owned());
        assertFalse(check(active,7L,Set.of("velocity"),List.of(new Entry("velocity",active,7,100))).owned());
    }
    @Test void anyOtherLiveOperatorOnTheComputerPreventsCustody() {
        var other=new Identity(body,UUID.randomUUID(),99,UUID.randomUUID());
        assertTrue(check(active,7L,Set.of("velocity"),List.of(new Entry("velocity",active,7,200),new Entry("other",other,1,200))).foreign());
        assertFalse(check(active,7L,Set.of("velocity"),List.of(new Entry("velocity",active,7,200),new Entry("old",other,1,99))).foreign());
    }
}
