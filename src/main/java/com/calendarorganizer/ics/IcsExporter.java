package com.calendarorganizer.ics;

import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.RecurringRule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;

public class IcsExporter {

    private static final DateTimeFormatter BASIC_DT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String CRLF = "\r\n";

    public void exportToFile(List<CalendarEvent> events, List<RecurringRule> rules, Path targetFile) throws IOException {
        Path parent = targetFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String text = exportToText(events, rules);
        Files.writeString(targetFile, text, StandardCharsets.UTF_8);
    }

    public String exportToText(List<CalendarEvent> events, List<RecurringRule> rules) {
        StringBuilder sb = new StringBuilder();
        appendFoldedLine(sb, "BEGIN:VCALENDAR");
        appendFoldedLine(sb, "VERSION:2.0");
        appendFoldedLine(sb, "PRODID:-//Calendar Organizer//EN");
        appendFoldedLine(sb, "CALSCALE:GREGORIAN");

        if (events != null) {
            for (CalendarEvent event : events) {
                writeEvent(sb, event);
            }
        }
        if (rules != null) {
            for (RecurringRule rule : rules) {
                writeRecurringRule(sb, rule);
            }
        }

        appendFoldedLine(sb, "END:VCALENDAR");
        return sb.toString();
    }

    private void writeEvent(StringBuilder sb, CalendarEvent event) {
        appendFoldedLine(sb, "BEGIN:VEVENT");
        appendFoldedLine(sb, "UID:" + event.getUid());
        appendFoldedLine(sb, "SUMMARY:" + escapeText(event.getTitle()));
        appendFoldedLine(sb, "DTSTART:" + event.getStartTime().format(BASIC_DT));
        appendFoldedLine(sb, "DTEND:" + event.getEndTime().format(BASIC_DT));
        if (event.getNotes() != null && !event.getNotes().isBlank()) {
            appendFoldedLine(sb, "DESCRIPTION:" + escapeText(event.getNotes()));
        }
        appendFoldedLine(sb, "CATEGORIES:" + event.getCategory().name());
        appendFoldedLine(sb, "END:VEVENT");
    }

    private void writeRecurringRule(StringBuilder sb, RecurringRule rule) {
        if (rule.getDaysOfWeek().isEmpty()) {
            return;
        }
        List<DayOfWeek> sortedDays = new ArrayList<>(rule.getDaysOfWeek());
        sortedDays.sort(Comparator.comparingInt(DayOfWeek::getValue));

        LocalDate anchorBase = rule.getStartDate();
        if (anchorBase == null) {
            anchorBase = LocalDate.now();
        }
        LocalDate anchorDate = anchorBase.with(TemporalAdjusters.nextOrSame(sortedDays.get(0)));
        LocalDateTime dtStart = anchorDate.atTime(rule.getStartTime());
        LocalDate endDay = anchorDate;
        if (!rule.getEndTime().isAfter(rule.getStartTime())) {
            endDay = anchorDate.plusDays(1);
        }
        LocalDateTime dtEnd = endDay.atTime(rule.getEndTime());

        StringBuilder rrule = new StringBuilder("RRULE:FREQ=WEEKLY;BYDAY=");
        StringJoiner byDayJoiner = new StringJoiner(",");
        for (DayOfWeek day : sortedDays) {
            byDayJoiner.add(toIcsDay(day));
        }
        rrule.append(byDayJoiner);
        if (rule.getEndDate() != null) {
            rrule.append(";UNTIL=").append(rule.getEndDate().format(BASIC_DATE));
        }

        appendFoldedLine(sb, "BEGIN:VEVENT");
        appendFoldedLine(sb, "UID:rule-" + rule.getId() + "@calendar-organizer");
        appendFoldedLine(sb, "SUMMARY:" + escapeText(rule.getTitle()));
        appendFoldedLine(sb, "DTSTART:" + dtStart.format(BASIC_DT));
        appendFoldedLine(sb, "DTEND:" + dtEnd.format(BASIC_DT));
        appendFoldedLine(sb, rrule.toString());
        appendFoldedLine(sb, "CATEGORIES:" + rule.getCategory().name());
        appendFoldedLine(sb, "END:VEVENT");
    }

    private String toIcsDay(DayOfWeek day) {
        switch (day) {
            case MONDAY:
                return "MO";
            case TUESDAY:
                return "TU";
            case WEDNESDAY:
                return "WE";
            case THURSDAY:
                return "TH";
            case FRIDAY:
                return "FR";
            case SATURDAY:
                return "SA";
            case SUNDAY:
                return "SU";
            default:
                return "MO";
        }
    }

    private String escapeText(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n");
    }

    private void appendFoldedLine(StringBuilder sb, String line) {
        //folds lines over 75 characters per rfc5545
        if (line.length() <= 75) {
            sb.append(line).append(CRLF);
            return;
        }
        int offset = 0;
        boolean first = true;
        while (offset < line.length()) {
            int maxLen;
            if (first) {
                maxLen = 75;
            } else {
                maxLen = 74;
            }
            int end = Math.min(offset + maxLen, line.length());
            if (!first) {
                sb.append(' ');
            }
            sb.append(line, offset, end).append(CRLF);
            offset = end;
            first = false;
        }
    }
}
