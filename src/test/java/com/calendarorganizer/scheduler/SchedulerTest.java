package com.calendarorganizer.scheduler;

import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.EventCategory;
import com.calendarorganizer.model.EventSource;
import com.calendarorganizer.model.GoalType;
import com.calendarorganizer.model.RecurringRule;
import com.calendarorganizer.model.ScheduleGoal;
import com.calendarorganizer.model.SchedulePreferences;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 16);

    private final Scheduler scheduler = new Scheduler();

    @Test
    void schedulesWorkoutAndStudyAroundWorkBusyAndMidnightSleep() {
        RecurringRule work = new RecurringRule(
                1L,
                "Work",
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
                LocalTime.of(7, 0),
                LocalTime.of(18, 0),
                EventCategory.WORK,
                null,
                null
        );
        CalendarEvent mondayBusy = CalendarEvent.create(
                "Busy",
                MONDAY.atTime(20, 0),
                MONDAY.atTime(22, 0),
                EventCategory.BUSY,
                EventSource.MANUAL
        );
        ScheduleGoal workout = new ScheduleGoal(1L, GoalType.WORKOUT, "Workout", 3, 60, 0, null, null);
        ScheduleGoal study = new ScheduleGoal(2L, GoalType.STUDY, "Study", 5, 90, 450, null, null);
        SchedulePreferences prefs = new SchedulePreferences(LocalTime.MIDNIGHT, 7);

        ScheduleResult result = scheduler.scheduleWeek(
                MONDAY,
                List.of(mondayBusy),
                List.of(work),
                List.of(workout, study),
                prefs
        );

        assertTrue(result.getUnscheduledNotes().isEmpty());
        long workoutCount = result.getScheduledEvents().stream()
                .filter(e -> e.getCategory() == EventCategory.WORKOUT)
                .count();
        long studyMinutes = result.getScheduledEvents().stream()
                .filter(e -> e.getCategory() == EventCategory.STUDY)
                .mapToLong(e -> Duration.between(e.getStartTime(), e.getEndTime()).toMinutes())
                .sum();

        assertEquals(3L, workoutCount);
        assertEquals(450L, studyMinutes);
        assertNoOverlaps(result.getScheduledEvents(), List.of(mondayBusy), List.of(work), prefs);
    }

    @Test
    void avoidsEventsCrossingMidnightOnBothDays() {
        CalendarEvent lateMeeting = CalendarEvent.create(
                "Late Deploy",
                MONDAY.atTime(22, 0),
                MONDAY.plusDays(1).atTime(2, 0),
                EventCategory.BUSY,
                EventSource.MANUAL
        );
        RecurringRule nightShift = new RecurringRule(
                2L,
                "Night Shift",
                EnumSet.of(DayOfWeek.WEDNESDAY),
                LocalTime.of(22, 0),
                LocalTime.of(6, 0),
                EventCategory.WORK,
                null,
                null
        );
        ScheduleGoal workout = new ScheduleGoal(1L, GoalType.WORKOUT, "Workout", 3, 60, 0, null, null);
        ScheduleGoal study = new ScheduleGoal(2L, GoalType.STUDY, "Study", 4, 90, 360, null, null);
        SchedulePreferences prefs = new SchedulePreferences(LocalTime.of(23, 0), 7);

        ScheduleResult result = scheduler.scheduleWeek(
                MONDAY,
                List.of(lateMeeting),
                List.of(nightShift),
                List.of(workout, study),
                prefs
        );

        assertNoOverlaps(result.getScheduledEvents(), List.of(lateMeeting), List.of(nightShift), prefs);
    }

    @Test
    void skipsCompletelyBookedDayAndHandlesFullWeekWithoutFreeSlots() {
        CalendarEvent fullMonday = CalendarEvent.create(
                "All Day Travel",
                MONDAY.atStartOfDay(),
                MONDAY.plusDays(1).atStartOfDay(),
                EventCategory.BUSY,
                EventSource.MANUAL
        );
        ScheduleGoal workout = new ScheduleGoal(1L, GoalType.WORKOUT, "Workout", 2, 60, 0, null, null);
        SchedulePreferences prefs = new SchedulePreferences(LocalTime.of(23, 0), 7);

        ScheduleResult partialResult = scheduler.scheduleWeek(
                MONDAY,
                List.of(fullMonday),
                List.of(),
                List.of(workout),
                prefs
        );
        assertEquals(2, partialResult.getScheduledEvents().size());
        for (CalendarEvent ev : partialResult.getScheduledEvents()) {
            assertFalse(ev.getStartTime().toLocalDate().equals(MONDAY));
        }

        CalendarEvent fullWeek = CalendarEvent.create(
                "Retreat",
                MONDAY.atStartOfDay(),
                MONDAY.plusDays(7).atStartOfDay(),
                EventCategory.BUSY,
                EventSource.MANUAL
        );
        ScheduleGoal study = new ScheduleGoal(2L, GoalType.STUDY, "Study", 3, 90, 270, null, null);
        ScheduleResult fullWeekResult = scheduler.scheduleWeek(
                MONDAY,
                List.of(fullWeek),
                List.of(),
                List.of(workout, study),
                prefs
        );

        assertTrue(fullWeekResult.getScheduledEvents().isEmpty());
        assertEquals(2, fullWeekResult.getUnscheduledNotes().size());
    }

    @Test
    void splitsStudySessionsInTightGapsAndIsDeterministic() {
        List<CalendarEvent> tightDays = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            LocalDate day = MONDAY.plusDays(d);
            tightDays.add(CalendarEvent.create("Morning", day.atTime(7, 0), day.atTime(18, 0), EventCategory.BUSY, EventSource.MANUAL));
            tightDays.add(CalendarEvent.create("Evening", day.atTime(18, 45), day.atTime(23, 0), EventCategory.BUSY, EventSource.MANUAL));
        }
        ScheduleGoal study = new ScheduleGoal(1L, GoalType.STUDY, "Study", 4, 90, 270, null, null);
        SchedulePreferences prefs = new SchedulePreferences(LocalTime.of(23, 0), 8);

        ScheduleResult first = scheduler.scheduleWeek(MONDAY, tightDays, List.of(), List.of(study), prefs);
        ScheduleResult second = scheduler.scheduleWeek(MONDAY, tightDays, List.of(), List.of(study), prefs);

        long totalMinutes = first.getScheduledEvents().stream()
                .mapToLong(e -> Duration.between(e.getStartTime(), e.getEndTime()).toMinutes())
                .sum();
        assertEquals(270L, totalMinutes);
        assertEquals(first.getScheduledEvents().size(), second.getScheduledEvents().size());
        for (int i = 0; i < first.getScheduledEvents().size(); i++) {
            assertEquals(first.getScheduledEvents().get(i).getStartTime(), second.getScheduledEvents().get(i).getStartTime());
            assertEquals(first.getScheduledEvents().get(i).getEndTime(), second.getScheduledEvents().get(i).getEndTime());
        }
        assertNoOverlaps(first.getScheduledEvents(), tightDays, List.of(), prefs);
    }

    @Test
    void respectsSleepTimesBeforeAtAndAfterMidnight() {
        ScheduleGoal workout = new ScheduleGoal(1L, GoalType.WORKOUT, "Workout", 2, 60, 0, null, null);
        ScheduleGoal study = new ScheduleGoal(2L, GoalType.STUDY, "Study", 2, 60, 120, null, null);

        List<LocalTime> sleepTimes = List.of(
                LocalTime.of(22, 30),
                LocalTime.MIDNIGHT,
                LocalTime.of(1, 30)
        );
        for (LocalTime sleep : sleepTimes) {
            SchedulePreferences prefs = new SchedulePreferences(sleep, 7);
            ScheduleResult result = scheduler.scheduleWeek(MONDAY, List.of(), List.of(), List.of(workout, study), prefs);
            assertEquals(4, result.getScheduledEvents().size());
            assertNoOverlaps(result.getScheduledEvents(), List.of(), List.of(), prefs);
        }
    }

    private void assertNoOverlaps(List<CalendarEvent> scheduled,
                                  List<CalendarEvent> fixed,
                                  List<RecurringRule> rules,
                                  SchedulePreferences prefs) {
        List<TimeInterval> blocked = new ArrayList<>();
        for (CalendarEvent ev : fixed) {
            blocked.add(new TimeInterval(ev.getStartTime(), ev.getEndTime()));
        }
        for (RecurringRule r : rules) {
            for (CalendarEvent occ : r.expandForRange(MONDAY, MONDAY.plusDays(6))) {
                blocked.add(new TimeInterval(occ.getStartTime(), occ.getEndTime()));
            }
        }
        LocalDate d = MONDAY.minusDays(1);
        while (!d.isAfter(MONDAY.plusDays(6))) {
            LocalDateTime s = d.atTime(prefs.getSleepTime());
            blocked.add(new TimeInterval(s, s.plusHours(prefs.getSleepDurationHours())));
            d = d.plusDays(1);
        }

        List<TimeInterval> placed = new ArrayList<>();
        for (CalendarEvent ev : scheduled) {
            TimeInterval interval = new TimeInterval(ev.getStartTime(), ev.getEndTime());
            for (TimeInterval b : blocked) {
                assertFalse(interval.overlaps(b), "Scheduled event " + ev.getStartTime() + "-" + ev.getEndTime()
                        + " overlaps blocked " + b.getStart() + "-" + b.getEnd());
            }
            for (TimeInterval other : placed) {
                assertFalse(interval.overlaps(other), "Scheduled events overlap each other");
            }
            placed.add(interval);
        }
    }
}
