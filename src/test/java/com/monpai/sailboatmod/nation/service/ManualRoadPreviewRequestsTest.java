package com.monpai.sailboatmod.nation.service;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ManualRoadPreviewRequestsTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Object LEVEL = new Object(), STACK = new Object();
    private static ManualRoadPreviewRequests.Context context(String target) {
        return context(target, STACK);
    }
    private static ManualRoadPreviewRequests.Context context(String target, Object stack) {
        return new ManualRoadPreviewRequests.Context(LEVEL, "overworld", target, "BUILD", stack);
    }
    private static final class QueueExecutor implements java.util.concurrent.Executor {
        final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        public void execute(Runnable action) { pending.add(action); }
        void drain() { while (!pending.isEmpty()) pending.remove().run(); }
    }
    @Test void successfulResultsWaitForTheServerExecutor() {
        var requests = new ManualRoadPreviewRequests(); var main = new QueueExecutor();
        var future = new CompletableFuture<String>(); var events = new ArrayList<String>();
        var ticket = requests.begin(PLAYER, context("a"));
        requests.watch(ticket, future, main, () -> context("a"), () -> events.add("clear"), (value, error) -> events.add(value));
        future.complete("ready"); assertTrue(events.isEmpty());
        main.drain(); assertEquals(List.of("clear", "ready"), events);
    }
    @Test void lateTargetAResultCannotEraseAlreadyAcceptedTargetB() {
        var requests = new ManualRoadPreviewRequests(); var main = new QueueExecutor();
        var a = new CompletableFuture<String>(); var b = new CompletableFuture<String>();
        var current = new AtomicReference<>(context("a")); var events = new ArrayList<String>();
        requests.watch(requests.begin(PLAYER, current.get()), a, main, current::get, () -> events.add("clear-a"), (v,e) -> events.add(v));
        requests.invalidate(PLAYER); current.set(context("b"));
        requests.watch(requests.begin(PLAYER, current.get()), b, main, current::get, () -> events.add("clear-b"), (v,e) -> events.add(v));
        b.complete("b"); main.drain(); a.complete("a"); main.drain();
        assertEquals(List.of("clear-b", "b"), events);
    }
    @Test void newerRequestForSameTargetKeepsItsPendingStateWhenOldRequestFinishes() {
        var requests = new ManualRoadPreviewRequests(); var main = new QueueExecutor();
        var first = new CompletableFuture<String>(); var second = new CompletableFuture<String>();
        var events = new ArrayList<String>();
        requests.watch(requests.begin(PLAYER, context("a")), first, main, () -> context("a"), () -> events.add("clear-old"), (v,e) -> events.add(v));
        requests.watch(requests.begin(PLAYER, context("a")), second, main, () -> context("a"), () -> events.add("clear-new"), (v,e) -> events.add(v));
        first.complete("old"); main.drain(); assertTrue(events.isEmpty());
        second.complete("new"); main.drain(); assertEquals(List.of("clear-new", "new"), events);
    }
    @Test void cancellingInvalidatesQueuedSuccessBeforeItCanApply() {
        var requests = new ManualRoadPreviewRequests(); var main = new QueueExecutor(); var events = new ArrayList<String>();
        var future = new CompletableFuture<String>();
        requests.watch(requests.begin(PLAYER, context("a")), future, main, () -> context("a"), () -> events.add("clear"), (v,e) -> events.add(v));
        future.complete("old"); requests.invalidate(PLAYER); main.drain(); assertEquals(List.of("clear"), events);
    }
    @Test void lateFailureCannotClearOrFailTheNewRequest() {
        var requests = new ManualRoadPreviewRequests(); var main = new QueueExecutor(); var events = new ArrayList<String>();
        var old = new CompletableFuture<String>(); var fresh = new CompletableFuture<String>();
        requests.watch(requests.begin(PLAYER, context("a")), old, main, () -> context("a"), () -> events.add("clear-old"), (v,e) -> events.add("fail-old"));
        requests.watch(requests.begin(PLAYER, context("a")), fresh, main, () -> context("a"), () -> events.add("clear-new"), (v,e) -> events.add(v));
        old.completeExceptionally(new IllegalStateException("old failure")); main.drain(); assertTrue(events.isEmpty());
        fresh.complete("new"); main.drain(); assertEquals(List.of("clear-new", "new"), events);
    }
    @Test void currentFailureClearsOnlyItsPendingFlagAndReportsOnTheServerExecutor() {
        var requests = new ManualRoadPreviewRequests(); var main = new QueueExecutor(); var events = new ArrayList<String>();
        var future = new CompletableFuture<String>(); var failure = new IllegalStateException("failed");
        requests.watch(requests.begin(PLAYER, context("a")), future, main, () -> context("a"), () -> events.add("clear"), (v,e) -> { assertSame(failure,e); events.add("failed"); });
        future.completeExceptionally(failure); assertTrue(events.isEmpty()); main.drain(); assertEquals(List.of("clear", "failed"), events);
    }
    @Test void deliveryChecksCurrentLevelDimensionTargetModeAndOriginalItem() {
        for (var changed : List.of(
                new ManualRoadPreviewRequests.Context(new Object(),"overworld","a","BUILD",STACK),
                new ManualRoadPreviewRequests.Context(LEVEL,"nether","a","BUILD",STACK),
                context("b"),
                new ManualRoadPreviewRequests.Context(LEVEL,"overworld","a","CANCEL",STACK),
                new ManualRoadPreviewRequests.Context(LEVEL,"overworld","a","BUILD",new Object()))) {
            var requests = new ManualRoadPreviewRequests(); var main = new QueueExecutor(); var events = new ArrayList<String>();
            var future = new CompletableFuture<String>();
            requests.watch(requests.begin(PLAYER, context("a")), future, main, () -> changed, () -> events.add("clear"), (v,e) -> events.add(v));
            future.complete("stale"); main.drain(); assertEquals(List.of("clear"), events);
        }
    }
    @Test void disconnectedPlayerHasNoContextAndCannotReceiveAnAppliedPreview() {
        var requests = new ManualRoadPreviewRequests(); var main = new QueueExecutor(); var events = new ArrayList<String>();
        var future = new CompletableFuture<String>();
        requests.watch(requests.begin(PLAYER, context("a")), future, main, () -> null, () -> events.add("clear"), (v,e) -> events.add(v));
        future.complete("stale"); main.drain(); assertEquals(List.of("clear"), events);
    }
    @Test void retiredDifferentItemClearsOnlyItsOwnPendingWhileTheNewItemIsStillPlanning() {
        var requests = new ManualRoadPreviewRequests(); var main = new QueueExecutor();
        var old = new CompletableFuture<String>(); var fresh = new CompletableFuture<String>();
        var otherStack = new Object(); var oldPending = new AtomicBoolean(true); var freshPending = new AtomicBoolean(true);
        var events = new ArrayList<String>();
        requests.watch(requests.begin(PLAYER, context("a")), old, main, () -> null,
                () -> oldPending.set(false), (v,e) -> events.add("old-applied"));
        requests.watch(requests.begin(PLAYER, context("b", otherStack)), fresh, main, () -> context("b", otherStack),
                () -> freshPending.set(false), (v,e) -> events.add(v));
        old.complete("old"); main.drain();
        assertFalse(oldPending.get(), "The stored original item must become usable again");
        assertTrue(freshPending.get(), "The newer item's request is still pending");
        assertTrue(events.isEmpty());
        fresh.complete("new"); main.drain();
        assertFalse(freshPending.get()); assertEquals(List.of("new"), events);
    }
    @Test void retiredDifferentItemFailureClearsItsPendingAfterTheNewItemAlreadyCompleted() {
        var requests = new ManualRoadPreviewRequests(); var main = new QueueExecutor();
        var old = new CompletableFuture<String>(); var fresh = new CompletableFuture<String>();
        var otherStack = new Object(); var oldPending = new AtomicBoolean(true); var freshPending = new AtomicBoolean(true);
        var events = new ArrayList<String>();
        requests.watch(requests.begin(PLAYER, context("a")), old, main, () -> null,
                () -> oldPending.set(false), (v,e) -> events.add("old-failed"));
        requests.watch(requests.begin(PLAYER, context("b", otherStack)), fresh, main, () -> context("b", otherStack),
                () -> freshPending.set(false), (v,e) -> events.add(v));
        fresh.complete("new"); main.drain();
        old.completeExceptionally(new IllegalStateException("old failure")); main.drain();
        assertFalse(oldPending.get()); assertFalse(freshPending.get()); assertEquals(List.of("new"), events);
    }
    @Test void invalidatingWhileAnotherItemIsHeldStillClearsTheOriginalItemsPending() {
        var requests = new ManualRoadPreviewRequests(); var main = new QueueExecutor();
        var old = new CompletableFuture<String>(); var oldPending = new AtomicBoolean(true);
        var events = new ArrayList<String>(); var heldItem = context("b", new Object());
        requests.watch(requests.begin(PLAYER, context("a")), old, main, () -> heldItem,
                () -> oldPending.set(false), (v,e) -> events.add(v));
        requests.invalidate(PLAYER); old.complete("old"); main.drain();
        assertFalse(oldPending.get()); assertTrue(events.isEmpty());
    }
    @Test void oldCompletionCannotClearANewerRequestOnTheOriginalItemAfterAnotherItemFinished() {
        var requests = new ManualRoadPreviewRequests(); var main = new QueueExecutor();
        var old = new CompletableFuture<String>(); var other = new CompletableFuture<String>();
        var fresh = new CompletableFuture<String>(); var otherStack = new Object();
        var originalPending = new AtomicBoolean(true); var events = new ArrayList<String>();
        requests.watch(requests.begin(PLAYER, context("a")), old, main, () -> context("a"),
                () -> originalPending.set(false), (v,e) -> events.add("old-applied"));
        requests.watch(requests.begin(PLAYER, context("b", otherStack)), other, main, () -> context("b", otherStack),
                () -> {}, (v,e) -> events.add(v));
        other.complete("other"); main.drain();
        requests.watch(requests.begin(PLAYER, context("a")), fresh, main, () -> context("a"),
                () -> originalPending.set(false), (v,e) -> events.add(v));
        old.complete("old"); main.drain();
        assertTrue(originalPending.get(), "The original item now belongs to the newer request");
        assertEquals(List.of("other"), events);
        fresh.complete("fresh"); main.drain();
        assertFalse(originalPending.get()); assertEquals(List.of("other", "fresh"), events);
    }
}
