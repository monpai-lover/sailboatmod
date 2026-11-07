package com.monpai.sailboatmod.transport.rail;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.Nullable;

/** The optional provider resolves actual trains. Core market code never links Create classes. */
public interface RailFreightNative {
    Optional<RailStationRef> station(ServerLevel level, BlockPos pos);
    List<RailStationRef> nearbyStations(ServerLevel level, BlockPos center, int radius);
    boolean connected(ServerLevel level, RailStationRef source, RailStationRef target);
    Optional<TrainView> atStation(ServerLevel level, RailStationRef station);
    Optional<TrainView> train(ServerLevel level, UUID trainId);
    Optional<Path> plan(ServerLevel level, UUID trainId, UUID owner, RailStationRef source, RailStationRef target,
                        List<RailStationRef> stopovers);
    @Nullable List<IItemHandlerModifiable> cargo(ServerLevel level, UUID trainId, UUID owner);
    boolean openStorage(ServerPlayer player, UUID trainId);
    @Nullable CompoundTag start(ServerLevel level, UUID trainId, UUID owner, RailStationRef source,
                                RailStationRef target, List<RailStationRef> stopovers, List<Integer> stopoverSeconds, UUID jobId,
                                @Nullable CompoundTag completedSchedule);
    boolean clearOwnedSchedule(ServerLevel level, UUID trainId, UUID owner, RailStationRef station,
                               CompoundTag ownedSchedule, boolean requireCompleted);
    boolean matchesOwnedSchedule(ServerLevel level, UUID trainId, List<RailStationRef> visits, CompoundTag ownedSchedule);
    void markDirty();

    record Path(int distanceMeters, String sourceName, String targetName) {}

    record TrainView(UUID id, UUID owner, String name, @Nullable RailStationRef station,
                     @Nullable RailStationRef destination, @Nullable Vec3 position, @Nullable AABB bounds,
                     double speed, double remaining, double legDistance, boolean usable, boolean canDriveBothWays, boolean manual,
                     boolean paused, boolean completed, boolean hasSchedule, @Nullable CompoundTag managedSchedule,
                     int cargoSlots, int cargoUsed, int fuelSafeSlots) {
        public boolean parkedAt(RailStationRef expected) {
            return usable && !manual && expected.equals(station) && destination == null
                    && Double.isFinite(speed) && Math.abs(speed) < 1e-6;
        }
    }
}
