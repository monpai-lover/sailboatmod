package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.block.entity.AirportBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Immutable metadata; unopened ports are read from existing NBT, never generated just for a menu. */
public record PortDockSnapshot(BlockPos pos, String name, String owner, String town, String nation,
                               PortBerthValidator.Zone zone) {
    public static PortDockSnapshot of(DockBlockEntity d) {
        return new PortDockSnapshot(d.getBlockPos().immutable(), d.getDockName(), d.getOwnerName(),
                d.getTownId(), d.getNationId(), PortCandidateListing.zone(d));
    }
    public WaterRoutePermissionService.DockAccess access() {
        return new WaterRoutePermissionService.DockAccess(town, nation);
    }
    public static DockBlockEntity loaded(ServerLevel level, BlockPos pos) {
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk != null && chunk.getBlockEntity(pos) instanceof DockBlockEntity dock ? dock : null;
    }
    public static boolean isPort(DockBlockEntity d) {
        return d != null && !(d instanceof PostStationBlockEntity) && !(d instanceof AirportBlockEntity);
    }
    /** Worker only. Main-thread callbacks are small memory-only reads; all waits occur on this worker. */
    public static Optional<PortDockSnapshot> read(ServerLevel level, BlockPos pos) {
        if (level.getServer().isSameThread()) throw new IllegalStateException("Port metadata IO on tick thread");
        try {
            // A loaded chunk is authoritative even when its dock was removed since the index was written.
            var live = PortAsyncRuntime.onMain(level.getServer(), () -> {
                var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
                if (chunk == null) return null;
                DockBlockEntity dock = loaded(level, pos);
                return isPort(dock) ? Optional.of(of(dock)) : Optional.<PortDockSnapshot>empty();
            }).get(1, TimeUnit.SECONDS);
            if (live != null) return live;
            var read = level.getChunkSource().chunkMap.read(new ChunkPos(pos)).get(1500, TimeUnit.MILLISECONDS);
            if (read.isEmpty()) return Optional.empty();
            // IOWorker can expose pending-write tags. Copy only the requested block-entity on the tick thread.
            CompoundTag tag = PortAsyncRuntime.onMain(level.getServer(), () -> {
                for (Tag value : read.get().getList("block_entities", Tag.TAG_COMPOUND)) {
                    CompoundTag be = (CompoundTag) value;
                    if (be.getInt("x") == pos.getX() && be.getInt("y") == pos.getY() && be.getInt("z") == pos.getZ())
                        return be.copy();
                }
                return null;
            }).get(1, TimeUnit.SECONDS);
            return fromNbt(pos, tag);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return Optional.empty();
        } catch (Exception unavailable) { return Optional.empty(); }
    }
    static Optional<PortDockSnapshot> fromNbt(BlockPos pos, CompoundTag tag) {
        if (tag == null || !"sailboatmod:dock".equals(tag.getString("id"))) return Optional.empty();
        var zone = new PortBerthValidator.Zone(pos.getX(), pos.getY(), pos.getZ(),
                tag.contains("ZoneMinX") ? tag.getInt("ZoneMinX") : -DockBlockEntity.ZONE_HALF_X,
                tag.contains("ZoneMaxX") ? tag.getInt("ZoneMaxX") : DockBlockEntity.ZONE_HALF_X,
                tag.contains("ZoneMinZ") ? tag.getInt("ZoneMinZ") : -DockBlockEntity.ZONE_HALF_Z,
                tag.contains("ZoneMaxZ") ? tag.getInt("ZoneMaxZ") : DockBlockEntity.ZONE_HALF_Z);
        return Optional.of(new PortDockSnapshot(pos.immutable(), tag.getString("DockName"), tag.getString("OwnerName"),
                tag.getString("TownId"), tag.getString("NationId"), zone));
    }
}
