package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.PlacedStructureRecord;
import com.monpai.sailboatmod.nation.service.StructureConstructionManager.StructureType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class StructurePlacementValidationService {

    public record ValidationResult(boolean valid, Component message) {
        public static ValidationResult ok() {
            return new ValidationResult(true, Component.empty());
        }

        public static ValidationResult invalid(Component message) {
            return new ValidationResult(false, message == null ? Component.empty() : message);
        }
    }

    private StructurePlacementValidationService() {
    }

    public static ValidationResult validate(Level level, StructureType type, BlockPos origin, int rotation) {
        if (level == null || type == null || origin == null) {
            return ValidationResult.invalid(Component.translatable("command.sailboatmod.nation.bank_constructor.failed"));
        }

        BlueprintService.BlueprintPlacement placement = BlueprintService.preparePlacement(
                level.holderLookup(Registries.BLOCK),
                type.nbtName(),
                origin,
                rotation
        );
        if (placement == null) {
            return ValidationResult.invalid(Component.translatable("command.sailboatmod.nation.bank_constructor.failed"));
        }
        return validate(level, type, origin, placement);
    }

    public static ValidationResult validate(Level level,
                                            StructureType type,
                                            BlockPos origin,
                                            BlueprintService.BlueprintPlacement placement) {
        if (level == null || type == null || origin == null || placement == null) {
            return ValidationResult.invalid(Component.translatable("command.sailboatmod.nation.bank_constructor.failed"));
        }
        if (!isWithinWorldBorder(level, placement.bounds())) {
            return ValidationResult.invalid(Component.translatable("command.sailboatmod.nation.structure.outside_border"));
        }
        if (type != StructureType.VICTORIAN_TOWN_HALL && TownService.getTownAt(level, origin) == null) {
            return ValidationResult.invalid(Component.translatable("command.sailboatmod.nation.town.facility.missing_town"));
        }
        PlacedStructureRecord overlap = firstOverlappingStructure(level, placement.bounds());
        if (overlap != null) {
            return ValidationResult.invalid(Component.translatable(
                    "command.sailboatmod.nation.structure.overlap_existing",
                    overlap.structureType(),
                    overlap.origin().getX(),
                    overlap.origin().getY(),
                    overlap.origin().getZ()
            ));
        }

        BlockPos blockedPos = firstBlockedPos(level, placement);
        if (blockedPos != null) {
            BlockState blockedState = level.getBlockState(blockedPos);
            return ValidationResult.invalid(Component.translatable(
                    "command.sailboatmod.nation.structure.blocked_detail",
                    blockedState.getBlock().getName(),
                    blockedPos.getX(),
                    blockedPos.getY(),
                    blockedPos.getZ()
            ));
        }
        return ValidationResult.ok();
    }

    private static boolean isWithinWorldBorder(Level level, BlueprintService.PlacementBounds bounds) {
        return level.getWorldBorder().isWithinBounds(bounds.min())
                && level.getWorldBorder().isWithinBounds(bounds.max())
                && level.isInWorldBounds(bounds.min())
                && level.isInWorldBounds(bounds.max());
    }

    private static BlockPos firstBlockedPos(Level level, BlueprintService.BlueprintPlacement placement) {
        for (var info : placement.blocks()) {
            BlockState currentState = level.getBlockState(info.pos());
            if (currentState.equals(info.state())) {
                continue;
            }
            if (!canAutoClear(level, info.pos(), currentState)) {
                return info.pos();
            }
        }
        return null;
    }

    static boolean canAutoClear(Level level, BlockPos pos, BlockState state) {
        if (state == null || state.isAir() || state.canBeReplaced() || state.liquid()) {
            return true;
        }
        if (state.hasBlockEntity()
                || state.is(Blocks.BEDROCK)
                || state.is(com.monpai.sailboatmod.registry.ModBlocks.TOWN_CORE_BLOCK.get())
                || state.is(com.monpai.sailboatmod.registry.ModBlocks.NATION_CORE_BLOCK.get())
                || state.is(com.monpai.sailboatmod.registry.ModBlocks.BANK_BLOCK.get())
                || state.is(com.monpai.sailboatmod.registry.ModBlocks.MARKET_BLOCK.get())
                || state.is(com.monpai.sailboatmod.registry.ModBlocks.DOCK_BLOCK.get())
                || state.is(com.monpai.sailboatmod.registry.ModBlocks.COTTAGE_BLOCK.get())
                || state.is(com.monpai.sailboatmod.registry.ModBlocks.BAR_BLOCK.get())
                || state.is(com.monpai.sailboatmod.registry.ModBlocks.SCHOOL_BLOCK.get())) {
            return false;
        }
        return state.is(BlockTags.DIRT)
                || state.is(BlockTags.SAND)
                || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.LOGS)
                || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.CROPS)
                || state.is(Blocks.GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.SNOW)
                || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.ICE)
                || state.is(Blocks.PACKED_ICE)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.MUD)
                || state.is(Blocks.MUDDY_MANGROVE_ROOTS);
    }

    private static PlacedStructureRecord firstOverlappingStructure(Level level, BlueprintService.PlacementBounds bounds) {
        if (level == null || bounds == null) {
            return null;
        }
        NationSavedData data = NationSavedData.get(level);
        String dimensionId = level.dimension().location().toString();
        BlockPos min = bounds.min();
        BlockPos max = bounds.max();
        for (PlacedStructureRecord structure : data.getPlacedStructures()) {
            if (!dimensionId.equals(structure.dimensionId())) {
                continue;
            }
            BlockPos otherMin = structure.origin();
            BlockPos otherMax = new BlockPos(
                    otherMin.getX() + structure.sizeW() - 1,
                    otherMin.getY() + structure.sizeH() - 1,
                    otherMin.getZ() + structure.sizeD() - 1
            );
            if (intersects(min, max, otherMin, otherMax)) {
                return structure;
            }
        }
        return null;
    }

    private static boolean intersects(BlockPos minA, BlockPos maxA, BlockPos minB, BlockPos maxB) {
        return minA.getX() <= maxB.getX() && maxA.getX() >= minB.getX()
                && minA.getY() <= maxB.getY() && maxA.getY() >= minB.getY()
                && minA.getZ() <= maxB.getZ() && maxA.getZ() >= minB.getZ();
    }
}
