package com.monpai.sailboatmod.compat.create;

import com.monpai.sailboatmod.market.logistics.rail.RailTrainSnapshot;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Navigation;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.station.GlobalStation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The only class that touches Create (6.0.x). Loaded only after {@link CreateRailLogistics} has checked that Create
 * is installed. Reads the server's railway manager on the main thread.
 */
final class CreateTrainBridge {
    // Catnip's Pair is supplied by Create at runtime, but is not on this optional compile-only classpath.
    // Read only the public field's presence; never link its external value type or inspect its contents.
    private static final Field WAITING_FOR_SIGNAL = signalField();

    private CreateTrainBridge() {
    }

    /** One native registry pass: unchanged overworld web projection and bounded all-dimension board observations. */
    static CreateRailLogistics.Readout overworldTrains() {
        List<RailTrainSnapshot> out = new ArrayList<>();
        CreateRailLogistics.BoardReadBuilder board = new CreateRailLogistics.BoardReadBuilder(CreateRailLogistics.trackedBoardTrainIds());
        for (Train train : Create.RAILWAYS.trains.values()) {
            if (train == null) continue;
            // Native absence is provable even when the bounded board projection is partial.
            board.observedNativeId(train.id);
            Navigation navigation = train.navigation;
            GlobalStation destination = navigation == null ? null : navigation.destination;
            GlobalStation station = train.getCurrentStation();
            if (board.accepts(train.id)) {
                board.add(new CreateRailLogistics.BoardSample(train.id, train.name == null ? "" : train.name.getString(),
                        station(station), station(destination), navigation == null ? Double.NaN : navigation.distanceToDestination,
                        train.speed, train.runtime != null && train.runtime.paused, train.derailed,
                        waitingForSignal(navigation), train.carriageWaitingForChunks != -1,
                        train.graph != null, destination != null, train.invalid));
            }
            if (train.invalid) continue;
            Optional<BlockPos> position = train.getPositionInDimension(Level.OVERWORLD);
            if (position.isEmpty()) {
                continue;
            }
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
        return board.finish(out);
    }

    private static CreateRailLogistics.Station station(GlobalStation station) {
        if (station == null) return null;
        var dimension = station.getBlockEntityDimension();
        BlockPos pos = station.getBlockEntityPos();
        // Missing native terminal metadata stays unbound; the train's present dimension is not a substitute.
        return new CreateRailLogistics.Station(station.getId(), dimension == null || pos == null ? "" : dimension.location().toString(),
                dimension == null || pos == null ? 0 : pos.asLong(), station.name);
    }

    private static Field signalField() {
        try { return Navigation.class.getField("waitingForSignal"); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
    }

    static boolean waitingForSignal(Navigation navigation) {
        if (navigation == null) return false;
        if (WAITING_FOR_SIGNAL == null) return true;
        try { return WAITING_FOR_SIGNAL.get(navigation) != null; }
        // Missing optional observation removes board ETA trust without interrupting the existing web projection.
        catch (IllegalAccessException | RuntimeException | LinkageError unavailable) { return true; }
    }
}
