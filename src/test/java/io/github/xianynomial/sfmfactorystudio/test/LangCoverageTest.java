package io.github.xianynomial.sfmfactorystudio.test;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语言键双向覆盖护栏（2026-09-09 固化）：
 * <ul>
 *   <li>代码引用的每个键必须登记在 zh_cn 与 en_us（缺 en = 英文界面显示中文，
 *       曾在 ed.pulse_cond / when_then / when_dots 三键复发）；</li>
 *   <li>语言文件里的每个键必须有静态代码引用（孤儿键 = 死内容，曾积累十余个）。</li>
 * </ul>
 * 提取规则与键构造方式一一对应：全键 Loc / K 常量 + 后缀 / 内联前缀 helper
 * （E/T/t）/ translation()（含 NeoForge 自动派生的 .comment）/ feedback /
 * Component.translatable。新增键构造方式时同步扩展这里。
 */
public class LangCoverageTest {
    private static final String P1 = "gui.sfmfactorystudio.";
    private static final String P2 = "sfmfactorystudio.";

    private static final Pattern FULL_LOC = Pattern.compile("new Loc\\(\\s*\"((?:gui\\.sfmfactorystudio|sfmfactorystudio)\\.[^\"]+)\"(?!\\s*\\+)");
    private static final Pattern TRANSLATION = Pattern.compile("translation\\(\\s*\"((?:gui\\.sfmfactorystudio|sfmfactorystudio)\\.[^\"]+)\"(?!\\s*\\+)");
    private static final Pattern FEEDBACK = Pattern.compile("feedback\\(player,\\s*\"([^\"]+)\"");
    private static final Pattern TRANSLATABLE = Pattern.compile("Component.translatable\\(\\s*\"(gui\\.sfmfactorystudio\\.[^\"]+)\"");
    private static final Pattern K_CONSTANT = Pattern.compile("String K = \"([^\"]+)\"");
    private static final Pattern LOC_WITH_K = Pattern.compile("Loc\\(\\s*K \\+ \\s*\"([^\"]+)\"");
    private static final Pattern SUFFIX = Pattern.compile("(?<![A-Za-z0-9_])[TtEe]\\(\\s*\"([^\"]+)\"");
    private static final Pattern INLINE_PREFIX = Pattern.compile("new Loc\\(\\s*\"((?:gui\\.sfmfactorystudio|sfmfactorystudio)\\.[^\"]*)\"\\s*\\+\\s*name");

    private static void collect(Pattern p, String s, Set<String> out) {
        p.matcher(s).results().map(m -> m.group(1)).forEach(out::add);
    }

    private static Set<String> extractCodeKeys() throws IOException {
        Set<String> keys = new HashSet<>();
        try (Stream<Path> paths = Files.walk(Paths.get("src/main/java"))) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                if (!p.toString().endsWith(".java")) continue;
                String s = Files.readString(p, StandardCharsets.UTF_8);
                collect(FULL_LOC, s, keys);
                collect(TRANSLATION, s, keys);
                collect(FEEDBACK, s, keys);
                collect(TRANSLATABLE, s, keys);
                String k = null;
                var km = K_CONSTANT.matcher(s);
                while (km.find()) {
                    k = km.group(1);
                    var lk = LOC_WITH_K.matcher(s);
                    while (lk.find()) keys.add(k + lk.group(1));
                    var sf = SUFFIX.matcher(s);
                    while (sf.find()) keys.add(k + sf.group(1));
                }
                var ip = INLINE_PREFIX.matcher(s);
                while (ip.find()) {
                    String pref = ip.group(1);
                    var sf = SUFFIX.matcher(s);
                    while (sf.find()) keys.add(pref + sf.group(1));
                }
                // NeoForge 配置注释派生键：<translationKey>.comment
                Set<String> withComment = new HashSet<>(keys);
                withComment.removeIf(key -> !key.startsWith(P2));
                for (String key : withComment) keys.add(key + ".comment");
            }
        }
        return keys;
    }

    private static Map<String, String> loadLang(String lang) throws IOException {
        Path p = Paths.get("src/main/resources/assets/sfmfactorystudio/lang/" + lang + ".json");
        return new Gson().fromJson(Files.readString(p, StandardCharsets.UTF_8),
                new TypeToken<Map<String, String>>() {}.getType());
    }

    @Test
    public void everyCodeKeyIsRegisteredInBothLanguages() throws IOException {
        Set<String> code = extractCodeKeys();
        assertTrue(code.size() > 500, "提取器应抓到大规模键集（骤降=规则失效）: " + code.size());
        for (String lang : new String[]{"zh_cn", "en_us"}) {
            Map<String, String> langMap = loadLang(lang);
            List<String> missing = code.stream().filter(k -> !langMap.containsKey(k)).sorted().toList();
            assertEquals(List.of(), missing, lang + " 缺少键（英文界面会显示中文）");
        }
    }

    @Test
    public void everyLangKeyHasAStaticCodeReference() throws IOException {
        Set<String> code = extractCodeKeys();
        for (String lang : new String[]{"zh_cn", "en_us"}) {
            Map<String, String> langMap = loadLang(lang);
            List<String> orphans = langMap.keySet().stream().filter(k -> !code.contains(k)).sorted().toList();
            assertEquals(List.of(), orphans, lang + " 存在孤儿键（死内容）");
        }
    }

    @Test
    public void zhAndEnHaveIdenticalKeySets() throws IOException {
        assertEquals(new HashSet<>(loadLang("zh_cn").keySet()),
                new HashSet<>(loadLang("en_us").keySet()));
    }
}