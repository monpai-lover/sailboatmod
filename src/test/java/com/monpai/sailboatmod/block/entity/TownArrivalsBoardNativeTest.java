package com.monpai.sailboatmod.block.entity;

import static com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.transport.LogisticsCarrier;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TownArrivalsBoardNativeTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    static PublicRow row(String id) {
        return new PublicRow(id, LogisticsCarrier.Kind.AIR, "飞机", "城际线", "甲机场", "乙机场", Relation.INBOUND,
                "CRUISE", State.MOVING, OptionalLong.of(1300));
    }
    static TownArrivalsBoardBlockEntity.Panel panel() {
        return new TownArrivalsBoardBlockEntity.Panel("乙镇", new BoardView(7, 200, Filter.ALL, 0, 2, 3, false, List.of(row("opaque-1"))));
    }
    static TownRecord town(String id) {
        return new TownRecord(id, "", "Actual " + id, UUID.randomUUID(), 0, "minecraft:overworld", 0, "", "");
    }
    @Test void outgoingPayloadContainsOnlyPublicWhitelistAndNoPersistedControllerData() {
        CompoundTag tag = TownArrivalsBoardBlockEntity.encodePublic(panel());
        assertEquals(Set.of("Version", "TownName", "Filter", "Page", "Pages", "Revision", "ObservedTick", "Syncing", "Overflow", "Rows"), tag.getAllKeys());
        assertTrue(tag.contains("Version", Tag.TAG_INT)); assertEquals(1, tag.getInt("Version"));
        CompoundTag r = tag.getList("Rows", Tag.TAG_COMPOUND).getCompound(0);
        assertEquals(Set.of("RowId", "Kind", "CarrierName", "RouteName", "SourceName", "TargetName", "Relation", "Phase", "State", "EtaTicks"), r.getAllKeys());
        for (String privateKey : List.of("TownId", "Owner", "OwnerUuid", "OrderId", "Cargo", "Money", "Inventory", "NationId", "Controller")) assertFalse(tag.toString().contains(privateKey));
        assertEquals(panel(), TownArrivalsBoardBlockEntity.decodePublic(tag));
    }
    private static void assertRejectedPublicPanel(CompoundTag tag) {
        var out = TownArrivalsBoardBlockEntity.decodePublic(tag);
        assertFalse(out.bound()); assertTrue(out.view().syncing()); assertTrue(out.view().rows().isEmpty());
        var client = new TownArrivalsBoardBlockEntity.ClientState();
        client.receive(TownArrivalsBoardBlockEntity.encodePublic(panel()), 10);
        client.receive(tag, 11);
        assertFalse(client.display(12).bound()); assertTrue(client.display(12).view().syncing());
        assertTrue(client.display(12).view().rows().isEmpty(), "a rejected packet cannot retain an earlier live ETA");
    }
    @Test void missingSchemaVersionRejectsOtherwiseValidLivePanel() {
        CompoundTag tag = TownArrivalsBoardBlockEntity.encodePublic(panel()); tag.remove("Version");
        assertRejectedPublicPanel(tag);
    }
    @Test void wrongTypedSchemaVersionRejectsOtherwiseValidLivePanel() {
        for (Tag version : List.<Tag>of(net.minecraft.nbt.StringTag.valueOf("1"), net.minecraft.nbt.LongTag.valueOf(1))) {
            CompoundTag tag = TownArrivalsBoardBlockEntity.encodePublic(panel()); tag.put("Version", version);
            assertRejectedPublicPanel(tag);
        }
    }
    @Test void unsupportedSchemaVersionRejectsOtherwiseValidLivePanel() {
        CompoundTag tag = TownArrivalsBoardBlockEntity.encodePublic(panel()); tag.putInt("Version", 2);
        assertRejectedPublicPanel(tag);
    }
    @Test void wrongTypedSyncingCannotMakeAnOtherwiseValidEtaLookFresh() {
        CompoundTag tag = TownArrivalsBoardBlockEntity.encodePublic(panel()); tag.putString("Syncing", "true");
        assertRejectedPublicPanel(tag);
    }
    @Test void everyRequiredMetadataFieldMustBePresentBeforeRowsCanBecomeLive() {
        for (String key : List.of("Filter", "Page", "Pages", "Revision", "ObservedTick", "Syncing", "Overflow", "Rows", "TownName", "Version")) {
            CompoundTag tag = TownArrivalsBoardBlockEntity.encodePublic(panel()); tag.remove(key);
            assertRejectedPublicPanel(tag);
        }
    }
    @Test void everyRequiredMetadataFieldMustUseItsExactTagType() {
        Map<String, Tag> badTypes = new LinkedHashMap<>();
        badTypes.put("TownName", net.minecraft.nbt.IntTag.valueOf(1));
        badTypes.put("Filter", net.minecraft.nbt.IntTag.valueOf(1));
        badTypes.put("Page", net.minecraft.nbt.LongTag.valueOf(0));
        badTypes.put("Pages", net.minecraft.nbt.LongTag.valueOf(2));
        badTypes.put("Revision", net.minecraft.nbt.IntTag.valueOf(7));
        badTypes.put("ObservedTick", net.minecraft.nbt.IntTag.valueOf(200));
        badTypes.put("Overflow", net.minecraft.nbt.LongTag.valueOf(3));
        badTypes.put("Rows", net.minecraft.nbt.StringTag.valueOf("not a row list"));
        for (var entry : badTypes.entrySet()) {
            CompoundTag tag = TownArrivalsBoardBlockEntity.encodePublic(panel()); tag.put(entry.getKey(), entry.getValue());
            assertRejectedPublicPanel(tag);
        }
        CompoundTag wrongList = TownArrivalsBoardBlockEntity.encodePublic(panel());
        ListTag strings = new ListTag(); strings.add(net.minecraft.nbt.StringTag.valueOf("not a public compound row")); wrongList.put("Rows", strings);
        assertRejectedPublicPanel(wrongList);
    }
    @Test void invalidMetadataRangesAndFilterMustFailClosedRatherThanClampLiveRows() {
        List<java.util.function.Consumer<CompoundTag>> corruptions = List.of(
                tag -> tag.putInt("Page", -1), tag -> tag.putInt("Page", 2),
                tag -> tag.putInt("Pages", 0), tag -> tag.putInt("Pages", 9),
                tag -> tag.putInt("Pages", Integer.MAX_VALUE), tag -> tag.putLong("Revision", -1),
                tag -> tag.putLong("ObservedTick", -1), tag -> tag.putInt("Overflow", -1),
                tag -> tag.putByte("Syncing", (byte) 2), tag -> tag.putByte("Syncing", (byte) -1),
                tag -> tag.putString("Filter", "UNKNOWN"), tag -> tag.putString("Filter", "all"),
                tag -> tag.putString("TownName", "a".repeat(65)), tag -> tag.putString("TownName", "🚢".repeat(65)));
        for (var corrupt : corruptions) {
            CompoundTag tag = TownArrivalsBoardBlockEntity.encodePublic(panel()); corrupt.accept(tag);
            assertRejectedPublicPanel(tag);
        }
    }
    @Test void actualWorldFreezeSignalExpiresEtaEvenWithAConstantGameTimeUntilFreshPostFreezePacket() {
        var manager = new net.minecraft.world.TickRateManager();
        long frozenGameTick = 40;
        var client = new TownArrivalsBoardBlockEntity.ClientState();
        client.receive(TownArrivalsBoardBlockEntity.encodePublic(panel()), frozenGameTick, manager.isFrozen());
        assertTrue(client.display(frozenGameTick, manager.isFrozen()).view().rows().getFirst().etaTicks().isPresent());
        manager.setFrozen(true); manager.tick(); assertFalse(manager.runsNormally());
        var frozen = client.display(frozenGameTick, manager.isFrozen());
        assertTrue(frozen.view().syncing()); assertTrue(frozen.view().rows().getFirst().etaTicks().isEmpty());
        assertEquals(State.STALE, frozen.view().rows().getFirst().state());
        manager.setFrozen(false); manager.tick();
        assertTrue(client.display(frozenGameTick, manager.isFrozen()).view().rows().getFirst().etaTicks().isEmpty(), "unfreeze cannot resurrect an old ETA");
        client.receive(TownArrivalsBoardBlockEntity.encodePublic(panel()), frozenGameTick, manager.isFrozen());
        assertTrue(client.display(frozenGameTick, manager.isFrozen()).view().rows().getFirst().etaTicks().isPresent());
    }
    @Test void packetReceivedWhileWorldIsFrozenCannotBecomeFreshAfterUnfreeze() {
        var manager = new net.minecraft.world.TickRateManager(); manager.setFrozen(true); manager.tick();
        var client = new TownArrivalsBoardBlockEntity.ClientState();
        client.receive(TownArrivalsBoardBlockEntity.encodePublic(panel()), 40, manager.isFrozen());
        manager.setFrozen(false); manager.tick();
        assertTrue(client.display(40, manager.isFrozen()).view().syncing());
        assertTrue(client.display(40, manager.isFrozen()).view().rows().getFirst().etaTicks().isEmpty());
    }
    @Test void decoderNeverExposesMoreThanEightRowsOrEightPages() {
        CompoundTag tag = TownArrivalsBoardBlockEntity.encodePublic(panel());
        ListTag rows = new ListTag();
        for (int i = 0; i < 30; i++) rows.add(tag.getList("Rows", Tag.TAG_COMPOUND).getCompound(0).copy());
        tag.put("Rows", rows); tag.putInt("Pages", 8); tag.putInt("Page", 7);
        var decoded = TownArrivalsBoardBlockEntity.decodePublic(tag);
        assertEquals(8, decoded.view().rows().size()); assertEquals(8, decoded.view().totalPages()); assertEquals(7, decoded.view().page());
    }
    @Test void invalidEnumsAndIdentityDropOnlyTheMalformedRow() {
        for (String key : List.of("Kind", "Relation", "State", "RowId")) {
            CompoundTag tag = TownArrivalsBoardBlockEntity.encodePublic(panel());
            CompoundTag broken = tag.getList("Rows", Tag.TAG_COMPOUND).getCompound(0);
            broken.putString(key, key.equals("RowId") ? "bad\nidentity" : "PRIVATE_ENUM");
            tag.getList("Rows", Tag.TAG_COMPOUND).add(TownArrivalsBoardBlockEntity.encodePublic(panel()).getList("Rows", Tag.TAG_COMPOUND).getCompound(0));
            assertEquals(1, TownArrivalsBoardBlockEntity.decodePublic(tag).view().rows().size(), key);
        }
    }
    @Test void invalidEtaCannotBecomeAnArrivalCountdown() {
        for (long eta : new long[]{-1, 0, MAX_ETA_TICKS + 1, Long.MAX_VALUE}) {
            CompoundTag tag = TownArrivalsBoardBlockEntity.encodePublic(panel());
            tag.getList("Rows", Tag.TAG_COMPOUND).getCompound(0).putLong("EtaTicks", eta);
            assertTrue(TownArrivalsBoardBlockEntity.decodePublic(tag).view().rows().getFirst().etaTicks().isEmpty());
        }
    }
    @Test void blockedAndUnknownPublicStatesCannotCarryCountdowns() {
        for (State state : State.values()) {
            if (state == State.MOVING) continue;
            CompoundTag tag = TownArrivalsBoardBlockEntity.encodePublic(panel());
            tag.getList("Rows", Tag.TAG_COMPOUND).getCompound(0).putString("State", state.name());
            var row = TownArrivalsBoardBlockEntity.decodePublic(tag).view().rows().getFirst();
            assertEquals(state, row.state());
            assertTrue(row.etaTicks().isEmpty(), state.name());
        }
    }
    @Test void verifiedRailDwellSurvivesTheBoardCodec() {
        CompoundTag tag = TownArrivalsBoardBlockEntity.encodePublic(panel());
        CompoundTag rowTag = tag.getList("Rows", Tag.TAG_COMPOUND).getCompound(0);
        rowTag.putString("State", State.DWELLING.name());
        rowTag.putString("Phase", "RAIL_FREIGHT");
        rowTag.putLong("EtaTicks", 640);
        var row = TownArrivalsBoardBlockEntity.decodePublic(tag).view().rows().getFirst();
        assertEquals(State.DWELLING, row.state());
        assertEquals(640, row.etaTicks().orElseThrow());
    }
    @Test void codecBoundsUnicodeNamesAndSuppressesFormattingAndUnknownPhase() {
        CompoundTag tag = TownArrivalsBoardBlockEntity.encodePublic(panel());
        tag.putString("TownName", "\u00a7c\u202e镇\n" + "\uD83D\uDEA2".repeat(25));
        CompoundTag r = tag.getList("Rows", Tag.TAG_COMPOUND).getCompound(0);
        for (String key : List.of("CarrierName", "RouteName", "SourceName", "TargetName")) r.putString(key, "\u00a7c\uD800\n" + "\uD83D\uDEA2".repeat(120));
        r.putString("Phase", "SECRET_ORDER_PHASE");
        var decoded = TownArrivalsBoardBlockEntity.decodePublic(tag);
        assertTrue(decoded.townName().codePointCount(0, decoded.townName().length()) <= 64);
        var out = decoded.view().rows().getFirst();
        assertTrue(out.carrierName().codePointCount(0, out.carrierName().length()) <= 64);
        assertTrue(out.routeName().codePointCount(0, out.routeName().length()) <= 96);
        assertTrue(out.sourceName().codePointCount(0, out.sourceName().length()) <= 64);
        assertFalse(out.toString().contains("§")); assertFalse(out.toString().contains("\uD800")); assertEquals("UNKNOWN", out.publicPhase());
    }
    @Test void malformedOrMissingTagsHaveSafeUnboundSyncingDefaults() {
        CompoundTag tag = new CompoundTag(); tag.putString("Filter", "UNKNOWN"); tag.putInt("Page", -7); tag.putInt("Pages", -1); tag.putLong("Revision", -1); tag.putLong("ObservedTick", -1);
        var decoded = assertDoesNotThrow(() -> TownArrivalsBoardBlockEntity.decodePublic(tag));
        assertFalse(decoded.bound()); assertEquals(Filter.ALL, decoded.view().filter()); assertEquals(0, decoded.view().page());
        assertEquals(1, decoded.view().totalPages()); assertEquals(0, decoded.view().revision()); assertEquals(0, decoded.view().observedTick()); assertTrue(decoded.view().syncing());
    }
    @Test void settingsDiskRoundTripIncludesOnlyOwnFilterAndPage() {
        var settings = new TownArrivalsBoardBlockEntity.Settings(Filter.AIR, 3);
        CompoundTag disk = TownArrivalsBoardBlockEntity.encodeSettings(settings);
        assertEquals(Set.of("Filter", "Page"), disk.getAllKeys());
        assertEquals(settings, TownArrivalsBoardBlockEntity.decodeSettings(disk));
    }
    @Test void oldDiskRowsAndCopiedTownBindingNeverBecomeFreshClientData() {
        CompoundTag disk = TownArrivalsBoardBlockEntity.encodePublic(panel());
        disk.putString("TownId", "copied-other-town"); disk.putString("OwnerUuid", UUID.randomUUID().toString());
        var client = new TownArrivalsBoardBlockEntity.ClientState();
        client.receive(TownArrivalsBoardBlockEntity.encodePublic(panel()), 10);
        client.loadDisk(disk);
        var out = client.display(11);
        assertFalse(out.bound()); assertTrue(out.view().syncing()); assertTrue(out.view().rows().isEmpty());
    }
    @Test void clientCountsDownTrustedGameTicksUntilOneHundredTickFreshnessExpires() {
        var client = new TownArrivalsBoardBlockEntity.ClientState(); client.receive(TownArrivalsBoardBlockEntity.encodePublic(panel()), 40);
        assertEquals(1250, client.display(90).view().rows().getFirst().etaTicks().orElseThrow());
        assertEquals(1200, client.display(140).view().rows().getFirst().etaTicks().orElseThrow());
        var stale = client.display(141); assertTrue(stale.view().syncing()); assertTrue(stale.view().rows().getFirst().etaTicks().isEmpty()); assertEquals(State.STALE, stale.view().rows().getFirst().state());
        CompoundTag fresh = TownArrivalsBoardBlockEntity.encodePublic(panel());
        fresh.putLong("ObservedTick", 300);
        client.receive(fresh, 142);
        assertEquals(1299, client.display(143).view().rows().getFirst().etaTicks().orElseThrow());
    }
    @Test void repeatedProjectionCannotRestartCountdownOrReviveExpiredEvidence() {
        var client = new TownArrivalsBoardBlockEntity.ClientState();
        CompoundTag same = TownArrivalsBoardBlockEntity.encodePublic(panel());
        same.getList("Rows", Tag.TAG_COMPOUND).getCompound(0).putLong("EtaTicks", 3);
        client.receive(same, 40);
        assertEquals(2, client.display(41).view().rows().getFirst().etaTicks().orElseThrow());
        client.receive(same, 42);
        assertEquals(1, client.display(42).view().rows().getFirst().etaTicks().orElseThrow());
        var elapsed = client.display(43);
        assertFalse(elapsed.view().syncing());
        assertEquals("opaque-1", elapsed.view().rows().getFirst().rowId());
        assertEquals(State.ETA_UNKNOWN, elapsed.view().rows().getFirst().state());
        assertTrue(elapsed.view().rows().getFirst().etaTicks().isEmpty());
        assertTrue(client.display(141).view().syncing());
        client.receive(same, 142);
        assertTrue(client.display(142).view().syncing(), "A packet with the same server tick is not new movement evidence");
        CompoundTag older = same.copy();
        older.putLong("ObservedTick", 199);
        client.receive(older, 143);
        assertTrue(client.display(143).view().syncing(), "An older projection cannot revive stale movement evidence");
    }
    @Test void clientClockResetExpiresInsteadOfResurrectingOldEta() {
        var client = new TownArrivalsBoardBlockEntity.ClientState(); client.receive(TownArrivalsBoardBlockEntity.encodePublic(panel()), 40);
        assertTrue(client.display(2).view().rows().getFirst().etaTicks().isEmpty());
    }
    @Test void configurationRequiresRealNearbyManagerInTheSameServerLevelAndActualTown() {
        assertTrue(TownArrivalsBoardBlockEntity.canConfigure(true, true, 64, "actual-town", true));
        assertFalse(TownArrivalsBoardBlockEntity.canConfigure(false, true, 1, "actual-town", true));
        assertFalse(TownArrivalsBoardBlockEntity.canConfigure(true, false, 1, "actual-town", true));
        assertFalse(TownArrivalsBoardBlockEntity.canConfigure(true, true, 64.00001, "actual-town", true));
        assertFalse(TownArrivalsBoardBlockEntity.canConfigure(true, true, Double.NaN, "actual-town", true));
        assertFalse(TownArrivalsBoardBlockEntity.canConfigure(true, true, -1, "actual-town", true));
        assertFalse(TownArrivalsBoardBlockEntity.canConfigure(true, true, 1, "", true));
        assertFalse(TownArrivalsBoardBlockEntity.canConfigure(true, true, 1, "actual-town", false));
    }
    @Test void managerCyclesOnlyAllWaterLandAirAndResetsPage() {
        var setting = new TownArrivalsBoardBlockEntity.Settings(Filter.ALL, 3);
        for (Filter filter : List.of(Filter.WATER, Filter.LAND, Filter.AIR, Filter.ALL)) {
            setting = setting.nextFilter(); assertEquals(filter, setting.filter()); assertEquals(0, setting.page());
        }
    }
    @Test void actualPositionalTownAlwaysWinsOverDiskBinding() {
        CompoundTag disk = new CompoundTag(); disk.putString("TownId", "copied-town"); disk.putString("Filter", "AIR");
        var actual = town("actual-town"); var queries = new ArrayList<String>();
        var bound = TownArrivalsBoardBlockEntity.collect(actual, TownArrivalsBoardBlockEntity.decodeSettings(disk), 200, (id, filter, page, now) -> {
            queries.add(id); assertEquals(Filter.AIR, filter); return panel().view();
        });
        assertEquals(List.of("actual-town"), queries); assertEquals(actual.name(), bound.townName());
    }
    @Test void outsideTownNeverQueriesAnyTownOrFallsBackToAllRoutes() {
        var out = TownArrivalsBoardBlockEntity.collect(null, new TownArrivalsBoardBlockEntity.Settings(Filter.ALL, 0), 200,
                (id, f, p, t) -> { fail("unbound board must never query service"); return panel().view(); });
        assertFalse(out.bound()); assertTrue(out.view().rows().isEmpty());
    }
    @Test void multipleTownBoardsReadTheProvidedSharedProjectionWithoutRefilteringRows() {
        var shared = panel().view(); var queries = new AtomicInteger(); var actual = town("actual-town");
        TownArrivalsBoardBlockEntity.Views views = (id, f, p, t) -> { queries.incrementAndGet(); return shared; };
        var first = TownArrivalsBoardBlockEntity.collect(actual, new TownArrivalsBoardBlockEntity.Settings(Filter.ALL, 0), 200, views);
        var second = TownArrivalsBoardBlockEntity.collect(actual, new TownArrivalsBoardBlockEntity.Settings(Filter.ALL, 0), 200, views);
        assertSame(shared, first.view()); assertSame(shared, second.view()); assertEquals(2, queries.get());
    }
    @Test void paginationUsesStableSharedGameTicksAndNeverLeavesAvailablePages() {
        assertEquals(0, TownArrivalsBoardBlockEntity.pageAt(0, 3, 0));
        assertEquals(0, TownArrivalsBoardBlockEntity.pageAt(199, 3, 0));
        assertEquals(1, TownArrivalsBoardBlockEntity.pageAt(200, 3, 0));
        assertEquals(2, TownArrivalsBoardBlockEntity.pageAt(400, 3, 0));
        assertEquals(0, TownArrivalsBoardBlockEntity.pageAt(600, 3, 0));
        assertEquals(0, TownArrivalsBoardBlockEntity.pageAt(Long.MAX_VALUE, 0, 0));
    }
}
