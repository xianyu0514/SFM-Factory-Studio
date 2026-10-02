package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import io.github.xianynomial.sfmfactorystudio.SFMGui;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 编辑器 JSON 存储的合并后台写盘（方块布局、草稿）。同款模式见
 * {@link DeferredLayoutWriter}：客户端线程只做协调，读改写合并与落盘全部
 * 在专用单线程执行——拖拽结束等事件路径不再有同步全文件写。
 *
 * <p>语义：{@link Core#update} 入队"单键值"（{@code JsonNull} = 删除该键）；后台任务把
 * 待写键合并进当前文件内容后原子替换，其他管理器的条目原样保留。写盘成功后按值移除
 * 已落盘的键——期间对同一键的新修改会留待下一轮，旧快照不会覆盖新更新。
 * {@link Core#flushSync} 供关闭路径同步收尾，退出绝不丢数据。
 *
 * <p>读取走最后已知内容缓存；首次访问同步读一次（与旧行为相同的单次成本）。
 * 缓存读取不叠加尚未落盘的待写键——当前调用方只在初始化阶段读（此时无待写）。
 */
public final class EditorFileStore {
    public static final long QUIET_MS = 500, MAX_DELAY_MS = 3000, RETRY_MS = 5000;
    private static final Gson GSON = new Gson();

    private static final Executor IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "sfmstudio-editor-io");
        thread.setDaemon(true);
        return thread;
    });
    private static final LongSupplier CLOCK = () -> System.nanoTime() / 1_000_000L;
    private static final Map<String, Core> CORES = new ConcurrentHashMap<>();

    private EditorFileStore() {
    }

    /** Enqueue one key (JsonNull deletes it); a debounced background write follows. */
    public static void update(Path file, String key, JsonElement value) {
        core(file).update(key, value);
    }

    /** Last known file content (cached); first access reads synchronously. Null when absent. */
    public static String readString(Path file) {
        return core(file).read();
    }

    /** Close-path durability: perform pending merges synchronously. */
    public static void flushSync(Path file) {
        core(file).flushSync();
    }

    /** Client tick driver: coordinates background writes, never serializes on the caller. */
    public static void tickAll() {
        for (Core core : CORES.values()) core.tick();
    }

    private static Core core(Path file) {
        return CORES.computeIfAbsent(file.toAbsolutePath().toString(),
                ignored -> new Core(IO, CLOCK, file, json -> writeAtomically(file, json),
                        failure -> SFMGui.LOGGER.warn("Failed to write editor store {}; retry scheduled",
                                file.getFileName(), failure)));
    }

    /** Injectable core (client-thread-owned); only encoded snapshots reach the writer. */
    public static final class Core {
        private final Executor executor;
        private final LongSupplier clock;
        private final Path file;
        private final Writer writer;
        private final Consumer<Throwable> onFailure;

        private volatile String cached;
        private boolean cachedValid;
        private final Map<String, JsonElement> pending = new LinkedHashMap<>();
        private long firstDirty, lastDirty, retryAfter;
        private CompletableFuture<Void> active;
        private Map<String, JsonElement> writtenSnapshot;
        private volatile String writtenJson;
        private long writes;

        @FunctionalInterface
        public interface Writer {
            void write(String json) throws IOException;
        }

        public Core(Executor executor, LongSupplier clock, Path file, Writer writer,
                    Consumer<Throwable> onFailure) {
            this.executor = executor;
            this.clock = clock;
            this.file = file;
            this.writer = writer;
            this.onFailure = onFailure;
        }

        public void update(String key, JsonElement value) {
            boolean idle = pending.isEmpty();
            pending.put(key, value);
            long now = clock.getAsLong();
            if (idle) firstDirty = now;
            lastDirty = now;
        }

        /** Cached content; first call reads the file once and remembers it. */
        public String read() {
            if (!cachedValid) {
                cachedValid = true;
                try {
                    cached = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
                } catch (Exception readFailure) {
                    cached = null;
                }
            }
            return cached;
        }

        public void tick() {
            if (active != null) {
                if (!active.isDone()) return;
                finishActive();
            }
            if (pending.isEmpty() || clock.getAsLong() < retryAfter) return;
            long now = clock.getAsLong();
            if (now - lastDirty < QUIET_MS && now - firstDirty < MAX_DELAY_MS) return;
            startWrite();
        }

        /** Blocks until pending merges are on disk (also awaits an in-flight write). */
        public void flushSync() {
            finishActive();
            if (!pending.isEmpty()) {
                startWrite();
                finishActive();
            }
        }

        private void finishActive() {
            if (active == null) return;
            try {
                active.join();
                for (Map.Entry<String, JsonElement> e : writtenSnapshot.entrySet()) {
                    pending.remove(e.getKey(), e.getValue());
                }
                cached = writtenJson;
                cachedValid = true;
                writes++;
            } catch (RuntimeException failure) {
                retryAfter = clock.getAsLong() + RETRY_MS;
                onFailure.accept(failure.getCause() == null ? failure : failure.getCause());
            }
            active = null;
            writtenSnapshot = null;
        }

        private void startWrite() {
            Map<String, JsonElement> snapshot = new LinkedHashMap<>(pending);
            String base = read();
            writtenSnapshot = snapshot;
            active = CompletableFuture.runAsync(() -> {
                JsonObject root = new JsonObject();
                merge(base, snapshot).forEach(root::add);
                String json = GSON.toJson(root);
                try {
                    writer.write(json);
                } catch (IOException writeFailure) {
                    throw new RuntimeException(writeFailure);
                }
                writtenJson = json;
            }, executor);
        }

        /** Merge pending keys into the base content; JsonNull values delete their key. */
        static Map<String, JsonElement> merge(String base, Map<String, JsonElement> snapshot) {
            JsonObject root = new JsonObject();
            if (base != null && !base.isBlank()) {
                try {
                    JsonElement parsed = JsonParser.parseReader(new JsonReader(new StringReader(base)));
                    if (parsed.isJsonObject()) root = parsed.getAsJsonObject();
                } catch (RuntimeException malformed) {
                    // 起点损坏时从空对象重建：待写键是唯一可恢复的事实
                }
            }
            for (Map.Entry<String, JsonElement> e : snapshot.entrySet()) {
                if (e.getValue() == null || e.getValue().isJsonNull()) root.remove(e.getKey());
                else root.add(e.getKey(), e.getValue());
            }
            Map<String, JsonElement> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) out.put(e.getKey(), e.getValue());
            return out;
        }

        /** Test/diagnostic counters. */
        public int pendingCount() {
            return pending.size();
        }

        public long writes() {
            return writes;
        }
    }

    /** Encoding and replacement both run on the writer thread, never the caller. */
    private static void writeAtomically(Path file, String json) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
