package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.dock.PostStationRegistry;
import com.monpai.sailboatmod.entity.CarriageEntity;
import com.monpai.sailboatmod.entity.SailboatEntity;
import com.monpai.sailboatmod.menu.PostStationMenu;
import com.monpai.sailboatmod.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

public class PostStationBlockEntity extends DockBlockEntity {
    public PostStationBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.POST_STATION_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    protected void registerFacility(Level level, BlockPos pos) {
        PostStationRegistry.register(level, pos);
    }

    @Override
    protected void unregisterFacility(Level level, BlockPos pos) {
        PostStationRegistry.unregister(level, pos);
    }

    @Override
    protected void syncFacilityMarkers() {
    }

    @Override
    protected String defaultFacilityName() {
        return "Post Station";
    }

    @Override
    protected String defaultFacilityNameSuffix() {
        return "'s Post Station";
    }

    @Override
    protected boolean isValidFacilityZone(Level level, int minX, int maxX, int minZ, int maxZ) {
        return isZoneMostlyLand(level, getBlockPos(), minX, maxX, minZ, maxZ);
    }

    @Override
    protected boolean supportsTransportEntity(SailboatEntity entity) {
        return entity instanceof CarriageEntity;
    }

    @Override
    protected String noAssignableTransportTranslationKey() {
        return "block.sailboatmod.post_station.no_target";
    }

    @Override
    protected String noRouteTranslationKey() {
        return "block.sailboatmod.post_station.no_route";
    }

    @Override
    protected String transportNotReadyTranslationKey() {
        return "screen.sailboatmod.post_station.vehicle_not_ready";
    }

    @Override
    protected String transportCargoFullTranslationKey() {
        return "screen.sailboatmod.post_station.error.vehicle_cargo_full";
    }

    @Override
    protected String transportLoadFailedTranslationKey() {
        return "screen.sailboatmod.post_station.error.vehicle_load_failed";
    }

    @Override
    public Component getDisplayName() {
        return Component.literal(getDockName());
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new PostStationMenu(containerId, playerInventory, worldPosition);
    }

    public static boolean isInsidePostStationZone(BlockPos stationPos, Vec3 point) {
        return isInsideDockZone(stationPos, point);
    }

    public static BlockPos findPostStationZoneContains(Level level, Vec3 point) {
        if (level == null || point == null) {
            return null;
        }
        for (BlockPos stationPos : PostStationRegistry.get(level)) {
            BlockEntity blockEntity = level.getBlockEntity(stationPos);
            if (!(blockEntity instanceof PostStationBlockEntity station)) {
                continue;
            }
            if (station.isInsideDockZone(point)) {
                return stationPos.immutable();
            }
        }
        return null;
    }

    public static BlockPos findNearestRegisteredPostStation(Level level, Vec3 point, double maxDistance) {
        if (level == null || point == null || maxDistance <= 0.0D) {
            return null;
        }
        BlockPos bestPos = null;
        double bestDistanceSq = maxDistance * maxDistance;
        for (BlockPos stationPos : PostStationRegistry.get(level)) {
            BlockEntity blockEntity = level.getBlockEntity(stationPos);
            if (!(blockEntity instanceof PostStationBlockEntity station)) {
                continue;
            }
            Vec3 center = Vec3.atCenterOf(stationPos);
            double distanceSq = center.distanceToSqr(point);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestPos = station.getBlockPos().immutable();
            }
        }
        return bestPos;
    }

    public static String getPostStationDisplayName(Level level, BlockPos stationPos) {
        if (level == null || stationPos == null) {
            return "Post Station";
        }
        BlockEntity blockEntity = level.getBlockEntity(stationPos);
        if (blockEntity instanceof PostStationBlockEntity station) {
            return station.getDockName();
        }
        return "Post Station";
    }

    private static boolean isZoneMostlyLand(Level level, BlockPos origin, int minX, int maxX, int minZ, int maxZ) {
        int samples = 0;
        int solid = 0;
        int water = 0;
        for (int x = minX; x <= maxX; x += Math.max(1, (maxX - minX) / 6)) {
            for (int z = minZ; z <= maxZ; z += Math.max(1, (maxZ - minZ) / 6)) {
                samples++;
                int worldX = origin.getX() + x;
                int worldZ = origin.getZ() + z;
                BlockPos surface = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, new BlockPos(worldX, origin.getY(), worldZ)).below();
                BlockState state = level.getBlockState(surface);
                if (!level.getFluidState(surface).isEmpty() || !level.getFluidState(surface.above()).isEmpty()) {
                    water++;
                    continue;
                }
                if (!state.isAir() && state.isFaceSturdy(level, surface, Direction.UP) && Math.abs(surface.getY() - origin.getY()) <= 6) {
                    solid++;
                }
            }
        }
        return samples > 0 && solid >= Math.max(4, Mth.ceil(samples * 0.65D)) && water <= Math.max(1, samples / 5);
    }
}
