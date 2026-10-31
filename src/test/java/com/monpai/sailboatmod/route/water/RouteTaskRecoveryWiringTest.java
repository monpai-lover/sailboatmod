package com.monpai.sailboatmod.route.water;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RouteTaskRecoveryWiringTest {
    private String src(String path)throws Exception{return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod",path));}
    @Test void reopenRequestsAuthoritativeTaskSnapshot()throws Exception{
        String screen=src("client/screen/AutoRouteDockSelectionScreen.java");
        assertTrue(screen.contains("new com.monpai.sailboatmod.network.packet.RequestRouteTasksPacket(recoveryQuery)"));
        assertTrue(screen.contains("routeRequest = task.id()"));assertTrue(screen.contains("packet.query().equals(recoveryQuery)"));
    }
    @Test void queueCancelsExactServerIdentity()throws Exception{
        String screen=src("client/screen/RouteTaskQueueScreen.java");assertTrue(screen.contains("new CancelPortWorkPacket(task.id(), true)"));
        assertTrue(screen.contains("packet.query().equals(query)"));
    }
    @Test void cancellationIsBoundToOwnedTaskNotSharedRouteKey()throws Exception{
        String sessions=src("route/water/RouteUiSessions.java");assertTrue(sessions.contains("owned(player).get(id)"));
        assertTrue(sessions.contains("session.cancelOwnedTask()"));assertFalse(sessions.contains("global().cancel(session.key)"));
    }
    @Test void terrainQueriesStayOutOfTaskListHandler()throws Exception{
        String p=src("network/packet/RequestRouteTasksPacket.java");assertTrue(p.contains("RouteUiSessions.tasks(player)"));
        assertFalse(p.contains("getChunk"));assertFalse(p.contains(".join("));
    }
    @Test void taskPacketsAreRegisteredAndProtocolIsBumped()throws Exception{
        String p=src("network/ModNetwork.java");assertTrue(p.contains("PROTOCOL_VERSION = \"19\""));
        assertTrue(p.contains("RequestRouteTasksPacket.class"));assertTrue(p.contains("SyncRouteTasksPacket.class"));
    }
}
