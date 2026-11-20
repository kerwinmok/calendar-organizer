package com.calendarorganizer.scheduler;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class TimeInterval {

    private final LocalDateTime start;
    private final LocalDateTime end;

    public TimeInterval(LocalDateTime start, LocalDateTime end) {
        this.start = Objects.requireNonNull(start, "start");
        this.end = Objects.requireNonNull(end, "end");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }
    }

    public LocalDateTime getStart() {
        return start;
    }

    public LocalDateTime getEnd() {
        return end;
    }

    public long durationMinutes() {
        return Duration.between(start, end).toMinutes();
    }

    public boolean overlaps(TimeInterval other) {
        return start.isBefore(other.end) && end.isAfter(other.start);
    }

    public TimeInterval clip(LocalDateTime min, LocalDateTime max) {
        if (!end.isAfter(min) || !start.isBefore(max)) {
            return null;
        }
        LocalDateTime clippedStart = start;
        if (clippedStart.isBefore(min)) {
            clippedStart = min;
        }
        LocalDateTime clippedEnd = end;
        if (clippedEnd.isAfter(max)) {
            clippedEnd = max;
        }
        if (!clippedEnd.isAfter(clippedStart)) {
            return null;
        }
        return new TimeInterval(clippedStart, clippedEnd);
    }

    public static List<TimeInterval> merge(List<TimeInterval> intervals) {
        if (intervals.isEmpty()) {
            return List.of();
        }
        List<TimeInterval> sorted = new ArrayList<>(intervals);
        sorted.sort(Comparator.comparing(TimeInterval::getStart).thenComparing(TimeInterval::getEnd));

        List<TimeInterval> merged = new ArrayList<>();
        TimeInterval current = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            TimeInterval next = sorted.get(i);
            if (!next.start.isAfter(current.end)) {
                LocalDateTime maxEnd = current.end;
                if (next.end.isAfter(maxEnd)) {
                    maxEnd = next.end;
                }
                current = new TimeInterval(current.start, maxEnd);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    public static List<TimeInterval> subtract(TimeInterval window, List<TimeInterval> mergedBlocked) {
        List<TimeInterval> free = new ArrayList<>();
        LocalDateTime cursor = window.start;
        for (TimeInterval block : mergedBlocked) {
            if (!block.end.isAfter(cursor)) {
                continue;
            }
            if (!block.start.isBefore(window.end)) {
                break;
            }
            if (block.start.isAfter(cursor)) {
                LocalDateTime gapEnd = block.start;
                if (gapEnd.isAfter(window.end)) {
                    gapEnd = window.end;
                }
                if (gapEnd.isAfter(cursor)) {
                    free.add(new TimeInterval(cursor, gapEnd));
                }
            }
            if (block.end.isAfter(cursor)) {
                cursor = block.end;
            }
            if (!cursor.isBefore(window.end)) {
                break;
            }
        }
        if (cursor.isBefore(window.end)) {
            free.add(new TimeInterval(cursor, window.end));
        }
        return free;
    }
}
