package com.calendarorganizer.ics;

import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.EventCategory;
import com.calendarorganizer.model.EventSource;
import com.calendarorganizer.model.RecurringRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IcsParserTest {

    @Test
    void parsesUtcAndTzidAndAllDayAndFoldedDescriptions() {
        String ics = ""
                + "BEGIN:VCALENDAR\r\n"
                + "VERSION:2.0\r\n"
                + "BEGIN:VEVENT\r\n"
                + "UID:utc-1\r\n"
                + "SUMMARY:Team Sync\\, Part 1\r\n"
                + "DESCRIPTION:Line one\\nLine\r\n"
                + "  two\r\n"
                + "DTSTART:20261116T170000Z\r\n"
                + "DTEND:20261116T180000Z\r\n"
                + "BEGIN:VALARM\r\n"
                + "ACTION:DISPLAY\r\n"
                + "DESCRIPTION:Reminder\r\n"
                + "END:VALARM\r\n"
                + "END:VEVENT\r\n"
                + "BEGIN:VEVENT\r\n"
                + "UID:tz-2\r\n"
                + "SUMMARY:East Coast Call\r\n"
                + "DTSTART;TZID=America/New_York:20261116T140000\r\n"
                + "DURATION:PT45M\r\n"
                + "END:VEVENT\r\n"
                + "BEGIN:VEVENT\r\n"
                + "UID:allday-3\r\n"
                + "SUMMARY:Reading Day\r\n"
                + "DTSTART;VALUE=DATE:20261120\r\n"
                + "DTEND;VALUE=DATE:20261121\r\n"
                + "END:VEVENT\r\n"
                + "END:VCALENDAR\r\n";

        IcsParser parser = new IcsParser(ZoneId.of("America/Denver"));
        IcsParseResult result = parser.parseText(ics);

        assertEquals(3, result.getEvents().size());

        CalendarEvent first = result.getEvents().get(0);
        assertEquals("utc-1", first.getUid());
        assertEquals("Team Sync, Part 1", first.getTitle());
        assertEquals("Line one\nLine two", first.getNotes());
        assertEquals(LocalDateTime.of(2026, 11, 16, 10, 0), first.getStartTime());
        assertEquals(LocalDateTime.of(2026, 11, 16, 11, 0), first.getEndTime());
        assertEquals(EventSource.IMPORTED, first.getSource());
        assertEquals(EventCategory.IMPORTED, first.getCategory());

        CalendarEvent second = result.getEvents().get(1);
        assertEquals(LocalDateTime.of(2026, 11, 16, 12, 0), second.getStartTime());
        assertEquals(LocalDateTime.of(2026, 11, 16, 12, 45), second.getEndTime());

        CalendarEvent third = result.getEvents().get(2);
        assertEquals(LocalDateTime.of(2026, 11, 20, 0, 0), third.getStartTime());
        assertEquals(LocalDateTime.of(2026, 11, 21, 0, 0), third.getEndTime());
    }

    @Test
    void parsesWeeklyRruleAndFileImport(@TempDir Path tempDir) throws IOException {
        String ics = ""
                + "BEGIN:VCALENDAR\r\n"
                + "BEGIN:VEVENT\r\n"
                + "UID:lecture-rrule\r\n"
                + "SUMMARY:CMPT 201 Lecture\r\n"
                + "DTSTART:20261116T100000\r\n"
                + "DTEND:20261116T112000\r\n"
                + "RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR;UNTIL=20261215T235959Z\r\n"
                + "END:VEVENT\r\n"
                + "END:VCALENDAR\r\n";

        Path icsFile = tempDir.resolve("schedule.ics");
        Files.writeString(icsFile, ics);

        IcsParser parser = new IcsParser(ZoneId.of("America/Denver"));
        IcsParseResult result = parser.parseFile(icsFile);

        assertTrue(result.getEvents().isEmpty());
        assertEquals(1, result.getRecurringRules().size());

        RecurringRule rule = result.getRecurringRules().get(0);
        assertEquals("CMPT 201 Lecture", rule.getTitle());
        assertEquals(EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), rule.getDaysOfWeek());
        assertEquals(LocalTime.of(10, 0), rule.getStartTime());
        assertEquals(LocalTime.of(11, 20), rule.getEndTime());
        assertEquals(LocalDate.of(2026, 11, 16), rule.getStartDate());
        assertEquals(LocalDate.of(2026, 12, 15), rule.getEndDate());
    }

    @Test
    void emitsWarningForEmptyOrInvalidText() {
        IcsParser parser = new IcsParser(ZoneId.of("UTC"));
        IcsParseResult emptyResult = parser.parseText("   ");
        assertFalse(emptyResult.getWarnings().isEmpty());

        IcsParseResult missingStart = parser.parseText("BEGIN:VCALENDAR\nBEGIN:VEVENT\nSUMMARY:NoDate\nEND:VEVENT\nEND:VCALENDAR");
        assertTrue(missingStart.getEvents().isEmpty());
        assertFalse(missingStart.getWarnings().isEmpty());
    }
}
