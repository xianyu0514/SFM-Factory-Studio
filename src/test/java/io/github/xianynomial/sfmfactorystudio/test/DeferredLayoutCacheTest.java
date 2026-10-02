package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class DeferredLayoutCacheTest {
    private static SlotLayoutData.Layout layout(String menu, int count, Integer cap) {
        return new SlotLayoutData.Layout("saved", menu,
                List.of(new SlotLayoutData.SlotCapture(10, 20, 0, "minecraft:stone", count, cap)));
    }
    private static SlotLayoutData.SlotAnchor anchor(String menu, int cap) {
        return new SlotLayoutData.SlotAnchor(menu, 0, 0, 10, 20, cap);
    }
    @Test void loadsOffCallerAndDoesNotAllowEarlyWrites() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Thread> reader = new AtomicReference<>();
        DeferredLayoutCache cache = new DeferredLayoutCache(worker, () -> {
            reader.set(Thread.currentThread()); entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("timeout");
            return Map.of("old", layout("Chest", 64, 3));
        }, failure -> fail(failure));
        try {
            assertFalse(cache.poll());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertNotEquals(Thread.currentThread(), reader.get());
            assertFalse(cache.poll());
            assertThrows(IllegalStateException.class, cache::snapshot);
            cache.capture("new", "fresh", "Chest", layout("Chest", 1, null).slots());
            assertFalse(cache.takeChanged());
            release.countDown(); worker.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(cache.poll());
            assertEquals(Set.of("old", "new"), cache.snapshot().keySet());
            assertTrue(cache.takeChanged());
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test void queuedEventsMatchSynchronousLoadWithoutLosingLearnedData() {
        Queue<Runnable> jobs = new ArrayDeque<>();
        Map<String, SlotLayoutData.Layout> disk = Map.of(
                "existing", SlotLayoutData.withAnchor(layout("Chest", 64, 3), anchor("Chest", 3)),
                "same-menu", layout("Chest", 12, 2), "other", layout("Machine", 5, 4));
        DeferredLayoutCache async = new DeferredLayoutCache(jobs::add, () -> disk, failure -> fail(failure));
        DeferredLayoutCache sync = new DeferredLayoutCache(Runnable::run, () -> disk, failure -> fail(failure));
        for (DeferredLayoutCache cache : List.of(async, sync)) {
            cache.capture("existing", "fresh", "Chest", layout("Chest", 32, null).slots());
            cache.setNoExposure("existing");
            cache.applyAnchor(anchor("Chest", 9));
            cache.capture("new", "new", "Machine", layout("Machine", 1, null).slots());
            cache.capture("new", "changed-menu", "Chest", layout("Chest", 2, null).slots());
            cache.applyAnchor(anchor("Chest", 8));
        }
        assertFalse(async.poll());
        jobs.remove().run();
        assertTrue(async.poll());
        assertEquals(sync.snapshot(), async.snapshot());
        assertTrue(async.get("existing").noExposure());
        assertEquals(32, async.get("existing").slots().get(0).count());
        assertEquals(8, async.get("same-menu").slots().get(0).capIndex());
        assertEquals(8, async.get("new").slots().get(0).capIndex());
        assertEquals(4, async.get("other").slots().get(0).capIndex());
        assertEquals(1, async.get("existing").anchors().size());
    }

    @Test void readOnlyLoadAndDuplicateEventsDoNotDirtyCache() {
        SlotLayoutData.Layout saved = SlotLayoutData.withAnchor(layout("Chest", 64, 3), anchor("Chest", 3));
        DeferredLayoutCache cache = new DeferredLayoutCache(Runnable::run, () -> Map.of("p", saved), failure -> fail(failure));
        assertTrue(cache.poll());
        assertFalse(cache.takeChanged());
        cache.capture("p", "saved", "Chest", saved.slots());
        cache.applyAnchor(anchor("Chest", 3));
        assertFalse(cache.takeChanged());
        assertThrows(UnsupportedOperationException.class, () -> cache.snapshot().clear());
    }

    @Test void delayedIncompleteCaptureDoesNotOverwriteMoreCompleteDiskLayout() {
        Queue<Runnable> jobs = new ArrayDeque<>();
        List<SlotLayoutData.SlotCapture> slots = new ArrayList<>(layout("Chest", 3, 7).slots());
        slots.add(new SlotLayoutData.SlotCapture(30, 20, 1, "minecraft:dirt", 1, 8));
        SlotLayoutData.Layout saved = new SlotLayoutData.Layout("full", "Chest", slots);
        DeferredLayoutCache cache = new DeferredLayoutCache(jobs::add, () -> Map.of("p", saved), failure -> fail(failure));
        cache.capture("p", "incomplete", "Chest", layout("Chest", 1, null).slots());
        jobs.remove().run(); cache.poll();
        assertEquals(saved, cache.get("p"));
        assertFalse(cache.takeChanged());
    }

    @Test void loadFailureStillReplaysNewCaptureAndReportsOnce() {
        Queue<Runnable> jobs = new ArrayDeque<>();
        List<Throwable> failures = new ArrayList<>();
        DeferredLayoutCache cache = new DeferredLayoutCache(jobs::add, () -> { throw new IOException("bad file"); }, failures::add);
        cache.capture("new", "fresh", "Chest", layout("Chest", 1, null).slots());
        jobs.remove().run();
        assertTrue(cache.poll()); assertTrue(cache.poll());
        assertNotNull(cache.get("new"));
        assertEquals(1, failures.size());
        assertTrue(cache.takeChanged());
    }

    @Test void executorRejectionDoesNotLeavePickerWaitingForever() {
        List<Throwable> failures = new ArrayList<>();
        DeferredLayoutCache cache = new DeferredLayoutCache(job -> { throw new RejectedExecutionException(); }, Map::of, failures::add);
        assertTrue(cache.poll());
        cache.capture("new", "fresh", "Chest", layout("Chest", 1, null).slots());
        assertNotNull(cache.get("new"));
        assertEquals(1, failures.size());
    }
}
