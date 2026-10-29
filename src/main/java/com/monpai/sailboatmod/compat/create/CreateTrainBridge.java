package com.monpai.sailboatmod.compat.create;

import com.monpai.sailboatmod.market.logistics.rail.RailTrainSnapshot;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Navigation;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.station.GlobalStation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The only class that touches Create (6.0.x). Loaded only after {@link CreateRailLogistics} has checked that Create
 * is installed. Reads the server's railway manager on the main thread.
 */
final class CreateTrainBridge {
    private CreateTrainBridge() {
    }

    /** Overworld trains, the dimension the web map shows. */
    static List<RailTrainSnapshot> overworldTrains() {
        List<RailTrainSnapshot> out = new ArrayList<>();
        for (Train train : Create.RAILWAYS.trains.values()) {
            if (train == null || train.invalid) {
                continue;
            }
            Optional<BlockPos> position = train.getPositionInDimension(Level.OVERWORLD);
            if (position.isEmpty()) {
                continue;
            }
            Navigation navigation = train.navigation;
            GlobalStation destination = navigation == null ? null : navigation.destination;
            GlobalStation station = train.getCurrentStation();
            boolean scheduled = destination != null
                    || (train.runtime != null && train.runtime.schedule != null && !train.runtime.paused);
            double destinationX = Double.NaN;
            double destinationZ = Double.NaN;
            if (destination != null && Level.OVERWORLD.equals(destination.getBlockEntityDimension())
                    && destination.getBlockEntityPos() != null) {
                destinationX = destination.getBlockEntityPos().getX() + 0.5D;
                destinationZ = destination.getBlockEntityPos().getZ() + 0.5D;
            }
            BlockPos pos = position.get();
            out.add(new RailTrainSnapshot(
                    train.id,
                    train.owner,
                    train.name == null ? "" : train.name.getString(),
                    pos.getX() + 0.5D,
                    pos.getZ() + 0.5D,
                    train.speed,
                    train.derailed,
                    station == null ? null : station.name,
                    scheduled,
                    destination == null ? null : destination.name,
                    destinationX,
                    destinationZ,
                    navigation == null ? 0.0D : navigation.distanceToDestination,
                    navigation == null ? 0.0D : navigation.distanceStartedAt,
                    navigation == null ? 0 : navigation.ticksWaitingForSignal));
        }
        return out;
    }
}
