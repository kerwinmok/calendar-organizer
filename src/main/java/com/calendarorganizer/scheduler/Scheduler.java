package com.calendarorganizer.scheduler;

import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.EventCategory;
import com.calendarorganizer.model.EventSource;
import com.calendarorganizer.model.GoalType;
import com.calendarorganizer.model.RecurringRule;
import com.calendarorganizer.model.ScheduleGoal;
import com.calendarorganizer.model.SchedulePreferences;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Scheduler {

    private static final int WORKOUT_BUFFER_MINUTES = 15;
    private static final int MIN_STUDY_CHUNK_MINUTES = 30;
    private static final int DEFAULT_STUDY_CHUNK_MINUTES = 90;

    public ScheduleResult scheduleWeek(LocalDate weekStart,
                                       List<CalendarEvent> existingEvents,
                                       List<RecurringRule> recurringRules,
                                       List<ScheduleGoal> goals,
                                       SchedulePreferences preferences) {
        SchedulePreferences prefs = preferences;
        if (prefs == null) {
            prefs = new SchedulePreferences();
        }
        LocalDateTime weekStartTime = weekStart.atStartOfDay();
        LocalDateTime weekEndTime = weekStart.plusDays(7).atStartOfDay();

        List<TimeInterval> blocked = collectBlockedIntervals(
                weekStart, weekStartTime, weekEndTime, existingEvents, recurringRules, prefs
        );

        List<CalendarEvent> scheduled = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (goals == null || goals.isEmpty()) {
            return new ScheduleResult(scheduled, notes);
        }

        List<ScheduleGoal> orderedGoals = new ArrayList<>(goals);
        orderedGoals.sort(
                Comparator.comparingInt((ScheduleGoal g) -> priorityOrder(g.getType()))
                        .thenComparingLong(ScheduleGoal::getId)
                        .thenComparing(ScheduleGoal::getTitle)
        );

        for (ScheduleGoal goal : orderedGoals) {
            if (goal.getType() == GoalType.WORKOUT) {
                placeWorkoutGoal(weekStart, goal, blocked, scheduled, notes);
            } else {
                placeStudyGoal(weekStart, goal, blocked, scheduled, notes);
            }
        }

        scheduled.sort(Comparator.comparing(CalendarEvent::getStartTime));
        return new ScheduleResult(scheduled, notes);
    }

    private int priorityOrder(GoalType type) {
        if (type == GoalType.WORKOUT) {
            return 0;
        }
        return 1;
    }

    private List<TimeInterval> collectBlockedIntervals(LocalDate weekStart,
                                                       LocalDateTime weekStartTime,
                                                       LocalDateTime weekEndTime,
                                                       List<CalendarEvent> existingEvents,
                                                       List<RecurringRule> recurringRules,
                                                       SchedulePreferences prefs) {
        List<TimeInterval> raw = new ArrayList<>();
        if (existingEvents != null) {
            for (CalendarEvent event : existingEvents) {
                if (event.getSource() != EventSource.SCHEDULED && event.getEndTime().isAfter(event.getStartTime())) {
                    addClipped(raw, new TimeInterval(event.getStartTime(), event.getEndTime()), weekStartTime, weekEndTime);
                }
            }
        }
        if (recurringRules != null) {
            LocalDate weekLastDay = weekStart.plusDays(6);
            for (RecurringRule rule : recurringRules) {
                for (CalendarEvent occ : rule.expandForRange(weekStart, weekLastDay)) {
                    if (occ.getEndTime().isAfter(occ.getStartTime())) {
                        addClipped(raw, new TimeInterval(occ.getStartTime(), occ.getEndTime()), weekStartTime, weekEndTime);
                    }
                }
            }
        }

        //handles midnight wrap for sleep starting the night before weekStart
        LocalDate cursor = weekStart.minusDays(1);
        LocalDate lastDay = weekStart.plusDays(6);
        while (!cursor.isAfter(lastDay)) {
            LocalDateTime sleepStart = cursor.atTime(prefs.getSleepTime());
            LocalDateTime sleepEnd = sleepStart.plusHours(prefs.getSleepDurationHours());
            addClipped(raw, new TimeInterval(sleepStart, sleepEnd), weekStartTime, weekEndTime);
            cursor = cursor.plusDays(1);
        }

        return new ArrayList<>(TimeInterval.merge(raw));
    }

    private void addClipped(List<TimeInterval> target,
                            TimeInterval candidate,
                            LocalDateTime min,
                            LocalDateTime max) {
        TimeInterval clipped = candidate.clip(min, max);
        if (clipped != null) {
            target.add(clipped);
        }
    }

    private void placeWorkoutGoal(LocalDate weekStart,
                                  ScheduleGoal goal,
                                  List<TimeInterval> blocked,
                                  List<CalendarEvent> scheduled,
                                  List<String> notes) {
        int duration = goal.getSessionMinutes();
        if (duration <= 0) {
            duration = 60;
        }
        int targetSessions = goal.getSessionsPerWeek();
        if (targetSessions <= 0 && goal.totalTargetMinutes() > 0) {
            targetSessions = Math.max(1, goal.totalTargetMinutes() / duration);
        }
        if (targetSessions <= 0) {
            return;
        }

        Set<Integer> usedDays = new HashSet<>();
        int placedCount = 0;

        for (int s = 0; s < targetSessions; s++) {
            SlotCandidate best = pickBestWorkoutSlot(weekStart, goal, duration, blocked, usedDays, true);
            if (best == null && targetSessions > 7) {
                best = pickBestWorkoutSlot(weekStart, goal, duration, blocked, Set.of(), false);
            }
            if (best == null) {
                break;
            }
            TimeInterval placedInterval = new TimeInterval(best.start, best.start.plusMinutes(duration));
            CalendarEvent event = CalendarEvent.create(
                    goal.getTitle(),
                    placedInterval.getStart(),
                    placedInterval.getEnd(),
                    EventCategory.WORKOUT,
                    EventSource.SCHEDULED
            );
            scheduled.add(event);
            blocked.add(placedInterval);
            List<TimeInterval> merged = TimeInterval.merge(blocked);
            blocked.clear();
            blocked.addAll(merged);
            usedDays.add(best.dayIndex);
            placedCount++;
        }

        if (placedCount < targetSessions) {
            notes.add("Could only schedule " + placedCount + " of " + targetSessions + " " + goal.getTitle() + " sessions.");
        }
    }

    private SlotCandidate pickBestWorkoutSlot(LocalDate weekStart,
                                              ScheduleGoal goal,
                                              int duration,
                                              List<TimeInterval> blocked,
                                              Set<Integer> excludedDays,
                                              boolean preferUniqueDay) {
        List<TimeInterval> mergedBlocked = TimeInterval.merge(blocked);
        SlotCandidate best = null;
        long bestDayFree = -1L;

        for (int d = 0; d < 7; d++) {
            if (preferUniqueDay && excludedDays.contains(d)) {
                continue;
            }
            LocalDate day = weekStart.plusDays(d);
            List<TimeInterval> dayFree = getBoundedDayFree(day, goal, mergedBlocked);
            long totalFree = totalMinutes(dayFree);
            TimeInterval chosen = findFirstSlotWithOptionalBuffer(dayFree, duration, WORKOUT_BUFFER_MINUTES);
            if (chosen == null) {
                chosen = findFirstSlotWithOptionalBuffer(dayFree, duration, 0);
            }
            if (chosen != null && totalFree > bestDayFree) {
                bestDayFree = totalFree;
                LocalDateTime start = chosen.getStart();
                if (chosen.durationMinutes() >= duration + 2L * WORKOUT_BUFFER_MINUTES) {
                    start = start.plusMinutes(WORKOUT_BUFFER_MINUTES);
                }
                best = new SlotCandidate(d, start, duration);
            }
        }
        return best;
    }

    private TimeInterval findFirstSlotWithOptionalBuffer(List<TimeInterval> intervals, int duration, int buffer) {
        long needed = duration + 2L * buffer;
        for (TimeInterval interval : intervals) {
            if (interval.durationMinutes() >= needed) {
                return interval;
            }
        }
        return null;
    }

    private void placeStudyGoal(LocalDate weekStart,
                                ScheduleGoal goal,
                                List<TimeInterval> blocked,
                                List<CalendarEvent> scheduled,
                                List<String> notes) {
        int remaining = goal.totalTargetMinutes();
        if (remaining <= 0) {
            return;
        }
        int preferredChunk = goal.getSessionMinutes();
        if (preferredChunk <= 0) {
            preferredChunk = DEFAULT_STUDY_CHUNK_MINUTES;
        }

        int[] studyMinutesPerDay = new int[7];
        int totalPlaced = 0;

        while (remaining >= MIN_STUDY_CHUNK_MINUTES) {
            int desiredChunk = Math.min(preferredChunk, remaining);
            SlotCandidate candidate = pickBestStudySlot(weekStart, goal, desiredChunk, remaining, studyMinutesPerDay, blocked);
            if (candidate == null) {
                break;
            }
            TimeInterval placedInterval = new TimeInterval(candidate.start, candidate.start.plusMinutes(candidate.durationMinutes));
            CalendarEvent event = CalendarEvent.create(
                    goal.getTitle(),
                    placedInterval.getStart(),
                    placedInterval.getEnd(),
                    EventCategory.STUDY,
                    EventSource.SCHEDULED
            );
            scheduled.add(event);
            blocked.add(placedInterval);
            List<TimeInterval> merged = TimeInterval.merge(blocked);
            blocked.clear();
            blocked.addAll(merged);

            studyMinutesPerDay[candidate.dayIndex] = studyMinutesPerDay[candidate.dayIndex] + candidate.durationMinutes;
            remaining = remaining - candidate.durationMinutes;
            totalPlaced = totalPlaced + candidate.durationMinutes;
        }

        if (remaining >= MIN_STUDY_CHUNK_MINUTES) {
            notes.add("Scheduled " + totalPlaced + " minutes of " + goal.getTitle()
                    + "; " + remaining + " minutes could not fit.");
        } else if (remaining > 0 && totalPlaced == 0) {
            notes.add("Could not schedule " + goal.getTitle() + " (" + remaining + " minutes below minimum block size).");
        }
    }

    private SlotCandidate pickBestStudySlot(LocalDate weekStart,
                                            ScheduleGoal goal,
                                            int desiredChunk,
                                            int remaining,
                                            int[] studyMinutesPerDay,
                                            List<TimeInterval> blocked) {
        List<TimeInterval> mergedBlocked = TimeInterval.merge(blocked);
        SlotCandidate fullChunkBest = findBalancedSlot(weekStart, goal, desiredChunk, studyMinutesPerDay, mergedBlocked, false);
        if (fullChunkBest != null) {
            return fullChunkBest;
        }
        int maxAllowable = Math.min(desiredChunk, remaining);
        return findBalancedSlot(weekStart, goal, maxAllowable, studyMinutesPerDay, mergedBlocked, true);
    }

    private SlotCandidate findBalancedSlot(LocalDate weekStart,
                                           ScheduleGoal goal,
                                           int desiredChunk,
                                           int[] studyMinutesPerDay,
                                           List<TimeInterval> mergedBlocked,
                                           boolean allowPartial) {
        int bestDay = -1;
        int fewestStudy = Integer.MAX_VALUE;
        LocalDateTime bestStart = null;
        int bestDuration = 0;

        for (int d = 0; d < 7; d++) {
            LocalDate day = weekStart.plusDays(d);
            List<TimeInterval> dayFree = getBoundedDayFree(day, goal, mergedBlocked);
            TimeInterval slot = findBestStudyInterval(dayFree, desiredChunk, allowPartial);
            if (slot == null) {
                continue;
            }
            int fitDuration = desiredChunk;
            if (slot.durationMinutes() < desiredChunk) {
                fitDuration = (int) slot.durationMinutes();
            }
            if (studyMinutesPerDay[d] < fewestStudy) {
                fewestStudy = studyMinutesPerDay[d];
                bestDay = d;
                bestStart = slot.getStart();
                bestDuration = fitDuration;
            }
        }

        if (bestDay < 0) {
            return null;
        }
        return new SlotCandidate(bestDay, bestStart, bestDuration);
    }

    private TimeInterval findBestStudyInterval(List<TimeInterval> dayFree, int desiredChunk, boolean allowPartial) {
        for (TimeInterval interval : dayFree) {
            if (interval.durationMinutes() >= desiredChunk) {
                return interval;
            }
        }
        if (!allowPartial) {
            return null;
        }
        TimeInterval largest = null;
        long largestMinutes = 0L;
        for (TimeInterval interval : dayFree) {
            long mins = interval.durationMinutes();
            if (mins >= MIN_STUDY_CHUNK_MINUTES && mins > largestMinutes) {
                largestMinutes = mins;
                largest = interval;
            }
        }
        return largest;
    }

    private List<TimeInterval> getBoundedDayFree(LocalDate day,
                                                 ScheduleGoal goal,
                                                 List<TimeInterval> mergedBlocked) {
        LocalDateTime windowStart = day.atStartOfDay();
        LocalDateTime windowEnd = day.plusDays(1).atStartOfDay();
        if (goal.getEarliestStart() != null) {
            LocalDateTime earliest = day.atTime(goal.getEarliestStart());
            if (earliest.isAfter(windowStart)) {
                windowStart = earliest;
            }
        }
        if (goal.getLatestEnd() != null && goal.getLatestEnd().isAfter(LocalTime.MIDNIGHT)) {
            LocalDateTime latest = day.atTime(goal.getLatestEnd());
            if (latest.isBefore(windowEnd)) {
                windowEnd = latest;
            }
        }
        if (!windowEnd.isAfter(windowStart)) {
            return List.of();
        }
        return TimeInterval.subtract(new TimeInterval(windowStart, windowEnd), mergedBlocked);
    }

    private long totalMinutes(List<TimeInterval> intervals) {
        long sum = 0L;
        for (TimeInterval interval : intervals) {
            sum = sum + interval.durationMinutes();
        }
        return sum;
    }

    private static class SlotCandidate {
        final int dayIndex;
        final LocalDateTime start;
        final int durationMinutes;

        SlotCandidate(int dayIndex, LocalDateTime start, int durationMinutes) {
            this.dayIndex = dayIndex;
            this.start = start;
            this.durationMinutes = durationMinutes;
        }
    }
}
