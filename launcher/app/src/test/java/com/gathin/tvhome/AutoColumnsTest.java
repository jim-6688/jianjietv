package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** AutoColumns.pick() is pure arithmetic: a JVM test can lock the thresholds down. */
public class AutoColumnsTest {

    @Test
    public void fewAppsKeepThreeBigColumns() {
        assertEquals(3, AutoColumns.pick(0));
        assertEquals(3, AutoColumns.pick(1));
        assertEquals(3, AutoColumns.pick(4));
        assertEquals(3, AutoColumns.pick(6));
    }

    @Test
    public void moreAppsAddColumnsSoTheyFit() {
        assertEquals(4, AutoColumns.pick(7));
        assertEquals(4, AutoColumns.pick(8));
        assertEquals(5, AutoColumns.pick(10));
        assertEquals(6, AutoColumns.pick(12));
    }

    @Test
    public void neverGoesPastSixColumns() {
        assertEquals(6, AutoColumns.pick(13));
        assertEquals(6, AutoColumns.pick(40));
        assertEquals(6, AutoColumns.pick(400));
    }

    @Test
    public void customRowAndBoundsAreHonoured() {
        assertEquals(6, AutoColumns.pick(6, 1, 3, 6));
        assertEquals(3, AutoColumns.pick(6, 3, 3, 6));
        assertEquals(2, AutoColumns.pick(4, 2, 2, 6));
        // A nonsensical row count is clamped to one row instead of dividing by zero.
        assertEquals(4, AutoColumns.pick(4, 0, 3, 6));
    }
}
