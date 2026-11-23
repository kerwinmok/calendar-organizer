package com.calendarorganizer.ui;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public final class CalendarGridMath {

    public static final double MIN_HOUR_HEIGHT = 24.0;
    public static final double MAX_HOUR_HEIGHT = 112.0;
    public static final double DEFAULT_HOUR_HEIGHT = 48.0;
    public static final int MAX_DAY_MINUTE = 23 * 60 + 59;
    public static final int MIN_DURATION_MINUTES = 15;
    public static final double MIN_EVENT_CARD_HEIGHT_PX = 22.0;
    public static final double MIN_SIDE_BY_SIDE_LANE_WIDTH_PX = 54.0;

    public static final class ClusterRange {
        private final int startIndex;
        private final int endIndexExclusive;
        private final long startMin;
        private final long endMin;

        public ClusterRange(int startIndex, int endIndexExclusive, long startMin, long endMin) {
            this.startIndex = startIndex;
            this.endIndexExclusive = endIndexExclusive;
            this.startMin = startMin;
            this.endMin = endMin;
        }

        public int getStartIndex() {
            return startIndex;
        }

        public int getEndIndexExclusive() {
            return endIndexExclusive;
        }

        public long getStartMin() {
            return startMin;
        }

        public long getEndMin() {
            return endMin;
        }
    }

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

    public static long minVisualSpanMinutes(double hourHeight) {
        double safeHourHeight = clampHourHeight(hourHeight);
        long span = (long) Math.ceil((MIN_EVENT_CARD_HEIGHT_PX / safeHourHeight) * 60.0);
        return Math.max(MIN_DURATION_MINUTES, span);
    }

    public static List<ClusterRange> computeVisualClusters(List<long[]> sortedStartEndMinutes, double hourHeight) {
        if (sortedStartEndMinutes == null || sortedStartEndMinutes.isEmpty()) {
            return List.of();
        }
        long minVisualSpan = minVisualSpanMinutes(hourHeight);
        List<ClusterRange> clusters = new ArrayList<>();

        int n = sortedStartEndMinutes.size();
        int clusterStartIdx = 0;
        long[] first = sortedStartEndMinutes.get(0);
        long clusterStartMin = first[0];
        long clusterLogicalEnd = Math.max(first[0], first[1]);
        long clusterVisualEnd = Math.max(clusterLogicalEnd, clusterStartMin + minVisualSpan);

        for (int j = 1; j < n; j++) {
            long[] next = sortedStartEndMinutes.get(j);
            long nextStart = next[0];
            long nextEnd = Math.max(next[0], next[1]);

            boolean logicallyOverlaps = nextStart < clusterLogicalEnd;
            boolean visuallyCollides = nextStart < clusterVisualEnd;
            boolean hasRoomAbove = (nextStart - clusterStartMin) >= minVisualSpan;

            if (logicallyOverlaps || (visuallyCollides && !hasRoomAbove)) {
                clusterLogicalEnd = Math.max(clusterLogicalEnd, nextEnd);
                clusterVisualEnd = Math.max(clusterVisualEnd, Math.max(nextEnd, nextStart + minVisualSpan));
            } else {
                clusters.add(new ClusterRange(clusterStartIdx, j, clusterStartMin, clusterLogicalEnd));
                clusterStartIdx = j;
                clusterStartMin = nextStart;
                clusterLogicalEnd = nextEnd;
                clusterVisualEnd = Math.max(clusterLogicalEnd, clusterStartMin + minVisualSpan);
            }
        }
        clusters.add(new ClusterRange(clusterStartIdx, n, clusterStartMin, clusterLogicalEnd));
        return clusters;
    }

    public static boolean shouldCollapseCluster(int eventCount, double columnWidthPx) {
        if (eventCount <= 1) {
            return false;
        }
        if (eventCount >= 3) {
            return true;
        }
        double laneWidth = (columnWidthPx - 6.0) / 2.0;
        return laneWidth < MIN_SIDE_BY_SIDE_LANE_WIDTH_PX;
    }
}
