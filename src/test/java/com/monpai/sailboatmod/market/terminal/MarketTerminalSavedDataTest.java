package com.monpai.sailboatmod.market.terminal;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarketTerminalSavedDataTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final BlockPos MARKET = new BlockPos(28110, 66, -48635);
    private static final BlockPos OLD_LINK = new BlockPos(33431923, -1166, -1978998);

    @Test
    void deletedMarketAliasesDoNotReviveWhenAnotherMarketUsesTheSamePosition() {
        MarketTerminalSavedData data = withAlias(DIMENSION, OLD_LINK, MARKET);
        assertEquals(MARKET, data.canonicalPosition(DIMENSION, OLD_LINK));

        data.removeEntry(DIMENSION, MARKET);
        data.putEntry(entry(DIMENSION, MARKET));

        assertEquals(OLD_LINK, data.canonicalPosition(DIMENSION, OLD_LINK),
                "a deleted market's old URL must not acquire a newly registered terminal");
        MarketTerminalSavedData reloaded = MarketTerminalSavedData.load(data.save(new CompoundTag()));
        assertEquals(OLD_LINK, reloaded.canonicalPosition(DIMENSION, OLD_LINK));
    }

    @Test
    void removingAnAliasAddressPurgesTheLinkAndMarksSavedDataDirty() {
        MarketTerminalSavedData data = withAlias(DIMENSION, OLD_LINK, MARKET);
        data.setDirty(false);

        data.removeEntry(DIMENSION, OLD_LINK);

        assertEquals(OLD_LINK, data.canonicalPosition(DIMENSION, OLD_LINK));
        assertTrue(data.containsPosition(DIMENSION, MARKET));
        assertTrue(data.isDirty(), "discarding an orphan URL must be persisted");
    }

    @Test
    void deletingCoordinatesInAnotherDimensionRetainsTheOriginalAlias() {
        MarketTerminalSavedData data = withAlias(DIMENSION, OLD_LINK, MARKET);
        data.putEntry(entry("minecraft:the_nether", MARKET));

        data.removeEntry("minecraft:the_nether", MARKET);

        assertEquals(MARKET, data.canonicalPosition(DIMENSION, OLD_LINK));
    }

    @Test
    void entryLookupReturnsTheRegisteredObjectWithoutFollowingOldLinks() {
        MarketTerminalSavedData data = withAlias(DIMENSION, OLD_LINK, MARKET);
        MarketTerminalSavedData.MarketTerminalEntry replacement = entry(DIMENSION, MARKET);
        data.putEntry(replacement);

        assertSame(replacement, data.getEntry(DIMENSION, MARKET));
        assertNull(data.getEntry(DIMENSION, OLD_LINK));
        assertNull(data.getEntry("minecraft:the_nether", MARKET));
        assertNull(data.getEntry(DIMENSION, null));
    }

    @Test
    void unresolvedLegacyCoordinatesRemainUnverifiedUntilPhysicallyRegistered() {
        CompoundTag legacy = new CompoundTag();
        legacy.putString("DimensionId", DIMENSION);
        legacy.putLong("MarketPos", 9189697286124907378L);
        ListTag entries = new ListTag();
        entries.add(legacy);
        CompoundTag root = new CompoundTag();
        root.put("Entries", entries);
        MarketTerminalSavedData data = MarketTerminalSavedData.load(root);
        BlockPos loadedPosition = data.entries().getFirst().marketPos();

        assertTrue(data.hasUnverifiedPosition(DIMENSION, loadedPosition));
        data = MarketTerminalSavedData.load(data.save(new CompoundTag()));
        assertTrue(data.hasUnverifiedPosition(DIMENSION, loadedPosition));

        data.putEntry(entry(DIMENSION, loadedPosition));
        assertFalse(data.hasUnverifiedPosition(DIMENSION, loadedPosition));
    }

    @Test
    void portableCoordinatesAndMissingEntriesAreNotUnverifiedLegacyPositions() {
        MarketTerminalSavedData data = withAlias(DIMENSION, OLD_LINK, MARKET);

        assertFalse(data.hasUnverifiedPosition(DIMENSION, MARKET));
        assertFalse(data.hasUnverifiedPosition(DIMENSION, OLD_LINK));
        assertFalse(data.hasUnverifiedPosition(DIMENSION, null));
        data.removeEntry(DIMENSION, MARKET);
        assertFalse(data.hasUnverifiedPosition(DIMENSION, MARKET));
    }

    @Test
    void deletingAnUnknownPositionDoesNotMarkSavedDataDirty() {
        MarketTerminalSavedData data = new MarketTerminalSavedData();

        data.removeEntry(DIMENSION, MARKET);

        assertFalse(data.isDirty());
    }

    private static MarketTerminalSavedData withAlias(String dimension, BlockPos previous, BlockPos canonical) {
        CompoundTag root = new CompoundTag();
        ListTag entries = new ListTag();
        entries.add(entry(dimension, canonical).save());
        root.put("Entries", entries);
        CompoundTag alias = new CompoundTag();
        alias.putString("DimensionId", dimension);
        alias.putIntArray("OldXYZ", new int[]{previous.getX(), previous.getY(), previous.getZ()});
        alias.putIntArray("CanonicalXYZ", new int[]{canonical.getX(), canonical.getY(), canonical.getZ()});
        ListTag aliases = new ListTag();
        aliases.add(alias);
        root.put("PositionAliases", aliases);
        return MarketTerminalSavedData.load(root);
    }

    private static MarketTerminalSavedData.MarketTerminalEntry entry(String dimension, BlockPos position) {
        return new MarketTerminalSavedData.MarketTerminalEntry(dimension, position, "Market", "owner", "Owner");
    }
}
