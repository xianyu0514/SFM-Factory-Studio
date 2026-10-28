package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Client-thread coordinator. Only immutable snapshots reach the background writer. */
public final class DeferredLayoutWriter {
    public static final long QUIET_MS = 1000, MAX_DELAY_MS = 5000, RETRY_MS = 5000;
    @FunctionalInterface public interface Sink {
        void write(Map<String, SlotLayoutData.Layout> snapshot) throws IOException;
    }
    private final Executor executor;
    private final LongSupplier clock;
    private final Sink sink;
    private final Consumer<Throwable> onFailure;
    private long revision, savedRevision, writingRevision;
    private long firstChange, lastChange, retryAfter;
    private CompletableFuture<Void> active;

    public DeferredLayoutWriter(Executor executor, LongSupplier clock, Sink sink, Consumer<Throwable> onFailure) {
        this.executor = executor;
        this.clock = clock;
        this.sink = sink;
        this.onFailure = onFailure;
    }
    public void changed() {
        long now = clock.getAsLong();
        if (revision == savedRevision || active != null && revision == writingRevision) firstChange = now;
        revision++;
        lastChange = now;
    }
    /** Idle ticks do no copying, encoding or file I/O. At most one save is in flight. */
    public void tick(Supplier<Map<String, SlotLayoutData.Layout>> snapshot) {
        long now = clock.getAsLong();
        if (active != null) {
            if (!active.isDone()) return;
            try {
                active.join(); // Already completed; never waits for the writer.
                savedRevision = writingRevision;
            } catch (RuntimeException failure) {
                retryAfter = now + RETRY_MS;
                onFailure.accept(failure.getCause() == null ? failure : failure.getCause());
            }
            active = null;
        }
        if (revision == savedRevision || now < retryAfter) return;
        if (now - lastChange < QUIET_MS && now - firstChange < MAX_DELAY_MS) return;
        try {
            var captured = snapshot.get();
            writingRevision = revision;
            active = CompletableFuture.runAsync(() -> {
                try { sink.write(captured); }
                catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            }, executor);
        } catch (RuntimeException failure) {
            retryAfter = now + RETRY_MS;
            onFailure.accept(failure);
        }
    }
    /** Encoding and replacement both run on the writer thread, never the render thread. */
    public static void writeAtomically(Path file, Map<String, SlotLayoutData.Layout> snapshot) throws IOException {
        String json = SlotLayoutData.writeAll(snapshot);
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "slot-layouts-", ".tmp");
        try {
            Files.writeString(temporary, json);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
