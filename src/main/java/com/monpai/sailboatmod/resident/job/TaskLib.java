package com.monpai.sailboatmod.resident.job;

import com.monpai.sailboatmod.resident.model.Profession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Static registry of all tasks, indexed by taskId.
 */
public final class TaskLib {
    private static final Map<String, ResidentTask> TASKS = new LinkedHashMap<>();

    // Farmer tasks
    public static final ResidentTask HARVEST_WHEAT = register(new ResidentTask(
            "harvest_wheat", "Harvest Wheat", 1200,
            List.of(), List.of(new ItemStack(Items.WHEAT, 3), new ItemStack(Items.WHEAT_SEEDS, 1))));
    public static final ResidentTask HARVEST_CARROT = register(new ResidentTask(
            "harvest_carrot", "Harvest Carrots", 1200,
            List.of(), List.of(new ItemStack(Items.CARROT, 4))));
    public static final ResidentTask HARVEST_POTATO = register(new ResidentTask(
            "harvest_potato", "Harvest Potatoes", 1200,
            List.of(), List.of(new ItemStack(Items.POTATO, 4))));

    // Miner tasks
    public static final ResidentTask MINE_STONE = register(new ResidentTask(
            "mine_stone", "Mine Stone", 1600,
            List.of(), List.of(new ItemStack(Items.COBBLESTONE, 8))));
    public static final ResidentTask MINE_IRON = register(new ResidentTask(
            "mine_iron", "Mine Iron Ore", 2400,
            List.of(), List.of(new ItemStack(Items.RAW_IRON, 2))));
    public static final ResidentTask MINE_COAL = register(new ResidentTask(
            "mine_coal", "Mine Coal", 1600,
            List.of(), List.of(new ItemStack(Items.COAL, 4))));

    // Lumberjack tasks
    public static final ResidentTask CHOP_OAK = register(new ResidentTask(
            "chop_oak", "Chop Oak", 1400,
            List.of(), List.of(new ItemStack(Items.OAK_LOG, 4))));
    public static final ResidentTask CHOP_SPRUCE = register(new ResidentTask(
            "chop_spruce", "Chop Spruce", 1400,
            List.of(), List.of(new ItemStack(Items.SPRUCE_LOG, 4))));

    // Fisherman tasks
    public static final ResidentTask CATCH_COD = register(new ResidentTask(
            "catch_cod", "Catch Cod", 1800,
            List.of(), List.of(new ItemStack(Items.COD, 3))));
    public static final ResidentTask CATCH_SALMON = register(new ResidentTask(
            "catch_salmon", "Catch Salmon", 1800,
            List.of(), List.of(new ItemStack(Items.SALMON, 2))));

    // Blacksmith tasks (consume raw materials)
    public static final ResidentTask SMELT_IRON = register(new ResidentTask(
            "smelt_iron", "Smelt Iron", 2000,
            List.of(new ItemStack(Items.RAW_IRON, 2), new ItemStack(Items.COAL, 1)),
            List.of(new ItemStack(Items.IRON_INGOT, 2))));
    public static final ResidentTask FORGE_SWORD = register(new ResidentTask(
            "forge_sword", "Forge Iron Sword", 3000,
            List.of(new ItemStack(Items.IRON_INGOT, 2), new ItemStack(Items.STICK, 1)),
            List.of(new ItemStack(Items.IRON_SWORD, 1))));
    public static final ResidentTask FORGE_SHIELD = register(new ResidentTask(
            "forge_shield", "Forge Shield", 3000,
            List.of(new ItemStack(Items.IRON_INGOT, 1), new ItemStack(Items.OAK_PLANKS, 6)),
            List.of(new ItemStack(Items.SHIELD, 1))));

    // Baker tasks (consume raw materials)
    public static final ResidentTask BAKE_BREAD = register(new ResidentTask(
            "bake_bread", "Bake Bread", 800,
            List.of(new ItemStack(Items.WHEAT, 3)),
            List.of(new ItemStack(Items.BREAD, 1))));

    private static ResidentTask register(ResidentTask task) {
        TASKS.put(task.taskId(), task);
        return task;
    }

    public static ResidentTask get(String taskId) {
        return TASKS.get(taskId);
    }

    public static Map<String, ResidentTask> all() {
        return Map.copyOf(TASKS);
    }

    private TaskLib() {}
}
