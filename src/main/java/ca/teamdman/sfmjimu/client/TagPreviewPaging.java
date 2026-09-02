package ca.teamdman.sfmjimu.client;

/** Pure paging rules for the compact JEI-style 14x10 resource-feature preview. */
public final class TagPreviewPaging {
    public static final int PAGE_SIZE = 140;

    private TagPreviewPaging() {
    }

    public static int pageCount(int total) {
        return Math.max(1, (Math.max(0, total) + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    public static int clampPage(int page, int total) {
        return Math.max(0, Math.min(page, pageCount(total) - 1));
    }

    public static int start(int page, int total) {
        return Math.min(Math.max(0, total), clampPage(page, total) * PAGE_SIZE);
    }

    public static int end(int page, int total) {
        return Math.min(Math.max(0, total), start(page, total) + PAGE_SIZE);
    }
}
