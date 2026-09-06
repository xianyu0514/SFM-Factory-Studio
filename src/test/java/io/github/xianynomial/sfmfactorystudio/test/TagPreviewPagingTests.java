package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.TagPreviewPaging;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TagPreviewPagingTests {
    @Test
    public void pagesAreExactlyOneHundredFortyItemsAndNeverEmptyNumbered() {
        assertEquals(1, TagPreviewPaging.pageCount(0));
        assertEquals(1, TagPreviewPaging.pageCount(70));
        assertEquals(1, TagPreviewPaging.pageCount(140));
        assertEquals(2, TagPreviewPaging.pageCount(141));
        assertEquals(200, TagPreviewPaging.pageCount(27_864));
        assertEquals(0, TagPreviewPaging.start(0, 27_864));
        assertEquals(140, TagPreviewPaging.end(0, 27_864));
        assertEquals(27_860, TagPreviewPaging.start(199, 27_864));
        assertEquals(27_864, TagPreviewPaging.end(199, 27_864));
        assertEquals(199, TagPreviewPaging.clampPage(99_999, 27_864));
    }
}
