package com.monpai.sailboatmod.network.packet.rail;

import com.monpai.sailboatmod.block.entity.RailFreightTerminalBlockEntity;
import com.monpai.sailboatmod.network.NetworkEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** No client inventory, train state or arbitrary NBT is accepted. */
public record RailTerminalActionPacket(ResourceLocation dimension, BlockPos terminalPos, UUID terminalId,
        long revision, Action action, BlockPos stationPos, UUID trainId, String name) {
    public enum Action { REFRESH, BIND, REGISTER, UNREGISTER, ENABLE, DISABLE, RENAME }
    public RailTerminalActionPacket {
        if (!com.monpai.sailboatmod.dock.FacilityNames.validOverride(name)) throw new IllegalArgumentException("Invalid terminal name");
        name = name.trim();
        Objects.requireNonNull(dimension); Objects.requireNonNull(terminalId); Objects.requireNonNull(action); Objects.requireNonNull(trainId);
        terminalPos = Objects.requireNonNull(terminalPos).immutable(); stationPos = Objects.requireNonNull(stationPos).immutable();
        if (revision < 0 || Math.abs((long) terminalPos.getX()) > 30_000_000 || Math.abs((long) terminalPos.getZ()) > 30_000_000
                || Math.abs((long) stationPos.getX()) > 30_000_000 || Math.abs((long) stationPos.getZ()) > 30_000_000)
            throw new IllegalArgumentException("Invalid terminal action");
    }
    public RailTerminalActionPacket(ResourceLocation dimension, BlockPos terminalPos, UUID terminalId,
            long revision, Action action, BlockPos stationPos, UUID trainId) {
        this(dimension, terminalPos, terminalId, revision, action, stationPos, trainId, "");
    }
    public static void encode(RailTerminalActionPacket p, FriendlyByteBuf b) {
        b.writeResourceLocation(p.dimension).writeBlockPos(p.terminalPos).writeUUID(p.terminalId).writeLong(p.revision);
        b.writeEnum(p.action); b.writeBlockPos(p.stationPos).writeUUID(p.trainId);
        if (p.action == Action.RENAME) b.writeUtf(p.name, 64);
    }
    public static RailTerminalActionPacket decode(FriendlyByteBuf b) {
        var dimension = b.readResourceLocation(); var pos = b.readBlockPos(); var id = b.readUUID(); long revision = b.readLong();
        var action = b.readEnum(Action.class); var station = b.readBlockPos(); var train = b.readUUID();
        return new RailTerminalActionPacket(dimension, pos, id, revision, action, station, train, action == Action.RENAME ? b.readUtf(64) : "");
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
            terminal.reply(player, result, false);
        });
        context.setPacketHandled(true);
    }
}
