package com.monpai.sailboatmod.transport.air.airport;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

class AirportEditorSessionTest {
    private final UUID owner = UUID.randomUUID();
    private AirportEditorSession session() {
        var runway = new AirportFacility("r", AirportFacility.Type.RUNWAY, new BlockPos(0, 80, 0), new BlockPos(96, 80, 0), 9, 4);
        return new AirportEditorSession(owner, 5, 100,
                new AirportEditorSession.Proposal(List.of(runway), new AirportGroundLayout(6, List.of(), List.of()), runway, false), "town");
    }
    private void complete(AirportEditorSession session) {
        for (int i = 0; i < 10000 && session.result().equals("pending"); i++)
            session.advance(128, p -> "town", p -> true, p -> true, p -> true);
        assertEquals("valid", session.result());
    }
    @Test void largeEditsRemainPendingAndShareOneExplicitWorkBudget() {
        var session = session();
        assertEquals("pending", session.result());
        int[] reads = {0};
        session.advance(128, p -> { reads[0]++; return "town"; }, p -> { reads[0]++; return true; }, p -> true, p -> true);
        assertEquals("pending", session.result());
        assertTrue(session.stepsUsed() <= 128);
        assertTrue(reads[0] <= 128);
        assertTrue(session.processedSteps() > 0);
        complete(session);
    }
    @Test void completedOldOrStaleOrUnauthorizedRequestsCannotCommit() {
        var old = session(); complete(old);
        assertTrue(old.canCommit(old, owner, 5, true, 101));
        assertFalse(old.canCommit(session(), owner, 5, true, 101), "superseded candidate must never commit or send an old success");
        assertFalse(old.canCommit(old, owner, 6, true, 101), "base revision changed during scan");
        assertFalse(old.canCommit(old, UUID.randomUUID(), 5, true, 101));
        assertFalse(old.canCommit(old, owner, 5, false, 101), "disconnect/permission loss must not persist silently");
        assertFalse(old.canCommit(old, owner, 5, true, 100 + AirportEditorSession.MAX_TICKS));
        old.cancel();
        assertFalse(old.canCommit(old, owner, 5, true, 101), "unloaded/cancelled candidate cannot later resume into a commit");
    }
    @Test void foreignInteriorChunkIsRejectedAndClaimsAreRecheckedAfterTerrain() {
        var foreign = session();
        for (int i = 0; i < 100 && foreign.result().equals("pending"); i++)
            foreign.advance(128, p -> p.getX() == 32 ? "foreign" : "town", p -> true, p -> true, p -> true);
        assertEquals("foreign_town", foreign.result());
        var changed = session();
        var revoked = new AtomicBoolean();
        for (int i = 0; i < 10000 && changed.result().equals("pending"); i++)
            changed.advance(128, p -> revoked.get() ? "foreign" : "town", p -> true, p -> { revoked.set(true); return true; }, p -> true);
        assertEquals("foreign_town", changed.result(), "claim revocation while terrain is being scanned needs a final bounded recheck");
    }
    @Test void unloadedCellsDoNotTriggerTerrainReads() {
        var session = session();
        for (int i = 0; i < 100 && session.result().equals("pending"); i++)
            session.advance(128, p -> "town", p -> false,
                    p -> { throw new AssertionError("no terrain read before loaded proof"); },
                    p -> { throw new AssertionError("no support read before loaded proof"); });
        assertEquals("unloaded", session.result());
    }
    @Test void unloadingADistantChunkAfterTheScanPreventsFinalCommit() {
        var session=session();complete(session);
        assertTrue(session.loadedBeforeCommit(p->true));
        assertFalse(session.loadedBeforeCommit(p->p.getX()<32||p.getX()>=48),"the airport can remain loaded while a previously scanned far chunk unloads");
    }
    @Test void anOwnershipChangeAfterAnAlreadyVisitedFinalPassChunkCannotCommit() {
        var session=session();var terrain=new AtomicBoolean();long[] epoch={7};int[] finalClaims={0};
        for(int i=0;i<10000&&session.result().equals("pending");i++)
            session.advance(128,p->{if(terrain.get()&&++finalClaims[0]==2)epoch[0]++;return "town";},p->true,p->{terrain.set(true);return true;},p->true,()->epoch[0]);
        assertEquals("stale_permission",session.result());
        assertFalse(session.canCommit(session,owner,5,true,101,epoch[0]));
    }

    @Test void automaticEditorWaitsForAuthorizedColdChunksAndReleasesEveryTicket() {
        var original = session();
        var backend = new EditorChunks();
        var request = new AirportEditorSession(owner, 5, 100, original.proposal, "town",
                new AirportEditorTerrain(backend, new AirportEditorTerrain.Budget()));

        request.advance(1, p -> "town", p -> true, p -> fail("Terrain read before ownership scan"), p -> true);
        assertTrue(backend.tickets.isEmpty());
        for (int i = 0; i < 100 && !request.loadingTerrain(); i++) {
            backend.tick++;
            request.advance(128, p -> "town", p -> false, p -> fail("Terrain read before FULL"), p -> true);
        }
        assertTrue(request.loadingTerrain());
        assertFalse(backend.tickets.isEmpty());
        assertEquals("pending", request.result());

        backend.ready = true;
        for (int i = 0; i < 10000 && request.result().equals("pending"); i++) {
            backend.tick++;
            request.advance(128, p -> "town", p -> backend.full(new ChunkPos(p)), p -> true, p -> true);
        }
        assertEquals("valid", request.result());
        assertTrue(request.loadedBeforeCommit(p -> backend.full(new ChunkPos(p))));
        request.cancel();
        assertTrue(backend.tickets.isEmpty());
    }

    @Test void aForeignChunkNeverReceivesAnEditorTicket() {
        var original = session();
        var backend = new EditorChunks();
        var request = new AirportEditorSession(owner, 5, 100, original.proposal, "town",
                new AirportEditorTerrain(backend, new AirportEditorTerrain.Budget()));
        while (request.result().equals("pending")) {
            backend.tick++;
            request.advance(128, p -> p.getX() == 32 ? "foreign" : "town", p -> false, p -> true, p -> true);
        }
        assertEquals("foreign_town", request.result());
        assertEquals(32, request.failurePosition().getX());
        assertTrue(backend.tickets.isEmpty());
    }

    @Test void aBlockedAdditionalLandingFacilityReportsTheExactBlock() {
        var original = session();
        var runway = original.proposal.changedRunway();
        var pad = new AirportFacility("pad", AirportFacility.Type.HELIPAD,
                new BlockPos(20, 80, 30), new BlockPos(22, 80, 30), 9, 6);
        var obstruction = new BlockPos(21, 82, 30);
        var proposal = new AirportEditorSession.Proposal(List.of(runway, pad), original.proposal.ground(), runway,
                false, List.of(pad));
        var request = new AirportEditorSession(owner, 5, 100, proposal, "town");
        for (int i = 0; i < 10000 && request.result().equals("pending"); i++) {
            request.advance(128, p -> "town", p -> true, p -> !p.equals(obstruction), p -> true);
        }
        assertEquals("obstructed", request.result());
        assertEquals(obstruction, request.failurePosition());
    }

    private static final class EditorChunks implements AirportEditorTerrain.Backend {
        final Set<ChunkPos> tickets = new HashSet<>();
        long tick;
        boolean ready;
        @Override public long tick() { return tick; }
        @Override public boolean full(ChunkPos chunk) { return ready && tickets.contains(chunk); }
        @Override public boolean validHeight(int y) { return y >= -64 && y < 320; }
        @Override public void ticket(UUID id, ChunkPos chunk, boolean retain) {
            assertTrue(retain ? tickets.add(chunk) : tickets.remove(chunk));
        }
    }
}
