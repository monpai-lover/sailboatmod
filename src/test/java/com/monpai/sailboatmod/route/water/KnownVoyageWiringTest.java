package com.monpai.sailboatmod.route.water;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
/** Static architecture contracts, not runtime UI or world integration tests. */
class KnownVoyageWiringTest {
    String read(String p)throws Exception{return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/"+p));}
    @Test void dispatchCannotCreateTerrainRoutes()throws Exception{for(String f:new String[]{"route/water/KnownVoyageService.java","route/water/WaterDestinationService.java","network/packet/DepartForDestinationPacket.java"}){String s=read(f);assertFalse(s.contains("submitDestinationRoute("));assertFalse(s.contains("submitAutoRoute("));assertFalse(s.contains("runSingleSegmentNbt("));}}
    @Test void graphContainsNoTerrainSamplerOrGeneratedGap()throws Exception{String s=read("route/water/KnownWaterRouteGraph.java");assertFalse(s.contains("getChunk"));assertFalse(s.contains("TerrainPredictor"));assertFalse(s.contains("WaterRoutePathfinder"));}
    @Test void dockSelectionUsesOnlyCachedGraphItinerary()throws Exception{String s=read("route/water/KnownVoyageService.java");int a=s.indexOf("if(!l.aboard){"),b=s.indexOf("// R-menu origin",a);String dock=s.substring(a,b);assertTrue(dock.contains("l.trips.get(target)"));assertFalse(dock.contains("KnownWaterEntryPlanner"));assertFalse(dock.contains("WaterRouteTaskExecutor"));}
    @Test void serverRejectsStaleMenuAndDuplicateClick()throws Exception{String s=read("route/water/KnownVoyageService.java");assertTrue(s.contains("l.consumed"));assertTrue(s.contains("l.targets.contains(target)"));assertTrue(s.contains("fresh.revision!=l.catalog.revision"));assertTrue(s.contains("!fresh.routes.equals(l.catalog.routes)"));}
    @Test void shipConnectorIsBoundedAndDoesNotSaveRoutes()throws Exception{String s=read("route/water/KnownWaterEntryPlanner.java");assertTrue(s.contains("MAX_ENTRY_DISTANCE"));assertTrue(s.contains("20_000_000_000L"));assertTrue(s.contains("StrictWaterDetour.find"));assertFalse(s.contains("register("));assertFalse(s.contains("submitAutoRoute"));}
    @Test void shipPositionIsRecheckedBeforeInstallingResult()throws Exception{String s=read("route/water/KnownVoyageService.java");assertTrue(s.contains("origin.distanceToSqr(boat.position())>2.25"));assertTrue(s.contains("authorized(player,l.source,l.vessel,true)"));}
    @Test void intermediateWaypointUsesArrivalPolicy()throws Exception{String s=read("entity/SailboatEntity.java");assertTrue(s.contains("AutopilotMotionPolicy.shouldStopForArrival(finalTarget, dist, stopRadius)"));}
    @Test void successfulGeometryDoesNotResetRecoveryAttempts()throws Exception{String s=read("entity/SailboatEntity.java");int a=s.indexOf("var splice = result.value();"),b=s.indexOf("private void tryRerouteToNextNavigableWaypointAsync",a);assertFalse(s.substring(a,b).contains("recoveryGuard.reset()"));assertTrue(s.contains("recoveryGuard.moved(getX(), getZ())"));}
    @Test void missingChunkDoesNotBecomeCachedLand()throws Exception{String s=read("entity/SailboatEntity.java");assertTrue(read("route/water/local/LoadedBoatCollisionField.java").contains("if(chunk==null)return BoatCollisionField.Cell.unknown()"));assertTrue(s.contains("autopilotAimBlocked && followerTerrainUnknown"));}
    @Test void uiHasSingleClickDestinationsAndSeparateConstruction()throws Exception{assertTrue(read("client/DockClientHooks.java").contains("KnownDestinationScreen"));assertTrue(read("client/DockClientHooks.java").contains("new AutoRouteDockSelectionScreen"));assertTrue(read("client/screen/KnownDestinationScreen.java").contains("send(1,port.position())"));}
    @Test void pausedAndWaitingAreMapVisible()throws Exception{String s=read("market/logistics/ShippingTraceService.java");assertTrue(s.contains("\"WAITING_TERRAIN\".equals(normalized)"));assertTrue(s.contains("\"PAUSED\".equals(normalized)"));assertTrue(s.contains("\"REROUTING\".equals(normalized)"));}
    @Test void protocolIncludesNewMenusAndPhases()throws Exception{
        String s=read("network/ModNetwork.java");
        assertTrue(s.contains("PROTOCOL_VERSION = \"25\""));
        assertEquals(124L,java.util.regex.Pattern.compile("CHANNEL\\.registerMessage\\(\\s*packetId\\+\\+\\s*,\\s*([\\w.]+)\\.class").matcher(s).results().count());
        assertTrue(s.contains("KnownVoyageMenuPacket.class"));assertTrue(s.contains("KnownDestinationsPacket.class"));
    }
    @Test void liveSnapshotsCannotFallBackToStaleDiskAfterFailure()throws Exception{String s=read("route/water/LiveWaterSnapshots.java");assertTrue(s.contains("TERRAIN_NOT_READY"));assertTrue(s.contains("PortChunkSnapshot.capture"));assertFalse(s.contains("getChunkNow"));}
    @Test void marketItinerariesAreNotStoredAsNewInfrastructure()throws Exception{String s=read("route/water/WaterDestinationService.java");assertTrue(s.contains("source.installKnownVoyage"));assertFalse(s.contains(".register("));assertFalse(s.contains(".setRoutes("));}
}
