package com.monpai.sailboatmod.market.web;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipException;

import static org.junit.jupiter.api.Assertions.*;

class MarketWebSavedMarketEvidenceTest {
    private static final BlockPos POSITION = new BlockPos(-48, 10639, 80);

    @Test
    void generatedUnloadedMarketAndLegacyWrappedMarketRemainPresent() throws Exception {
        CompoundTag chunk = chunk("minecraft:full");
        chunk.getList("block_entities", 10).add(market(POSITION));
        assertEquals("PRESENT", inspect(Optional.of(chunk)));
        CompoundTag legacy = chunk.copy();
        legacy.put("TileEntities", legacy.get("block_entities"));
        legacy.remove("block_entities");
        CompoundTag wrapped = new CompoundTag();
        wrapped.put("Level", legacy);
        assertEquals("PRESENT", inspect(Optional.of(wrapped)));
    }

    @Test
    void successfulAbsentAndVerifiedFullMissingMarketArePrunable() throws Exception {
        assertEquals("MISSING", inspect(Optional.empty()));
        assertEquals("MISSING", inspect(Optional.of(chunk("full"))));
        CompoundTag otherPosition = chunk("full");
        otherPosition.getList("block_entities", 10).add(market(POSITION.above()));
        assertEquals("MISSING", inspect(Optional.of(otherPosition)));
    }

    @Test
    void protoUnknownWrongChunkAndMalformedBlockEntitiesRemainUnavailable() throws Exception {
        assertEquals("UNAVAILABLE", inspect(Optional.of(chunk("surface"))));
        assertEquals("UNAVAILABLE", inspect(Optional.of(chunk("mod:future"))));
        CompoundTag wrongChunk = chunk("full");
        wrongChunk.putInt("xPos", 123);
        assertEquals("UNAVAILABLE", inspect(Optional.of(wrongChunk)));
        CompoundTag wrongList = chunk("full");
        wrongList.putString("block_entities", "damaged");
        assertEquals("UNAVAILABLE", inspect(Optional.of(wrongList)));
        CompoundTag missingCoordinate = market(POSITION);
        missingCoordinate.remove("y");
        CompoundTag malformed = chunk("full");
        malformed.getList("block_entities", 10).add(missingCoordinate);
        assertEquals("UNAVAILABLE", inspect(Optional.of(malformed)));
    }

    @Test
    void structuralCorruptionIsSeparatedFromTimeoutAndTransientIoFailure() throws Exception {
        assertTrue(corrupt(new EOFException("truncated NBT")));
        assertTrue(corrupt(new CompletionException(new ZipException("invalid compressed data"))));
        assertFalse(corrupt(new IOException("device temporarily unavailable")));
        assertFalse(corrupt(new TimeoutException("busy disk")));
        assertFalse(corrupt(new java.util.concurrent.CancellationException("server stopping")));
    }

    private static CompoundTag chunk(String status) {
        CompoundTag chunk = new CompoundTag();
        chunk.putString("Status", status);
        chunk.putInt("xPos", POSITION.getX() >> 4);
        chunk.putInt("zPos", POSITION.getZ() >> 4);
        chunk.put("block_entities", new ListTag());
        return chunk;
    }

    private static CompoundTag market(BlockPos position) {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "sailboatmod:market");
        entity.putInt("x", position.getX());
        entity.putInt("y", position.getY());
        entity.putInt("z", position.getZ());
        return entity;
    }

    private static String inspect(Optional<CompoundTag> chunk) throws Exception {
        Method method = resolver().getDeclaredMethod("inspectSavedMarket", Optional.class, BlockPos.class);
        method.setAccessible(true);
        return method.invoke(null, chunk, POSITION).toString();
    }

    private static boolean corrupt(Throwable failure) throws Exception {
        Method method = resolver().getDeclaredMethod("isStructuralCorruption", Throwable.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, failure);
    }

    private static Class<?> resolver() {
        try { return Class.forName("com.monpai.sailboatmod.market.web.MarketWebMarketResolver"); }
        catch (ClassNotFoundException missing) { return fail("asynchronous market evidence classifier is missing", missing); }
    }
}
