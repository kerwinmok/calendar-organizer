package com.calendarorganizer.workspace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsWindowDockerTest {

    @Test
    void computesPhysicalHostRectAcrossDpiScalesAndNegativeMonitorOrigins() {
        WindowsWindowSnapper.Rect std = WindowsWindowDocker.computeHostPhysicalRect(
                100, 50, 400.0, 60.0, 500.0, 600.0, 1.0, 1.0
        );
        assertEquals(500, std.getX());
        assertEquals(110, std.getY());
        assertEquals(500, std.getWidth());
        assertEquals(600, std.getHeight());

        WindowsWindowSnapper.Rect hidpiLeftMonitor = WindowsWindowDocker.computeHostPhysicalRect(
                -1920, 0, 200.0, 80.0, 600.0, 400.0, 1.5, 1.5
        );
        assertEquals(-1620, hidpiLeftMonitor.getX());
        assertEquals(120, hidpiLeftMonitor.getY());
        assertEquals(900, hidpiLeftMonitor.getWidth());
        assertEquals(600, hidpiLeftMonitor.getHeight());
    }

    @Test
    void compensatesInvisibleDwmFrameShadowBorders() {
        WindowsWindowSnapper.Rect target = new WindowsWindowSnapper.Rect(600, 100, 700, 650);
        WindowsWindowSnapper.Rect currentFull = new WindowsWindowSnapper.Rect(93, 50, 814, 507);
        WindowsWindowSnapper.Rect currentExt = new WindowsWindowSnapper.Rect(100, 50, 800, 500);

        WindowsWindowSnapper.Rect adjusted = WindowsWindowDocker.computeFrameCompensatedRect(
                target, currentFull, currentExt
        );
        assertEquals(593, adjusted.getX());
        assertEquals(100, adjusted.getY());
        assertEquals(714, adjusted.getWidth());
        assertEquals(657, adjusted.getHeight());
    }

    @Test
    void detectsCursorHitInsideDropZoneAndDragOutThreshold() {
        WindowsWindowSnapper.Rect zone = new WindowsWindowSnapper.Rect(500, 100, 600, 600);
        assertTrue(WindowsWindowDocker.isPointInsideRect(500, 100, zone));
        assertTrue(WindowsWindowDocker.isPointInsideRect(800, 400, zone));
        assertFalse(WindowsWindowDocker.isPointInsideRect(499, 400, zone));

        WindowsWindowSnapper.Rect slightlyOff = new WindowsWindowSnapper.Rect(512, 105, 600, 600);
        assertFalse(WindowsWindowDocker.hasMovedOutsideThreshold(slightlyOff, zone, 45));

        WindowsWindowSnapper.Rect draggedAway = new WindowsWindowSnapper.Rect(580, 160, 600, 600);
        assertTrue(WindowsWindowDocker.hasMovedOutsideThreshold(draggedAway, zone, 45));
    }
}
