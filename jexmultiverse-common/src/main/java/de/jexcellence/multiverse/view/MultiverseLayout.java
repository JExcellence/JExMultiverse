package de.jexcellence.multiverse.view;

import org.jetbrains.annotations.NotNull;

/**
 * Slot math shared by every JExMultiverse chest view (6 rows): back at 0, header at 4, filter at 8, a 28-slot
 * body (rows 1-4, columns 1-7), close at 45 and the page arrows at 48 / 50. Small content is centred instead of
 * starting top-left.
 *
 * @author JExcellence
 * @since 3.8.0
 */
public final class MultiverseLayout {

    /** Rows of every view. */
    public static final int ROWS = 6;
    /** Columns of one body row. */
    public static final int BODY_COLUMNS = 7;
    /** Rows of the body. */
    public static final int BODY_ROWS = 4;
    /** Slots of a full body page. */
    public static final int PAGE_SIZE = BODY_COLUMNS * BODY_ROWS;

    public static final int SLOT_BACK = 0;
    public static final int SLOT_HEADER = 4;
    public static final int SLOT_FILTER = 8;
    public static final int SLOT_CLOSE = 45;
    public static final int SLOT_PAGE_PREV = 48;
    public static final int SLOT_PAGE_NEXT = 50;

    private static final int ROW_WIDTH = 9;

    private static final int[][] COMPACT_COLUMNS = {
            {},
            {4},
            {3, 5},
            {3, 4, 5},
            {2, 3, 5, 6},
            {2, 3, 4, 5, 6},
            {1, 2, 3, 5, 6, 7},
            {1, 2, 3, 4, 5, 6, 7}
    };

    private static final int[][] SPACED_COLUMNS = {
            {},
            {4},
            {3, 5},
            {2, 4, 6},
            {1, 3, 5, 7}
    };

    private MultiverseLayout() {
    }

    /**
     * Slots for {@code count} list cards in the body, centred horizontally and vertically, with the rows as even
     * as possible (8 cards become 4 + 4).
     *
     * @param count how many cards, clamped to 0-28
     * @return the slots in reading order
     */
    public static int @NotNull [] centred(int count) {
        int cards = Math.clamp(count, 0, PAGE_SIZE);
        if (cards == 0) {
            return new int[0];
        }
        int rows = (cards + BODY_COLUMNS - 1) / BODY_COLUMNS;
        int firstRow = 1 + (BODY_ROWS - rows) / 2;
        int perRow = (cards + rows - 1) / rows;
        int[] slots = new int[cards];
        int placed = 0;
        for (int row = 0; row < rows; row++) {
            int inRow = Math.min(perRow, cards - placed);
            for (int column : COMPACT_COLUMNS[inRow]) {
                slots[placed++] = (firstRow + row) * ROW_WIDTH + column;
            }
        }
        return slots;
    }

    /**
     * Slots for up to seven hub cards in one row, with a free column between them when up to four fit.
     *
     * @param count how many cards, clamped to 0-7
     * @param row   the inventory row (0-5)
     * @return the slots from left to right
     */
    public static int @NotNull [] spacedRow(int count, int row) {
        int cards = Math.clamp(count, 0, BODY_COLUMNS);
        int[] columns = cards < SPACED_COLUMNS.length ? SPACED_COLUMNS[cards] : COMPACT_COLUMNS[cards];
        int[] slots = new int[columns.length];
        for (int i = 0; i < columns.length; i++) {
            slots[i] = row * ROW_WIDTH + columns[i];
        }
        return slots;
    }

    /** @return the page count for {@code entries} body cards (at least one). */
    public static int pageCount(int entries) {
        return Math.max(1, (entries + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /** @return {@code page} clamped into {@code [0, pages)}. */
    public static int clampPage(int page, int pages) {
        return Math.clamp(page, 0, Math.max(0, pages - 1));
    }

    /** @return the centre slot of the body, for a single notice card. */
    public static int centreSlot() {
        return 2 * ROW_WIDTH + 4;
    }
}
