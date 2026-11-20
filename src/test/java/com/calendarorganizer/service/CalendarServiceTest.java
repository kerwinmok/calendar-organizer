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
import com.calendarorganizer.parser.ParseResultMapper;
import com.calendarorganizer.parser.RuleParser;
import com.calendarorganizer.scheduler.Scheduler;
import com.calendarorganizer.storage.CalendarRepository;
import com.calendarorganizer.storage.DatabaseManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    }
}
