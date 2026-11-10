package com.monpai.sailboatmod.transport.air.airport;

import com.monpai.sailboatmod.block.entity.AirportBlockEntity;
import com.monpai.sailboatmod.nation.service.NationClaimService;
import com.monpai.sailboatmod.roadplanner.service.SharedMapTerrainService;
import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.transport.TransportRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/** Installs the bundled layout after the editor checks its claim and operational footprint. */
final class RegionalAirportPlacement {
    private RegionalAirportPlacement() {}

    static String install(ServerPlayer player, AirportBlockEntity airport, AirportEditorSession.Proposal proposal,
                          Consumer<BlockPos> issue) {
        if (!AirportManagementService.canManage(player, airport)) return "forbidden";
        if (!player.getAbilities().instabuild) return "regional_creative";
        if (airport.configurationOccupied(proposal.facilities(), proposal.ground())) return "occupied";

        var level = player.serverLevel();
        var planned = proposal.regionalBlocks();
        int floorY = airport.getBlockPos().getY() - 1;
        var previous = new LinkedHashMap<BlockPos, BlockState>();
        var aboveFloor = new ArrayList<BlockPos>();
        AABB area = new AABB(airport.getBlockPos());
        for (var entry : planned.entrySet()) {
            BlockPos pos = entry.getKey();
            if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos))
                return reject(issue, pos, "invalid_geometry");
            if (!level.hasChunkAt(pos)) return reject(issue, pos, "unloaded");
            BlockState state = level.getBlockState(pos);
            if (state.equals(entry.getValue())) continue;
            boolean floor = pos.getY() == floorY;
            if (!level.mayInteract(player, pos) || !NationClaimService.canPlace(level, player.getUUID(), pos)
                    || floor && !NationClaimService.canBreak(level, player.getUUID(), pos))
                return reject(issue, pos, "foreign_town");
            if (!state.getFluidState().isEmpty() || level.getBlockEntity(pos) != null || !floor && !state.isAir())
                return reject(issue, pos, "obstructed");
            if (floor && (!state.isFaceSturdy(level, pos, Direction.UP) || !state.isCollisionShapeFullBlock(level, pos)))
                return reject(issue, pos, "unsupported");
            if (floor && !AirportPrefab.replaceableSurface(state)) return reject(issue, pos, "prefab_surface");
            previous.put(pos, state);
            if (!floor) {
                aboveFloor.add(pos);
                area = area.minmax(new AABB(pos));
            }
        }

        var occupied = new ArrayList<AABB>();
        level.getEntities((Entity) null, area, value -> value.isAlive() && !value.isSpectator())
                .forEach(entity -> occupied.add(entity.getBoundingBox()));
        TransportRegistry.nearby(level, area).stream().filter(LogisticsCarrier::isTransportAlive)
                .forEach(carrier -> occupied.add(carrier.transportBounds()));
        for (AABB bounds : occupied) {
            for (BlockPos pos : aboveFloor) {
                if (bounds.intersects(new AABB(pos)) && !planned.get(pos).getCollisionShape(level, pos).isEmpty())
                    return reject(issue, pos, "occupied");
            }
        }

        var writes = new HashSet<BlockPos>();
        boolean placed = AirportPrefab.placeAtomically(List.copyOf(previous.keySet()), pos -> {
            if (level.getBlockState(pos).equals(previous.get(pos))) {
                writes.add(pos);
                if (level.setBlock(pos, planned.get(pos), AirportPrefab.PLACEMENT_FLAGS)) return true;
            }
            issue.accept(pos);
            return false;
        }, pos -> {
            if (writes.contains(pos) && level.getBlockState(pos).equals(planned.get(pos)))
                level.setBlock(pos, previous.get(pos), AirportPrefab.PLACEMENT_FLAGS);
        }, () -> {
            for (var entry : planned.entrySet()) {
                if (!level.getBlockState(entry.getKey()).equals(entry.getValue())) {
                    issue.accept(entry.getKey());
                    return false;
                }
            }
            return airport.replaceGroundConfiguration(proposal.ground().revision() - 1, proposal.facilities(), proposal.ground());
        });
        if (!placed) return "prefab_failed";

        var changedChunks = new LinkedHashSet<ChunkPos>();
        for (BlockPos pos : previous.keySet()) changedChunks.add(new ChunkPos(pos));
        for (ChunkPos chunk : changedChunks) SharedMapTerrainService.global().changedChunk(level, chunk.x, chunk.z);
        return "ok";
    }

    private static String reject(Consumer<BlockPos> issue, BlockPos pos, String result) {
        issue.accept(pos);
        return result;
    }
}
