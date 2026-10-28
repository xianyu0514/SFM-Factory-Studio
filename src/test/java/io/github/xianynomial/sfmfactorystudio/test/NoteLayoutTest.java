package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.EditorLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 注释脚注的行数与高度计算。
 *
 * <p>这里锁死的是整个注释方案的前提：<b>2 行以内不增加卡片高度</b>
 * （2×10 + 2×2 = 24 = FOOT_H）。一旦有人调了 NOTE_LINE_H / NOTE_PAD_V，
 * 这条断言会立刻失败，避免"零增高"悄悄失效。
 */
class NoteLayoutTest {

    private static final EditorLayout.Metrics M = EditorLayout.APPROX;

    @Test
    void blankTextTakesNoLines() {
        assertEquals(0, EditorLayout.noteLineCount(null, 300, M));
        assertEquals(0, EditorLayout.noteLineCount("", 300, M));
        assertEquals(0, EditorLayout.noteLineCount("   \n  ", 300, M), "纯空白不应占行");
        assertEquals(0, EditorLayout.noteLineCount("abc", 0, M), "宽度为 0 时不计算");
    }

    @Test
    void shortTextIsOneLine() {
        assertEquals(1, EditorLayout.noteLineCount("hello", 300, M));
        assertEquals(1, EditorLayout.noteLineCount("短注释", 300, M));
    }

    @Test
    void cjkIsNinePixelsPerChar() {
        String ten = "一二三四五六七八九十";
        assertEquals(90, M.width(ten), "中文字宽必须是 9px，与 Font.width 实测一致");
        assertEquals(1, EditorLayout.noteLineCount(ten, 90, M));
        assertEquals(2, EditorLayout.noteLineCount(ten + "一", 90, M), "第 11 个字放不下就要折行");
    }

    @Test
    void explicitNewlineForcesBreak() {
        assertEquals(2, EditorLayout.noteLineCount("上\n下", 300, M));
        assertEquals(3, EditorLayout.noteLineCount("a\nb\nc", 300, M));
    }

    @Test
    void twoLinesCostNothing() {
        // 核心断言：2 行 = 2*10 + 4 = 24 = FOOT_H，不增加卡片高度
        assertEquals(24, EditorLayout.noteHeight(2));
        assertEquals(0, EditorLayout.noteExtraHeight(2), "两行注释必须零增高");
        assertEquals(0, EditorLayout.noteExtraHeight(1));
        assertEquals(0, EditorLayout.noteExtraHeight(0));
    }

    @Test
    void threeLinesStartGrowing() {
        assertEquals(35, EditorLayout.noteHeight(3));
        assertEquals(11, EditorLayout.noteExtraHeight(3), "第 3 行开始，每行 +11px");
        assertEquals(22, EditorLayout.noteExtraHeight(4));
    }

    @Test
    void longNoteWrapsByWidth() {
        String longNote = "注".repeat(60);   // 540px
        assertEquals(2, EditorLayout.noteLineCount(longNote, 300, M));
        int narrow = EditorLayout.noteLineCount(longNote, 150, M);
        assertTrue(narrow >= 4, "更窄的卡片应当折更多行，实际 " + narrow);
    }

    @Test
    void singleWideCharStillTakesALine() {
        assertEquals(1, EditorLayout.noteLineCount("中", 3, M), "宽度比一个字还小时也不能丢字");
        assertEquals(2, EditorLayout.noteLineCount("中中", 3, M));
    }

    @Test
    void commentTextOfConcatenatesBodyComments() {
        BProgram.TimerTrigger trigger = new BProgram.TimerTrigger();
        trigger.body.add(new BProgram.Statement.Comment("第一条"));
        trigger.body.add(new BProgram.Statement.Input());
        trigger.body.add(new BProgram.Statement.Comment("第二条"));

        assertEquals("第一条\n第二条", EditorLayout.commentTextOf(trigger),
                "多条注释按顺序拼接、用换行分隔");
    }

    @Test
    void commentTextOfIgnoresBlankComments() {
        BProgram.TimerTrigger trigger = new BProgram.TimerTrigger();
        trigger.body.add(new BProgram.Statement.Comment("   "));
        assertEquals("", EditorLayout.commentTextOf(trigger), "空白注释视同没有注释");
    }
}
