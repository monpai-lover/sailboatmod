package com.monpai.sailboatmod.network.packet.rail;

import com.monpai.sailboatmod.block.entity.RailFreightTerminalBlockEntity;
import com.monpai.sailboatmod.network.NetworkEvent;
import com.monpai.sailboatmod.transport.rail.RailRoute;
import com.monpai.sailboatmod.transport.rail.RailStationRef;
import com.monpai.sailboatmod.transport.rail.ui.RailTerminalStopovers;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** No client inventory, train state or arbitrary NBT is accepted. */
public record RailTerminalActionPacket(ResourceLocation dimension, BlockPos terminalPos, UUID terminalId,
        long revision, Action action, BlockPos stationPos, UUID trainId, String name, RailTerminalStopovers.Plan stopovers) {
    public enum Action {
        REFRESH, BIND, REGISTER, UNREGISTER, ENABLE, DISABLE, RENAME, SET_STOPOVERS, SET_TIMED_STOPOVERS;

        private boolean editsStopovers() {
            return this == SET_STOPOVERS || this == SET_TIMED_STOPOVERS;
        }
    }
    private static final UUID NONE = new UUID(0, 0);
    public RailTerminalActionPacket {
        if (!com.monpai.sailboatmod.dock.FacilityNames.validOverride(name)) throw new IllegalArgumentException("Invalid terminal name");
        name = name.trim();
        Objects.requireNonNull(dimension); Objects.requireNonNull(terminalId); Objects.requireNonNull(action); Objects.requireNonNull(trainId);
        terminalPos = Objects.requireNonNull(terminalPos).immutable(); stationPos = Objects.requireNonNull(stationPos).immutable();
        if (revision < 0 || Math.abs((long) terminalPos.getX()) > 30_000_000 || Math.abs((long) terminalPos.getZ()) > 30_000_000
                || Math.abs((long) stationPos.getX()) > 30_000_000 || Math.abs((long) stationPos.getZ()) > 30_000_000)
            throw new IllegalArgumentException("Invalid terminal action");
        if (action.editsStopovers() != (stopovers != null)
                || stopovers != null && (!sameDimension(dimension, stopovers.destination())
                || stopovers.stops().stream().anyMatch(choice -> !sameDimension(dimension, choice))
                || action == Action.SET_STOPOVERS && stopovers.dwellSeconds().stream()
                        .anyMatch(seconds -> seconds != RailRoute.DEFAULT_STOPOVER_SECONDS)))
            throw new IllegalArgumentException("Invalid rail stopover action");
    }
    public RailTerminalActionPacket(ResourceLocation dimension, BlockPos terminalPos, UUID terminalId,
            long revision, Action action, BlockPos stationPos, UUID trainId, String name) {
        this(dimension, terminalPos, terminalId, revision, action, stationPos, trainId, name, null);
    }
    public RailTerminalActionPacket(ResourceLocation dimension, BlockPos terminalPos, UUID terminalId,
            long revision, Action action, BlockPos stationPos, UUID trainId) {
        this(dimension, terminalPos, terminalId, revision, action, stationPos, trainId, "");
    }
    public static RailTerminalActionPacket setStopovers(ResourceLocation dimension, BlockPos terminalPos, UUID terminalId,
            long revision, RailTerminalStopovers.Choice destination, List<RailTerminalStopovers.Choice> stops) {
        return new RailTerminalActionPacket(dimension, terminalPos, terminalId, revision, Action.SET_STOPOVERS,
                BlockPos.ZERO, NONE, "", new RailTerminalStopovers.Plan(destination, stops));
    }
    public static RailTerminalActionPacket setTimedStopovers(ResourceLocation dimension, BlockPos terminalPos, UUID terminalId,
            long revision, RailTerminalStopovers.Choice destination, List<RailTerminalStopovers.Choice> stops,
            List<Integer> dwellSeconds) {
        return new RailTerminalActionPacket(dimension, terminalPos, terminalId, revision, Action.SET_TIMED_STOPOVERS,
                BlockPos.ZERO, NONE, "", new RailTerminalStopovers.Plan(destination, stops, dwellSeconds));
    }
    public static void encode(RailTerminalActionPacket p, FriendlyByteBuf b) {
        b.writeResourceLocation(p.dimension).writeBlockPos(p.terminalPos).writeUUID(p.terminalId).writeLong(p.revision);
        b.writeEnum(p.action); b.writeBlockPos(p.stationPos).writeUUID(p.trainId);
        if (p.action == Action.RENAME) b.writeUtf(p.name, 64);
        if (p.stopovers != null) {
            writeChoice(b, p.stopovers.destination());
            b.writeVarInt(p.stopovers.stops().size());
            for (int i = 0; i < p.stopovers.stops().size(); i++) {
                writeChoice(b, p.stopovers.stops().get(i));
                if (p.action == Action.SET_TIMED_STOPOVERS) b.writeVarInt(p.stopovers.dwellSeconds().get(i));
            }
        }
    }
    public static RailTerminalActionPacket decode(FriendlyByteBuf b) {
        var dimension = b.readResourceLocation(); var pos = b.readBlockPos(); var id = b.readUUID(); long revision = b.readLong();
        var action = b.readEnum(Action.class); var station = b.readBlockPos(); var train = b.readUUID();
        if (action.editsStopovers()) {
            var destination = readChoice(b, dimension);
            int count = b.readVarInt();
            if (count < 0 || count > RailRoute.MAX_STOPOVERS) throw new IllegalArgumentException("Invalid rail stopover count");
            var stops = new ArrayList<RailTerminalStopovers.Choice>(count);
            var dwell = new ArrayList<Integer>(count);
            for (int i = 0; i < count; i++) {
                stops.add(readChoice(b, dimension));
                if (action == Action.SET_TIMED_STOPOVERS) dwell.add(b.readVarInt());
            }
            return action == Action.SET_TIMED_STOPOVERS
                    ? setTimedStopovers(dimension, pos, id, revision, destination, stops, dwell)
                    : setStopovers(dimension, pos, id, revision, destination, stops);
        }
        return new RailTerminalActionPacket(dimension, pos, id, revision, action, station, train, action == Action.RENAME ? b.readUtf(64) : "");
    }
    private static boolean sameDimension(ResourceLocation dimension, RailTerminalStopovers.Choice choice) {
        return dimension.toString().equals(choice.station().dimensionId());
    }
    private static void writeChoice(FriendlyByteBuf b, RailTerminalStopovers.Choice choice) {
        b.writeBlockPos(choice.terminalPos()).writeUUID(choice.terminalId())
                .writeBlockPos(choice.station().stationPos()).writeUUID(choice.station().stationId());
    }
    private static RailTerminalStopovers.Choice readChoice(FriendlyByteBuf b, ResourceLocation dimension) {
        BlockPos pos = b.readBlockPos();
        UUID id = b.readUUID();
        return new RailTerminalStopovers.Choice(pos, id,
                new RailStationRef(dimension.toString(), b.readBlockPos(), b.readUUID()));
    }
    public static void handle(RailTerminalActionPacket p, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player == null || !player.serverLevel().dimension().location().equals(p.dimension)
                    || !player.serverLevel().hasChunkAt(p.terminalPos)
                    || !(player.serverLevel().getBlockEntity(p.terminalPos) instanceof RailFreightTerminalBlockEntity terminal)
                    || !terminal.canManage(player)) return;
            String result = terminal.apply(player, p);
            terminal.reply(player, result, false, p.action);
        });
        context.setPacketHandled(true);
    }
}
