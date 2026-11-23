package com.calendarorganizer.storage;

import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.CalendarLayer;
import com.calendarorganizer.model.EventCategory;
import com.calendarorganizer.model.EventColor;
import com.calendarorganizer.model.EventSource;
import com.calendarorganizer.model.GoalType;
import com.calendarorganizer.model.RecurringRule;
import com.calendarorganizer.model.ScheduleGoal;
import com.calendarorganizer.model.SchedulePreferences;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void updatesEventAndRecurringRuleTimings() {
        CalendarEvent saved = repository.saveEvent(CalendarEvent.create(
                "Study Group",
                LocalDateTime.of(2026, 11, 18, 13, 0),
                LocalDateTime.of(2026, 11, 18, 14, 0),
                EventCategory.STUDY,
                EventSource.MANUAL
        ));
        CalendarEvent modified = new CalendarEvent(
                saved.getId(),
                saved.getUid(),
                "Study Group Moved",
                LocalDateTime.of(2026, 11, 18, 15, 30),
                LocalDateTime.of(2026, 11, 18, 17, 0),
                EventCategory.BUSY,
                EventSource.MANUAL,
                "Library"
        );
        repository.updateEvent(modified);

        CalendarEvent reloaded = repository.findAllEvents().get(0);
        assertEquals("Study Group Moved", reloaded.getTitle());
        assertEquals(LocalDateTime.of(2026, 11, 18, 15, 30), reloaded.getStartTime());
        assertEquals(LocalDateTime.of(2026, 11, 18, 17, 0), reloaded.getEndTime());
        assertEquals(EventCategory.BUSY, reloaded.getCategory());
        assertEquals("Library", reloaded.getNotes());

        RecurringRule rule = repository.saveRecurringRule(new RecurringRule(
                0L,
                "Work",
                EnumSet.of(DayOfWeek.MONDAY),
                LocalTime.of(7, 0),
                LocalTime.of(15, 0),
                EventCategory.WORK,
                null,
                null
        ));
        RecurringRule updatedRule = new RecurringRule(
                rule.getId(),
                "Work Shift",
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                LocalTime.of(8, 30),
                LocalTime.of(16, 30),
                EventCategory.WORK,
                null,
                null
        );
        repository.updateRecurringRule(updatedRule);

        RecurringRule reloadedRule = repository.findAllRecurringRules().get(0);
        assertEquals("Work Shift", reloadedRule.getTitle());
        assertEquals(EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), reloadedRule.getDaysOfWeek());
        assertEquals(LocalTime.of(8, 30), reloadedRule.getStartTime());
        assertEquals(LocalTime.of(16, 30), reloadedRule.getEndTime());

        assertThrows(IllegalArgumentException.class, () -> repository.updateEvent(CalendarEvent.create(
                "Unsaved",
                LocalDateTime.of(2026, 11, 18, 9, 0),
                LocalDateTime.of(2026, 11, 18, 10, 0),
                EventCategory.GENERAL,
                EventSource.MANUAL
        )));
        assertThrows(IllegalArgumentException.class, () -> repository.updateRecurringRule(new RecurringRule(
                0L,
                "Unsaved",
                EnumSet.of(DayOfWeek.FRIDAY),
                LocalTime.of(9, 0),
                LocalTime.of(10, 0),
                EventCategory.GENERAL,
                null,
                null
        )));
    }

    @Test
    void loadsSavesDeletesCustomPreferencesAndPreservesThemOnCalendarReset() {
        assertEquals("fallback", repository.loadPreference("gemini_api_key", "fallback"));

        repository.savePreference("gemini_api_key", "key-one");
        assertEquals("key-one", repository.loadPreference("gemini_api_key", ""));

        repository.savePreference("gemini_api_key", "key-two");
        assertEquals("key-two", repository.loadPreference("gemini_api_key", ""));

        repository.clearAllCalendarData();
        assertEquals("key-two", repository.loadPreference("gemini_api_key", ""));

        repository.deletePreference("gemini_api_key");
        assertEquals("", repository.loadPreference("gemini_api_key", ""));
    }

    @Test
    void seedsAndManagesCalendarLayersColorsNotesAndReassignsOnDelete() {
        List<CalendarLayer> seeded = repository.findAllCalendars();
        assertEquals(3, seeded.size());
        assertEquals("General", seeded.get(0).getName());
        assertEquals("Work", seeded.get(1).getName());
        assertEquals("School", seeded.get(2).getName());

        CalendarLayer clubs = repository.createCalendar("Clubs", EventColor.PURPLE);
        assertTrue(clubs.getId() > 0L);
        assertEquals(EventColor.PURPLE, clubs.getColor());
        assertThrows(IllegalArgumentException.class, () -> repository.createCalendar("Clubs", EventColor.RED));

        repository.updateCalendar(clubs.withNameAndColor("Campus Clubs", EventColor.PINK));
        repository.setOnlyCalendarVisible(clubs.getId());
        for (CalendarLayer layer : repository.findAllCalendars()) {
            if (layer.getId() == clubs.getId()) {
                assertTrue(layer.isVisible());
                assertEquals("Campus Clubs", layer.getName());
                assertEquals(EventColor.PINK, layer.getColor());
            } else {
                assertFalse(layer.isVisible());
            }
        }
        repository.setAllCalendarsVisible(true);

        CalendarEvent clubEvent = repository.saveEvent(new CalendarEvent(
                0L,
                null,
                "Club Meeting",
                LocalDateTime.of(2026, 11, 18, 17, 0),
                LocalDateTime.of(2026, 11, 18, 18, 0),
                EventCategory.GENERAL,
                EventSource.MANUAL,
                "Room 101",
                clubs.getId(),
                EventColor.GREEN
        ));
        RecurringRule clubRule = repository.saveRecurringRule(new RecurringRule(
                0L,
                "Weekly Standup",
                EnumSet.of(DayOfWeek.FRIDAY),
                LocalTime.of(16, 0),
                LocalTime.of(16, 30),
                EventCategory.GENERAL,
                null,
                null,
                clubs.getId(),
                EventColor.AMBER,
                "Discord voice"
        ));

        CalendarEvent loadedEvent = repository.findAllEvents().get(0);
        assertEquals(clubs.getId(), loadedEvent.getCalendarId());
        assertEquals(EventColor.GREEN, loadedEvent.getColor());
        assertEquals("Room 101", loadedEvent.getNotes());

        RecurringRule loadedRule = repository.findAllRecurringRules().get(0);
        assertEquals(clubs.getId(), loadedRule.getCalendarId());
        assertEquals(EventColor.AMBER, loadedRule.getColor());
        assertEquals("Discord voice", loadedRule.getNotes());

        long generalId = seeded.get(0).getId();
        repository.deleteCalendarAndReassign(clubs.getId(), generalId);
        assertEquals(3, repository.findAllCalendars().size());
        assertEquals(generalId, repository.findAllEvents().get(0).getCalendarId());
        assertEquals(generalId, repository.findAllRecurringRules().get(0).getCalendarId());
        assertEquals(clubEvent.getId(), repository.findAllEvents().get(0).getId());
        assertEquals(clubRule.getId(), repository.findAllRecurringRules().get(0).getId());
    }

    @Test
    void migratesLegacyDatabaseWithoutCalendarIdOrColorColumns(@TempDir Path tempDir) throws Exception {
        Path legacyDbPath = tempDir.resolve("legacy.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + legacyDbPath.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                    "CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, uid TEXT UNIQUE NOT NULL, "
                            + "title TEXT NOT NULL, start_time TEXT NOT NULL, end_time TEXT NOT NULL, "
                            + "category TEXT NOT NULL, source TEXT NOT NULL, notes TEXT)"
            );
            stmt.executeUpdate(
                    "CREATE TABLE recurring_rules (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, "
                            + "days_of_week TEXT NOT NULL, start_time TEXT NOT NULL, end_time TEXT NOT NULL, "
                            + "category TEXT NOT NULL, start_date TEXT, end_date TEXT)"
            );
            stmt.executeUpdate(
                    "INSERT INTO events (uid, title, start_time, end_time, category, source, notes) "
                            + "VALUES ('legacy-1', 'Existing Event', '2026-11-18T10:00:00', '2026-11-18T11:00:00', 'BUSY', 'MANUAL', 'Legacy notes')"
            );
        }

        DatabaseManager db = new DatabaseManager(legacyDbPath);
        db.initialize();
        CalendarRepository migratedRepo = new CalendarRepository(db);

        assertEquals(3, migratedRepo.findAllCalendars().size());
        List<CalendarEvent> loaded = migratedRepo.findAllEvents();
        assertEquals(1, loaded.size());
        assertEquals("Existing Event", loaded.get(0).getTitle());
        assertEquals("Legacy notes", loaded.get(0).getNotes());
        assertEquals(1L, loaded.get(0).getCalendarId());
        assertEquals(EventColor.DEFAULT, loaded.get(0).getColor());
    }
}
