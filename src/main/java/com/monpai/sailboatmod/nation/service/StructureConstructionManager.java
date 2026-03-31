package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;

public final class StructureConstructionManager {

    public enum StructureType {
        VICTORIAN_BANK("victorian_bank", "item.sailboatmod.structure.victorian_bank", 19, 10, 18),
        VICTORIAN_TOWN_HALL("victorian_town_hall", "item.sailboatmod.structure.victorian_town_hall", 21, 12, 21),
        NATION_CAPITOL("nation_capitol", "item.sailboatmod.structure.nation_capitol", 25, 14, 25),
        OPEN_AIR_MARKETPLACE("open_air_marketplace", "item.sailboatmod.structure.open_air_marketplace", 17, 9, 15),
        WATERFRONT_DOCK("waterfront_dock", "item.sailboatmod.structure.waterfront_dock", 16, 8, 12);

        private final String nbtName;
        private final String translationKey;
        private final int w, h, d;

        StructureType(String nbtName, String translationKey, int w, int h, int d) {
            this.nbtName = nbtName;
            this.translationKey = translationKey;
            this.w = w;
            this.h = h;
            this.d = d;
        }

        public String nbtName() { return nbtName; }
        public String translationKey() { return translationKey; }
        public int w() { return w; }
        public int h() { return h; }
        public int d() { return d; }

        public static final List<StructureType> ALL = List.of(values());
    }

    private static final int BUILD_DURATION_TICKS = 200; // ~10 seconds

    private StructureConstructionManager() {}

    public static boolean placeStructureAnimated(ServerLevel level, BlockPos origin, ServerPlayer player, StructureType type, int rotation) {
        StructureTemplate template = loadTemplate(level, type.nbtName());
        if (template == null) return false;

        net.minecraft.world.level.block.Rotation mcRotation = switch (rotation % 4) {
            case 1 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
            case 2 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
            case 3 -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
            default -> net.minecraft.world.level.block.Rotation.NONE;
        };
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(mcRotation);

        // Collect blocks by Y layer for animated placement
        net.minecraft.core.Vec3i size = template.getSize();
        java.util.List<java.util.List<BlockPos>> layers = new java.util.ArrayList<>();
        // Place immediately but track for animation effect - use a simpler approach:
        // Place foundation instantly, then animate upper layers
        template.placeInWorld(level, origin, origin, settings, level.getRandom(), Block.UPDATE_ALL);

        placeCoreBlock(level, origin, type);
        fixCoreOwnership(level, origin, template, player, type);

        NationSavedData data = NationSavedData.get(level);
        NationMemberRecord member = data.getMember(player.getUUID());
        if (member != null) {
            TownRecord town = TownService.getTownForMember(data, member);
            String structureId = java.util.UUID.randomUUID().toString().substring(0, 8);
            com.monpai.sailboatmod.nation.model.PlacedStructureRecord record = new com.monpai.sailboatmod.nation.model.PlacedStructureRecord(
                    structureId, member.nationId(), town == null ? "" : town.townId(),
                    type.nbtName(), level.dimension().location().toString(),
                    origin.asLong(), type.w(), type.h(), type.d(), System.currentTimeMillis());
            data.putPlacedStructure(record);
            generateRoadToNearest(level, data, record);
        }

        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.structure.placed", Component.translatable(type.translationKey())));
        return true;
    }

    // Keep old method for backward compat
    public static boolean placeStructure(ServerLevel level, BlockPos origin, ServerPlayer player, StructureType type) {
        return placeStructureAnimated(level, origin, player, type, 0);
    }

    public static boolean demolishStructure(ServerLevel level, BlockPos pos, ServerPlayer player) {
        NationSavedData data = NationSavedData.get(level);
        com.monpai.sailboatmod.nation.model.PlacedStructureRecord target = findStructureAt(data, level, pos);
        if (target == null) return false;

        // Clear blocks in the structure area
        BlockPos origin = target.origin();
        for (int y = 0; y < target.sizeH(); y++) {
            for (int z = 0; z < target.sizeD(); z++) {
                for (int x = 0; x < target.sizeW(); x++) {
                    level.setBlock(origin.offset(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }

        // Remove roads connected to this structure
        removeRoadsForStructure(level, data, target);
        data.removePlacedStructure(target.structureId());
        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.structure.demolished"));
        return true;
    }

    public static boolean relocateStructure(ServerLevel level, BlockPos oldPos, BlockPos newOrigin, ServerPlayer player) {
        NationSavedData data = NationSavedData.get(level);
        com.monpai.sailboatmod.nation.model.PlacedStructureRecord target = findStructureAt(data, level, oldPos);
        if (target == null) return false;

        StructureType type = StructureType.ALL.stream().filter(t -> t.nbtName().equals(target.structureType())).findFirst().orElse(null);
        if (type == null) return false;

        // Demolish old
        BlockPos origin = target.origin();
        for (int y = 0; y < target.sizeH(); y++) {
            for (int z = 0; z < target.sizeD(); z++) {
                for (int x = 0; x < target.sizeW(); x++) {
                    level.setBlock(origin.offset(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        removeRoadsForStructure(level, data, target);
        data.removePlacedStructure(target.structureId());

        // Place at new location
        return placeStructure(level, newOrigin, player, type);
    }

    private static com.monpai.sailboatmod.nation.model.PlacedStructureRecord findStructureAt(NationSavedData data, ServerLevel level, BlockPos pos) {
        String dim = level.dimension().location().toString();
        for (com.monpai.sailboatmod.nation.model.PlacedStructureRecord s : data.getPlacedStructures()) {
            if (!dim.equals(s.dimensionId())) continue;
            BlockPos o = s.origin();
            if (pos.getX() >= o.getX() && pos.getX() < o.getX() + s.sizeW()
                    && pos.getY() >= o.getY() && pos.getY() < o.getY() + s.sizeH()
                    && pos.getZ() >= o.getZ() && pos.getZ() < o.getZ() + s.sizeD()) {
                return s;
            }
        }
        return null;
    }

    private static void placeCoreBlock(ServerLevel level, BlockPos origin, StructureType type) {
        BlockPos center = new BlockPos(origin.getX() + type.w() / 2, origin.getY() + 1, origin.getZ() + type.d() / 2);
        switch (type) {
            case VICTORIAN_BANK -> level.setBlock(center, ModBlocks.BANK_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
            case VICTORIAN_TOWN_HALL -> level.setBlock(center, ModBlocks.TOWN_CORE_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
            case NATION_CAPITOL -> level.setBlock(center, ModBlocks.NATION_CORE_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
            case OPEN_AIR_MARKETPLACE -> level.setBlock(center, ModBlocks.MARKET_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
            case WATERFRONT_DOCK -> level.setBlock(center, ModBlocks.DOCK_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static void fixCoreOwnership(ServerLevel level, BlockPos origin, StructureTemplate template, ServerPlayer player, StructureType type) {
        net.minecraft.core.Vec3i size = template.getSize();
        NationSavedData data = NationSavedData.get(level);
        NationMemberRecord member = data.getMember(player.getUUID());
        if (member == null) return;

        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(ModBlocks.TOWN_CORE_BLOCK.get())) {
                        TownRecord town = TownService.getTownForMember(data, member);
                        if (town != null && !town.hasCore()) {
                            TownService.placeCoreAt(data, level, town, pos);
                        }
                    } else if (state.is(ModBlocks.NATION_CORE_BLOCK.get())) {
                        NationRecord nation = data.getNation(member.nationId());
                        if (nation != null && !nation.hasCore()) {
                            NationClaimService.placeCore(player, pos);
                        }
                    } else if (state.is(ModBlocks.MARKET_BLOCK.get()) || state.is(ModBlocks.DOCK_BLOCK.get())) {
                        // Market and dock blocks get their town association via chunk claim
                        // Ensure the chunk is claimed for the player's town
                        net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(pos);
                        if (data.getClaim(level, cp) == null) {
                            TownRecord town = TownService.getTownForMember(data, member);
                            if (town != null) {
                                data.putClaim(new com.monpai.sailboatmod.nation.model.NationClaimRecord(
                                        level.dimension().location().toString(), cp.x, cp.z,
                                        town.nationId(), town.townId(),
                                        com.monpai.sailboatmod.nation.model.NationClaimAccessLevel.MEMBER.id(),
                                        com.monpai.sailboatmod.nation.model.NationClaimAccessLevel.MEMBER.id(),
                                        com.monpai.sailboatmod.nation.model.NationClaimAccessLevel.MEMBER.id(),
                                        com.monpai.sailboatmod.nation.model.NationClaimAccessLevel.MEMBER.id(),
                                        com.monpai.sailboatmod.nation.model.NationClaimAccessLevel.MEMBER.id(),
                                        com.monpai.sailboatmod.nation.model.NationClaimAccessLevel.MEMBER.id(),
                                        com.monpai.sailboatmod.nation.model.NationClaimAccessLevel.MEMBER.id(),
                                        System.currentTimeMillis()));
                            }
                        }
                    }
                }
            }
        }
    }

    private static void generateRoadToNearest(ServerLevel level, NationSavedData data, com.monpai.sailboatmod.nation.model.PlacedStructureRecord placed) {
        BlockPos center = placed.center();
        String dim = placed.dimensionId();
        com.monpai.sailboatmod.nation.model.PlacedStructureRecord nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (com.monpai.sailboatmod.nation.model.PlacedStructureRecord s : data.getPlacedStructures()) {
            if (s.structureId().equals(placed.structureId())) continue;
            if (!dim.equals(s.dimensionId())) continue;
            if (!placed.townId().equals(s.townId()) && !placed.nationId().equals(s.nationId())) continue;
            double dist = center.distSqr(s.center());
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = s;
            }
        }
        if (nearest == null || nearestDist > 10000) return; // max 100 blocks
        buildRoad(level, center, nearest.center());
    }

    private static void buildRoad(ServerLevel level, BlockPos from, BlockPos to) {
        BlockState roadBlock = net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB.defaultBlockState();
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) return;
        for (int i = 0; i <= steps; i++) {
            int x = from.getX() + Math.round((float) dx * i / steps);
            int z = from.getZ() + Math.round((float) dz * i / steps);
            // Find surface Y
            BlockPos surface = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, new BlockPos(x, 0, z)).below();
            BlockState existing = level.getBlockState(surface);
            if (existing.isAir() || existing.liquid()) surface = surface.below();
            // Only place road on natural ground, not inside structures
            BlockState below = level.getBlockState(surface);
            if (!below.isAir() && !below.liquid()) {
                BlockPos roadPos = surface.above();
                BlockState atRoad = level.getBlockState(roadPos);
                if (atRoad.isAir() || atRoad.liquid()) {
                    level.setBlock(roadPos, roadBlock, Block.UPDATE_ALL);
                    // Place 3-wide road
                    if (Math.abs(dx) >= Math.abs(dz)) {
                        tryPlaceRoad(level, roadPos.north(), roadBlock);
                        tryPlaceRoad(level, roadPos.south(), roadBlock);
                    } else {
                        tryPlaceRoad(level, roadPos.east(), roadBlock);
                        tryPlaceRoad(level, roadPos.west(), roadBlock);
                    }
                }
            }
        }
    }

    private static void tryPlaceRoad(ServerLevel level, BlockPos pos, BlockState roadBlock) {
        BlockState at = level.getBlockState(pos);
        if (at.isAir() || at.liquid()) {
            BlockState below = level.getBlockState(pos.below());
            if (!below.isAir() && !below.liquid()) {
                level.setBlock(pos, roadBlock, Block.UPDATE_ALL);
            }
        }
    }

    private static void removeRoadsForStructure(ServerLevel level, NationSavedData data, com.monpai.sailboatmod.nation.model.PlacedStructureRecord removed) {
        BlockPos center = removed.center();
        String dim = removed.dimensionId();
        BlockState roadBlock = net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB.defaultBlockState();
        for (com.monpai.sailboatmod.nation.model.PlacedStructureRecord s : data.getPlacedStructures()) {
            if (s.structureId().equals(removed.structureId())) continue;
            if (!dim.equals(s.dimensionId())) continue;
            clearRoadBetween(level, center, s.center(), roadBlock);
        }
    }

    private static void clearRoadBetween(ServerLevel level, BlockPos from, BlockPos to, BlockState roadBlock) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) return;
        for (int i = 0; i <= steps; i++) {
            int x = from.getX() + Math.round((float) dx * i / steps);
            int z = from.getZ() + Math.round((float) dz * i / steps);
            for (int dy = -2; dy <= 2; dy++) {
                BlockPos surface = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, new BlockPos(x, 0, z)).above(dy);
                removeRoadAt(level, surface, roadBlock);
                if (Math.abs(dx) >= Math.abs(dz)) {
                    removeRoadAt(level, surface.north(), roadBlock);
                    removeRoadAt(level, surface.south(), roadBlock);
                } else {
                    removeRoadAt(level, surface.east(), roadBlock);
                    removeRoadAt(level, surface.west(), roadBlock);
                }
            }
        }
    }

    private static void removeRoadAt(ServerLevel level, BlockPos pos, BlockState roadBlock) {
        if (level.getBlockState(pos).is(roadBlock.getBlock())) {
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static StructureTemplate loadTemplate(ServerLevel level, String nbtName) {
        ResourceLocation id = new ResourceLocation(SailboatMod.MODID, nbtName);
        StructureTemplate template = level.getStructureManager().get(id).orElse(null);
        if (template != null) return template;
        try {
            String path = "/data/" + SailboatMod.MODID + "/structures/" + nbtName + ".nbt";
            InputStream is = StructureConstructionManager.class.getResourceAsStream(path);
            if (is == null) {
                is = Thread.currentThread().getContextClassLoader().getResourceAsStream("data/" + SailboatMod.MODID + "/structures/" + nbtName + ".nbt");
            }
            if (is == null) return null;
            CompoundTag tag = NbtIo.readCompressed(is);
            is.close();
            template = new StructureTemplate();
            template.load(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), tag);
            return template;
        } catch (Exception e) {
            return null;
        }
    }
}
