package com.monpai.sailboatmod.route.water.local;

import com.monpai.sailboatmod.route.water.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BoatCollisionFieldTest {
    static final BoatCollisionField.Hull HULL=new BoatCollisionField.Hull(1,1,64.78,66.6,64);
    static final BoatCollisionField.Cell WATER=new BoatCollisionField.Cell(true,true,List.of());
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    static Vec3 p(double x,double z){return new Vec3(x,65,z);}
    static BoatCollisionField empty(){return new BoatCollisionField(HULL,(x,z)->WATER,List.of());}
    static BoatCollisionField withShapes(List<AABB> boxes) {
        return new BoatCollisionField(HULL,(x,z)->new BoatCollisionField.Cell(true,true,
                boxes.stream().filter(b->x==(int)Math.floor(b.minX)&&z==(int)Math.floor(b.minZ)).toList()),List.of());
    }
    @Test void freeOpenWaterRemainsClear(){assertEquals(BoatCollisionField.Check.CLEAR,empty().segment(p(-4.9,-3.8),p(15.2,5.1)));}
    @Test void centredFootprintChecksNegativeSideNotOnlyPositiveOffsets(){
        var field=withShapes(List.of(new AABB(-1,65,4,0,66,5)));
        assertEquals(BoatCollisionField.Check.BLOCKED,field.segment(p(.4,0),p(.4,8)));
    }
    @Test void floatingHeightNeverChangesWaterInterface(){
        var hull=BoatCollisionField.Hull.at(new AABB(-1,64.91,-1,1,66.51,1),65);
        assertEquals(64,hull.waterY());assertTrue(hull.minY()<64.91);
    }
    @Test void realSeaPickleShapesAreNotEquivalentToWater(){
        for(int count=1;count<=4;count++) {
            var state=Blocks.SEA_PICKLE.defaultBlockState().setValue(SeaPickleBlock.PICKLES,count);
            assertTrue(WaterSurfaceRules.isNavigableSurface(state),"old water-only map accepts the waterlogged state");
            var boxes=state.getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO,CollisionContext.empty()).toAabbs();
            assertFalse(boxes.isEmpty());
            var field=withShapes(boxes.stream().map(b->b.move(7,65,0)).toList());
            assertEquals(BoatCollisionField.Check.BLOCKED,field.segment(p(0,.5),p(14,.5)));
            var underwater=withShapes(boxes.stream().map(b->b.move(7,64,0)).toList());
            assertEquals(BoatCollisionField.Check.CLEAR,underwater.segment(p(0,.5),p(14,.5)),"shape below draft must not be treated as a full solid water column");
        }
    }
    @Test void thinObstacleCannotBeSkippedBetweenSamples(){
        var field=withShapes(List.of(new AABB(7.12,65,.49,7.13,65.2,.51)));
        assertEquals(BoatCollisionField.Check.BLOCKED,field.segment(p(0,.5),p(20,.5)));
    }
    @Test void nonemptyShapeOutsideSweptHullDoesNotBlockDiagonalTravel(){
        var field=withShapes(List.of(new AABB(0,65,10,1,66,11)));
        assertEquals(BoatCollisionField.Check.CLEAR,field.segment(p(0,0),p(12,12)));
    }
    @Test void parkedBoatIsAnObstacleEvenThoughAllColumnsAreWater(){
        var field=new BoatCollisionField(HULL,(x,z)->WATER,List.of(new AABB(8,64.9,-1,10,66.5,1)));
        assertEquals(BoatCollisionField.Check.BLOCKED,field.segment(p(0,0),p(20,0)));
        assertEquals(BoatCollisionField.Check.CLEAR,field.segment(p(0,4),p(20,4)));
    }
    @Test void marginCanBeEscapedWithoutPermittingCrossingTheBoat(){
        var field=new BoatCollisionField(HULL,(x,z)->WATER,List.of(new AABB(1.1,64.9,-1,3.1,66.5,1)));
        assertEquals(BoatCollisionField.Check.CLEAR,field.segment(p(0,0),p(-1,0)));
        assertEquals(BoatCollisionField.Check.BLOCKED,field.segment(p(0,0),p(4,0)));
    }
    @Test void unloadedColumnsAreUnknownNotLand(){
        var field=new BoatCollisionField(HULL,(x,z)->x>5?BoatCollisionField.Cell.unknown():WATER,List.of());
        assertEquals(BoatCollisionField.Check.UNKNOWN,field.segment(p(0,0),p(12,0)));
    }
    @Test void trueLandAndWrongWaterHeightStayBlocked(){
        var land=new BoatCollisionField(HULL,(x,z)->new BoatCollisionField.Cell(true,false,List.of()),List.of());
        assertEquals(BoatCollisionField.Check.BLOCKED,land.segment(p(0,0),p(2,0)));
        assertEquals(BoatCollisionField.Check.BLOCKED,empty().segment(p(0,0),new Vec3(2,66,0)));
    }
    @Test void bypassesStationaryBoatAndKeepsTrueDestination(){
        var field=new BoatCollisionField(HULL,(x,z)->WATER,List.of(new AABB(8,64.9,-1,10,66.5,1)));
        List<Vec3> original=List.of(p(0,0),p(3000,0));
        var result=BoatLocalDetour.plan(field,p(0,0),original,1);assertTrue(result.successful(),result.reason().toString());
        var splice=result.value();assertTrue(splice.detour().size()>2);assertEquals(1,splice.tailIndex());
        assertEquals(p(0,0),splice.detour().getFirst());
        for(int i=1;i<splice.detour().size();i++)assertEquals(BoatCollisionField.Check.CLEAR,field.segment(splice.detour().get(i-1),splice.detour().get(i)));
        var merged=new ArrayList<>(splice.detour());merged.addAll(original.subList(splice.tailIndex(),original.size()));
        assertEquals(original.getLast(),merged.getLast());
    }
    @Test void bypassUsesActualSeaPickleCollisionShape(){
        var shape=Blocks.SEA_PICKLE.defaultBlockState().setValue(SeaPickleBlock.PICKLES,4)
                .getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO,CollisionContext.empty());
        var field=withShapes(shape.toAabbs().stream().map(b->b.move(8,65,0)).toList());
        var result=BoatLocalDetour.plan(field,p(0,.5),List.of(p(0,.5),p(1000,.5)),1);
        assertTrue(result.successful(),result.reason().toString());
        for(int i=1;i<result.value().detour().size();i++)assertEquals(BoatCollisionField.Check.CLEAR,
                field.segment(result.value().detour().get(i-1),result.value().detour().get(i)));
    }
    @Test void impassableTrafficBarrierDoesNotProduceFakeRepair(){
        var field=new BoatCollisionField(HULL,(x,z)->WATER,List.of(new AABB(2,64.9,-100,100,66.5,100)));
        assertFalse(BoatLocalDetour.plan(field,p(0,0),List.of(p(0,0),p(1000,0)),1).successful());
    }
    @Test void noUnknownSeaShortcutIsPublished(){
        var field=new BoatCollisionField(HULL,(x,z)->BoatCollisionField.Cell.unknown(),List.of());
        assertEquals(WaterRouteFailureReason.TERRAIN_NOT_READY,BoatLocalDetour.plan(field,p(0,0),List.of(p(0,0),p(1000,0)),1).reason());
    }
    @Test void cancellationIsHonoured(){
        assertThrows(java.util.concurrent.CancellationException.class,()->WaterRouteCancellation.run(()->true,
                ()->BoatLocalDetour.plan(empty(),p(0,0),List.of(p(0,0),p(1000,0)),1)));
    }
    @Test void finalApproachAlsoSupportsAnObstacleBypass(){
        var field=new BoatCollisionField(HULL,(x,z)->WATER,List.of(new AABB(6,64.9,-1,8,66.5,1)));
        var result=BoatLocalDetour.plan(field,p(0,0),List.of(p(0,0),p(16,0)),1);
        assertTrue(result.successful());
        assertEquals(p(16,0),result.value().detour().getLast());assertEquals(2,result.value().tailIndex());
    }
    @Test void alreadyCaughtOnPickleCanSeparateButCannotMoveDeeperOrAcrossIt(){
        var field=withShapes(List.of(new AABB(0,64.8,0,1,65.2,1)));
        assertEquals(BoatCollisionField.Check.CLEAR,field.segment(p(-.8,.5),p(-2,.5)));
        assertEquals(BoatCollisionField.Check.BLOCKED,field.segment(p(-.8,.5),p(2,.5)));
        assertEquals(BoatCollisionField.Check.BLOCKED,field.segment(p(-.8,.5),p(-.2,.5)));
        assertEquals(BoatCollisionField.Check.BLOCKED,field.segment(p(-2,.5),p(2,.5)));
    }
    @Test void hullTouchingPickleTopStillFindsASeparatingRescue(){
        var boxes=Blocks.SEA_PICKLE.defaultBlockState().setValue(SeaPickleBlock.PICKLES,4)
                .getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO,CollisionContext.empty()).toAabbs();
        double top=boxes.stream().mapToDouble(b->b.maxY).max().orElseThrow()+64;
        var hull=BoatCollisionField.Hull.at(new AABB(7.5,top,-.5,9.5,top+1.6,1.5),65);
        var physical=boxes.stream().map(b->b.move(8,64,0)).toList();
        var field=new BoatCollisionField(hull,(x,z)->new BoatCollisionField.Cell(true,true,x==8&&z==0?physical:List.of()),List.of());
        var result=BoatLocalDetour.plan(field,p(8.5,.5),List.of(p(8.5,.5),p(1000,.5)),1);
        assertTrue(result.successful(),result.reason().toString());
        for(int i=1;i<result.value().detour().size();i++)assertEquals(BoatCollisionField.Check.CLEAR,
                field.segment(result.value().detour().get(i-1),result.value().detour().get(i)));
    }

    @Test void detourIsFollowableWithoutRepeatedCollisionsOrWaypointStalls() throws Exception {
        var field=new BoatCollisionField(HULL,(x,z)->WATER,List.of(new AABB(8,64.9,-1,10,66.5,1)));
        Vec3 at=p(0,0),last=at,end=p(40,0);
        var original=List.of(at,end);var plan=BoatLocalDetour.plan(field,at,original,1);
        assertTrue(plan.successful());var route=new ArrayList<>(plan.value().detour());
        route.addAll(original.subList(plan.value().tailIndex(),original.size()));
        // Kinematic smoke test of the production follower; not a Minecraft physics integration.
        int index=1,ticks=0;double heading=0;
        while(com.monpai.sailboatmod.route.WaterPolylineFollower.horizontal(at,end)>1&&ticks++<1000) {
            index=com.monpai.sailboatmod.route.WaterPolylineFollower.advance(route,index,last,at,1.25,3,
                    (a,b)->field.segment(a,b)==BoatCollisionField.Check.CLEAR);
            var aim=com.monpai.sailboatmod.route.WaterPolylineFollower.aim(route,index,at,4,
                    (a,b)->field.segment(a,b)==BoatCollisionField.Check.CLEAR);
            assertFalse(aim.blocked(),"Detour follower stalled at "+at);
            double angle=Math.atan2(aim.point().z-at.z,aim.point().x-at.x);
            double diff=Math.atan2(Math.sin(angle-heading),Math.cos(angle-heading));
            heading+=Math.clamp(diff,-Math.toRadians(2),Math.toRadians(2));
            double speed=Math.abs(diff)>Math.toRadians(50)?0:.12;
            Vec3 next=at.add(Math.cos(heading)*speed,0,Math.sin(heading)*speed);
            assertEquals(BoatCollisionField.Check.CLEAR,field.segment(at,next));last=at;at=next;
        }
        assertTrue(ticks<1000,"Follower never reached destination");
        var out=java.nio.file.Path.of("build/verification/parked-boat-detour.csv");
        java.nio.file.Files.createDirectories(out.getParent());
        java.nio.file.Files.writeString(out,"x,y,z\n"+route.stream().map(p->p.x+","+p.y+","+p.z).collect(java.util.stream.Collectors.joining("\n"))+"\n");
    }

}
