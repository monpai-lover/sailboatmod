package com.monpai.sailboatmod.route.water.local;

import com.monpai.sailboatmod.entity.SailboatEntity;
import com.monpai.sailboatmod.entity.CarriageEntity;
import com.monpai.sailboatmod.route.water.PortAsyncRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.ChestBoat;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Main-thread-only capture. Workers receive only immutable geometry. No getChunk/load/generation.
 * Capture is sliced through the shared 2ms/tick mailbox; at most 32 columns per mailbox step.
 */
public final class LoadedBoatCollisionField {
    public static final int RADIUS=24;
    private static final double PARKED_HORIZONTAL_SPEED_SQUARED=1.0E-6;
    private LoadedBoatCollisionField() { }

    /** A temporary pause never makes a creature or an unknown vehicle into fixed route geometry.
     * This only selects planner input; vanilla collision and pushing remain authoritative.
     */
    private static boolean staticDetourObstacle(Entity entity) {
        if(entity.isPassenger()||entity.isVehicle()
                ||entity.getDeltaMovement().horizontalDistanceSqr()>PARKED_HORIZONTAL_SPEED_SQUARED)return false;
        if(entity instanceof SailboatEntity vessel)
            return !vessel.isAutopilotActive()&&vessel.getEngineGear()==SailboatEntity.EngineGear.STOP;
        if(entity instanceof CarriageEntity carriage)
            return !carriage.isAutopilotActive()&&carriage.getEngineGear()==SailboatEntity.EngineGear.STOP;
        // Unknown boat subclasses may drive themselves even while momentarily stationary.
        return entity.getClass()==Boat.class||entity.getClass()==ChestBoat.class
                ||entity instanceof ArmorStand||entity instanceof HangingEntity||entity instanceof EndCrystal;
    }
    public static final class Probe {
        private final ServerLevel level;
        private final CollisionContext context;
        private final BoatCollisionField.Hull hull;
        private final Map<Long,BoatCollisionField.Cell> cache=new HashMap<>();
        private final LoadedView view;
        private final List<AABB> entities;
        private final int capacity;
        public Probe(ServerLevel level,SailboatEntity boat,double interfaceY,int capacity) {
            if(!level.getServer().isSameThread())throw new IllegalStateException("Live collision read off server thread");
            this.level=level;this.capacity=capacity;this.context=CollisionContext.of(boat);
            this.hull=BoatCollisionField.Hull.at(boat.getBoundingBox(),interfaceY);
            this.view=new LoadedView(level);
            // Only fixed decorations and genuinely parked known vehicles are static detour inputs.
            // Creatures/traffic retain vanilla physical collision without repeatedly rewriting a route.
            var nearby=level.getEntities(boat,boat.getBoundingBox().inflate(RADIUS+8),
                    e->e.isAlive()&&!e.isPassengerOfSameVehicle(boat)&&boat.canCollideWith(e)&&staticDetourObstacle(e));
            this.entities=nearby.stream().limit(256).map(Entity::getBoundingBox).toList();
            // Too many physical obstacles: do not silently omit the remainder and say CLEAR.
            if(nearby.size()>256)throw new IllegalStateException("Local vessel density exceeds collision snapshot budget");
        }
        public BoatCollisionField field(){return new BoatCollisionField(hull,this::column,entities);}
        BoatCollisionField.Cell column(int x,int z) {
            long key=BoatCollisionField.key(x,z);var old=cache.get(key);if(old!=null)return old;
            if(cache.size()>=capacity)return BoatCollisionField.Cell.unknown();
            var chunk=level.getChunkSource().getChunkNow(x>>4,z>>4);
            if(chunk==null)return BoatCollisionField.Cell.unknown(); // Never cache an unloaded chunk as land.
            view.missing=false;
            boolean water=chunk.getBlockState(new BlockPos(x,hull.waterY(),z)).getFluidState().is(FluidTags.WATER);
            var shapes=new ArrayList<AABB>();
            for(int y=(int)Math.floor(hull.minY())-1;y<=(int)Math.floor(hull.maxY());y++) {
                BlockPos at=new BlockPos(x,y,z);
                BlockState state=chunk.getBlockState(at);
                if(state.isAir())continue;
                for(AABB box:state.getCollisionShape(view,at,context).toAabbs()) {
                    AABB absolute=box.move(x,y,z);
                    if(absolute.maxY>hull.minY()&&absolute.minY<hull.maxY())shapes.add(absolute);
                }
            }
            var cell=new BoatCollisionField.Cell(!view.missing,water,shapes);
            if(cell.known())cache.put(key,cell);
            return cell;
        }
    }
    public static CompletableFuture<BoatCollisionField> capture(ServerLevel level,SailboatEntity boat,double interfaceY,
                                                                BooleanSupplier cancelled) {
        var future=new CompletableFuture<BoatCollisionField>();
        // Must be called on the server thread. Store no mutable world references in the output.
        final Probe probe;
        try {probe=new Probe(level,boat,interfaceY,10_000);}catch(RuntimeException error){return CompletableFuture.failedFuture(error);}
        int centerX=(int)Math.floor(boat.getX()),centerZ=(int)Math.floor(boat.getZ()),side=2*RADIUS+7;
        int minX=centerX-RADIUS-3,minZ=centerZ-RADIUS-3;
        long created=level.getGameTime();
        class Cursor {
            int i;
            final Map<Long,BoatCollisionField.Cell> columns=new HashMap<>();
            void queue() {
                if(future.isDone())return;
                PortAsyncRuntime.onMain(level.getServer(),()->{
                    if(future.isDone())return null;
                    if(cancelled.getAsBoolean()||boat.isRemoved()||boat.level()!=level){future.cancel(false);return null;}
                    if(level.getGameTime()-created>200){future.completeExceptionally(new IllegalStateException("Local snapshot expired"));return null;}
                    long endTime=System.nanoTime()+500_000L;int end=Math.min(side*side,i+32);
                    do {
                        int x=minX+i%side,z=minZ+i/side;
                        columns.put(BoatCollisionField.key(x,z),probe.column(x,z));i++;
                    } while(i<end&&System.nanoTime()<endTime);
                    if(i==side*side){
                        Map<Long,BoatCollisionField.Cell> immutable=Map.copyOf(columns);
                        future.complete(new BoatCollisionField(probe.hull,(x,z)->immutable.getOrDefault(BoatCollisionField.key(x,z),BoatCollisionField.Cell.unknown()),probe.entities));
                    }
                    return null;
                }).whenComplete((v,error)->{if(error!=null)future.completeExceptionally(error);else if(!future.isDone())queue();});
            }
        }
        new Cursor().queue();return future;
    }
    /** Collision shape callbacks can query neighbours; missing neighbours must not load them. */
    private static final class LoadedView implements BlockGetter {
        final ServerLevel level;boolean missing;
        LoadedView(ServerLevel level){this.level=level;}
        public BlockState getBlockState(BlockPos pos){
            var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            if(chunk==null){missing=true;return Blocks.BARRIER.defaultBlockState();}
            return chunk.getBlockState(pos);
        }
        public FluidState getFluidState(BlockPos pos){return getBlockState(pos).getFluidState();}
        public BlockEntity getBlockEntity(BlockPos pos){return null;}
        public int getHeight(){return level.getHeight();}
        public int getMinBuildHeight(){return level.getMinBuildHeight();}
    }
}
