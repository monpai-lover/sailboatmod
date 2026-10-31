package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.compat.tellus.TellusSurfaceSampler;
import com.monpai.sailboatmod.route.terrain.*;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Online LOD AND actual generator-column integration. No Natural Earth/GSHHS fallback. */
@EnabledIfSystemProperty(named="tellus.native",matches="true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TellusNativeRoutesTest {
    private static EarthChunkGenerator generator;
    private static LevelHeightAccessor heights;
    private static int sea;
    private static final Path OUT=Path.of("build/native-evidence");
    private static final Map<Long,NbtChunkWaterReader.ChunkColumns> CAPTURED=new ConcurrentHashMap<>();
    private static final AtomicLong rawColumns=new AtomicLong(),nativeSurfaces=new AtomicLong();

    @BeforeAll static void prepare() throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();Files.createDirectories(OUT);
        var components=EarthGeneratorSettings.class.getRecordComponents();Object[] v=new Object[components.length];Class<?>[] t=new Class<?>[components.length];
        for(int i=0;i<v.length;i++){v[i]=components[i].getAccessor().invoke(EarthGeneratorSettings.DEFAULT);t[i]=components[i].getType();if(components[i].getName().equals("worldScale"))v[i]=100.0;}
        var settings=EarthGeneratorSettings.class.getConstructor(t).newInstance(v);
        generator=EarthChunkGenerator.create(VanillaRegistries.createLookup(),settings);
        heights=LevelHeightAccessor.create(generator.getMinY(),generator.getGenDepth());sea=generator.getSeaLevel();
        Files.writeString(OUT.resolve("route-settings.txt"),settings+"\ngeneratorSeaLevel="+sea+"\nnew-world: no saved NBT override; native LOD and generated columns only\n");
    }
    static GeneratorColumnPredictor rawPredictor(){return new GeneratorColumnPredictor((x,z)->{rawColumns.incrementAndGet();return generator.getBaseColumn(x,z,heights,null);},PrefetchGate.ALWAYS_READY,heights.getMinBuildHeight(),heights.getMaxBuildHeight());}
    static TerrainPredictor predictor(){return new TerrainPredictor(){
        public PrefetchGate gate(){return PrefetchGate.ALWAYS_READY;}
        public Prediction predictChunk(int cx,int cz,long maxWait){
            long k=key(cx,cz);var old=CAPTURED.get(k);if(old!=null)return Prediction.ready(old);
            var p=rawPredictor().predictChunk(cx,cz,maxWait);
            if(p.status()==Prediction.Status.READY)CAPTURED.put(k,p.columns());return p;
        }
    };}
    static TerrainSurfaceSampler surface(){var sampler=new TellusSurfaceSampler(generator);return (x,z,spacing)->{nativeSurfaces.incrementAndGet();return sampler.sample(x,z,spacing);};}
    static long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    static BlockPos endpoint(int x,int z){
        var p=predictor().predictChunk(Math.floorDiv(x,16),Math.floorDiv(z,16),5000);
        assertEquals(Prediction.Status.READY,p.status(),"endpoint data failed");
        int i=(z&15)*16+(x&15);assertTrue(p.columns().water()[i],"requested endpoint is real land "+x+","+z);
        return new BlockPos(x,p.columns().surfaceY()[i]+1,z);
    }
    @Test @Order(1) @Timeout(300) void lodWaterHeightsAgreeWithActualGeneratedColumns() throws Exception {
        StringBuilder csv=new StringBuilder("x,z,lodBlockY,actualBlockY,boatY\n");
        for(int[] p:new int[][]{{28238,-43642},{32258,-50119},{27997,-42143}}) {
            BlockPos actual=endpoint(p[0],p[1]);var lod=surface().sample(p[0],p[1],128).orElseThrow();
            csv.append(p[0]).append(',').append(p[1]).append(',').append(lod.surfaceY()).append(',').append(actual.getY()-1).append(',').append(actual.getY()).append('\n');
            Files.writeString(OUT.resolve("native-height-agreement.csv"),csv);
            assertTrue(lod.water());assertEquals(actual.getY(),lod.surfaceY()+1,"block surface and boat coordinate conventions must agree");
        }
    }
    @Test @Order(2) @Timeout(900) void original7623BlockManyIslandRouteOnLiveTellus() throws Exception {
        route("tellus-7623-islands",28238,-43642,32258,-50119,false);
    }
    @Test @Order(3) @Timeout(900) void creteToIstanbulLongRouteOnLiveTellus() throws Exception {
        route("tellus-crete-istanbul",27997,-42143,32249,-50109,false);
    }
    @Test @Order(4) @Timeout(900) void warmReverseRouteRetainsStrictCollisionChecks() throws Exception {
        route("tellus-7623-reverse-warm",32258,-50119,28238,-43642,false);
    }
    @Test @Order(5) @Timeout(600) void delayedNativeInputsResumeWithoutChangingTheGeography() throws Exception {
        // Actual native data; only delivery latency is simulated. Same corridor includes the strait.
        route("tellus-delayed-strait",29145,-48695,30120,-49350,true);
    }
    static void route(String name,int sx,int sz,int gx,int gz,boolean delayed) throws Exception {
        long started=System.nanoTime();BlockPos start=endpoint(sx,sz),goal=endpoint(gx,gz);
        AtomicInteger jobs=new AtomicInteger();
        var pool=new ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(128),r->{Thread t=new Thread(r,"Tellus-native-test-IO");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        RouteTerrainSource.AsyncWork executor=new RouteTerrainSource.AsyncWork(){
            @Override public <T> CompletableFuture<T> submit(Supplier<T> work){
                CompletableFuture<T> result=new CompletableFuture<>();
                FutureTask<Void> task=new FutureTask<>(()->{try{if(delayed&&jobs.incrementAndGet()%7==0)Thread.sleep(20);if(!result.isCancelled())result.complete(work.get());}catch(Throwable e){result.completeExceptionally(e);}return null;});
                result.whenComplete((v,e)->{if(result.isCancelled()){task.cancel(true);pool.remove(task);}});
                try{pool.execute(task);}catch(RejectedExecutionException e){result.completeExceptionally(e);}return result;
            }
        };
        var prediction=predictor();var map=new RealBlockWaterMap(null,sea).enableOnDemand().retryUnknownPrediction().withPredictor(prediction,PredictionBudget.sixtySeconds());
        try(RouteTerrainSource terrain=new RouteTerrainSource((cx,cz)->NbtChunkWaterReader.ReadResult.absent(),prediction,TellusNativeRoutesTest::surface,executor)) {
            map.withAsyncTerrain(terrain);
            var coarse=CoarseWaterRouteWorld.realFirst(terrain::read,terrain::surface,sea);
            var detail=RealBlockWaterWorld.streaming(map,sea,1,start,goal);
            var planner=new HierarchicalWaterRoutePlanner(coarse,detail,start,goal,WaterRoutePolicy.nbtRefine(),raw->WaterAutoRouteService.finishDetailedSegment(map,raw,sea,1));
            var status=HierarchicalWaterRoutePlanner.Status.RUNNING;long next=0,maxSlice=0;int waits=0;
            while(status==HierarchicalWaterRoutePlanner.Status.RUNNING||status==HierarchicalWaterRoutePlanner.Status.WAITING) {
                long before=System.nanoTime();status=planner.step(64);maxSlice=Math.max(maxSlice,System.nanoTime()-before);
                if(status==HierarchicalWaterRoutePlanner.Status.WAITING){waits++;Thread.sleep(2);}
                if(System.nanoTime()>=next){String line=name+" "+planner.progress()+" step="+planner.currentStepSize()+" "+terrain.diagnostic();System.out.println(line);Files.writeString(OUT.resolve(name+"-progress.txt"),line+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);next=System.nanoTime()+5_000_000_000L;}
                if(System.nanoTime()-started>800_000_000_000L)break;
            }
            String report="status="+status+" reason="+planner.failureReason()+" "+planner.progress()+" elapsedMs="+(System.nanoTime()-started)/1_000_000.0+" maxPlannerSliceMs="+maxSlice/1_000_000.0+" waitingPolls="+waits+" nativeColumns="+rawColumns+" nativeSurfaceCalls="+nativeSurfaces+"\n"+terrain.diagnostic()+"\n";
            Files.writeString(OUT.resolve(name+"-result.txt"),report);
            assertEquals(HierarchicalWaterRoutePlanner.Status.SUCCESS,status,report);
            assertEquals(start,planner.path().getFirst());assertEquals(goal,planner.path().getLast());
            // Independently sample every quarter block of the final route against ACTUAL Tellus columns.
            // Do not rely solely on vertices or on the same supercover implementation under test.
            double length=0;long checked=0;StringBuilder path=new StringBuilder("x,y,z\n");
            for(BlockPos p:planner.path())path.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append('\n');
            Files.writeString(OUT.resolve(name+"-route.csv"),path);
            for(int i=1;i<planner.path().size();i++){
                var a=planner.path().get(i-1);var b=planner.path().get(i);double d=Math.sqrt(a.distSqr(b));length+=d;int n=Math.max(1,(int)Math.ceil(d*4));
                for(int j=0;j<=n;j++){
                    int x=(int)Math.floor(a.getX()+.5+(b.getX()-a.getX())*(double)j/n),z=(int)Math.floor(a.getZ()+.5+(b.getZ()-a.getZ())*(double)j/n);
                    for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++) {
                        int xx=x+dx,zz=z+dz;var p=prediction.predictChunk(Math.floorDiv(xx,16),Math.floorDiv(zz,16),5000);assertEquals(Prediction.Status.READY,p.status());int k=(zz&15)*16+(xx&15);
                        assertTrue(p.columns().water()[k],"real Tellus land collision "+xx+","+zz);assertEquals(a.getY(),p.columns().surfaceY()[k]+1);checked++;
                    }
                }
            }
            Files.writeString(OUT.resolve(name+"-result.txt"),"lengthBlocks="+length+" strictNativeHullSamples="+checked+" collisions=0\n",StandardOpenOption.APPEND);
            if(name.contains("7623")||name.contains("crete"))assertTrue(length>7000);
        } finally {pool.shutdownNow();dumpColumns(name);}
    }
    static void dumpColumns(String name) throws IOException {
        try(var writer=new BufferedWriter(new OutputStreamWriter(new java.util.zip.GZIPOutputStream(Files.newOutputStream(OUT.resolve(name+"-columns.csv.gz"))),java.nio.charset.StandardCharsets.UTF_8))) {
            writer.write("x,z,water,surfaceBlockY\n");
            for(var entry:CAPTURED.entrySet()) {int cx=(int)(entry.getKey()>>32),cz=(int)(long)entry.getKey();var c=entry.getValue();for(int i=0;i<256;i++)writer.write(((cx<<4)+(i&15))+","+((cz<<4)+(i>>4))+","+(c.water()[i]?1:0)+","+c.surfaceY()[i]+"\n");}
        }
    }
}
