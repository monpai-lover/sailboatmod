package com.monpai.sailboatmod.network.packet.airport;

import com.monpai.sailboatmod.block.entity.AirportBlockEntity;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.NetworkEvent;
import com.monpai.sailboatmod.transport.air.airport.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Fixed, bounded wire schema. Client data is converted to domain commands, never written into a BE. */
public record AirportActionPacket(ResourceLocation dimension, BlockPos airportPos, long revision, Action action,
        String id, String runwayId, String standId, List<Vec3> points, boolean flag,
        double yaw, double length, double width, int clearance, double speed, int holdPoint) {
    public static final int MAX_POINTS = 64;
    public enum Action { REFRESH, RENAME, UPSERT_RUNWAY, REMOVE_RUNWAY, VALIDATE_RUNWAY,
        UPSERT_STAND, REMOVE_STAND, UPSERT_TAXI_ROUTE, REMOVE_TAXI_ROUTE, APPLY_AUTO_LAYOUT }
    public AirportActionPacket {
        if (dimension == null || dimension.toString().length() > 256) throw new IllegalArgumentException("Invalid dimension");
        airportPos = Objects.requireNonNull(airportPos).immutable();
        Objects.requireNonNull(action);
        id = bounded(id); runwayId = bounded(runwayId); standId = bounded(standId);
        if (points == null || points.size() > MAX_POINTS) throw new IllegalArgumentException("Too many points");
        points = List.copyOf(points);
        for (Vec3 point : points) if (point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)
                || Math.abs(point.x) > 30_000_000 || Math.abs(point.z) > 30_000_000 || point.y < -2048 || point.y > 2047)
            throw new IllegalArgumentException("Invalid coordinate");
        if (revision < 0 || !Double.isFinite(yaw) || !Double.isFinite(length) || !Double.isFinite(width) || !Double.isFinite(speed))
            throw new IllegalArgumentException("Invalid number");
    }
    private static String bounded(String text) {
        if (text == null || text.length() > 64) throw new IllegalArgumentException("Text too long");
        return text;
    }
    public static AirportActionPacket simple(ResourceLocation dimension, BlockPos pos, long revision, Action action, String value) {
        return new AirportActionPacket(dimension, pos, revision, action, value, "", "", List.of(), false, 0, 0, 0, 0, 0, -1);
    }
    public static void encode(AirportActionPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUtf(packet.dimension.toString(),256).writeBlockPos(packet.airportPos).writeLong(packet.revision); buffer.writeEnum(packet.action);
        buffer.writeUtf(packet.id,64).writeUtf(packet.runwayId,64).writeUtf(packet.standId,64).writeVarInt(packet.points.size());
        for (Vec3 point : packet.points) buffer.writeDouble(point.x).writeDouble(point.y).writeDouble(point.z);
        buffer.writeBoolean(packet.flag).writeDouble(packet.yaw).writeDouble(packet.length).writeDouble(packet.width)
                .writeVarInt(packet.clearance).writeDouble(packet.speed).writeVarInt(packet.holdPoint);
    }
    public static AirportActionPacket decode(FriendlyByteBuf buffer) {
        ResourceLocation dimension = ResourceLocation.tryParse(buffer.readUtf(256));
        if (dimension == null) throw new IllegalArgumentException("Invalid dimension");
        BlockPos pos = buffer.readBlockPos(); long revision = buffer.readLong(); Action action = buffer.readEnum(Action.class);
        String id = buffer.readUtf(64), runway = buffer.readUtf(64), stand = buffer.readUtf(64);
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_POINTS) throw new IllegalArgumentException("Too many points");
        List<Vec3> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) points.add(new Vec3(buffer.readDouble(),buffer.readDouble(),buffer.readDouble()));
        return new AirportActionPacket(dimension,pos,revision,action,id,runway,stand,points,buffer.readBoolean(),buffer.readDouble(),buffer.readDouble(),
                buffer.readDouble(),buffer.readVarInt(),buffer.readDouble(),buffer.readVarInt());
    }
    public CompoundTag toActionTag() {
        CompoundTag result = new CompoundTag(); result.putString("Id", id);
        switch (action) {
            case REFRESH -> result.putString("Action","refresh");
            case RENAME -> { result.putString("Action","rename"); result.putString("Name",id); }
            case REMOVE_RUNWAY -> result.putString("Action","remove_facility");
            case VALIDATE_RUNWAY -> result.putString("Action","validate");
            case REMOVE_STAND -> result.putString("Action","remove_stand");
            case REMOVE_TAXI_ROUTE -> result.putString("Action","remove_taxi_route");
            case UPSERT_RUNWAY -> {
                requirePoints(2);
                if (width != Math.rint(width)) throw new IllegalArgumentException("Runway width must be whole blocks");
                result.putString("Action","upsert_facility");
                result.put("Facility",new AirportFacility(id,AirportFacility.Type.RUNWAY,wholeBlock(points.getFirst()),wholeBlock(points.getLast()),(int)width,clearance).save());
            }
            case UPSERT_STAND -> {
                requirePoints(1); result.putString("Action","upsert_stand");
                result.put("Stand",new AirportParkingStand(id,points.getFirst(),yaw,length,width,clearance,flag).save());
            }
            case UPSERT_TAXI_ROUTE -> {
                result.putString("Action","upsert_taxi_route");
                result.put("TaxiRoute",new AirportTaxiRoute(id,flag ? AirportTaxiRoute.Direction.INBOUND : AirportTaxiRoute.Direction.OUTBOUND,
                        runwayId,standId,points,width,clearance,speed,holdPoint).save());
            }
            case APPLY_AUTO_LAYOUT -> {
                requirePoints(2);
                result.putString("Action", "auto_layout");
                result.putLong("CornerA", wholeBlock(points.getFirst()).asLong());
                result.putLong("CornerB", wholeBlock(points.getLast()).asLong());
            }
        }
        return result;
    }
    private static BlockPos wholeBlock(Vec3 point) {
        if (point.x != Math.rint(point.x) || point.y != Math.rint(point.y) || point.z != Math.rint(point.z))
            throw new IllegalArgumentException("Airport coordinates must be whole blocks");
        return BlockPos.containing(point);
    }
    private void requirePoints(int expected) { if (points.size() != expected) throw new IllegalArgumentException("Wrong point count"); }
    public static void handle(AirportActionPacket packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player == null || !packet.dimension.equals(player.serverLevel().dimension().location())) return;
            if (!player.serverLevel().hasChunkAt(packet.airportPos)) return;
            if (!(player.serverLevel().getBlockEntity(packet.airportPos) instanceof AirportBlockEntity airport)) return;
            String code;
            if (packet.action == Action.REFRESH) {
                if (!AirportManagementService.canManage(player, airport)) return;
                code = "";
            } else {
                try { code = AirportManagementService.apply(player, packet.airportPos, packet.revision, packet.toActionTag()).code(); }
                catch (IllegalArgumentException exception) { code = "invalid_geometry"; }
            }
            if (AirportManagementService.canManage(player, airport))
                ModNetwork.CHANNEL.send(player, new AirportSnapshotPacket(AirportScreenData.capture(player,airport,code),packet.action != Action.REFRESH || packet.flag));
            else if (packet.action != Action.REFRESH)
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("screen.sailboatmod.airport.result."+code));
        });
        context.setPacketHandled(true);
    }
}
