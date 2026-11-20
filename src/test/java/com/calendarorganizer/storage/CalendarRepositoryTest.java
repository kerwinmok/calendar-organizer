package com.calendarorganizer.storage;

import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.EventCategory;
import com.calendarorganizer.model.EventSource;
import com.calendarorganizer.model.GoalType;
import com.calendarorganizer.model.RecurringRule;
import com.calendarorganizer.model.ScheduleGoal;
import com.calendarorganizer.model.SchedulePreferences;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalendarRepositoryTest {

    private CalendarRepository repository;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("test.db"));
        db.initialize();
        repository = new CalendarRepository(db);
    }

    @Test
    void savesAndQueriesEventsInRangeIncludingOverlapAcrossBoundaries() {
        CalendarEvent beforeRange = CalendarEvent.create(
                "Early",
                LocalDateTime.of(2026, 11, 15, 10, 0),
                LocalDateTime.of(2026, 11, 15, 12, 0),
                EventCategory.GENERAL,
                EventSource.MANUAL
        );
        CalendarEvent crossingMidnight = CalendarEvent.create(
                "Late Shift",
                LocalDateTime.of(2026, 11, 15, 22, 0),
                LocalDateTime.of(2026, 11, 16, 2, 0),
                EventCategory.BUSY,
                EventSource.MANUAL
        );
        CalendarEvent insideRange = CalendarEvent.create(
                "Meeting",
                LocalDateTime.of(2026, 11, 16, 14, 0),
                LocalDateTime.of(2026, 11, 16, 15, 0),
                EventCategory.BUSY,
                EventSource.MANUAL
        );

        repository.saveEvent(beforeRange);
        repository.saveEvent(crossingMidnight);
        repository.saveEvent(insideRange);

        List<CalendarEvent> found = repository.findEventsInRange(
                LocalDateTime.of(2026, 11, 16, 0, 0),
                LocalDateTime.of(2026, 11, 17, 0, 0)
        );

        assertEquals(2, found.size());
        assertEquals("Late Shift", found.get(0).getTitle());
        assertEquals("Meeting", found.get(1).getTitle());
    }

    @Test
    void upsertsByUidWithoutDuplicating() {
        CalendarEvent first = new CalendarEvent(
                0L,
                "google-uid-123",
                "Dentist",
                LocalDateTime.of(2026, 11, 18, 9, 0),
                LocalDateTime.of(2026, 11, 18, 10, 0),
                EventCategory.IMPORTED,
                EventSource.IMPORTED,
                "Room A"
        );
        CalendarEvent saved = repository.upsertEventByUid(first);

        CalendarEvent updated = new CalendarEvent(
                0L,
                "google-uid-123",
                "Dentist Updated",
                LocalDateTime.of(2026, 11, 18, 10, 0),
                LocalDateTime.of(2026, 11, 18, 11, 0),
                EventCategory.IMPORTED,
                EventSource.IMPORTED,
                "Room B"
        );
        CalendarEvent afterUpsert = repository.upsertEventByUid(updated);

        List<CalendarEvent> all = repository.findAllEvents();
        assertEquals(1, all.size());
        assertEquals(saved.getId(), afterUpsert.getId());
        assertEquals("Dentist Updated", all.get(0).getTitle());
        assertEquals("Room B", all.get(0).getNotes());
    }

    @Test
    void roundTripsRecurringRulesGoalsAndPreferences() {
        RecurringRule rule = repository.saveRecurringRule(new RecurringRule(
                0L,
                "Work",
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
                LocalTime.of(7, 0),
                LocalTime.of(18, 0),
                EventCategory.WORK,
                LocalDate.of(2026, 11, 1),
                null
        ));

        ScheduleGoal goal = repository.saveGoal(new ScheduleGoal(
                0L,
                GoalType.WORKOUT,
                "Workout",
                3,
                60,
                0,
                LocalTime.of(6, 0),
                LocalTime.of(22, 0)
        ));

        repository.savePreferences(new SchedulePreferences(LocalTime.of(0, 0), 7));

        List<RecurringRule> rules = repository.findAllRecurringRules();
        assertEquals(1, rules.size());
        assertEquals(rule.getId(), rules.get(0).getId());
        assertTrue(rules.get(0).getDaysOfWeek().contains(DayOfWeek.THURSDAY));

        List<ScheduleGoal> goals = repository.findAllGoals();
        assertEquals(1, goals.size());
        assertEquals(goal.getId(), goals.get(0).getId());
        assertEquals(180, goals.get(0).totalTargetMinutes());

        SchedulePreferences prefs = repository.loadPreferences();
        assertEquals(LocalTime.of(0, 0), prefs.getSleepTime());
        assertEquals(7, prefs.getSleepDurationHours());

        repository.deleteRecurringRule(rule.getId());
        repository.deleteGoal(goal.getId());
        assertTrue(repository.findAllRecurringRules().isEmpty());
        assertTrue(repository.findAllGoals().isEmpty());
    }

    @Test
    void deletesPastEventsResetsCalendarDataOptimizesAndPersistsTheme() {
        repository.saveEvent(CalendarEvent.create(
                "Old Event",
                LocalDateTime.of(2026, 11, 10, 9, 0),
                LocalDateTime.of(2026, 11, 10, 10, 0),
                EventCategory.GENERAL,
                EventSource.MANUAL
        ));
        repository.saveEvent(CalendarEvent.create(
                "Future Event",
                LocalDateTime.of(2026, 11, 25, 9, 0),
                LocalDateTime.of(2026, 11, 25, 10, 0),
                EventCategory.BUSY,
                EventSource.MANUAL
        ));
        repository.saveRecurringRule(new RecurringRule(
                0L,
                "Work",
                EnumSet.of(DayOfWeek.MONDAY),
                LocalTime.of(8, 0),
                LocalTime.of(17, 0),
                EventCategory.WORK,
                null,
                null
        ));
        repository.saveGoal(new ScheduleGoal(0L, GoalType.WORKOUT, "Workout", 3, 60, 0, null, null));
        repository.savePreferences(new SchedulePreferences(LocalTime.of(1, 0), 7));
        repository.saveTheme("midnight");

        assertEquals("midnight", repository.loadTheme());

        int deletedPast = repository.deleteEventsEndingBefore(LocalDateTime.of(2026, 11, 20, 0, 0));
        assertEquals(1, deletedPast);
        assertEquals(1, repository.findAllEvents().size());

        StorageStats statsBeforeReset = repository.getStorageStats();
        assertEquals(1, statsBeforeReset.getEventCount());
        assertEquals(1, statsBeforeReset.getRecurringRuleCount());
        assertEquals(1, statsBeforeReset.getGoalCount());
        assertTrue(statsBeforeReset.getDatabaseBytes() > 0L);

        StorageStats afterReset = repository.clearAllCalendarData();
        assertEquals(0, afterReset.getEventCount());
        assertEquals(0, afterReset.getRecurringRuleCount());
        assertEquals(0, afterReset.getGoalCount());
        assertEquals(SchedulePreferences.DEFAULT_SLEEP_TIME, repository.loadPreferences().getSleepTime());
        assertEquals("midnight", repository.loadTheme());
    }
}
