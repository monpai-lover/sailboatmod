package com.monpai.sailboatmod.road.model;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public record RoadMaterial(Block surface, Block stair, Block slab, Block fence, Block fenceGate) {
    public static final RoadMaterial STONE_BRICK = new RoadMaterial(
        Blocks.STONE_BRICKS, Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB,
        Blocks.OAK_FENCE, Blocks.OAK_FENCE_GATE
    );
    public static final RoadMaterial SANDSTONE = new RoadMaterial(
        Blocks.SANDSTONE, Blocks.SANDSTONE_STAIRS, Blocks.SANDSTONE_SLAB,
        Blocks.BIRCH_FENCE, Blocks.BIRCH_FENCE_GATE
    );
    public static final RoadMaterial COBBLESTONE = new RoadMaterial(
        Blocks.COBBLESTONE, Blocks.COBBLESTONE_STAIRS, Blocks.COBBLESTONE_SLAB,
        Blocks.SPRUCE_FENCE, Blocks.SPRUCE_FENCE_GATE
    );
    public static final RoadMaterial MOSSY_COBBLE = new RoadMaterial(
        Blocks.MOSSY_COBBLESTONE, Blocks.MOSSY_COBBLESTONE_STAIRS, Blocks.MOSSY_COBBLESTONE_SLAB,
        Blocks.DARK_OAK_FENCE, Blocks.DARK_OAK_FENCE_GATE
    );
    public static final RoadMaterial RED_SANDSTONE = new RoadMaterial(
        Blocks.RED_SANDSTONE, Blocks.RED_SANDSTONE_STAIRS, Blocks.RED_SANDSTONE_SLAB,
        Blocks.ACACIA_FENCE, Blocks.ACACIA_FENCE_GATE
    );
    public static final RoadMaterial DIRT_PATH = new RoadMaterial(
        Blocks.DIRT_PATH, Blocks.OAK_STAIRS, Blocks.OAK_SLAB,
        Blocks.OAK_FENCE, Blocks.OAK_FENCE_GATE
    );
}
