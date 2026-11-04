package com.monpai.sailboatmod.route.water.local;

import com.google.common.collect.ImmutableList;
import com.monpai.sailboatmod.entity.SailboatEntity;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real capture predicate, before any immutable planner geometry is constructed. */
class LoadedBoatEntityObstaclesTest {
    private static Unsafe unsafe;
    private static final AABB OBSTACLE = new AABB(8, 64.9, -1, 10, 66.5, 1);

    @BeforeAll
    static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        unsafe = (Unsafe) field.get(null);
    }

    @Test
    void stationaryCreatureDoesNotBecomeAStaticDetourObstacle() throws Exception {
        TestZombie creature = obstacle(TestZombie.class, Vec3.ZERO);
        assertTrue(subject(level(List.of())).canCollideWith(creature), "exercise a physically collidable creature");
        assertTrue(capture(List.of(creature)).isEmpty(), "a still creature is a mobile type, not route terrain");
    }

    @Test
    void movingCreatureDoesNotBecomeAnExpandedStaticDetourObstacle() throws Exception {
        assertTrue(capture(List.of(obstacle(TestZombie.class, new Vec3(.2, 0, 0)))).isEmpty());
    }

    @Test
    void playersItemsAndProjectilesAreNeverStaticRouteObstacles() throws Exception {
        for (Class<? extends Entity> type : List.of(TestPlayer.class, TestItem.class, TestArrow.class)) {
            Entity entity = obstacle(type, Vec3.ZERO);
            assertTrue(subject(level(List.of())).canCollideWith(entity), "exercise the type filter, not a noncollidable box");
            assertTrue(capture(List.of(entity)).isEmpty(), type.getSimpleName());
        }
    }

    @Test
    void fixedCollidableDecorationStillBlocksAndCanBeBypassed() throws Exception {
        TestCrystal decoration = obstacle(TestCrystal.class, Vec3.ZERO);
        assertTrue(subject(level(List.of())).canCollideWith(decoration));
        List<AABB> boxes = capture(List.of(decoration));
        assertEquals(1, boxes.size());
        assertEquals(BoatCollisionField.Check.BLOCKED, water(boxes).segment(p(0, 0), p(20, 0)));
        assertTrue(BoatLocalDetour.plan(water(boxes), p(0, 0), List.of(p(0, 0), p(1000, 0)), 1).successful());
    }

    @Test
    void unoccupiedInactiveParkedBoatStillParticipatesInLocalAvoidance() throws Exception {
        TestLevel level = level(List.of());
        TestBoat parked = new TestBoat(level);
        parked.setBoundingBox(OBSTACLE);
        List<AABB> boxes = capture(List.of(parked));
        assertEquals(1, boxes.size());
        assertEquals(BoatCollisionField.Check.BLOCKED, water(boxes).segment(p(0, 0), p(20, 0)));
    }

    @Test
    void movingOccupiedAutopilotOrPoweredBoatsAreNotParkedObstacles() throws Exception {
        TestLevel level = level(List.of());
        List<TestBoat> boats = new ArrayList<>();
        TestBoat moving = new TestBoat(level); moving.setDeltaMovement(.05, 0, 0); boats.add(moving);
        TestBoat occupied = new TestBoat(level);
        Field passengers = Entity.class.getDeclaredField("passengers"); passengers.setAccessible(true);
        passengers.set(occupied, ImmutableList.of(obstacle(TestZombie.class, Vec3.ZERO))); boats.add(occupied);
        TestBoat automatic = new TestBoat(level); automatic.autopilot = true; boats.add(automatic);
        TestBoat powered = new TestBoat(level); powered.gear = SailboatEntity.EngineGear.ONE_THIRD_AHEAD; boats.add(powered);
        for (TestBoat boat : boats) {
            boat.setBoundingBox(OBSTACLE);
            assertTrue(capture(List.of(boat)).isEmpty(), "mobile/active vehicles cannot be frozen into local route geometry");
        }
    }

    @Test
    void ordinaryUnoccupiedStationaryBoatIsStillAnObstacle() throws Exception {
        Boat boat = new Boat(EntityType.BOAT, level(List.of())); boat.setBoundingBox(OBSTACLE);
        assertEquals(1, capture(List.of(boat)).size());
    }

    @Test
    void excludedCreaturesDoNotExhaustTheStaticObstacleSnapshotBudget() throws Exception {
        List<Entity> creatures = new ArrayList<>();
        for (int i = 0; i < 300; i++) creatures.add(obstacle(TestZombie.class, Vec3.ZERO));
        assertTrue(capture(creatures).isEmpty());
    }

    private static List<AABB> capture(List<? extends Entity> nearby) throws Exception {
        TestLevel level = level(nearby);
        return new LoadedBoatCollisionField.Probe(level, subject(level), 65, 2048).field().entities();
    }

    private static TestBoat subject(TestLevel level) {
        TestBoat boat = new TestBoat(level);
        boat.setPos(0, 65, 0);
        return boat;
    }
    private static Vec3 p(double x, double z) { return new Vec3(x, 65, z); }
    private static BoatCollisionField water(List<AABB> boxes) {
        return new BoatCollisionField(new BoatCollisionField.Hull(1, 1, 64.78, 66.6, 64),
                (x, z) -> new BoatCollisionField.Cell(true, true, List.of()), boxes);
    }

    private static TestLevel level(List<? extends Entity> entities) throws Exception {
        TestLevel level = allocate(TestLevel.class);
        level.nearby = List.copyOf(entities);
        level.server = allocate(DedicatedServer.class);
        Field thread = MinecraftServer.class.getDeclaredField("serverThread"); thread.setAccessible(true);
        thread.set(level.server, Thread.currentThread());
        return level;
    }

    private static <T extends Entity> T obstacle(Class<T> type, Vec3 motion) throws Exception {
        T entity = allocate(type);
        Field passengers = Entity.class.getDeclaredField("passengers"); passengers.setAccessible(true);
        passengers.set(entity, ImmutableList.of());
        entity.setBoundingBox(OBSTACLE); entity.setDeltaMovement(motion); return entity;
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception { return (T) unsafe.allocateInstance(type); }

    private static final class TestLevel extends ServerLevel {
        private DedicatedServer server;
        private List<Entity> nearby;
        private TestLevel() { super(null, command -> {}, null, null, null, null, null, false, 0L, List.of(), false, null); }
        @Override public MinecraftServer getServer() { return server; }
        @Override public List<Entity> getEntities(Entity except, AABB area, Predicate<? super Entity> filter) {
            return nearby.stream().filter(entity -> entity != except && area.intersects(entity.getBoundingBox()) && filter.test(entity)).toList();
        }
    }

    private static final class TestBoat extends SailboatEntity {
        private boolean autopilot;
        private EngineGear gear = EngineGear.STOP;
        private TestBoat(Level level) { super(EntityType.BOAT, level); }
        @Override public boolean isAutopilotActive() { return autopilot; }
        @Override public EngineGear getEngineGear() { return gear; }
    }

    private static final class TestZombie extends Zombie {
        private TestZombie() { super(EntityType.ZOMBIE, null); }
        @Override public boolean isAlive() { return true; }
        @Override public boolean isPushable() { return true; }
    }
    private static final class TestPlayer extends ServerPlayer {
        private TestPlayer() { super(null, null, null, null); }
        @Override public boolean isAlive() { return true; }
        @Override public boolean isPushable() { return true; }
    }
    private static final class TestItem extends ItemEntity {
        private TestItem() { super(EntityType.ITEM, null); }
        @Override public boolean isAlive() { return true; }
        @Override public boolean isPushable() { return true; }
    }
    private static final class TestArrow extends Arrow {
        private TestArrow() { super(EntityType.ARROW, null); }
        @Override public boolean isAlive() { return true; }
        @Override public boolean isPushable() { return true; }
    }
    private static final class TestCrystal extends EndCrystal {
        private TestCrystal() { super(EntityType.END_CRYSTAL, null); }
        @Override public boolean isAlive() { return true; }
        @Override public boolean canBeCollidedWith() { return true; }
    }
}
