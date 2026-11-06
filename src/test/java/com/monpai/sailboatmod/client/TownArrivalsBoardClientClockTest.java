package com.monpai.sailboatmod.client;

import static com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import com.monpai.sailboatmod.block.entity.TownArrivalsBoardBlockEntity;
import com.monpai.sailboatmod.block.entity.TownArrivalsBoardBlockEntity.ClientMoment;
import com.monpai.sailboatmod.transport.LogisticsCarrier;
import java.lang.reflect.Field;
import java.util.List;
import java.util.OptionalLong;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.TickRateManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class TownArrivalsBoardClientClockTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @AfterEach void safeClock() { TownArrivalsBoardBlockEntity.installClientClock(() -> new ClientMoment(0, 0, true)); }
    private record World(long constantGameTime) { }
    private static CompoundTag livePacket() {
        var row = new PublicRow("opaque", LogisticsCarrier.Kind.AIR, "飞机", "城际线", "甲机场", "乙机场", Relation.INBOUND,
                "CRUISE", State.MOVING, OptionalLong.of(1300));
        return TownArrivalsBoardBlockEntity.encodePublic(new TownArrivalsBoardBlockEntity.Panel("乙镇", new BoardView(7, 200, Filter.ALL, 0, 1, 0, false, List.of(row))));
    }
    private static TownArrivalsBoardBlockEntity board(TownArrivalsBoardClientClock.ClockState clock) throws Exception {
        Field unsafe = Unsafe.class.getDeclaredField("theUnsafe"); unsafe.setAccessible(true);
        var board = (TownArrivalsBoardBlockEntity) ((Unsafe) unsafe.get(null)).allocateInstance(TownArrivalsBoardBlockEntity.class);
        Field state = TownArrivalsBoardBlockEntity.class.getDeclaredField("client"); state.setAccessible(true);
        state.set(board, new TownArrivalsBoardBlockEntity.ClientState());
        TownArrivalsBoardBlockEntity.installClientClock(clock::moment);
        board.handleUpdateTag(livePacket(), null); // The actual packet entry point, without any renderer or OpenGL.
        return board;
    }
    private static void noEta(TownArrivalsBoardBlockEntity board) {
        var panel = board.getClientPanel();
        assertTrue(panel.view().syncing());
        assertTrue(panel.view().rows().getFirst().etaTicks().isEmpty());
        assertEquals(State.STALE, panel.view().rows().getFirst().state());
    }
    @Test void culledBoardMissedShortFreezeRequiresFreshPostFreezePacket() throws Exception {
        var clock = new TownArrivalsBoardClientClock.ClockState(); var world = new World(40); var ticks = new TickRateManager();
        clock.advance(world, ticks.isFrozen()); var received = clock.moment(); var board = board(clock);
        assertTrue(board.getClientPanel().view().rows().getFirst().etaTicks().isPresent());
        ticks.setFrozen(true);
        for (int i = 0; i < 8; i++) clock.advance(world, ticks.isFrozen()); // No board read or packet during the entire freeze.
        ticks.setFrozen(false); clock.advance(world, ticks.isFrozen());
        noEta(board); assertTrue(clock.moment().epoch() > received.epoch());
        assertTrue(clock.moment().ticks() - received.ticks() < 100, "history must catch freezes shorter than the age limit");
        for (int i = 0; i < 5; i++) clock.advance(world, false);
        noEta(board);
        board.handleUpdateTag(livePacket(), null);
        assertTrue(board.getClientPanel().view().rows().getFirst().etaTicks().isPresent());
    }
    @Test void culledBoardMissedLongFreezeCannotResurrectEtaBeforeNextPacket() throws Exception {
        var clock = new TownArrivalsBoardClientClock.ClockState(); var world = new World(40); var ticks = new TickRateManager();
        clock.advance(world, false); var board = board(clock); ticks.setFrozen(true);
        for (int i = 0; i < 120; i++) clock.advance(world, ticks.isFrozen());
        ticks.setFrozen(false); clock.advance(world, ticks.isFrozen());
        assertEquals(40, world.constantGameTime()); noEta(board);
    }
    @Test void independentClientTicksExpireEtaWhenUnfrozenWorldTimeStaysConstant() throws Exception {
        var clock = new TownArrivalsBoardClientClock.ClockState(); var world = new World(40);
        clock.advance(world, false); var board = board(clock);
        for (int i = 0; i < 101; i++) clock.advance(world, false);
        assertEquals(40, world.constantGameTime()); noEta(board);
    }
    @Test void worldSwitchInvalidatesRecentOldBoardBeforeAnyFreshPacket() throws Exception {
        var clock = new TownArrivalsBoardClientClock.ClockState();
        clock.advance(new World(40), false); var received = clock.moment(); var board = board(clock);
        clock.advance(new World(40), false); // Equal values still identify a different actual client world.
        noEta(board); assertNotEquals(received.epoch(), clock.moment().epoch());
        board.handleUpdateTag(livePacket(), null);
        assertTrue(board.getClientPanel().view().rows().getFirst().etaTicks().isPresent());
    }
    @Test void frozenPacketCannotBecomeFreshWhenBoardIsCulledUntilAfterUnfreeze() throws Exception {
        var clock = new TownArrivalsBoardClientClock.ClockState(); var world = new World(40);
        clock.advance(world, true); var board = board(clock);
        clock.advance(world, false); noEta(board);
        board.handleUpdateTag(livePacket(), null);
        assertTrue(board.getClientPanel().view().rows().getFirst().etaTicks().isPresent());
    }
    @Test void disconnectAndReconnectInvalidatePreviousWorldTrust() throws Exception {
        var clock = new TownArrivalsBoardClientClock.ClockState();
        clock.advance(new World(40), false); var board = board(clock);
        clock.advance(null, false); assertTrue(clock.moment().frozen());
        clock.advance(new World(40), false); noEta(board);
    }
    @Test void clientPostStateCountsTicksAndRecordsOnlyFreezeEntriesAndWorldChanges() {
        var clock = new TownArrivalsBoardClientClock.ClockState(); var world = new World(40);
        assertTrue(clock.moment().frozen()); clock.advance(world, false);
        assertEquals(1, clock.moment().ticks()); long initial = clock.moment().epoch();
        clock.advance(world, false); assertEquals(initial, clock.moment().epoch());
        clock.advance(world, true); assertEquals(initial + 1, clock.moment().epoch());
        clock.advance(world, true); assertEquals(initial + 1, clock.moment().epoch());
        clock.advance(world, false); assertEquals(initial + 1, clock.moment().epoch());
        clock.advance(world, true); assertEquals(initial + 2, clock.moment().epoch());
        assertEquals(6, clock.moment().ticks());
        clock.advance(null, false); assertTrue(clock.moment().frozen()); assertEquals(initial + 3, clock.moment().epoch());
    }
    @Test void receiptStaysValidAtExactlyOneHundredClientTicksThenExpires() throws Exception {
        var clock = new TownArrivalsBoardClientClock.ClockState(); var world = new World(40);
        clock.advance(world, false); var board = board(clock);
        for (int i = 0; i < 100; i++) clock.advance(world, false);
        assertTrue(board.getClientPanel().view().rows().getFirst().etaTicks().isPresent());
        clock.advance(world, false); noEta(board);
    }
}
