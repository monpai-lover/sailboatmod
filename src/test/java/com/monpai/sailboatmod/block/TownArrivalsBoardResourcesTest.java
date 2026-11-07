package com.monpai.sailboatmod.block;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.LevelReader;
import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import sun.misc.Unsafe;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TownArrivalsBoardResourcesTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    static JsonObject resource(String path) throws Exception { return JsonParser.parseString(Files.readString(Path.of("src/main/resources", path))).getAsJsonObject(); }
    static TownArrivalsBoardBlock boardFixture() throws Exception {
        // Vanilla bootstrap freezes intrusive holders; these stateless methods only need a horizontal vanilla state.
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return (TownArrivalsBoardBlock) ((Unsafe) field.get(null)).allocateInstance(TownArrivalsBoardBlock.class);
    }
    @Test void thinWallShapesFollowFacingAndStayInsideOneBlock() throws Exception {
        var board = boardFixture();
        for (Direction face : Direction.Plane.HORIZONTAL) {
            var state = Blocks.LADDER.defaultBlockState().setValue(TownArrivalsBoardBlock.FACING, face);
            var box = board.getShape(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty()).bounds();
            assertTrue(box.minX >= 0 && box.minY >= 0 && box.minZ >= 0 && box.maxX <= 1 && box.maxY <= 1 && box.maxZ <= 1);
            assertEquals(3 / 16.0, face.getAxis() == Direction.Axis.X ? box.getXsize() : box.getZsize(), 1e-8);
            if (face == Direction.SOUTH) assertEquals(0, box.minZ);
            if (face == Direction.NORTH) assertEquals(1, box.maxZ);
            if (face == Direction.EAST) assertEquals(0, box.minX);
            if (face == Direction.WEST) assertEquals(1, box.maxX);
            assertEquals(face.getClockWise(), board.rotate(state, Rotation.CLOCKWISE_90).getValue(TownArrivalsBoardBlock.FACING));
            assertEquals(face, board.mirror(board.mirror(state, Mirror.FRONT_BACK), Mirror.FRONT_BACK).getValue(TownArrivalsBoardBlock.FACING));
        }
    }
    @Test void boardDropsWhenItsActualSupportingFaceDisappears() {
        assertEquals(Direction.NORTH, TownArrivalsBoardBlock.supportFace(Direction.SOUTH));
        assertTrue(TownArrivalsBoardBlock.supportLost(Direction.NORTH, Direction.SOUTH, false));
        assertFalse(TownArrivalsBoardBlock.supportLost(Direction.EAST, Direction.SOUTH, false));
        assertFalse(TownArrivalsBoardBlock.supportLost(Direction.NORTH, Direction.SOUTH, true));
    }
    @Test void survivalChecksTheRealBlockBehindTheFacing() throws Exception {
        var board = boardFixture();
        BlockPos pos = new BlockPos(40, 80, -12);
        for (Direction face : Direction.Plane.HORIZONTAL) {
            var state = Blocks.LADDER.defaultBlockState().setValue(TownArrivalsBoardBlock.FACING, face);
            BlockPos behind = pos.relative(face.getOpposite());
            LevelReader solid = (LevelReader) Proxy.newProxyInstance(LevelReader.class.getClassLoader(), new Class<?>[]{LevelReader.class}, (proxy, method, args) -> {
                if (method.getName().equals("getBlockState")) { assertEquals(behind, args[0]); return Blocks.STONE.defaultBlockState(); }
                throw new AssertionError("unexpected world query: " + method.getName());
            });
            LevelReader air = (LevelReader) Proxy.newProxyInstance(LevelReader.class.getClassLoader(), new Class<?>[]{LevelReader.class}, (proxy, method, args) -> {
                if (method.getName().equals("getBlockState")) { assertEquals(behind, args[0]); return Blocks.AIR.defaultBlockState(); }
                throw new AssertionError("unexpected world query: " + method.getName());
            });
            assertTrue(board.canSurvive(state, solid, pos)); assertFalse(board.canSurvive(state, air, pos));
        }
    }
    @Test void nativeBlockstateModelsRecipeAndLootAreExact() throws Exception {
        var states = resource("assets/sailboatmod/blockstates/town_arrivals_board.json").getAsJsonObject("variants");
        assertEquals(Set.of("facing=north", "facing=south", "facing=east", "facing=west"), states.keySet());
        for (var value : states.asMap().values()) assertEquals("sailboatmod:block/town_arrivals_board", value.getAsJsonObject().get("model").getAsString());
        assertEquals(180, states.getAsJsonObject("facing=north").get("y").getAsInt());
        assertEquals(270, states.getAsJsonObject("facing=east").get("y").getAsInt());
        assertEquals(90, states.getAsJsonObject("facing=west").get("y").getAsInt());
        var model = resource("assets/sailboatmod/models/block/town_arrivals_board.json");
        assertTrue(model.getAsJsonArray("elements").size() >= 2);
        assertEquals("minecraft:block/black_concrete", model.getAsJsonObject("textures").get("panel").getAsString());
        assertEquals("sailboatmod:block/town_arrivals_board", resource("assets/sailboatmod/models/item/town_arrivals_board.json").get("parent").getAsString());
        var recipe = resource("data/sailboatmod/recipe/town_arrivals_board.json");
        assertEquals("minecraft:crafting_shaped", recipe.get("type").getAsString());
        assertEquals("[\"PPP\",\"GRG\",\"PPP\"]", recipe.getAsJsonArray("pattern").toString());
        assertEquals("minecraft:oak_planks", recipe.getAsJsonObject("key").getAsJsonObject("P").get("item").getAsString());
        assertEquals("minecraft:glass", recipe.getAsJsonObject("key").getAsJsonObject("G").get("item").getAsString());
        assertEquals("minecraft:redstone", recipe.getAsJsonObject("key").getAsJsonObject("R").get("item").getAsString());
        assertEquals("sailboatmod:town_arrivals_board", recipe.getAsJsonObject("result").get("id").getAsString()); assertEquals(1, recipe.getAsJsonObject("result").get("count").getAsInt());
        var pools = resource("data/sailboatmod/loot_table/blocks/town_arrivals_board.json").getAsJsonArray("pools");
        assertEquals(1, pools.size()); var entries = pools.get(0).getAsJsonObject().getAsJsonArray("entries"); assertEquals(1, entries.size());
        assertEquals("sailboatmod:town_arrivals_board", entries.get(0).getAsJsonObject().get("name").getAsString());
        assertFalse(pools.toString().contains("copy_components"));
    }
    @Test void serverClassesHaveNoClientDependencyAndClientRegistrationIsDistRestricted() throws Exception {
        for (String path : new String[]{"block/TownArrivalsBoardBlock.java", "block/entity/TownArrivalsBoardBlockEntity.java"}) {
            String source = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod", path));
            assertFalse(source.contains("net.minecraft.client")); assertFalse(source.contains("MarketSavedData"));
        }
        String events = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/client/ClientEvents.java"));
        assertTrue(events.contains("value = Dist.CLIENT")); assertTrue(events.contains("TownArrivalsBoardRenderer::new"));
        String be = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/block/entity/TownArrivalsBoardBlockEntity.java"));
        assertTrue(be.contains("TownService.getTownAt(server, worldPosition)"));
        assertTrue(be.contains("TownService.canManageTown(actor, NationSavedData.get(server), current)"));
        assertTrue(be.contains("TownVoyageService.existing(server.getServer())"));
        assertTrue(be.contains("server.getServer().overworld().getGameTime()"));
        assertFalse(be.contains("getEntities")); assertFalse(be.contains("getChunk("));
    }
}
