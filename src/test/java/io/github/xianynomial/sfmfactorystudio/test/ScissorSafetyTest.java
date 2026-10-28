package io.github.xianynomial.sfmfactorystudio.test;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 裁剪区（scissor）释放护栏。
 *
 * <p>为什么需要它：{@code enableScissor} 改的是全局渲染状态。若绘制中途抛异常、
 * {@code disableScissor} 被跳过，裁剪矩形会一直留到后续所有绘制上——玩家看到
 * 整个游戏画面被裁成界面的一小块，只能重启客户端（本模组 2026-10-02 已因此出过一次事故）。
 * 正确写法只有一种：{@code clip(...)} 之后用 {@code try/finally} 包住绘制，在 finally 里释放。
 *
 * <p>断言一（严格）：每一处 {@code disableScissor()} 都必须紧跟在 {@code } finally {} 之后
 * （允许中间隔空行或注释）。这条能挡住"改回裸 disable"的回归，零误报。
 *
 * <p>断言二（粗粒度）：每一处 {@code enableScissor} 之后 300 行内必须出现 {@code disableScissor}，
 * 用来抓"整段忘记释放"。300 行远大于当前最长方法的跨度，不会误报。
 */
public class ScissorSafetyTest {

    private static final String ENABLE = ".enableScissor(";
    private static final String DISABLE = ".disableScissor()";
    /** 一处 enable 到对应 disable 的最大合理跨度（当前最长方法约 250 行）。 */
    private static final int MAX_ENABLE_TO_DISABLE_LINES = 300;

    @Test
    public void everyDisableScissorIsInsideFinally() throws IOException {
        List<String> failures = new ArrayList<>();
        for (Path p : javaSources()) {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).contains(DISABLE)) continue;
                int j = i - 1;
                while (j >= 0 && isSkippable(lines.get(j))) j--;
                String prev = j >= 0 ? lines.get(j).trim() : "";
                if (!prev.endsWith("finally {")) {
                    failures.add(p + ":" + (i + 1)
                            + "  disableScissor 不在 finally 内（紧邻上一行是「" + prev + "」）");
                }
            }
        }
        assertTrue(failures.isEmpty(),
                "scissor 必须在 try/finally 的 finally 里释放，否则异常时裁剪会泄漏到全屏:\n"
                        + String.join("\n", failures));
    }

    @Test
    public void everyEnableScissorIsReleasedWithinReasonableSpan() throws IOException {
        List<String> failures = new ArrayList<>();
        for (Path p : javaSources()) {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).contains(ENABLE)) continue;
                int limit = Math.min(lines.size(), i + MAX_ENABLE_TO_DISABLE_LINES);
                boolean found = false;
                for (int k = i + 1; k < limit; k++) {
                    if (lines.get(k).contains(DISABLE)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    failures.add(p + ":" + (i + 1)
                            + "  enableScissor 之后 " + MAX_ENABLE_TO_DISABLE_LINES + " 行内没有 disableScissor");
                }
            }
        }
        assertTrue(failures.isEmpty(),
                "发现只开不关的裁剪区，会把整块画面裁掉:\n" + String.join("\n", failures));
    }

    /** 空行与纯注释行在判断「紧邻」时跳过。 */
    private static boolean isSkippable(String line) {
        String s = line.trim();
        return s.isEmpty() || s.startsWith("//") || s.startsWith("/*") || s.startsWith("*");
    }

    private static List<Path> javaSources() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(Paths.get("src/main/java"))) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                if (p.toString().endsWith(".java")) out.add(p);
            }
        }
        return out;
    }
}
