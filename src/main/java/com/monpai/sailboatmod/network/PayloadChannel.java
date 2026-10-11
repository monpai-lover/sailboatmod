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
