package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static com.monpai.sailboatmod.compat.flightcontrol.FlightTaskSuccessorRegistry.*;
import static org.junit.jupiter.api.Assertions.*;

class FlightTaskSuccessorRegistryTest {
    private final FlightTaskSuccessorRegistry registry = new FlightTaskSuccessorRegistry();
    private final Object server = new Object();
    private final UUID owner = UUID.randomUUID(), body = UUID.randomUUID();
    private final UUID original = UUID.randomUUID(), successor = UUID.randomUUID();
    private final String terminal = "minecraft:overworld/20481034,129,20481035";
    private State state(UUID task, String phase, String reason, String detail, boolean owned, boolean foreign) {
        return new State(task, phase, reason, detail, 3, 2, "unique-card", true, true, true,
                new Binding(body, "20481034,129,20481034", 1, 4, "controller-identities-and-local-authority"), owned, foreign);
    }
    private void claim() { registry.claim(server, terminal, owner, body, state(original,"RUNNING","NONE","running_route:waypoint=2",true,false)); }
    private Attempt begin() { return registry.begin(server,terminal,state(original,"PREEMPTED","LEASE_LOST","route_lease_renew:STALE_TOKEN",false,false),101); }
    private void signed() { claim(); registry.finish(begin(),state(successor,"STARTING","NONE","resuming",false,false),101); }

    @Test void internalRecoveryWaitsForActualLeaseThenConsumesExactlyOnce() {
        claim();
        Attempt proof = begin();
        assertNotNull(proof, "A claimed original task has a precisely observed native recovery source");
        registry.finish(proof,state(successor,"STARTING","NONE","resuming",false,false),101);
        assertEquals(Decision.PENDING, registry.consume(server,terminal,owner,body,original,state(successor,"STARTING","NONE","resuming",false,false),102));
        assertEquals(Decision.ACCEPTED, registry.consume(server,terminal,owner,body,original,state(successor,"RUNNING","NONE","running_route:waypoint=2",true,false),103));
        assertEquals(Decision.REJECTED, registry.consume(server,terminal,owner,body,original,state(successor,"RUNNING","NONE","running_route:waypoint=2",true,false),104));
    }
    @Test void sameCardPublicResumeWithoutInternalObservationIsRejected() {
        claim();
        assertEquals(Decision.REJECTED, registry.consume(server,terminal,owner,body,original,state(successor,"RUNNING","NONE","running_route:waypoint=2",true,false),103));
    }
    @Test void pausedAndUnrecognizedFailureSourcesCannotSign() {
        claim();
        assertNull(registry.begin(server,terminal,state(original,"PAUSED","NONE","paused",false,false),101));
        assertNull(registry.begin(server,terminal,state(original,"PREEMPTED","LEASE_LOST","route_lease_runtime_missing",false,false),101));
        assertNull(registry.begin(server,terminal,state(original,"BLOCKED","VEHICLE_STATE_STALE","vehicle_state_stale",false,false),101));
    }
    @Test void commandPublishStaleTokenCanSignButDifferentTaskCannot() {
        claim();
        assertNotNull(registry.begin(server,terminal,state(original,"PREEMPTED","LEASE_LOST","route_command_publish:STALE_TOKEN",false,false),101));
        assertNull(registry.begin(server,terminal,state(successor,"PREEMPTED","LEASE_LOST","route_lease_renew:STALE_TOKEN",false,false),101));
    }
    @Test void pendingCannotAuthorizeForeignLeaseOrUnacquiredRunningState() {
        signed();
        assertEquals(Decision.REJECTED,registry.consume(server,terminal,owner,body,original,state(successor,"RUNNING","NONE","running",false,false),103));
        signed();
        assertEquals(Decision.REJECTED,registry.consume(server,terminal,owner,body,original,state(successor,"RUNNING","NONE","running",true,true),103));
    }
    @Test void aPendingTaskThatLosesItsLeaseCannotResurrectTheConsumedProof() {
        signed();
        assertEquals(Decision.PENDING,registry.consume(server,terminal,owner,body,original,state(successor,"STARTING","NONE","resuming",false,false),102));
        assertEquals(Decision.REJECTED,registry.consume(server,terminal,owner,body,original,state(successor,"BLOCKED","LEASE_CONFLICT","lease_conflict",false,true),103));
        assertEquals(Decision.REJECTED,registry.consume(server,terminal,owner,body,original,state(successor,"RUNNING","NONE","running",true,false),104));
    }
    @Test void enablementSlotProfileTopologyAndAuthorityChangesInvalidateProof() {
        for (int change=0;change<8;change++) {
            signed(); State s=state(successor,"RUNNING","NONE","running",true,false);
            State changed=new State(s.taskId(),s.phase(),s.blockReason(),s.detail(),change==0?4:3,change==1?3:2,change==2?"other-card":s.fingerprint(),
                    true,change!=3,change!=4,new Binding(body,s.binding().computerPosition(),change==5?2:1,change==7?5:4,
                    change==6?"other-authority":s.binding().controls()),true,false);
            assertEquals(Decision.REJECTED,registry.consume(server,terminal,owner,body,original,changed,103),"change="+change);
        }
    }
    @Test void ownersBodiesServersAndTerminalLocationsAreNotInterchangeable() {
        signed(); State current=state(successor,"HOLDING","NONE","hovering",true,false);
        assertEquals(Decision.REJECTED,registry.consume(server,terminal,UUID.randomUUID(),body,original,current,103));
        assertEquals(Decision.REJECTED,registry.consume(server,terminal,owner,UUID.randomUUID(),original,current,103));
        assertEquals(Decision.REJECTED,registry.consume(new Object(),terminal,owner,body,original,current,103));
        assertEquals(Decision.REJECTED,registry.consume(server,terminal+"/other",owner,body,original,current,103));
    }
    @Test void missingClaimExpiredProofAndExplicitClearFailClosed() {
        assertNull(begin());
        signed();
        assertEquals(Decision.REJECTED,registry.consume(server,terminal,owner,body,original,state(successor,"HOLDING","NONE","hovering",true,false),122));
        signed(); registry.clear(server,terminal,owner);
        assertEquals(Decision.REJECTED,registry.consume(server,terminal,owner,body,original,state(successor,"HOLDING","NONE","hovering",true,false),103));
    }
}
