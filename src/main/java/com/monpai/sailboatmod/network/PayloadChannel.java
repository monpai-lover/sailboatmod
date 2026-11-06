package com.monpai.sailboatmod.network;

import com.monpai.sailboatmod.SailboatMod;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.registration.HandlerThread;

/** NeoForge payload registrations backed by the mod's existing packet codecs. */
public final class PayloadChannel {
    private final Map<Class<?>, Registration<?>> registrations = new LinkedHashMap<>();
    private final String version;

    PayloadChannel(String version) {
        this.version = version;
    }

    public <T> void registerMessage(int ignoredId, Class<T> packetClass,
            BiConsumer<T, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, T> decoder,
            BiConsumer<T, Supplier<NetworkEvent.Context>> handler, Optional<NetworkDirection> direction) {
        var type = new CustomPacketPayload.Type<Envelope<T>>(ResourceLocation.fromNamespaceAndPath(
                SailboatMod.MODID, packetClass.getSimpleName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT)));
        if (registrations.putIfAbsent(packetClass, new Registration<>(type, encoder, decoder, handler,
                direction.orElseThrow())) != null) {
            throw new IllegalStateException("Duplicate payload registration: " + packetClass.getName());
        }
    }

    void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(version);
        registrations.values().forEach(registration -> registration.register(registrar));
    }

    public void sendToServer(Object packet) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(wrap(packet, NetworkDirection.PLAY_TO_SERVER));
    }

    public void send(ServerPlayer target, Object packet) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(target, wrap(packet, NetworkDirection.PLAY_TO_CLIENT));
    }

    /** Completion releases a bounded map-flight slot on BOTH success and failure, without blocking. */
    public void send(ServerPlayer target, Object packet, Runnable completion) {
        var payload = new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                wrap(packet, NetworkDirection.PLAY_TO_CLIENT));
        var connection = target.connection.getConnection();
        var channel = connection.channel();
        if (!connection.isConnected() || channel == null) { completion.run(); return; }
        // Connection can close between admission and send. Release on closure as well; remove the listener
        // after a normal write so successful sends do not accumulate connection-lifetime callbacks.
        io.netty.util.concurrent.GenericFutureListener<io.netty.util.concurrent.Future<? super Void>> onClose =
                ignored -> completion.run();
        Runnable finished = () -> { channel.closeFuture().removeListener(onClose); completion.run(); };
        channel.closeFuture().addListener(onClose);
        try {
            connection.send(payload, new net.minecraft.network.PacketSendListener() {
                @Override public void onSuccess() { finished.run(); }
                @Override public net.minecraft.network.protocol.Packet<?> onFailure() {
                    finished.run(); return null;
                }
            });
        } catch (RuntimeException failed) { finished.run(); throw failed; }
    }

    private CustomPacketPayload wrap(Object packet, NetworkDirection direction) {
        Registration<?> registration = registrations.get(packet.getClass());
        if (registration == null) throw new IllegalArgumentException("Unregistered payload: " + packet.getClass().getName());
        if (registration.direction != direction) throw new IllegalArgumentException("Wrong payload direction: " + packet.getClass().getName());
        return registration.wrap(packet);
    }

    private record Envelope<T>(CustomPacketPayload.Type<Envelope<T>> type, T packet) implements CustomPacketPayload {}

    private record Registration<T>(CustomPacketPayload.Type<Envelope<T>> type,
            BiConsumer<T, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, T> decoder,
            BiConsumer<T, Supplier<NetworkEvent.Context>> handler, NetworkDirection direction) {
        void register(PayloadRegistrar registrar) {
            // Only map requests have network-safe admission handlers; other payloads retain main-thread dispatch.
            String path = type.id().getPath();
            if (path.equals("shared_map_terrain_request_packet") || path.equals("road_planner_map_preload_request_packet")) {
                registrar = registrar.executesOn(HandlerThread.NETWORK);
            }
            StreamCodec<RegistryFriendlyByteBuf, Envelope<T>> codec = new StreamCodec<>() {
                @Override public Envelope<T> decode(RegistryFriendlyByteBuf buffer) {
                    return new Envelope<>(type, decoder.apply(buffer));
                }
                @Override public void encode(RegistryFriendlyByteBuf buffer, Envelope<T> payload) {
                    encoder.accept(payload.packet(), buffer);
                }
            };
            if (direction == NetworkDirection.PLAY_TO_SERVER) registrar.playToServer(type, codec, this::handle);
            else registrar.playToClient(type, codec, this::handle);
        }

        private void handle(Envelope<T> payload, IPayloadContext context) {
            var adapted = new NetworkEvent.Context(context);
            handler.accept(payload.packet(), () -> adapted);
        }

        @SuppressWarnings("unchecked")
        private Envelope<T> wrap(Object packet) {
            return new Envelope<>(type, (T) packet);
        }
    }
}
