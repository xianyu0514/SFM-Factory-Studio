package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlockSourceMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 积木 id ↔ 源码区间映射。这是双向联动的地基，必须精确。 */
class BlockSourceMapTest {

    @Test
    void emptyMapNeverMatches() {
        assertEquals(-1, BlockSourceMap.EMPTY.blockAt(0));
        assertEquals(-1, BlockSourceMap.EMPTY.blockAt(42));
        assertNull(BlockSourceMap.EMPTY.rangeOf(1));
        assertEquals(-1, BlockSourceMap.EMPTY.startOf(1));
        assertTrue(BlockSourceMap.EMPTY.isEmpty());
    }

    @Test
    void rangeIsHalfOpen() {
        BlockSourceMap m = new BlockSourceMap();
        m.put(7, 10, 20);
        assertEquals(-1, m.blockAt(9), "start 之前不算命中");
        assertEquals(7, m.blockAt(10), "start 本身应命中");
        assertEquals(7, m.blockAt(19), "end 前一个字符命中");
        assertEquals(-1, m.blockAt(20), "end 是开区间，不命中");
        assertArrayEquals(new int[]{10, 20}, m.rangeOf(7));
        assertEquals(10, m.startOf(7));
    }

    @Test
    void nestedRangesResolveToInnermost() {
        // 外层 if: 0..100，内层 input: 30..50
        BlockSourceMap m = new BlockSourceMap();
        m.put(1, 0, 100);
        m.put(2, 30, 50);
        assertEquals(1, m.blockAt(10), "外层区段内命中外层");
        assertEquals(2, m.blockAt(35), "内层区段内必须命中内层，而不是外层 if");
        assertEquals(2, m.blockAt(49));
        assertEquals(1, m.blockAt(60), "回到外层");
    }

    @Test
    void disjointRangesPickTheContainingOne() {
        BlockSourceMap m = new BlockSourceMap();
        m.put(1, 0, 20);
        m.put(2, 40, 60);
        m.put(3, 80, 100);
        assertEquals(1, m.blockAt(5));
        assertEquals(2, m.blockAt(45));
        assertEquals(3, m.blockAt(95));
        assertEquals(-1, m.blockAt(30), "区间之间的空隙不命中");
    }

    @Test
    void invalidEntriesAreIgnored() {
        BlockSourceMap m = new BlockSourceMap();
        m.put(0, 0, 10);      // id 非法
        m.put(1, 10, 10);     // 空区间
        m.put(2, 20, 15);     // end < start
        m.put(3, -5, 10);     // 负偏移
        assertTrue(m.isEmpty(), "非法输入不应污染映射表");
    }

    @Test
    void insertionOrderDoesNotMatter() {
        // 先插入靠后的区间，再插入靠前的——查询必须仍按位置正确
        BlockSourceMap m = new BlockSourceMap();
        m.put(2, 50, 80);
        m.put(1, 0, 30);
        assertEquals(1, m.blockAt(10));
        assertEquals(2, m.blockAt(60));
    }

    @Test
    void manyRangesStayCorrect() {
        BlockSourceMap m = new BlockSourceMap();
        for (int i = 0; i < 200; i++) {
            m.put(i + 1, i * 10, i * 10 + 8);
        }
        assertEquals(1, m.blockAt(0));
        assertEquals(1, m.blockAt(7));
        assertEquals(100, m.blockAt(990));
        assertEquals(-1, m.blockAt(9), "每个区间末尾 2 个字符是空隙");
    }
}
