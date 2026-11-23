package com.calendarorganizer.ui;

import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.EventCategory;
import com.calendarorganizer.model.EventColor;
import com.calendarorganizer.model.EventSource;
import com.calendarorganizer.model.RecurringRule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;

public final class EventFormValidator {

    public static final DateTimeFormatter TIME_PARSE_FMT = DateTimeFormatter.ofPattern("H:mm");
    public static final DateTimeFormatter TIME_DISPLAY_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private EventFormValidator() {
    }

    public static LocalTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Use HH:mm format (e.g. 09:00).");
        }
        try {
            return LocalTime.parse(raw.trim(), TIME_PARSE_FMT);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Use HH:mm format (e.g. 09:00).");
        }
    }

    public static CalendarEvent buildEvent(CalendarEvent existing,
                                           String rawTitle,
                                           LocalDate date,
                                           String rawStart,
                                           String rawEnd,
                                           EventCategory category,
                                           String notes) {
        return buildEvent(existing, rawTitle, date, rawStart, rawEnd, category, 1L, EventColor.DEFAULT, notes);
    }

    public static CalendarEvent buildEvent(CalendarEvent existing,
                                           String rawTitle,
                                           LocalDate date,
                                           String rawStart,
                                           String rawEnd,
                                           EventCategory category,
                                           long calendarId,
                                           EventColor color,
                                           String notes) {
        if (rawTitle == null || rawTitle.isBlank()) {
            throw new IllegalArgumentException("Title is required.");
        }
        if (date == null) {
            throw new IllegalArgumentException("Date is required.");
        }
        LocalTime start = parseTime(rawStart);
        LocalTime end = parseTime(rawEnd);
        if (end.equals(start)) {
            throw new IllegalArgumentException("End time cannot be the same as start time.");
        }

        LocalDate endDay = date;
        if (end.isBefore(start)) {
            endDay = date.plusDays(1);
        }
        LocalDateTime startDt = date.atTime(start);
        LocalDateTime endDt = endDay.atTime(end);

        EventCategory safeCategory = category;
        if (safeCategory == null) {
            safeCategory = EventCategory.GENERAL;
        }
        long safeCalendarId = calendarId;
        if (safeCalendarId <= 0L) {
            safeCalendarId = 1L;
        }
        EventColor safeColor = color;
        if (safeColor == null) {
            safeColor = EventColor.DEFAULT;
        }
        String safeNotes = notes;
        if (safeNotes == null) {
            safeNotes = "";
        }

        if (existing == null) {
            return new CalendarEvent(
                    0L, null, rawTitle.trim(), startDt, endDt, safeCategory, EventSource.MANUAL, safeNotes, safeCalendarId, safeColor
            );
        }
        return new CalendarEvent(
                existing.getId(),
                existing.getUid(),
                rawTitle.trim(),
                startDt,
                endDt,
                safeCategory,
                existing.getSource(),
                safeNotes,
                safeCalendarId,
                safeColor
        );
    }

    public static RecurringRule buildRecurringRule(RecurringRule existing,
                                                     String rawTitle,
                                                     Set<DayOfWeek> days,
                                                     String rawStart,
                                                     String rawEnd,
                                                     EventCategory category) {
        long ruleCalId = 1L;
        EventColor ruleColor = EventColor.DEFAULT;
        String ruleNotes = "";
        if (existing != null) {
            ruleCalId = existing.getCalendarId();
            ruleColor = existing.getColor();
            ruleNotes = existing.getNotes();
        }
        return buildRecurringRule(existing, rawTitle, days, rawStart, rawEnd, category, ruleCalId, ruleColor, ruleNotes);
    }

    public static RecurringRule buildRecurringRule(RecurringRule existing,
                                                     String rawTitle,
                                                     Set<DayOfWeek> days,
                                                     String rawStart,
                                                     String rawEnd,
                                                     EventCategory category,
                                                     long calendarId,
                                                     EventColor color,
                                                     String notes) {
        if (rawTitle == null || rawTitle.isBlank()) {
            throw new IllegalArgumentException("Title is required.");
        }
        if (days == null || days.isEmpty()) {
            throw new IllegalArgumentException("Select at least one day of the week.");
        }
        LocalTime start = parseTime(rawStart);
        LocalTime end = parseTime(rawEnd);
        if (end.equals(start)) {
            throw new IllegalArgumentException("End time cannot be the same as start time.");
        }

        EventCategory safeCategory = category;
        if (safeCategory == null) {
            safeCategory = EventCategory.GENERAL;
        }
        long safeCalendarId = calendarId;
        if (safeCalendarId <= 0L) {
            safeCalendarId = 1L;
        }
        EventColor safeColor = color;
        if (safeColor == null) {
            safeColor = EventColor.DEFAULT;
        }
        String safeNotes = notes;
        if (safeNotes == null) {
            safeNotes = "";
        }
        long ruleId = 0L;
        LocalDate startDate = null;
        LocalDate endDate = null;
        if (existing != null) {
            ruleId = existing.getId();
            startDate = existing.getStartDate();
            endDate = existing.getEndDate();
        }
        return new RecurringRule(
                ruleId, rawTitle.trim(), days, start, end, safeCategory, startDate, endDate, safeCalendarId, safeColor, safeNotes
        );
    }
}
