package com.calendarorganizer.ui;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalendarGridMathTest {

    @Test
    void clampsZoomAndConvertsMinutes() {
        assertEquals(CalendarGridMath.MIN_HOUR_HEIGHT, CalendarGridMath.clampHourHeight(10.0));
        assertEquals(60.0, CalendarGridMath.clampHourHeight(60.0));
        assertEquals(CalendarGridMath.MAX_HOUR_HEIGHT, CalendarGridMath.clampHourHeight(200.0));

        assertEquals(15, CalendarGridMath.snapMinutes15(14));
        assertEquals(-15, CalendarGridMath.snapMinutes15(-14));
        assertEquals(60, CalendarGridMath.snapMinutes30Floor(89));

        assertEquals(LocalTime.of(0, 0), CalendarGridMath.minutesToTime(-5));
        assertEquals(LocalTime.of(9, 30), CalendarGridMath.minutesToTime(9 * 60 + 30));
        assertEquals(LocalTime.of(23, 59), CalendarGridMath.minutesToTime(1500));
    }

    @Test
    void computesDragMoveWithoutLateNightBackjump() {
        assertArrayEquals(new int[]{645, 705}, CalendarGridMath.computeDragMoveRange(600, 660, 45));
        assertArrayEquals(new int[]{0, 60}, CalendarGridMath.computeDragMoveRange(30, 90, -120));
        assertArrayEquals(new int[]{1379, 1439}, CalendarGridMath.computeDragMoveRange(1320, 1380, 120));
        assertArrayEquals(new int[]{1380, 1435}, CalendarGridMath.computeDragMoveRange(1380, 1435, 0));
    }

    @Test
    void computesResizeEndWithMinAndMaxBounds() {
        assertEquals(690, CalendarGridMath.computeResizeEnd(600, 660, 30));
        assertEquals(615, CalendarGridMath.computeResizeEnd(600, 660, -120));
        assertEquals(CalendarGridMath.MAX_DAY_MINUTE, CalendarGridMath.computeResizeEnd(1380, 1410, 120));
    }

    @Test
    void computesDragCreateAndDoubleClickRangesSafelyNearMidnight() {
        double hourHeight = 48.0;

        assertArrayEquals(new int[]{540, 630},
                CalendarGridMath.computeDragCreateRange(9.0 * hourHeight, 10.5 * hourHeight, hourHeight));
        assertArrayEquals(new int[]{540, 630},
                CalendarGridMath.computeDragCreateRange(10.5 * hourHeight, 9.0 * hourHeight, hourHeight));
        assertArrayEquals(new int[]{540, 570},
                CalendarGridMath.computeDragCreateRange(9.0 * hourHeight, 9.05 * hourHeight, hourHeight));

        int[] nearMidnightDrag = CalendarGridMath.computeDragCreateRange(23.85 * hourHeight, 23.98 * hourHeight, hourHeight);
        assertTrue(nearMidnightDrag[1] <= CalendarGridMath.MAX_DAY_MINUTE);
        assertTrue(nearMidnightDrag[1] - nearMidnightDrag[0] >= CalendarGridMath.MIN_DURATION_MINUTES);

        assertArrayEquals(new int[]{840, 900},
                CalendarGridMath.computeDoubleClickRange(14.4 * hourHeight, hourHeight));

        int[] lateDoubleClick = CalendarGridMath.computeDoubleClickRange(23.95 * hourHeight, hourHeight);
        assertTrue(lateDoubleClick[1] <= CalendarGridMath.MAX_DAY_MINUTE);
        assertTrue(lateDoubleClick[1] - lateDoubleClick[0] >= CalendarGridMath.MIN_DURATION_MINUTES);
    }
}
