package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Client-thread-owned cache. Only reading/parsing the initial file runs on the I/O worker. */
public final class DeferredLayoutCache {
    @FunctionalInterface
    public interface Source { Map<String, SlotLayoutData.Layout> read() throws Exception; }

    private final Executor executor;
    private final Source source;
    private final Consumer<Throwable> onFailure;
    private final Map<String, SlotLayoutData.Layout> layouts = new LinkedHashMap<>();
    private final Map<String, Set<String>> byMenu = new HashMap<>();
    private final List<Runnable> pending = new ArrayList<>();
    private CompletableFuture<Map<String, SlotLayoutData.Layout>> loading;
    private boolean ready;
    private boolean changed;

    public DeferredLayoutCache(Executor executor, Source source, Consumer<Throwable> onFailure) {
        this.executor = executor;
        this.source = source;
        this.onFailure = onFailure;
    }

    /** Never waits for disk. Replay live events only after the disk baseline is installed. */
    public boolean poll() {
        if (ready) return true;
        if (loading == null) {
            try {
                loading = CompletableFuture.supplyAsync(() -> {
                    try { return source.read(); }
                    catch (Exception failure) { throw new CompletionException(failure); }
                }, executor);
            } catch (RuntimeException failure) {
                loading = CompletableFuture.failedFuture(failure);
            }
        }
        if (!loading.isDone()) return false;
        try { loading.join().forEach(this::put); }
        catch (RuntimeException failure) { onFailure.accept(failure); }
        ready = true;
        loading = null;
        for (Runnable operation : pending) operation.run();
        pending.clear();
        return true;
    }

    private void mutate(Runnable operation) {
        if (poll()) operation.run();
        else pending.add(operation);
    }

    private void put(String key, SlotLayoutData.Layout layout) {
        SlotLayoutData.Layout old = layouts.put(key, layout);
        if (old != null && !old.menuClass().equals(layout.menuClass())) {
            Set<String> keys = byMenu.get(old.menuClass());
            keys.remove(key);
            if (keys.isEmpty()) byMenu.remove(old.menuClass());
        }
        byMenu.computeIfAbsent(layout.menuClass(), ignored -> new LinkedHashSet<>()).add(key);
    }

    public void capture(String key, String title, String menuClass, List<SlotLayoutData.SlotCapture> slots) {
        List<SlotLayoutData.SlotCapture> snapshot = List.copyOf(slots);
        mutate(() -> {
            SlotLayoutData.Layout old = layouts.get(key);
            if (!SlotLayoutData.preferCapture(snapshot, old == null ? null : old.slots())) return;
            SlotLayoutData.Layout update = SlotLayoutData.capturedLayout(title, menuClass, snapshot, old);
            if (update.equals(old)) return;
            put(key, update);
            changed = true;
        });
    }

    public void applyAnchor(SlotLayoutData.SlotAnchor anchor) {
        mutate(() -> {
            Set<String> keys = byMenu.get(anchor.menuClass());
            if (keys == null) return;
            for (String key : keys) {
                SlotLayoutData.Layout old = layouts.get(key);
                SlotLayoutData.Layout update = SlotLayoutData.withAnchor(old, anchor);
                if (update.equals(old)) continue;
                layouts.put(key, update);
                changed = true;
            }
        });
    }

    public void setNoExposure(String key) {
        mutate(() -> {
            SlotLayoutData.Layout old = layouts.get(key);
            if (old == null || old.noExposure()) return;
            layouts.put(key, SlotLayoutData.withNoExposure(old, true));
            changed = true;
        });
    }

    public SlotLayoutData.Layout get(String key) {
        poll();
        return layouts.get(key);
    }

    public boolean takeChanged() {
        boolean result = changed;
        changed = false;
        return result;
    }

    public Map<String, SlotLayoutData.Layout> snapshot() {
        if (!ready) throw new IllegalStateException("Initial layouts are still loading");
        return Collections.unmodifiableMap(new LinkedHashMap<>(layouts));
    }
}
