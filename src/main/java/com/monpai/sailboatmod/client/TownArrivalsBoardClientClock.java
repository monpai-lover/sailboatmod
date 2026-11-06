package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.block.entity.TownArrivalsBoardBlockEntity.ClientMoment;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** One clock for the physical client, including ticks when worlds or boards are not being rendered. */
@EventBusSubscriber(modid = SailboatMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class TownArrivalsBoardClientClock {
    private static final ClockState CLOCK = new ClockState();
    private TownArrivalsBoardClientClock() { }
    public static ClientMoment now() { return CLOCK.moment(); }
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        var client = Minecraft.getInstance();
        var world = client.level;
        CLOCK.advance(world, world == null || client.isPaused() || world.tickRateManager().isFrozen());
    }
    /** The production event's pure state; holds no boards, and only the current world identity. */
    public static final class ClockState {
        private Object currentWorld;
        private volatile ClientMoment moment = new ClientMoment(0, 0, true);
        public ClientMoment moment() { return moment; }
        public void advance(Object world, boolean frozen) {
            boolean nextFrozen = world == null || frozen;
            boolean changedTrust = world != currentWorld || nextFrozen && !moment.frozen();
            long epoch = moment.epoch();
            if (changedTrust && epoch < Long.MAX_VALUE) epoch++;
            long ticks = moment.ticks() < Long.MAX_VALUE ? moment.ticks() + 1 : Long.MAX_VALUE;
            currentWorld = world; // Replacing with the current world or null releases the previous world reference.
            moment = new ClientMoment(ticks, epoch, nextFrozen);
        }
    }
}
