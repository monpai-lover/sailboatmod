package com.monpai.sailboatmod.transport.control;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Conservative admission for automatically selected physical storage. */
public final class CargoStorageRecognition {
    public static final int MAX_BINDINGS = 32;

    private CargoStorageRecognition() { }

    public static boolean standaloneStorage(@Nullable BlockEntity target) {
        if (!(target instanceof RandomizableContainerBlockEntity container) || container.getLootTable() != null)
            return false;
        var state = target.getBlockState();
        Class<?> type = target.getClass();
        if (type == BarrelBlockEntity.class) return state.is(Blocks.BARREL);
        if (type == ShulkerBoxBlockEntity.class) return state.getBlock().getClass() == ShulkerBoxBlock.class;
        return ((type == ChestBlockEntity.class && state.is(Blocks.CHEST))
                || (type == TrappedChestBlockEntity.class && state.is(Blocks.TRAPPED_CHEST)))
                && state.getValue(ChestBlock.TYPE) == ChestType.SINGLE;
    }

    public static boolean localHandler(BlockEntity target, @Nullable IItemHandler handler) {
        if (handler == null || !standaloneStorage(target)) return false;
        if (target instanceof ShulkerBoxBlockEntity shulker) {
            // The native sided wrapper compares its backing inventory and requested side.
            return new SidedInvWrapper(shulker, null).equals(handler);
        }
        return handler.getClass() == InvWrapper.class && ((InvWrapper) handler).getInv() == target;
    }

    public static boolean emptyForDiscovery(BlockEntity target, @Nullable IItemHandler handler) {
        return localHandler(target, handler) && ((RandomizableContainerBlockEntity) target).isEmpty()
                && !target.collectComponents().has(DataComponents.LOCK);
    }

    /** Empty Optional means there is no room to bind every candidate without guessing. */
    public static Optional<List<BlockPos>> additions(BlockPos origin, List<BlockPos> positions,
            List<BlockPos> selected, Set<BlockPos> excluded) {
        var offsets = positions.stream().flatMap(pos -> VehicleRegistrationSetup.relativeOffset(origin, pos).stream())
                .filter(offset -> !BlockPos.ZERO.equals(offset) && !selected.contains(offset) && !excluded.contains(offset))
                .distinct().sorted().toList();
        return selected.size() + offsets.size() <= MAX_BINDINGS ? Optional.of(offsets) : Optional.empty();
    }
}
