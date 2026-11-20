package com.calendarorganizer.ics;

import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.EventCategory;
import com.calendarorganizer.model.EventSource;
import com.calendarorganizer.model.RecurringRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IcsExporterTest {

    @Test
    void roundTripsEventsAndRecurringRulesThroughFile(@TempDir Path tempDir) throws IOException {
        CalendarEvent event = new CalendarEvent(
                10L,
                "evt-10@calendar-organizer",
                "Dentist, Checkup; Room 2",
                LocalDateTime.of(2026, 11, 18, 15, 0),
                LocalDateTime.of(2026, 11, 18, 16, 0),
                EventCategory.BUSY,
                EventSource.MANUAL,
                "First line\nSecond very long line with plenty of text to trigger RFC 5545 folding at seventy five chars"
        );

        RecurringRule rule = new RecurringRule(
                4L,
                "Work Shift",
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
                LocalTime.of(7, 0),
                LocalTime.of(18, 0),
                EventCategory.WORK,
                LocalDate.of(2026, 11, 16),
                LocalDate.of(2026, 12, 31)
        );

        IcsExporter exporter = new IcsExporter();
        Path outFile = tempDir.resolve("exported.ics");
        exporter.exportToFile(List.of(event), List.of(rule), outFile);

        String rawText = exporter.exportToText(List.of(event), List.of(rule));
        assertTrue(rawText.contains("BEGIN:VCALENDAR\r\n"));
        assertTrue(rawText.contains("RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH;UNTIL=20261231"));
        for (String line : rawText.split("\r\n")) {
            assertTrue(line.length() <= 75);
        }

        IcsParser parser = new IcsParser(ZoneId.of("America/Denver"));
        IcsParseResult reparsed = parser.parseFile(outFile);

        assertEquals(1, reparsed.getEvents().size());
        CalendarEvent parsedEvent = reparsed.getEvents().get(0);
        assertEquals(event.getUid(), parsedEvent.getUid());
        assertEquals(event.getTitle(), parsedEvent.getTitle());
        assertEquals(event.getStartTime(), parsedEvent.getStartTime());
        assertEquals(event.getEndTime(), parsedEvent.getEndTime());
        assertEquals(event.getNotes(), parsedEvent.getNotes());

        assertEquals(1, reparsed.getRecurringRules().size());
        RecurringRule parsedRule = reparsed.getRecurringRules().get(0);
        assertEquals(rule.getTitle(), parsedRule.getTitle());
        assertEquals(rule.getDaysOfWeek(), parsedRule.getDaysOfWeek());
        assertEquals(rule.getStartTime(), parsedRule.getStartTime());
        assertEquals(rule.getEndTime(), parsedRule.getEndTime());
        assertEquals(rule.getEndDate(), parsedRule.getEndDate());
    }
}
