package com.calendarorganizer.service;

import com.calendarorganizer.ics.IcsExporter;
import com.calendarorganizer.ics.IcsParser;
import com.calendarorganizer.llm.GeminiProvider;
import com.calendarorganizer.llm.OllamaProvider;
import com.calendarorganizer.llm.ParserMode;
import com.calendarorganizer.llm.ScheduleParserService;
import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.EventCategory;
import com.calendarorganizer.model.EventSource;
import com.calendarorganizer.model.RecurringRule;
import com.calendarorganizer.parser.ParseResultMapper;
import com.calendarorganizer.parser.RuleParser;
import com.calendarorganizer.scheduler.Scheduler;
import com.calendarorganizer.storage.CalendarRepository;
import com.calendarorganizer.storage.DatabaseManager;
import com.calendarorganizer.storage.StorageStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalendarServiceTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 16);

    private CalendarService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("calendar.db"));
        db.initialize();
        CalendarRepository repo = new CalendarRepository(db);
        ScheduleParserService parserService = new ScheduleParserService(
                new RuleParser(),
                new OllamaProvider("http://127.0.0.1:1", "none", HttpClient.newHttpClient()),
                new GeminiProvider("", "gemini-flash-lite-latest", "http://127.0.0.1:1", HttpClient.newHttpClient())
        );
        service = new CalendarService(
                repo,
                parserService,
                new ParseResultMapper(),
                new Scheduler(),
                new IcsParser(ZoneId.of("America/Denver")),
                new IcsExporter()
        );
    }

    @Test
    void appliesSentencesAutoSchedulesAndRoundTripsIcs(@TempDir Path tempDir) throws IOException {
        ApplySentenceResult workRes = service.applySentence("i work mon-thurs 7-6pm", MONDAY, ParserMode.RULES);
        assertEquals(1, workRes.getAddedRules());

        ApplySentenceResult goalsRes = service.applySentence(
                "i want to work out and study, i'm busy at 8pm for 2 hours, and i want to sleep at 12",
                MONDAY,
                ParserMode.RULES
        );
        assertEquals(1, goalsRes.getAddedEvents());
        assertEquals(2, goalsRes.getUpdatedGoals());
        assertTrue(goalsRes.isUpdatedSleep());
        assertNotNull(goalsRes.getScheduleResult());
        assertEquals(LocalTime.MIDNIGHT, service.loadPreferences().getSleepTime());

        List<CalendarEvent> weekEvents = service.getEventsForWeek(MONDAY);
        long workCount = weekEvents.stream().filter(e -> e.getCategory() == EventCategory.WORK).count();
        long busyCount = weekEvents.stream().filter(e -> e.getCategory() == EventCategory.BUSY).count();
        long workoutCount = weekEvents.stream().filter(e -> e.getCategory() == EventCategory.WORKOUT).count();
        long studyCount = weekEvents.stream().filter(e -> e.getCategory() == EventCategory.STUDY).count();

        assertEquals(4L, workCount);
        assertEquals(1L, busyCount);
        assertEquals(3L, workoutCount);
        assertTrue(studyCount >= 3L);

        Path exported = tempDir.resolve("week.ics");
        service.exportIcsFile(exported);

        IcsImportSummary imported = service.importIcsFile(exported);
        assertTrue(imported.getImportedEvents() >= 5);
        assertEquals(1, imported.getImportedRules());

        service.clearScheduledEvents();
        long remainingScheduled = service.getEventsForWeek(MONDAY).stream()
                .filter(e -> e.getSource() == EventSource.SCHEDULED)
                .count();
        assertEquals(0L, remainingScheduled);

        service.saveTheme("paper");
        assertEquals("paper", service.loadTheme());

        StorageStats stats = service.optimizeAndCompact();
        assertTrue(stats.getDatabaseBytes() > 0L);

        int removedPast = service.clearPastEvents(MONDAY.plusDays(7));
        assertTrue(removedPast >= 1);
        assertEquals(0, service.getStorageStats().getEventCount());

        StorageStats resetStats = service.resetCalendarHistoryAndSettings();
        assertEquals(0, resetStats.getEventCount());
        assertEquals(0, resetStats.getRecurringRuleCount());
        assertEquals(0, resetStats.getGoalCount());
    }

    @Test
    void savesAndUpdatesManualEventAndRecurringRuleTimings() {
        CalendarEvent manual = service.saveManualEvent(CalendarEvent.create(
                "Lab Review",
                LocalDateTime.of(2026, 11, 18, 10, 0),
                LocalDateTime.of(2026, 11, 18, 11, 0),
                EventCategory.STUDY,
                EventSource.MANUAL
        ));
        assertTrue(manual.getId() > 0L);

        CalendarEvent moved = new CalendarEvent(
                manual.getId(),
                manual.getUid(),
                "Lab Review Updated",
                LocalDateTime.of(2026, 11, 18, 14, 0),
                LocalDateTime.of(2026, 11, 18, 15, 30),
                EventCategory.BUSY,
                EventSource.MANUAL,
                "Room 204"
        );
        service.updateEvent(moved);

        service.applySentence("i work mon 8-12pm", MONDAY, ParserMode.RULES);
        RecurringRule existingRule = service.findAllRecurringRules().get(0);
        RecurringRule updatedRule = new RecurringRule(
                existingRule.getId(),
                "Lab Duty",
                EnumSet.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
                LocalTime.of(9, 30),
                LocalTime.of(13, 0),
                EventCategory.WORK,
                existingRule.getStartDate(),
                existingRule.getEndDate()
        );
        service.updateRecurringRule(updatedRule);

        List<CalendarEvent> weekEvents = service.getEventsForWeek(MONDAY);
        List<CalendarEvent> manualEvents = weekEvents.stream()
                .filter(e -> e.getSource() == EventSource.MANUAL)
                .toList();
        assertEquals(1, manualEvents.size());
        assertEquals("Lab Review Updated", manualEvents.get(0).getTitle());
        assertEquals(LocalDateTime.of(2026, 11, 18, 14, 0), manualEvents.get(0).getStartTime());
        assertEquals(LocalDateTime.of(2026, 11, 18, 15, 30), manualEvents.get(0).getEndTime());

        List<CalendarEvent> recurringEvents = weekEvents.stream()
                .filter(e -> e.getSource() == EventSource.RECURRING)
                .toList();
        assertEquals(2, recurringEvents.size());
        assertEquals(DayOfWeek.TUESDAY, recurringEvents.get(0).getStartTime().getDayOfWeek());
        assertEquals(DayOfWeek.THURSDAY, recurringEvents.get(1).getStartTime().getDayOfWeek());
        assertEquals(LocalTime.of(9, 30), recurringEvents.get(0).getStartTime().toLocalTime());
        assertEquals(LocalTime.of(13, 0), recurringEvents.get(0).getEndTime().toLocalTime());
    }

    @Test
    void persistsAndClearsAiParserPreferences(@TempDir Path tempDir) {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("ai.db"));
        db.initialize();
        CalendarService realService = new CalendarService(new CalendarRepository(db));

        assertFalse(realService.hasSavedGeminiApiKey());
        realService.saveGeminiApiKey("  sample-key-123  ");
        assertTrue(realService.hasSavedGeminiApiKey());

        realService.clearGeminiApiKey();
        assertFalse(realService.hasSavedGeminiApiKey());

        realService.saveOllamaSettings("http://127.0.0.1:11435", "qwen2.5");
        assertEquals("http://127.0.0.1:11435", realService.loadOllamaHost());
        assertEquals("qwen2.5", realService.loadOllamaModel());

        realService.saveOllamaSettings("", "");
        assertEquals(OllamaProvider.DEFAULT_MODEL, realService.loadOllamaModel());
    }

    @Test
    void appliesSentenceInAutoModeOnBackgroundExecutorWithoutDeadlock(@TempDir Path tempDir) throws Exception {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("deadlock.db"));
        db.initialize();
        CalendarService realService = new CalendarService(new CalendarRepository(db));
        realService.saveOllamaSettings("http://127.0.0.1:1", "llama3.2");

        CompletableFuture<ApplySentenceResult> future = CompletableFuture.supplyAsync(
                () -> realService.applySentence("i work mon-thurs 7-6pm", MONDAY, ParserMode.AUTO),
                CalendarService.backgroundExecutor()
        );
        ApplySentenceResult result = future.get(8, TimeUnit.SECONDS);
        assertEquals(1, result.getAddedRules());
    }
}
