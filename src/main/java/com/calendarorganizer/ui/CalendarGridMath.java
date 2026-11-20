package com.calendarorganizer.ui;

import java.time.LocalTime;

public final class CalendarGridMath {

    public static final double MIN_HOUR_HEIGHT = 24.0;
    public static final double MAX_HOUR_HEIGHT = 112.0;
    public static final double DEFAULT_HOUR_HEIGHT = 48.0;
    public static final int MAX_DAY_MINUTE = 23 * 60 + 59;
    public static final int MIN_DURATION_MINUTES = 15;

    private CalendarGridMath() {
    }

    public static double clampHourHeight(double hourHeight) {
        return Math.max(MIN_HOUR_HEIGHT, Math.min(MAX_HOUR_HEIGHT, hourHeight));
    }

    public static int yToMinutes(double y, double hourHeight) {
        if (hourHeight <= 0.0) {
            return 0;
        }
        int raw = (int) Math.round((y / hourHeight) * 60.0);
        return Math.max(0, Math.min(MAX_DAY_MINUTE, raw));
    }

    public static int snapMinutes15(int minutes) {
        return (int) (Math.round(minutes / 15.0) * 15);
    }

    public static int snapMinutes30Floor(int minutes) {
        int floored = (int) (Math.floor(minutes / 30.0) * 30);
        return Math.max(0, Math.min(23 * 60 + 30, floored));
    }

    public static LocalTime minutesToTime(int totalMinutes) {
        int clamped = Math.max(0, Math.min(MAX_DAY_MINUTE, totalMinutes));
        return LocalTime.of(clamped / 60, clamped % 60);
    }

    public static int[] computeDragMoveRange(int origStartMin, int origEndMin, int deltaMinutes) {
        int duration = Math.max(1, origEndMin - origStartMin);
        int maxStart = Math.max(0, MAX_DAY_MINUTE - duration);
        int newStart = Math.max(0, Math.min(maxStart, origStartMin + deltaMinutes));
        int newEnd = Math.min(MAX_DAY_MINUTE, newStart + duration);
        return new int[]{newStart, newEnd};
    }

    public static int computeResizeEnd(int startMin, int origEndMin, int deltaMinutes) {
        int minEnd = Math.min(MAX_DAY_MINUTE, startMin + MIN_DURATION_MINUTES);
        return Math.max(minEnd, Math.min(MAX_DAY_MINUTE, origEndMin + deltaMinutes));
    }

    public static int[] computeDragCreateRange(double startY, double endY, double hourHeight) {
        int m1 = Math.max(0, Math.min(MAX_DAY_MINUTE, snapMinutes15(yToMinutes(Math.min(startY, endY), hourHeight))));
        int m2 = Math.max(0, Math.min(MAX_DAY_MINUTE, snapMinutes15(yToMinutes(Math.max(startY, endY), hourHeight))));
        if (m2 - m1 < MIN_DURATION_MINUTES) {
            m2 = Math.min(MAX_DAY_MINUTE, m1 + 30);
            if (m2 - m1 < MIN_DURATION_MINUTES) {
                m1 = Math.max(0, m2 - 30);
            }
        }
        return new int[]{m1, m2};
    }

    public static int[] computeDoubleClickRange(double y, double hourHeight) {
        int startMin = snapMinutes30Floor(yToMinutes(y, hourHeight));
        int endMin = Math.min(MAX_DAY_MINUTE, startMin + 60);
        if (endMin - startMin < MIN_DURATION_MINUTES) {
            startMin = Math.max(0, endMin - 60);
        }
        return new int[]{startMin, endMin};
    }
}
