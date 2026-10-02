package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class LayoutCachePerformanceTest {
    @TempDir Path temp;
    private static SlotLayoutData.Layout layout(String title) {
        return new SlotLayoutData.Layout(title, "ChestMenu", List.of(
                new SlotLayoutData.SlotCapture(10, 20, 0, "minecraft:stone", 64, null)));
    }
    private static class Harness {
        final AtomicLong time = new AtomicLong();
        final Queue<Runnable> jobs = new ArrayDeque<>();
        final List<Map<String, SlotLayoutData.Layout>> writes = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();
        final Map<String, SlotLayoutData.Layout> live = new LinkedHashMap<>();
        int snapshots;
        boolean fail;
        final DeferredLayoutWriter writer = new DeferredLayoutWriter(jobs::add, time::get, snapshot -> {
            if (fail) throw new IOException("simulated failure");
            writes.add(snapshot);
        }, errors::add);
        void tick() { writer.tick(() -> { snapshots++; return Map.copyOf(live); }); }
        void change(String title) { live.put("1|2|3", layout(title)); writer.changed(); }
    }
    @Test void idleTicksNeverCopyOrSerializeCache() {
        var h = new Harness();
        for (int i = 0; i < 10000; i++) h.tick();
        assertEquals(0, h.snapshots);
        assertTrue(h.jobs.isEmpty());
    }
    @Test void recaptureAndAnchorBurstCollapseIntoOneBackgroundSave() {
        var h = new Harness();
        for (int i = 0; i < 200; i++) { h.time.set(i); h.change("capture " + i); h.tick(); }
        assertEquals(0, h.snapshots);
        h.time.addAndGet(1000); h.tick();
        assertEquals(1, h.snapshots);
        assertTrue(h.writes.isEmpty(), "tick must not execute the JSON writer");
        h.jobs.remove().run(); h.tick();
        assertEquals("capture 199", h.writes.get(0).get("1|2|3").title());
        for (int i = 0; i < 100; i++) h.tick();
        assertEquals(1, h.writes.size());
    }
    @Test void sustainedChangesStillSaveWithinMaximumDelay() {
        var h = new Harness();
        for (int i = 0; i <= 5000; i += 100) { h.time.set(i); h.change("capture " + i); h.tick(); }
        assertEquals(1, h.jobs.size());
    }
    @Test void slowWriterDoesNotQueueEveryTickOrLoseNewerChanges() {
        var h = new Harness(); h.change("old"); h.time.set(1000); h.tick();
        h.change("new"); h.time.set(9000);
        for (int i = 0; i < 1000; i++) h.tick();
        assertEquals(1, h.jobs.size());
        h.jobs.remove().run(); h.tick();
        assertEquals("old", h.writes.get(0).get("1|2|3").title());
        assertEquals(1, h.jobs.size());
        h.jobs.remove().run(); h.tick();
        assertEquals("new", h.writes.get(1).get("1|2|3").title());
    }
    @Test void failedWriteRetainsChangesAndBacksOffBeforeRetry() {
        var h = new Harness(); h.fail = true; h.change("kept"); h.time.set(1000); h.tick();
        h.jobs.remove().run(); h.tick();
        assertEquals(1, h.errors.size());
        h.time.set(5999); h.tick(); assertTrue(h.jobs.isEmpty());
        h.fail = false; h.time.set(6000); h.tick(); h.jobs.remove().run(); h.tick();
        assertEquals("kept", h.writes.get(0).get("1|2|3").title());
    }
    @Test void executorRejectionDoesNotLoseDirtyState() {
        AtomicLong time = new AtomicLong(); List<Throwable> errors = new ArrayList<>();
        var writer = new DeferredLayoutWriter(task -> { throw new RejectedExecutionException(); },
                time::get, snapshot -> fail("must not write"), errors::add);
        writer.changed(); time.set(1000); writer.tick(Map::of);
        writer.tick(Map::of); assertEquals(1, errors.size());
        time.set(6000); writer.tick(Map::of); assertEquals(2, errors.size());
    }
    @Test void actualFileEncodingRunsOnWorkerAndReplacesValidJson() throws Exception {
        Path file = temp.resolve("slot-layouts.json"); Files.writeString(file, "{\"old\":{\"slots\":[]}}");
        var executor = Executors.newSingleThreadExecutor();
        try {
            Thread caller = Thread.currentThread();
            var saved = new CompletableFuture<Void>(); var time = new AtomicLong();
            var writer = new DeferredLayoutWriter(executor, time::get, snapshot -> {
                assertNotSame(caller, Thread.currentThread());
                DeferredLayoutWriter.writeAtomically(file, snapshot); saved.complete(null);
            }, saved::completeExceptionally);
            writer.changed(); time.set(1000); writer.tick(() -> Map.of("new", layout("Saved")));
            saved.get(5, TimeUnit.SECONDS);
            assertEquals(Map.of("new", layout("Saved")), SlotLayoutData.readAll(Files.readString(file)));
            try (var files = Files.list(temp)) { assertEquals(1, files.count()); }
        } finally { executor.shutdownNow(); }
    }
    @Test void repeatedCaptureAndAnchorPreserveLearnedMappingWithoutChanges() {
        var original = layout("Chest");
        assertFalse(SlotLayoutData.preferCapture(original.slots(), original.slots()));
        var anchor = new SlotLayoutData.SlotAnchor("ChestMenu", 0, 0, 10, 20, 4);
        var learned = SlotLayoutData.withAnchor(original, anchor);
        assertSame(learned, SlotLayoutData.withAnchor(learned, anchor));
        assertEquals(learned, SlotLayoutData.capturedLayout("Chest", "ChestMenu", original.slots(), learned));
    }
    @Test void snapshotsCannotBeMutatedThroughCallerLists() {
        var slots = new ArrayList<>(layout("x").slots());
        var record = new SlotLayoutData.Layout("x", "ChestMenu", slots);
        slots.clear(); assertEquals(1, record.slots().size());
        assertThrows(UnsupportedOperationException.class, () -> record.slots().clear());
    }
    @Test void spatialIndexMatchesOriginalDeduplicationIncludingNegativeCoordinates() {
        var random = new Random(19); var expected = new ArrayList<SlotLayoutData.SlotCapture>();
        var collector = new SlotCaptureCollector();
        for (int i = 0; i < 2000; i++) {
            var slot = new SlotLayoutData.SlotCapture(random.nextInt(500) - 250, random.nextInt(500) - 250, i, "", 0, null);
            boolean keep = expected.stream().noneMatch(s -> {
                long dx = s.x() - slot.x(), dy = s.y() - slot.y(); return dx * dx + dy * dy < 100;
            });
            assertEquals(keep, collector.add(slot));
            if (keep) expected.add(slot);
        }
        assertEquals(expected, collector.slots());
    }
}
