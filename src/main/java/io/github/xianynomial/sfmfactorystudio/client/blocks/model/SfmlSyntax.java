package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

/**
 * SFML 语法白名单——「什么能进程序」的唯一裁决点。
 *
 * <p>背景：资源标签 id 含 <code>-</code> 或 <code>.</code> 时（如某些模组
 * 分类），序列化会静默降级成 {@code #*}、诊断再拦保存，用户在两端都看到
 * 莫名其妙的结果。此类把散落在选择器 / 手动输入 / JEI 拖入 / 序列化 /
 * 诊断五处的规则收敛为一处：
 *
 * <ul>
 *   <li>入口（选择器、手输、拖入）用 {@link #isEncodable} 提前拦截——
 *       写不进程序的值根本不该被选中；</li>
 *   <li>序列化（BlocksToSfml）与诊断（ProgramDiagnostics）引用同一常量，
 *       保证"拦截的"和"报错的"永远一致；</li>
 *   <li>SFM 上游改文法时只改本类。</li>
 * </ul>
 *
 * <p>规则依据：SFM 4.34 SFML.g4 的 IDENTIFIER 仅允许
 * <code>[a-zA-Z0-9_*]</code>；标签 id 形态为
 * {@code namespace/path} 或 {@code path/sub}（冒号与斜杠为结构分隔符）。
 */
public final class SfmlSyntax {
    private SfmlSyntax() {
    }

    /**
     * 标签 id（不含 # 前缀）的完整形态：
     * {@code ns / path( / path)*}，ns 可省略；每段可用 {@code *} 通配。
     * 与 BlocksToSfml.normalizeTagMatcher / ProgramDiagnostics 的历史正则同规则。
     */
    public static final String TAG_PATTERN =
            "[a-zA-Z_*][a-zA-Z0-9_]*(?::[a-zA-Z_*][a-zA-Z0-9_]*)?(?:/[a-zA-Z_*][a-zA-Z0-9_]*)*";

    /** 单段合法字符：字母 / 数字 / 下划线 / 星号（SFML IDENTIFIER 全集）。 */
    public static boolean isIdentifierText(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '*';
            if (!ok) return false;
        }
        return true;
    }

    /**
     * 标签 id 是否可无损写进 SFML（不含 # 前缀）。false 的值必须被
     * 选择器隐藏、被手动输入拒绝——任何降级都会产生错误的筛选语义。
     */
    public static boolean isEncodableTag(String tagId) {
        if (tagId == null) return false;
        String s = tagId.trim();
        if (s.isEmpty()) return false;
        // 结构分隔符切开后每段都必须是纯 IDENTIFIER 字符
        for (String part : s.split("[:/]")) {
            if (!isIdentifierText(part)) return false;
        }
        return true;
    }

    /**
     * 手动输入的标签匹配串校验：容忍前导 #，其余同 {@link #isEncodableTag}。
     * 返回规范化后的匹配串，非法返回 null。
     */
    public static String sanitizeTagMatcher(String input) {
        if (input == null) return null;
        String s = input.trim().replaceFirst("^#+", "");
        return isEncodableTag(s) ? s : null;
    }
}
