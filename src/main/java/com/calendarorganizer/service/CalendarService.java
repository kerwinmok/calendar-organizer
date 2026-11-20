package com.calendarorganizer.service;

import com.calendarorganizer.ics.IcsExporter;
import com.calendarorganizer.ics.IcsParseResult;
import com.calendarorganizer.ics.IcsParser;
import com.calendarorganizer.llm.GeminiProvider;
import com.calendarorganizer.llm.OllamaProvider;
import com.calendarorganizer.llm.ParserMode;
import com.calendarorganizer.llm.ScheduleParserService;
import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.EventSource;
import com.calendarorganizer.model.RecurringRule;
import com.calendarorganizer.model.ScheduleGoal;
import com.calendarorganizer.model.SchedulePreferences;
import com.calendarorganizer.parser.ParseResult;
import com.calendarorganizer.parser.ParseResultMapper;
import com.calendarorganizer.parser.RuleParser;
import com.calendarorganizer.scheduler.ScheduleResult;
import com.calendarorganizer.scheduler.Scheduler;
import com.calendarorganizer.storage.CalendarRepository;
import com.calendarorganizer.storage.StorageStats;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class CalendarService {

    private final CalendarRepository repository;
    private final ScheduleParserService parserService;
    private final ParseResultMapper mapper;
    private final Scheduler scheduler;
    private final IcsParser icsParser;
    private final IcsExporter icsExporter;

    public CalendarService(CalendarRepository repository) {
        this(repository, createSharedParserService());
    }

    private CalendarService(CalendarRepository repository, ScheduleParserService parserService) {
        this(
                repository,
                parserService,
                new ParseResultMapper(),
                new Scheduler(),
                new IcsParser(),
                new IcsExporter()
        );
    }

    private static ScheduleParserService createSharedParserService() {
        HttpClient sharedClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        return new ScheduleParserService(
                new RuleParser(),
                new OllamaProvider(sharedClient),
                new GeminiProvider(sharedClient)
        );
    }

    public CalendarService(CalendarRepository repository,
                           ScheduleParserService parserService,
                           ParseResultMapper mapper,
                           Scheduler scheduler,
                           IcsParser icsParser,
                           IcsExporter icsExporter) {
        this.repository = repository;
        this.parserService = parserService;
        this.mapper = mapper;
        this.scheduler = scheduler;
        this.icsParser = icsParser;
        this.icsExporter = icsExporter;
    }

    public LocalDate weekStartOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public ApplySentenceResult applySentence(String sentence, LocalDate referenceDate, ParserMode mode) {
        ParseResult parseResult = parserService.parse(sentence, referenceDate, mode);
        List<CalendarEvent> events = mapper.toEvents(parseResult, referenceDate);
        List<RecurringRule> rules = mapper.toRecurringRules(parseResult);
        List<ScheduleGoal> goals = mapper.toGoals(parseResult);

        for (CalendarEvent event : events) {
            repository.saveEvent(event);
        }
        for (RecurringRule rule : rules) {
            repository.saveRecurringRule(rule);
        }
        for (ScheduleGoal goal : goals) {
            repository.deleteGoalsByType(goal.getType());
            repository.saveGoal(goal);
        }

        boolean updatedSleep = false;
        if (parseResult.getSleepTime() != null) {
            SchedulePreferences current = repository.loadPreferences();
            repository.savePreferences(new SchedulePreferences(parseResult.getSleepTime(), current.getSleepDurationHours()));
            updatedSleep = true;
        }

        ScheduleResult scheduleResult = null;
        if (!parseResult.isEmpty() && !repository.findAllGoals().isEmpty()) {
            scheduleResult = rescheduleWeek(weekStartOf(referenceDate));
        }

        return new ApplySentenceResult(
                parseResult,
                events.size(),
                rules.size(),
                goals.size(),
                updatedSleep,
                scheduleResult
        );
    }

    public ScheduleResult rescheduleWeek(LocalDate weekStart) {
        repository.deleteScheduledEventsInRange(weekStart.atStartOfDay(), weekStart.plusDays(7).atStartOfDay());
        List<CalendarEvent> existing = repository.findEventsInRange(
                weekStart.minusDays(1).atStartOfDay(),
                weekStart.plusDays(8).atStartOfDay()
        );
        List<RecurringRule> rules = repository.findAllRecurringRules();
        List<ScheduleGoal> goals = repository.findAllGoals();
        SchedulePreferences prefs = repository.loadPreferences();

        ScheduleResult result = scheduler.scheduleWeek(weekStart, existing, rules, goals, prefs);
        for (CalendarEvent scheduled : result.getScheduledEvents()) {
            repository.saveEvent(scheduled);
        }
        return result;
    }

    public List<CalendarEvent> getEventsForWeek(LocalDate weekStart) {
        List<CalendarEvent> combined = new ArrayList<>();
        combined.addAll(repository.findEventsInRange(
                weekStart.atStartOfDay(),
                weekStart.plusDays(7).atStartOfDay()
        ));
        LocalDate weekEndDay = weekStart.plusDays(6);
        for (RecurringRule rule : repository.findAllRecurringRules()) {
            combined.addAll(rule.expandForRange(weekStart, weekEndDay));
        }
        combined.sort(Comparator.comparing(CalendarEvent::getStartTime).thenComparing(CalendarEvent::getEndTime));
        return combined;
    }

    public IcsImportSummary importIcsFile(Path file) throws IOException {
        IcsParseResult parsed = icsParser.parseFile(file);
        return persistIcsResult(parsed);
    }

    public IcsImportSummary importIcsText(String icsText) {
        IcsParseResult parsed = icsParser.parseText(icsText);
        return persistIcsResult(parsed);
    }

    private IcsImportSummary persistIcsResult(IcsParseResult parsed) {
        for (CalendarEvent event : parsed.getEvents()) {
            repository.upsertEventByUid(event);
        }
        for (RecurringRule rule : parsed.getRecurringRules()) {
            repository.saveRecurringRule(rule);
        }
        return new IcsImportSummary(parsed.getEvents().size(), parsed.getRecurringRules().size(), parsed.getWarnings());
    }

    public void exportIcsFile(Path targetFile) throws IOException {
        List<CalendarEvent> events = repository.findAllEvents();
        List<RecurringRule> rules = repository.findAllRecurringRules();
        icsExporter.exportToFile(events, rules, targetFile);
    }

    public List<RecurringRule> findAllRecurringRules() {
        return repository.findAllRecurringRules();
    }

    public List<ScheduleGoal> findAllGoals() {
        return repository.findAllGoals();
    }

    public SchedulePreferences loadPreferences() {
        return repository.loadPreferences();
    }

    public void updateSleepTime(LocalTime sleepTime) {
        SchedulePreferences current = repository.loadPreferences();
        repository.savePreferences(new SchedulePreferences(sleepTime, current.getSleepDurationHours()));
    }

    public void deleteEvent(long id) {
        repository.deleteEvent(id);
    }

    public void deleteRecurringRule(long id) {
        repository.deleteRecurringRule(id);
    }

    public void deleteGoal(long id) {
        repository.deleteGoal(id);
    }

    public void clearScheduledEvents() {
        repository.deleteEventsBySource(EventSource.SCHEDULED);
    }

    public int clearPastEvents(LocalDate beforeDate) {
        return repository.deleteEventsEndingBefore(beforeDate.atStartOfDay());
    }

    public StorageStats resetCalendarHistoryAndSettings() {
        return repository.clearAllCalendarData();
    }

    public StorageStats getStorageStats() {
        return repository.getStorageStats();
    }

    public StorageStats optimizeAndCompact() {
        return repository.optimizeDatabase();
    }

    public String loadTheme() {
        return repository.loadTheme();
    }

    public void saveTheme(String themeId) {
        repository.saveTheme(themeId);
    }
}
