package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.EditorLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Guards free horizontal card placement: cards side by side must survive a
 * release (overlap resolution may only push cards DOWN that actually
 * intersect in both axes) and dragging must move x live.
 */
public class SideBySideTest {

    @Test
    public void sideBySideCardsStaySideBySideAfterRelease() {
        BProgram p = new BProgram();
        BProgram.TimerTrigger a = new BProgram.TimerTrigger();
        a.body.add(new BProgram.Statement.Input());
        BProgram.TimerTrigger b = new BProgram.TimerTrigger();
        b.body.add(new BProgram.Statement.Input());
        p.triggers.add(a);
        p.triggers.add(b);

        EditorLayout el = new EditorLayout();
        el.setProgram(p);
        el.relayout(false, -1);  // initial auto layout (single column)

        // Drop b to the right of a (x gap 400 > card width 380 → no overlap)
        int[] pa = el.cardPosOf(a.id);
        el.setCardPos(b.id, pa[0] + 400, pa[1]);
        el.relayout(false, b.id);  // release: b is the protected card

        int[] rb = el.cardRectOf(b.id);
        assertNotNull(rb);
        assertEquals(pa[0] + 400, rb[0], "横排：b 的 x 应保持在右侧");
        assertEquals(pa[1], rb[1], "横排：b 的 y 应保持不变");
    }

    @Test
    public void draggingCardMovesXLive() {
        BProgram p = new BProgram();
        BProgram.TimerTrigger a = new BProgram.TimerTrigger();
        BProgram.TimerTrigger b = new BProgram.TimerTrigger();
        b.body.add(new BProgram.Statement.Input());
        p.triggers.add(a);
        p.triggers.add(b);
        EditorLayout el = new EditorLayout();
        el.setProgram(p);
        el.relayout(false, -1);
        // Drag frame: dragging=true skips overlap resolution, x must move live
        el.setCardPos(b.id, 600, 700);
        el.relayout(true, b.id);
        int[] rb = el.cardRectOf(b.id);
        assertNotNull(rb);
        assertEquals(600, rb[0]);
        assertEquals(700, rb[1]);
        // the card's row must shift with it (cache shift, not re-layout)
        int[] row = el.rowRectOf(b.body.get(0).id);
        assertNotNull(row);
        assertEquals(600 + EditorLayout.CARD_INNER, row[0]);
    }
}
