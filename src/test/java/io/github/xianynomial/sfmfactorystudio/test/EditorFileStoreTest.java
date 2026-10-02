package io.github.xianynomial.sfmfactorystudio.test;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.EditorFileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 编辑器存储护栏：合并保留他人条目、JsonNull 删除、失败重试、关闭同步收尾。 */
public class EditorFileStoreTest {
    private static final Executor DIRECT = Runnable::run;

    private static JsonElement json(String raw) {
        return JsonParser.parseString(raw);
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content);
    }

    @Test
    public void mergePreservesOtherKeysAndNullDeletes(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("layouts.json");
        write(file, "{\"other_manager\":[[1,2]],\"mine\":[[3,4]]}");
        EditorFileStore.Core core = new EditorFileStore.Core(DIRECT, () -> 0L, file,
                json -> write(file, json), failure -> {
        });
        core.update("mine", json("[[9,9]]"));
        core.update("gone", JsonNull.INSTANCE);
        core.flushSync();
        String content = Files.readString(file);
        assertTrue(content.contains("other_manager"), "其他管理器条目必须原样保留");
        assertTrue(content.contains("[[9,9]]"), "待写键应更新");
        assertFalse(content.contains("\"gone\""), "JsonNull 应删除该键");
        assertEquals(content, core.read(), "写盘后缓存应指向最新内容");
    }

    @Test
    public void corruptedBaseRebuildsFromPending(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("layouts.json");
        write(file, "{not valid json");
        List<String> written = new ArrayList<>();
        EditorFileStore.Core core = new EditorFileStore.Core(DIRECT, () -> 0L, file,
                written::add, failure -> {
        });
        core.update("mine", json("[[1]]"));
        core.flushSync();
        assertTrue(written.get(0).contains("\"mine\":[[1]]"), "损坏起点从待写键重建");
    }

    @Test
    public void failureRetriesAndKeepsPending(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("drafts.json");
        long[] now = {0};
        int[] attempts = {0};
        EditorFileStore.Core core = new EditorFileStore.Core(DIRECT, () -> now[0], file,
                json -> {
                    if (attempts[0]++ == 0) throw new IOException("disk full");
                    try {
                        write(file, json);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, failure -> {
                });
        core.update("k", json("{\"sfml\":\"input\"}"));
        now[0] = EditorFileStore.MAX_DELAY_MS + 1;
        core.tick();
        now[0] += EditorFileStore.RETRY_MS + 1;
        core.tick();
        core.flushSync();
        String content = Files.readString(file);
        assertTrue(content.contains("input"), "重试后必须落盘");
        assertEquals(1, occurrences(content, "\"k\""), "键只写一份");
        assertEquals(2, attempts[0], "首次失败 + 重试成功 = 两次写");
    }

    private static int occurrences(String text, String needle) {
        int count = 0, from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    @Test
    public void quietWindowDelaysWrite(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("layouts.json");
        long[] now = {0};
        List<String> written = new ArrayList<>();
        EditorFileStore.Core core = new EditorFileStore.Core(DIRECT, () -> now[0], file,
                written::add, failure -> {
        });
        core.update("mine", json("[[1]]"));
        core.tick();
        assertTrue(written.isEmpty(), "安静窗口内不得写盘");
        now[0] = EditorFileStore.MAX_DELAY_MS + 1;
        core.tick();
        assertEquals(1, written.size(), "超过最长等待后必须写盘");
    }

    @Test
    public void flushSyncWritesImmediatelyWithoutTick(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("layouts.json");
        EditorFileStore.Core core = new EditorFileStore.Core(DIRECT, () -> 0L, file,
                json -> write(file, json), failure -> {
        });
        core.update("mine", json("[[1]]"));
        core.flushSync();
        assertEquals(1, core.writes(), "关闭路径同步收尾");
        assertTrue(Files.readString(file).contains("[[1]]"));
    }

    @Test
    public void emptyFileStartsClean(@TempDir Path dir) {
        Path file = dir.resolve("layouts.json");
        EditorFileStore.Core core = new EditorFileStore.Core(DIRECT, () -> 0L, file,
                json -> {
                }, failure -> {
        });
        assertNull(core.read(), "不存在的文件读取返回 null");
    }
}
