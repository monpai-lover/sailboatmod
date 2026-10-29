package com.monpai.sailboatmod.util;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OfflineChunkNbtReaderInterruptTest {
    @Test
    void anInterruptedDiskWaitPreservesCancellationWithoutCancellingSharedIo() throws Exception {
        CompletableFuture<Optional<CompoundTag>> sharedRead = new CompletableFuture<>() {
            @Override public Optional<CompoundTag> get(long timeout, TimeUnit unit) throws InterruptedException {
                throw new InterruptedException("cancelled while waiting for disk");
            }
        };
        try {
            assertTrue(await(sharedRead).isEmpty());
            assertTrue(Thread.currentThread().isInterrupted(), "the caller must still see cancellation before native prediction");
            assertFalse(sharedRead.isCancelled(), "a shared vanilla IOWorker read belongs to more than this preview");
        } finally { Thread.interrupted(); }
    }

    @Test
    void aCompletedSavedReadDoesNotRestartCancelledPlanning() throws Exception {
        CompoundTag tag = new CompoundTag();
        tag.putString("Status", "minecraft:full");
        CompletableFuture<Optional<CompoundTag>> sharedRead = CompletableFuture.completedFuture(Optional.of(tag));
        try {
            Thread.currentThread().interrupt();
            assertTrue(await(sharedRead).isEmpty(), "a completed future must not bypass the caller's cancellation flag");
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @SuppressWarnings("unchecked")
    private static Optional<CompoundTag> await(CompletableFuture<Optional<CompoundTag>> future) throws Exception {
        try {
            Method method = OfflineChunkNbtReader.class.getDeclaredMethod("awaitFull", CompletableFuture.class, long.class);
            method.setAccessible(true);
            return (Optional<CompoundTag>) method.invoke(null, future, 1000L);
        } catch (NoSuchMethodException missing) {
            return fail("safe offline NBT waiting must preserve interrupted cancellation for its map/route callers");
        }
    }
}
