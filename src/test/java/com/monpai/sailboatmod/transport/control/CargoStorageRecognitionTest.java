package com.monpai.sailboatmod.transport.control;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.LockCode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static com.monpai.sailboatmod.transport.control.CargoStorageRecognition.additions;
import static com.monpai.sailboatmod.transport.control.CargoStorageRecognition.emptyForDiscovery;
import static org.junit.jupiter.api.Assertions.*;

class CargoStorageRecognitionTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void acceptsOnlyStandaloneVanillaHandlersOwnedByTheActualContainer() {
        var chest = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        var barrel = new BarrelBlockEntity(BlockPos.ZERO.east(), Blocks.BARREL.defaultBlockState());
        var shulker = new ShulkerBoxBlockEntity(BlockPos.ZERO.west(), Blocks.SHULKER_BOX.defaultBlockState());
        var joinedChest = new ChestBlockEntity(BlockPos.ZERO,
                Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT));
        var furnace = new FurnaceBlockEntity(BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());

        assertTrue(emptyForDiscovery(chest, new InvWrapper(chest)));
        assertTrue(emptyForDiscovery(barrel, new InvWrapper(barrel)));
        assertTrue(emptyForDiscovery(shulker, new SidedInvWrapper(shulker, null)));
        assertFalse(emptyForDiscovery(chest, new InvWrapper(barrel)));
        assertFalse(emptyForDiscovery(joinedChest, new InvWrapper(joinedChest)));
        assertFalse(emptyForDiscovery(furnace, new SidedInvWrapper(furnace, null)));
        assertFalse(emptyForDiscovery(barrel, null));

        barrel.setItem(0, new ItemStack(Items.COAL));
        assertFalse(emptyForDiscovery(barrel, new InvWrapper(barrel)));
        assertTrue(barrel.getItem(0).is(Items.COAL));

        chest.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON);
        assertFalse(emptyForDiscovery(chest, new InvWrapper(chest)));
        assertEquals(BuiltInLootTables.SIMPLE_DUNGEON, chest.getLootTable());

        shulker.applyComponents(DataComponentMap.EMPTY,
                DataComponentPatch.builder().set(DataComponents.LOCK, new LockCode("Private")).build());
        assertFalse(emptyForDiscovery(shulker, new SidedInvWrapper(shulker, null)));
    }

    @Test void recheckAddsOnlyNewUnexcludedOffsetsAndDoesNotChooseAmongOverflow() {
        BlockPos controller = new BlockPos(200, 60, -100);
        List<BlockPos> candidates = List.of(controller, controller.east(2), controller.east(3),
                controller.east(3), controller.east(65), controller.east(1));
        assertEquals(Optional.of(List.of(BlockPos.ZERO.east(3))), additions(controller, candidates,
                List.of(BlockPos.ZERO.east(1)), Set.of(BlockPos.ZERO.east(2))));

        List<BlockPos> existing = IntStream.rangeClosed(1, 31).mapToObj(BlockPos.ZERO::west).toList();
        assertEquals(Optional.empty(), additions(controller, candidates, existing, Set.of()));
        assertEquals(Optional.of(List.of(BlockPos.ZERO.east(3))), additions(controller, candidates, existing,
                Set.of(BlockPos.ZERO.east(1), BlockPos.ZERO.east(2))));
    }
}
