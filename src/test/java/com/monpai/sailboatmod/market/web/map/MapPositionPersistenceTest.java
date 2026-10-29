package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.market.terminal.MarketTerminalSavedData;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.market.web.MarketWebService;
import net.minecraft.nbt.Tag;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MapPositionPersistenceTest {
    private static final BlockPos MARKET = new BlockPos(28110, 66, -48635);
    private static final long OLD_DENSE_MARKET = 9189697286124907378L;

    @Test
    void marketStoresPortableCoordinatesAndReadsThemBeforeTheRuntimePackedFormat() {
        var entry = new MarketTerminalSavedData.MarketTerminalEntry(
                "minecraft:overworld", MARKET, "Dev's Market", "owner", "Dev");
        CompoundTag saved = entry.save();
        assertArrayEquals(new int[]{28110, 66, -48635}, saved.getIntArray("MarketPosXYZ"));
        saved.putLong("MarketPos", OLD_DENSE_MARKET);
        assertEquals(MARKET, MarketTerminalSavedData.MarketTerminalEntry.load(saved).marketPos());
    }

    @Test
    void nationCoreSurvivesAChangeOfPackedCoordinateFormat() {
        var record = new NationRecord("nation", "Dev Nation", "DEV", 0, 0,
                UUID.randomUUID(), 0, "", "minecraft:overworld", MARKET.asLong(), "");
        NationSavedData data = new NationSavedData();
        data.putNation(record);
        CompoundTag saved = data.save(new CompoundTag()).getList("Nations", Tag.TAG_COMPOUND).getCompound(0);
        assertArrayEquals(new int[]{28110, 66, -48635}, saved.getIntArray("CorePosXYZ"));
        saved.putLong("CorePos", OLD_DENSE_MARKET);
        assertEquals(MARKET, BlockPos.of(NationSavedData.load(coreRoot("Nations", saved)).getNation("nation").corePos()));
    }

    @Test
    void townCoreSurvivesAChangeOfPackedCoordinateFormat() {
        var record = new TownRecord("town", "nation", "Dev Town", UUID.randomUUID(), 0,
                "minecraft:overworld", MARKET.asLong(), "", "european");
        NationSavedData data = new NationSavedData();
        data.putTown(record);
        CompoundTag saved = data.save(new CompoundTag()).getList("Towns", Tag.TAG_COMPOUND).getCompound(0);
        assertArrayEquals(new int[]{28110, 66, -48635}, saved.getIntArray("CorePosXYZ"));
        saved.putLong("CorePos", OLD_DENSE_MARKET);
        assertEquals(MARKET, BlockPos.of(NationSavedData.load(coreRoot("Towns", saved)).getTown("town").corePos()));
    }

    @Test
    void absentCoresKeepTheirSentinelAndOldVanillaMarketsStillLoad() {
        var nation = new NationRecord("nation", "No Core", "NC", 0, 0,
                UUID.randomUUID(), 0, "", "", NationRecord.noCorePos(), "");
        var town = new TownRecord("town", "", "No Core", UUID.randomUUID(), 0,
                "", TownRecord.noCorePos(), "", "european");
        assertFalse(nation.save().contains("CorePosXYZ"));
        assertFalse(town.save().contains("CorePosXYZ"));
        assertFalse(NationRecord.load(nation.save()).hasCore());
        assertFalse(TownRecord.load(town.save()).hasCore());
        CompoundTag old = new CompoundTag();
        old.putString("DimensionId", "minecraft:overworld");
        old.putLong("MarketPos", MARKET.asLong());
        assertEquals(MARKET, MarketTerminalSavedData.MarketTerminalEntry.load(old).marketPos());
    }

    @Test
    void recoveredMarketLinksSurviveRestartAndCannotPointAtAnUnregisteredMarket() {
        var entry = new MarketTerminalSavedData.MarketTerminalEntry(
                "minecraft:overworld", MARKET, "Dev's Market", "owner", "Dev");
        CompoundTag root = new CompoundTag();
        ListTag entries = new ListTag();
        entries.add(entry.save());
        root.put("Entries", entries);
        BlockPos staleLink = new BlockPos(33431923, -1166, -1978998);
        CompoundTag alias = new CompoundTag();
        alias.putString("DimensionId", "minecraft:overworld");
        alias.putIntArray("OldXYZ", new int[]{33431923, -1166, -1978998});
        alias.putIntArray("CanonicalXYZ", new int[]{28110, 66, -48635});
        ListTag aliases = new ListTag();
        aliases.add(alias);
        root.put("PositionAliases", aliases);

        var data = MarketTerminalSavedData.load(root);
        assertEquals(MARKET, data.canonicalPosition("minecraft:overworld", staleLink));
        assertEquals(MarketWebService.encodeMarketId("minecraft:overworld", MARKET),
                MarketWebService.canonicalMarketId(data, MarketWebService.encodeMarketId("minecraft:overworld", staleLink)),
                "map focus must canonicalize the old URL before comparing marker IDs");
        data = MarketTerminalSavedData.load(data.save(new CompoundTag()));
        assertEquals(MARKET, data.canonicalPosition("minecraft:overworld", staleLink));
        assertEquals(staleLink, data.canonicalPosition("minecraft:the_nether", staleLink));
        data.removeEntry("minecraft:overworld", MARKET);
        assertEquals(staleLink, data.canonicalPosition("minecraft:overworld", staleLink));
    }

    @Test
    void savingOtherRecordsDoesNotMakeAnUnverifiedLegacyMarketPositionAuthoritative() {
        CompoundTag old = new CompoundTag();
        old.putString("DimensionId", "minecraft:overworld");
        old.putLong("MarketPos", OLD_DENSE_MARKET);
        var data = MarketTerminalSavedData.load(coreRoot("Entries", old));
        data.putEntry(new MarketTerminalSavedData.MarketTerminalEntry("minecraft:overworld",
                new BlockPos(9, 66, 9), "Other Market", "other", "Other"));
        CompoundTag stored = data.save(new CompoundTag()).getList("Entries", Tag.TAG_COMPOUND).getCompound(0);
        assertFalse(stored.contains("MarketPosXYZ"), "unknown old positions must remain retryable");
        assertEquals(OLD_DENSE_MARKET, stored.getLong("MarketPos"));
        assertFalse(MarketTerminalSavedData.load(data.save(new CompoundTag())).save(new CompoundTag())
                .getList("Entries", Tag.TAG_COMPOUND).getCompound(0).contains("MarketPosXYZ"));
    }

    @Test
    void highCoreCoordinatesSurviveAnIncompatibleRuntimeWithoutBeingTruncatedOnSave() {
        for (String collection : new String[]{"Nations", "Towns"}) {
            CompoundTag core = new CompoundTag();
            core.putString(collection.equals("Nations") ? "NationId" : "TownId", "core");
            core.putString("CoreDimension", "minecraft:overworld");
            core.putLong("CorePos", OLD_DENSE_MARKET);
            core.putIntArray("CorePosXYZ", new int[]{28110, 10639, -48635});
            var data = NationSavedData.load(coreRoot(collection, core));
            CompoundTag stored = data.save(new CompoundTag()).getList(collection, Tag.TAG_COMPOUND).getCompound(0);
            assertArrayEquals(new int[]{28110, 10639, -48635}, stored.getIntArray("CorePosXYZ"));
        }
    }

    @Test
    void marketsAtDifferentTellusHeightsDoNotCollideInAVanillaRuntime() {
        CompoundTag root = new CompoundTag();
        ListTag list = new ListTag();
        for (int y : new int[]{0, 4096}) {
            list.add(new MarketTerminalSavedData.MarketTerminalEntry("minecraft:overworld",
                    new BlockPos(28110, y, -48635), "Market " + y, "owner", "Dev").save());
        }
        root.put("Entries", list);
        var data = MarketTerminalSavedData.load(root);
        assertEquals(2, data.entries().size());
        data = MarketTerminalSavedData.load(data.save(new CompoundTag()));
        assertEquals(2, data.entries().size());
        assertTrue(data.containsPosition("minecraft:overworld", new BlockPos(28110, 0, -48635)));
        assertTrue(data.containsPosition("minecraft:overworld", new BlockPos(28110, 4096, -48635)));
    }

    private static CompoundTag coreRoot(String key, CompoundTag entry) {
        CompoundTag root = new CompoundTag();
        ListTag entries = new ListTag();
        entries.add(entry);
        root.put(key, entries);
        return root;
    }
}
