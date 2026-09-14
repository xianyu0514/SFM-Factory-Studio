package io.github.xianynomial.sfmfactorystudio.test;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 界面硬编码中文护栏（2026-09-10 i18n 收尾）。扫描 {@code src/main/java}：
 * 字符串字面量含 CJK 且不属于以下豁免 = 红（键齐全≠键被使用，比较符菜单、
 * 成本提示拼接、示例内容、"当"图标四类漏网均绕过了键系统）：
 * <ul>
 *   <li>Loc/E/T/t 构造的中文默认参数（键可写 {@code K + "..."} 表达式）——运行时按语言解析；</li>
 *   <li>{@code .comment(} / {@code .translation(} —— NeoForge 配置后备（有 translation 键）；</li>
 *   <li>{@code ExamplePrograms.java} —— 刻意的双语脚手架数据表（两种语言都有编译器护栏）；</li>
 *   <li>行内标记 {@code // i18n-ok} —— 确属开发面文字的显式豁免（需写明原因）。</li>
 * </ul>
 * 代码注释里的中文不在检查范围（本项目注释约定为中文）。
 */
public class HardcodedChineseTest {
    private static final Pattern LOC_CTOR = Pattern.compile(
            "(?<![A-Za-z])(?:new Loc|[TtEe])\\(\\s*(?:\"(?:[^\"\\\\]|\\\\.)*\"|[A-Za-z_$][\\w$.]*\\s*\\+\\s*\"(?:[^\"\\\\]|\\\\.)*\")\\s*,\\s*\"(?:[^\"\\\\]|\\\\.)*\"\\s*\\)",
            Pattern.DOTALL);
    private static final Pattern CJK_IN_QUOTES = Pattern.compile("\"[^\"]*[\\u4e00-\\u9fa5][^\"]*\"");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    @Test
    public void noHardcodedChineseOutsideLocalizationDefaults() throws IOException {
        List<String> failures = new ArrayList<>();
        Path root = Paths.get("src/main/java");
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                if (!p.toString().endsWith(".java")) continue;
                if (p.toString().endsWith("ExamplePrograms.java")) continue; // 刻意的双语脚手架数据表（见类注释）
                String original = Files.readString(p, StandardCharsets.UTF_8);
                String s = blankOut(BLOCK_COMMENT.matcher(original));
                s = blankOut(LOC_CTOR.matcher(s));
                String[] lines = s.split("\n", -1);
                String[] origLines = original.split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    String noComment = lines[i].replaceAll("//.*", "");
                    if (origLines[i].contains("i18n-ok") || noComment.contains(".comment(")
                            || noComment.contains(".translation(")) {
                        continue;
                    }
                    if (CJK_IN_QUOTES.matcher(noComment).find()) {
                        failures.add(p + ":" + (i + 1) + " " + origLines[i].trim());
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(),
                "发现绕过翻译系统的硬编码中文（改用 Loc 键 / 英文化，或行内 // i18n-ok 写明原因豁免）:\n"
                        + String.join("\n", failures));
    }

    /** 命中段整段置空但保留换行，保证后续按行扫描的行号与原文件一致。 */
    private static String blankOut(Matcher m) {
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group().replaceAll("[^\\n]", " ")));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
