package io.github.xianynomial.sfmfactorystudio.client.blocks;

/**
 * Glass light theme of the block editor: panel/canvas/card surfaces, text
 * and accent colors, plus chrome metrics (content-space metrics live in
 * {@code EditorLayout}). Near-opaque on purpose — translucent glass over
 * 1.21's blurred world reads as "the whole UI is blurry".
 */
public final class BlockTheme {
    public static final int G_PANEL = 0xF6EAF0F7;         // panel (~96% opacity)
    public static final int G_CANVAS = 0xF2E3EAF3;        // canvas (~95%)
    public static final int G_DOT = 0x509FB4CC;           // dot grid
    public static final int G_CARD = 0xFFFFFFFF;          // block card (opaque, crisp text)
    public static final int G_CARD_TRANS = 0xF6FFFFFF;    // palette cards (opaque)
    public static final int G_BORDER = 0xFFC9D4E2;
    public static final int G_BORDER_SOFT = 0x80AEBDCD;
    public static final int G_SHADOW = 0x30203A5A;
    public static final int C_TEXT = 0xFF1B2432;
    public static final int C_TEXT_SUB = 0xFF5C6779;
    public static final int C_PILL = 0xEDF4F9FB;
    public static final int C_PILL_BORDER = 0xFFBCC9D8;
    public static final int C_PILL_HOVER = 0xFFE0EBFB;
    public static final int C_SLOT = 0x8C8A93A6;          // vanilla-ish translucent slot
    public static final int C_SAVE = 0xE60FA968;
    public static final int C_SAVE_H = 0xE60C8F58;
    public static final int C_DIRTY = 0xFFE0483E;
    public static final int C_SELECT = 0xFF2F6FED;
    public static final int C_BAND = 0x302F6FED;
    public static final int C_BAND_BORDER = 0xFF2F6FED;
    public static final int C_ERR = 0xFFD13438;
    public static final int C_WARN = 0xFFB45309;

    public static final int A_TIMER = 0xFFF59E0B;
    public static final int A_PULSE = 0xFFEF4444;
    public static final int A_INPUT = 0xFF10B981;
    public static final int A_OUTPUT = 0xFF3B82F6;
    public static final int A_ENERGY = 0xFFF59E0B;
    public static final int A_FORGET = 0xFF64748B;
    public static final int A_IF = 0xFF8B5CF6;
    public static final int A_RAW = 0xFF475569;
    public static final int A_COMMENT = 0xFFEAB308;

    // ---- chrome metrics (screen px) -------------------------------------------
    public static final int PALETTE_W = 128;
    public static final int TOOLBAR_H = 28;
    public static final int ISSUES_W = 236;
    public static final int CANVAS_PAD = 12;

    private BlockTheme() {
    }
}
