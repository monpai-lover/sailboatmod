package com.monpai.sailboatmod.route.water;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class AsyncPortWiringTest {
    private String src(String path)throws Exception{return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod",path));}
    @Test void noGlobalSaveInRouteReads()throws Exception{String s=src("route/water/NbtChunkWaterReader.java");assertFalse(s.contains("readSafeFromWorkerThread("));assertFalse(s.contains(".save("));assertTrue(s.contains("raw.get().copy()"));}
    @Test void createPacketDoesNotWaitForChunkFuture()throws Exception{String s=src("network/packet/CreateAutoRoutePacket.java");assertTrue(s.contains("getChunkFuture("));assertFalse(s.contains(".join()"));assertFalse(s.contains(".get(20"));}
    @Test void snapshotsAreSliced()throws Exception{String s=src("route/water/PortChunkSnapshot.java");assertTrue(s.contains("index+32"));assertFalse(s.contains("getChunk("));}
    @Test void ioQueueRejectsRatherThanRunsOnCaller()throws Exception{String s=src("route/water/PortAsyncRuntime.java");assertTrue(s.contains("new ThreadPoolExecutor.AbortPolicy()"));assertTrue(s.contains("MAIN_BUDGET_NANOS"));}
    @Test void cancelledListDoesNotReopenScreen()throws Exception{String s=src("client/DockClientHooks.java");assertTrue(s.contains("selection.acceptsList(requestId)"));}
    @Test void chatMixinIsClientOnlyResource()throws Exception{String s=Files.readString(Path.of("src/main/resources/sailboatmod-chat.mixins.json"));assertTrue(s.contains("\"client\""));assertFalse(s.contains("\"mixins\""));}
    @Test void buildingRetiresPreviousListRequest()throws Exception{
        String s=src("client/screen/AutoRouteDockSelectionScreen.java");
        String create=s.substring(s.indexOf("private void createRoute()"),s.indexOf("public boolean acceptsList"));
        assertTrue(create.contains("CancelPortWorkPacket(listRequest,false)"));
        assertTrue(create.indexOf("listing=false")<create.indexOf("building=true"));
        assertTrue(create.contains("listRequest=java.util.UUID.randomUUID()"));
    }
}
