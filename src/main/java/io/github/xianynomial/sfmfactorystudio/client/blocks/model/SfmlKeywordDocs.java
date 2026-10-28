package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SFML 关键词悬停文档（双语数据表，同 {@link ExamplePrograms} 模式）：
 * 鼠标在代码编辑器里停在关键词上时浮出说明与示例，代码模式对新手自解释。
 *
 * <p>数据移植并翻译自 TeamDman 官方 SFML VSCode 扩展（MPL-2.0，platform/visual-studio-code
 * 的 tooltip.ts），then/do/end 与若干措辞为本项目补充；示例行是 SFML 代码，保留英文原样。
 */
public final class SfmlKeywordDocs {
    /** 一条关键词文档：中英说明 + 可运行示例。 */
    public record Entry(String keyword, String en, String zh, List<String> examples) {
        public String description(boolean english) {
            return english ? en : zh;
        }
    }

    private static final String RR_EN = "Round robin: distribute across multiple outputs in turn"
            + " (one at a time, slower). \"by block\" rotates per block, \"by label\" per label";
    private static final String RR_ZH = "轮流分配：多个输出之间轮流发送（一次只发一个，较慢）。"
            + "by block 按方块轮流，by label 按标签轮流";
    private static final List<String> RR_EX = List.of(
            "output to chest round robin by block",
            "input from interface1, interface2 round robin by label");

    private static final List<Entry> ENTRIES = List.of(
            new Entry("if",
                    "Conditional statement: executes the block when the expression evaluates to true",
                    "条件语句：表达式为真时执行其中的代码块",
                    List.of("if redstone > 5 then ... end", "if chest has lt 10 coal then ... end")),
            new Entry("else",
                    "Optional branch of an if statement, taken when the condition is false",
                    "否则分支：条件为假时执行",
                    List.of("if redstone > 5 then ... else ... end")),
            new Entry("then",
                    "Starts the branch of an if statement",
                    "那么：if 条件成立时执行的分支由此开始",
                    List.of("if chest has gt 10 coal then ... end")),
            new Entry("end",
                    "Closes an if or every structure",
                    "结束：关闭 if/every 结构",
                    List.of("every 20 ticks do ... end")),
            new Entry("overall",
                    "Compares the condition against all bound blocks combined (same as writing nothing)",
                    "整体判断：所有标签方块合计后比较（与不写效果相同）",
                    List.of("if overall chest has > 1000 stone then ... end")),
            new Entry("some",
                    "True when at least one of the bound blocks meets the condition",
                    "任一判断：至少一个标签方块满足条件即成立",
                    List.of("if some chest has < 64 coal then ... end")),
            new Entry("one",
                    "True when exactly one of the bound blocks meets the condition",
                    "唯一判断：恰好一个标签方块满足条件才成立",
                    List.of("if one chest has < 64 coal then ... end")),
            new Entry("every",
                    "Creates a timed trigger",
                    "定时触发：按周期重复执行",
                    List.of("every 5 ticks do ... end", "every 10 ticks do ... end",
                            "every redstone pulse do ... end", "every second do ... end")),
            new Entry("do",
                    "Starts the body of an every loop",
                    "循环体：every 的执行体由此开始",
                    List.of("every 20 ticks do ... end")),
            new Entry("each",
                    "Applies the operation to every matching element individually",
                    "逐个应用：对每个匹配的标签方块分别执行",
                    List.of("input from each chest", "if each chest has > 0 then ... end")),
            new Entry("not",
                    "Negates the expression (true becomes false and vice versa)",
                    "逻辑非：真变假、假变真",
                    List.of("if not (chest has lt 5 coal) then ... end")),
            new Entry("and",
                    "Logical conjunction: both conditions must hold",
                    "逻辑与：两个条件都为真才成立",
                    List.of("if chest has lt 5 coal and furnace has lt 5 iron_* then ... end")),
            new Entry("or",
                    "Logical disjunction: either condition may hold",
                    "逻辑或：任一条件为真即成立",
                    List.of("if chest has lt 5 coal or chest has eq 5 charcoal then ... end")),
            cmp("gt", "Greater than comparison", "大于"),
            sym(">", "Greater than comparison", "大于"),
            cmp("lt", "Less than comparison", "小于"),
            sym("<", "Less than comparison", "小于"),
            cmp("eq", "Equality comparison", "等于"),
            sym("=", "Equality comparison", "等于"),
            cmp("le", "Less than or equal comparison", "小于等于"),
            sym("<=", "Less than or equal comparison", "小于等于"),
            cmp("ge", "Greater than or equal comparison", "大于等于"),
            sym(">=", "Greater than or equal comparison", "大于等于"),
            new Entry("input",
                    "Extracts contents from inventories (the block editor calls this 取出)",
                    "取出：从容器取出内容",
                    List.of("input from chest", "input fluid::, item:: from interface",
                            "input fe:: from \"mek_cube\" top side")),
            new Entry("output",
                    "Sends contents to inventories (the block editor calls this 存入)",
                    "存入：向容器放入内容",
                    List.of("output to chest", "output fluid::, item:: to interface",
                            "output fe:: to \"mek_cube\" top side")),
            new Entry("slots",
                    "Restricts the operation to specific inventory slots",
                    "槽位限定：只操作列出的槽位",
                    List.of("output fluid:: to furnace slots 1-3", "input from chest slots 5,9,13")),
            new Entry("retain",
                    "On input: keeps at least N in the source. On output: tops up to at most N",
                    "保留：取出时在源容器至少留 N 个；存入时最多补到 N 个",
                    List.of("input retain 64 coal from chest", "output retain 4 coal to furnace")),
            new Entry("except",
                    "Excludes specific resources from the operation",
                    "排除：把列出的资源排除在操作外",
                    List.of("input * except cobblestone, dirt from chest",
                            "output fluid:: except fluid::lava to interface")),
            new Entry("forget",
                    "Clears previous input records; can also unbind labels",
                    "清空取出记录：也可用于清除标签绑定",
                    List.of("forget", "forget chest")),
            new Entry("with",
                    "Only moves resources that have the given tag",
                    "按标签筛选：只操作带有该标签的资源",
                    List.of("input with #minecraft:logs", "output with #c:my_tag")),
            new Entry("without",
                    "Only moves resources lacking the given tag",
                    "反向筛选：排除带有该标签的资源",
                    List.of("input without #minecraft:logs", "output without #c:my_tag")),
            new Entry("round", RR_EN, RR_ZH, RR_EX),
            new Entry("robin", RR_EN, RR_ZH, RR_EX),
            new Entry("by", RR_EN, RR_ZH, RR_EX),
            side("top", "top", "顶"),
            side("bottom", "bottom", "底"),
            side("north", "north", "北"),
            side("south", "south", "南"),
            side("east", "east", "东"),
            side("west", "west", "西"),
            new Entry("side",
                    "Specifies a direction for the operation; \"each side\" means every side",
                    "侧面限定：与方向词连用；each side 表示所有面",
                    List.of("input from machine top side", "input from interface each side")),
            new Entry("tick",
                    "One game tick (0.05 s). Only usable with energy without config changes",
                    "一刻（0.05 秒）：不做配置更改时仅能量操作可用",
                    List.of("every tick do")),
            new Entry("ticks",
                    "Time unit: 1 tick = 0.05 seconds",
                    "时间单位：1 刻 = 0.05 秒",
                    List.of("every 5 ticks do ... end", "every 40 ticks do ... end")),
            new Entry("second",
                    "Time unit: equals 20 ticks",
                    "时间单位：1 秒 = 20 刻",
                    List.of("every second do")),
            new Entry("seconds",
                    "Time unit in seconds",
                    "时间单位（秒）",
                    List.of("every 2 seconds do", "every 50 seconds output *")),
            new Entry("redstone",
                    "Reads the redstone power level on the manager block",
                    "红石信号：读取管理器方块上的红石强度",
                    List.of("if redstone > 0 then", "every redstone pulse do")),
            new Entry("pulse",
                    "Triggers when the redstone signal on the manager block changes",
                    "红石脉冲：管理器方块信号变化时触发",
                    List.of("every redstone pulse do")),
            new Entry("name",
                    "Names the current program (optional)",
                    "给程序命名（可选）",
                    List.of("name \"Redstone factory v3\""))
    );

    private static Entry cmp(String keyword, String en, String zh) {
        return new Entry(keyword, en, zh,
                List.of("if redstone " + keyword + " 5 then ... end",
                        "if chest has " + keyword + " 10 coal then ... end"));
    }

    private static Entry sym(String keyword, String en, String zh) {
        return new Entry(keyword, en, zh,
                List.of("if redstone " + keyword + " 5 then ... end",
                        "if chest has " + keyword + " 10 coal then ... end"));
    }

    private static Entry side(String keyword, String enName, String zhName) {
        return new Entry(keyword,
                "Specifies the " + enName + " side of a block",
                "方向限定：指定方块的" + zhName + "面",
                List.of("input from machine " + keyword + " side",
                        "output to furnace " + keyword + " slots 1-3"));
    }

    private static volatile Map<String, Entry> byKeyword;

    private SfmlKeywordDocs() {
    }

    private static Map<String, Entry> index() {
        Map<String, Entry> map = byKeyword;
        if (map == null) {
            map = new HashMap<>();
            for (Entry entry : ENTRIES) map.putIfAbsent(entry.keyword().toLowerCase(Locale.ROOT), entry);
            byKeyword = map;
        }
        return map;
    }

    /** Case-insensitive keyword lookup; null when the word has no documentation. */
    public static Entry lookup(String word) {
        if (word == null || word.isEmpty()) return null;
        return index().get(word.toLowerCase(Locale.ROOT));
    }

    /** 按当前游戏语言选择（非 zh 前缀一律英文）。headless 测试 JVM 回退中文。 */
    public static boolean preferEnglish() {
        try {
            return !net.minecraft.client.Minecraft.getInstance()
                    .getLanguageManager().getSelected().startsWith("zh");
        } catch (Throwable t) {
            return false;
        }
    }

    /** Comparison-symbol character: forms its own token next to word characters. */
    public static boolean symbol(char c) {
        return c == '<' || c == '>' || c == '=';
    }

    /** Word character for hover extraction (same rule as the editor's word selection). */
    public static boolean word(char c) {
        return Character.isLetterOrDigit(c) || "_:/#.*-".indexOf(c) >= 0;
    }

    /**
     * The hover token under a text position: a word run, or a comparison-symbol
     * run such as "&lt;=" inside "a&lt;=b". Null on whitespace or unknown characters.
     */
    public static String wordAt(String text, int position) {
        if (text == null || text.isEmpty()) return null;
        int p = Math.max(0, Math.min(position, text.length() - 1));
        char at = text.charAt(p);
        if (Character.isWhitespace(at)) return null;
        boolean symbols = symbol(at);
        int start = p, end = p + 1;
        while (start > 0 && member(text.charAt(start - 1), symbols)) start--;
        while (end < text.length() && member(text.charAt(end), symbols)) end++;
        return text.substring(start, end);
    }

    private static boolean member(char c, boolean symbols) {
        return symbols ? symbol(c) : word(c);
    }

    /** Whole-token, case-sensitive occurrences of word in text: [start, end) pairs. */
    public static List<int[]> occurrences(String text, String word) {
        List<int[]> out = new ArrayList<>();
        if (text == null || word == null || word.isEmpty()) return out;
        boolean symbols = symbol(word.charAt(0));
        int from = 0;
        while (true) {
            int hit = text.indexOf(word, from);
            if (hit < 0) return out;
            int end = hit + word.length();
            boolean leftOk = hit == 0 || !member(text.charAt(hit - 1), symbols);
            boolean rightOk = end >= text.length() || !member(text.charAt(end), symbols);
            if (leftOk && rightOk) out.add(new int[]{hit, end});
            from = hit + 1;
        }
    }

    /** Number of documented keywords (data-integrity guard). */
    public static int size() {
        return ENTRIES.size();
    }
}
