package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.entity.CarriageEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 raytrace 命中马车「整模型子框」(含超出实体 AABB 的车头辕杆/车尾/车篷)后,发此 C2S 包
 * 请求服务端执行 interact(上车/开存储)。客户端不直接调 interact(无 owner 权威、无法 startRiding)。
 *
 * <p>服务端校验:不信客户端命中点,只用实体距离做防作弊门控(玩家与马车距离 ≤ 触达范围+余量)。
 * 通过则调 {@link CarriageEntity#interact}(沿用现有上车/Shift 开存储/owner 校验逻辑)。
 */
public record InteractCarriagePacket(int carriageId, InteractionHand hand) {

    public static void encode(InteractCarriagePacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.carriageId);
        buffer.writeEnum(packet.hand);
    }

    public static InteractCarriagePacket decode(FriendlyByteBuf buffer) {
        return new InteractCarriagePacket(
                buffer.readVarInt(),
                buffer.readEnum(InteractionHand.class)
        );
    }

    public static void handle(InteractCarriagePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            Entity entity = sender.level().getEntity(packet.carriageId);
            if (!(entity instanceof CarriageEntity carriage) || !carriage.isAlive()) {
                return;
            }
            // 服务端距离门控:getEntityReach() 与客户端 raytrace 同源,+1.0 格留网络/插值余量。不信客户端命中点。
            double maxReach = sender.getEntityReach() + 1.0D;
            if (sender.distanceToSqr(carriage) > maxReach * maxReach) {
                return;
            }
            carriage.interact(sender, packet.hand);
        });
        context.setPacketHandled(true);
    }
}
