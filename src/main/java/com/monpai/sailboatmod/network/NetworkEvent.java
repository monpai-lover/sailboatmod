package com.monpai.sailboatmod.network;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Adapts existing packet handlers to NeoForge's payload context. */
public final class NetworkEvent {
    private NetworkEvent() {}

    public static final class Context {
        private final IPayloadContext context;

        Context(IPayloadContext context) {
            this.context = context;
        }

        public ServerPlayer getSender() {
            return context.player() instanceof ServerPlayer player ? player : null;
        }

        public CompletableFuture<Void> enqueueWork(Runnable work) {
            return context.enqueueWork(work);
        }

        public void setPacketHandled(boolean handled) {
            // A registered NeoForge payload is handled when its handler returns.
        }
    }
}
