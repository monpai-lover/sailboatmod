package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.util.OfflineChunkPalette;
import com.monpai.sailboatmod.route.terrain.*;
import net.minecraft.nbt.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Actual terrain-only NBT from the two problem regions; no invented chunk statuses. */
class ProtoChunkRoutingTest {
    static CompoundTag fixture(String name) throws Exception {
        try (var input = ProtoChunkRoutingTest.class.getResourceAsStream("/route-water/proto-regions/" + name + ".nbt.gz.b64")) {
            assertNotNull(input);
            byte[] compressed = Base64.getMimeDecoder().decode(new String(input.readAllBytes(), StandardCharsets.US_ASCII));
            return NbtIo.readCompressed(new ByteArrayInputStream(compressed), NbtAccounter.unlimitedHeap());
        }
    }
    private static NbtChunkWaterReader.ReadResult decode(CompoundTag tag) {
        return NbtChunkWaterReader.decodeForRouting(tag, -64, 320);
    }
    private static void water256(NbtChunkWaterReader.ReadResult result) {
        assertTrue(result.hasTerrain()); assertFalse(result.allowsPrediction());
        for (int i=0;i<256;i++) { assertTrue(result.columns().water()[i]); assertEquals(64, result.columns().surfaceY()[i]); }
    }
    @Test void actualInitializeLightChunkHasNavigableWaterWithoutNeedingFullOrPrediction() throws Exception {
        var tag = fixture("target-initialize-light"); var before = tag.copy();
        var result = decode(tag); water256(result);
        assertEquals(NbtChunkWaterReader.ReadStatus.PROTO_TERRAIN,result.status());
        assertEquals("initialize_light", result.generationStatus()); assertEquals(before,tag);
    }
    @Test void actualCarversChunkHasNavigableWaterWithoutRepeatedDiskRetry() throws Exception {
        var result=decode(fixture("target-carvers")); water256(result);
        assertEquals("carvers",result.generationStatus());
    }
    @Test void actualFullLandCannotBeOverwrittenByTellusOcean() throws Exception {
        var result=decode(fixture("sample-full"));assertEquals(NbtChunkWaterReader.ReadStatus.READY,result.status());
        for(boolean water:result.columns().water())assertFalse(water);
        AtomicInteger calls=new AtomicInteger();
        var coarse=CoarseWaterRouteWorld.realFirst((cx,cz)->result,(x,z,spacing)-> {
            calls.incrementAndGet();return Optional.of(new SurfaceSample(64,null,true,20));
        },63);
        assertFalse(coarse.sample(5,5,WaterRoutePolicy.nbtCoarse()).passable()); assertEquals(0,calls.get());
    }
    @Test void actualBiomesAllAirPermitsPredictionButIsNotReportedAsMissingFile() throws Exception {
        var result=decode(fixture("sample-biomes"));
        assertEquals(NbtChunkWaterReader.ReadStatus.PRE_TERRAIN,result.status());
        assertTrue(result.allowsPrediction());assertFalse(result.hasTerrain());
    }
    @Test void actualStructureStartsAllAirPermitsPrediction() throws Exception {
        var result=decode(fixture("sample-structure_starts"));
        assertEquals(NbtChunkWaterReader.ReadStatus.PRE_TERRAIN,result.status());assertTrue(result.allowsPrediction());
    }
    @Test void earlyStatusWithUnexpectedSavedSolidBlocksMustNotUsePrediction() throws Exception {
        var tag=fixture("sample-full");tag.putString("Status","minecraft:biomes");var result=decode(tag);
        assertEquals(NbtChunkWaterReader.ReadStatus.UNAVAILABLE,result.status());
        assertEquals(NbtChunkWaterReader.ReadIssue.UNEXPECTED_EARLY_BLOCKS,result.issue());assertFalse(result.allowsPrediction());
    }
    @Test void unknownOrMissingStatusIsNotAssumedUngenerated() throws Exception {
        var tag=fixture("target-carvers");tag.putString("Status","other:carvers");
        assertFalse(decode(tag).allowsPrediction());tag.remove("Status");
        assertEquals(NbtChunkWaterReader.ReadIssue.UNKNOWN_STATUS,decode(tag).issue());
    }
    @Test void partiallyGeneratedNoiseRemainsUnknown() throws Exception {
        var tag=fixture("target-carvers");tag.putString("Status","minecraft:noise");
        assertEquals(NbtChunkWaterReader.ReadIssue.INCOMPLETE_TERRAIN,decode(tag).issue());
    }
    @Test void malformedPackedArrayIsRejectedRatherThanDecodingPaletteZeroAsWater() throws Exception {
        var tag=fixture("target-carvers");var sections=tag.getList("sections",Tag.TAG_COMPOUND);
        boolean changed=false;
        for(int i=0;i<sections.size();i++) {
            var states=sections.getCompound(i).getCompound("block_states");
            if(states.getList("palette",Tag.TAG_COMPOUND).size()>1){states.putLongArray("data",new long[0]);changed=true;break;}
        }
        assertTrue(changed);assertEquals(NbtChunkWaterReader.ReadIssue.DECODE_ERROR,decode(tag).issue());
    }
    @Test void allAirLateProtoIsNotProvenOceanOrLand() throws Exception {
        var tag=fixture("sample-biomes");tag.putString("Status","minecraft:carvers");
        assertEquals(NbtChunkWaterReader.ReadIssue.INCOMPLETE_TERRAIN,decode(tag).issue());
    }
    @Test void legacyLevelWrapperAndBareStatusAreReadWithoutMutatingSave() throws Exception {
        var body=fixture("target-carvers");body.putString("Status","carvers");
        var root=new CompoundTag();root.put("Level",body);var before=root.copy();
        water256(decode(root));assertEquals(before,root);
    }
    @Test void sharedWebPaletteReaderStillRequiresFullStatus() throws Exception {
        var tag=fixture("target-carvers");
        assertTrue(OfflineChunkPalette.decode(tag,-64,320).isEmpty());
        assertFalse(OfflineChunkPalette.decodeTerrainSnapshot(tag,-64,320).isEmpty());
    }
    @Test void coarseRetainsProtoWaterHeight65NotDefaultSeaLevel63() throws Exception {
        var result=decode(fixture("target-carvers"));
        var coarse=CoarseWaterRouteWorld.realFirst((cx,cz)->result,null,63);
        assertEquals(new BlockPos(5,65,5),coarse.sample(5,5,WaterRoutePolicy.nbtCoarse()).surfacePos());
    }
    @Test void genuineIoErrorNeverTriggersPrediction() {
        AtomicInteger calls=new AtomicInteger();
        var coarse=CoarseWaterRouteWorld.realFirst((cx,cz)->NbtChunkWaterReader.ReadResult.unavailable(
                NbtChunkWaterReader.ReadIssue.IO_ERROR,""),(x,z,s)->{calls.incrementAndGet();return Optional.of(new SurfaceSample(64,null,true,20));},63);
        assertNull(coarse.sample(5,5,WaterRoutePolicy.nbtCoarse()));assertEquals(0,calls.get());
    }
    @Test void stableProtoResultIsReadOnceEvenAcrossThousandsOfSearchPolls() throws Exception {
        var result=decode(fixture("target-initialize-light"));AtomicInteger calls=new AtomicInteger();
        try(var cache=new DeferredTerrainCache<Integer,NbtChunkWaterReader.ReadResult>(k->{calls.incrementAndGet();return CompletableFuture.completedFuture(result);},
                r->r.status()!=NbtChunkWaterReader.ReadStatus.UNAVAILABLE,4096,8)) {
            for(int i=0;i<10_000;i++)assertSame(result,cache.poll(17));
            assertEquals(1,calls.get());assertTrue(cache.diagnostic().contains("retry=0"));
        }
    }
    @Test void fineMapAcceptsProtoAndEarlyPredictionButProtectsFullLand() throws Exception {
        var wet=decode(fixture("target-carvers"));var dry=decode(fixture("sample-full"));var early=decode(fixture("sample-biomes"));
        var predictor=new TerrainPredictor(){public PrefetchGate gate(){return PrefetchGate.ALWAYS_READY;}
            public Prediction predictChunk(int x,int z,long wait){return Prediction.ready(wet.columns());}};
        var direct=new RouteTerrainSource.AsyncWork(){public <T> CompletableFuture<T> submit(Supplier<T> work){return CompletableFuture.completedFuture(work.get());}};
        try(var terrain=new RouteTerrainSource((cx,cz)->cx==0?wet:cx==1?dry:early,predictor,()->null,direct)) {
            var map=new RealBlockWaterMap(null,63).enableOnDemand().retryUnknownPrediction()
                    .withPredictor(predictor,PredictionBudget.sixtySeconds()).withAsyncTerrain(terrain);
            assertTrue(map.sampleHullStrict(5,5,1).passable());assertEquals(65,map.sampleHullStrict(5,5,1).surfacePos().getY());
            assertFalse(map.sampleHullStrict(20,5,1).passable());assertTrue(map.sampleHullStrict(36,5,1).passable());
            assertTrue(terrain.diagnostic().contains("PROTO_TERRAIN/carvers"));
            assertTrue(terrain.diagnostic().contains("PRE_TERRAIN/biomes"));
        }
    }
    @Test void longRouteTraversesMixedSavedAndProtoChunksAndPredictedEarlyTerrain() throws Exception {
        var wet=decode(fixture("target-carvers"));var light=decode(fixture("target-initialize-light"));var early=decode(fixture("sample-structure_starts"));
        var full=new NbtChunkWaterReader.ReadResult(NbtChunkWaterReader.ReadStatus.READY,wet.columns());
        AtomicInteger reads=new AtomicInteger(),predictions=new AtomicInteger();
        var predictor=new TerrainPredictor(){public PrefetchGate gate(){return PrefetchGate.ALWAYS_READY;}
            public Prediction predictChunk(int cx,int cz,long wait){predictions.incrementAndGet();return Prediction.ready(wet.columns());}};
        var direct=new RouteTerrainSource.AsyncWork(){public <T> CompletableFuture<T> submit(Supplier<T> work){return CompletableFuture.completedFuture(work.get());}};
        try(var terrain=new RouteTerrainSource((cx,cz)->{reads.incrementAndGet();return switch(Math.floorMod(cx,4)){case 0->wet;case 1->light;case 2->early;default->full;};},
                predictor,()->(x,z,s)->Optional.of(new SurfaceSample(64,null,true,20)),direct)) {
            var map=new RealBlockWaterMap(null,63).enableOnDemand().retryUnknownPrediction().withPredictor(predictor,PredictionBudget.sixtySeconds()).withAsyncTerrain(terrain);
            var a=new BlockPos(3,65,5);var b=new BlockPos(7010,65,5);
            var coarse=CoarseWaterRouteWorld.realFirst(terrain::read,terrain::surface,63);
            var fine=RealBlockWaterWorld.streaming(map,63,1,a,b);
            var planner=new HierarchicalWaterRoutePlanner(coarse,fine,a,b,WaterRoutePolicy.nbtRefine());
            var state=HierarchicalWaterRoutePlanner.Status.RUNNING;
            for(int i=0;i<50000&&(state==HierarchicalWaterRoutePlanner.Status.RUNNING||state==HierarchicalWaterRoutePlanner.Status.WAITING);i++)state=planner.step(64);
            assertEquals(HierarchicalWaterRoutePlanner.Status.SUCCESS,state);
            assertEquals(a,planner.path().getFirst());assertEquals(b,planner.path().getLast());assertTrue(predictions.get()>0);assertTrue(reads.get()>0);
            assertTrue(terrain.diagnostic().contains("retry=0"));
        }
    }
    @Test void newProductionNbtPipelineHasNoBlockingWaitAndRetainsRequestIdentity() throws Exception {
        String src=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/monpai/sailboatmod/route/water/NbtChunkWaterReader.java"));
        String pipeline=src.substring(src.indexOf("public static java.util.concurrent.CompletableFuture<ReadResult> readForRoutingAsync"),src.indexOf("private static ReadResult readFailure"));
        assertFalse(pipeline.contains(".join("));assertFalse(pipeline.contains(".get(Math"));
        assertTrue(pipeline.contains("PortChunkSnapshot.capture"));assertTrue(pipeline.contains("raw.get().copy()"));
        assertTrue(pipeline.contains("thenApply(raw -> raw)"));assertTrue(pipeline.contains("future.cancel(true)"));
    }
}
