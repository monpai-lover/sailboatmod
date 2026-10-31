package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real (simplified GSHHS intermediate) coastline fixture, NOT a live Tellus generation/network test. */
class AegeanRouteRegressionTest {
    private static final BlockPos CRETE = new BlockPos(27997,65,-42143);
    private static final BlockPos ISTANBUL = new BlockPos(32249,65,-50109);
    @Test void creteToIstanbulPassesTheAegeanAndDardanellesThenStrictlyRefinesEverySegment() throws Exception {
        Raster raster=Raster.load();
        WaterRouteWorld coarse=WaterRouteDeferredEdgesTest.world((x,z)->raster.sample(x,z,false));
        WaterRouteWorld fine=WaterRouteDeferredEdgesTest.world((x,z)->raster.sample(x,z,true));
        var planner=new HierarchicalWaterRoutePlanner(coarse,fine,CRETE,ISTANBUL,WaterRoutePolicy.nbtRefine());
        HierarchicalWaterRoutePlanner.Status status=HierarchicalWaterRoutePlanner.Status.RUNNING;
        for(int i=0;i<20_000&&(status==HierarchicalWaterRoutePlanner.Status.RUNNING||status==HierarchicalWaterRoutePlanner.Status.WAITING);i++)status=planner.step(64);
        assertEquals(HierarchicalWaterRoutePlanner.Status.SUCCESS,status,planner.failureReason().toString());
        assertEquals(CRETE,planner.path().getFirst());assertEquals(ISTANBUL,planner.path().getLast());
        assertTrue(planner.progress().totalSegments()>1);
        // Verify every half-block along the resulting segments against the strict 2x2 footprint,
        // not just the returned vertices. No implicit endpoint/water exemptions.
        double length=0;
        for(int j=1;j<planner.path().size();j++) {
            var a=planner.path().get(j-1);var b=planner.path().get(j);double d=Math.sqrt(a.distSqr(b));length+=d;
            int n=Math.max(1,(int)Math.ceil(d*2));
            for(int k=0;k<=n;k++) {
                int x=(int)Math.round(a.getX()+(b.getX()-a.getX())*(double)k/n);
                int z=(int)Math.round(a.getZ()+(b.getZ()-a.getZ())*(double)k/n);
                assertTrue(raster.sample(x,z,true).passable(),"land/hull collision at "+x+","+z);
            }
        }
        assertTrue(length>9000&&length<16000,"a bounded sea route, not a straight line through Turkey");
        Path out=Path.of("build/route-regression");Files.createDirectories(out);
        StringBuilder csv=new StringBuilder("x,y,z\n");for(var p:planner.path())csv.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append('\n');
        Files.writeString(out.resolve("aegean-route.csv"),csv);
        Files.writeString(out.resolve("aegean-result.txt"),"SUCCESS\n"+planner.progress()+"\nlengthBlocks="+length+"\nstrictHullCollisions=0\nfixture=GSHHS intermediate; not live Tellus\n");
    }
    @Test void uploadedDestinationHasARealBerthAndALocalWaterExit() throws Exception {
        List<String> rows=resourceLines("junshi-surface.txt");
        var zone=new PortBerthValidator.Zone(73,3,100,-5,23,-9,36);
        var result=PortBerthValidator.resolve(zone,(x,z)-> {
            if(z<0||z>=rows.size()||x<0||x>=rows.get(0).length())return PortBerthValidator.Cell.unknown();
            char c=rows.get(z).charAt(x);
            return c=='?'?PortBerthValidator.Cell.unknown():Character.isDigit(c)
                    ?new PortBerthValidator.Cell(PortBerthValidator.State.WATER,c-'0',PortBerthValidator.Origin.NBT)
                    :new PortBerthValidator.Cell(PortBerthValidator.State.LAND,0,PortBerthValidator.Origin.NBT);
        },()->false);
        assertEquals(PortBerthValidator.Verdict.VERIFIED,result.verdict());
        assertEquals(2,result.berth().y()); // local water top=1, boat surface=2; world origin isn't stored in this structure
    }
    @Test void uploadedCoastalCropDoesNotLetUnknownOutsideEdgesFreezeTheKnownHarbourPath() throws Exception {
        List<String> rows=resourceLines("junshi-surface.txt");
        WaterRouteWorld world=WaterRouteDeferredEdgesTest.world((x,z)->{
            if(x<0||z<0||z+1>=rows.size()||x+1>=rows.get(0).length())return null;
            char top=rows.get(z).charAt(x);if(!Character.isDigit(top))return WaterColumn.blocked();
            for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++)if(rows.get(z+dz).charAt(x+dx)!=top)return WaterColumn.blocked();
            return WaterColumn.passable(new BlockPos(x,top-'0'+1,z),0);
        });
        var start=new BlockPos(100,2,108);var goal=new BlockPos(73,2,108);
        var p=new WaterRoutePathfinder(world,start,goal,new WaterRoutePolicy(2,1,1,3,200,10000,0,64,0,1800),1);
        for(int i=0;i<1000&&p.status()!=WaterRoutePathfinder.Status.SUCCESS&&p.status()!=WaterRoutePathfinder.Status.FAILED;i++)p.step(32,0);
        assertEquals(WaterRoutePathfinder.Status.SUCCESS,p.status());assertEquals(start,p.path().getFirst());assertEquals(goal,p.path().getLast());
    }
    static List<String> resourceLines(String name)throws IOException {
        try(InputStream stream=Objects.requireNonNull(AegeanRouteRegressionTest.class.getResourceAsStream("/route-terrain/"+name))) {
            return new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).lines().toList();
        }
    }
    static final class Raster {
        final boolean[][] water;final int x0,z0,step;
        Raster(boolean[][] water,int x0,int z0,int step){this.water=water;this.x0=x0;this.z0=z0;this.step=step;}
        static Raster load()throws IOException {
            var lines=resourceLines("aegean-gshhs-i.rle");var h=lines.getFirst().split(" ");
            int w=Integer.parseInt(h[0]),height=Integer.parseInt(h[1]);boolean[][] data=new boolean[height][w];
            for(int z=0;z<height;z++){var r=lines.get(z+1).split(" ");boolean val=r[0].equals("W");int x=0;
                for(int k=1;k<r.length;k++){int n=Integer.parseInt(r[k]);Arrays.fill(data[z],x,x+n,val);x+=n;val=!val;}
                if(x!=w)throw new IOException("invalid RLE row");
            }
            return new Raster(data,Integer.parseInt(h[2]),Integer.parseInt(h[3]),Integer.parseInt(h[4]));
        }
        boolean wet(int x,int z){int xx=Math.floorDiv(x-x0,step),zz=Math.floorDiv(z-z0,step);return zz>=0&&zz<water.length&&xx>=0&&xx<water[0].length&&water[zz][xx];}
        WaterColumn sample(int x,int z,boolean hull){return wet(x,z)&&(!hull||wet(x+1,z)&&wet(x,z+1)&&wet(x+1,z+1))?WaterColumn.passable(new BlockPos(x,65,z),0):WaterColumn.blocked();}
    }
}
