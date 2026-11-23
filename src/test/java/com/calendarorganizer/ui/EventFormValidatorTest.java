package com.calendarorganizer.ui;

import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.EventCategory;
import com.calendarorganizer.model.EventColor;
import com.calendarorganizer.model.EventSource;
import com.calendarorganizer.model.RecurringRule;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventFormValidatorTest {

    @Test
    void parsesFlexibleHourFormatsAndRejectsInvalidTimes() {
        assertEquals(LocalTime.of(9, 0), EventFormValidator.parseTime("9:00"));
        assertEquals(LocalTime.of(9, 0), EventFormValidator.parseTime("09:00"));
        assertEquals(LocalTime.of(0, 0), EventFormValidator.parseTime("0:00"));
        assertEquals(LocalTime.of(23, 45), EventFormValidator.parseTime(" 23:45 "));

        assertThrows(IllegalArgumentException.class, () -> EventFormValidator.parseTime("25:00"));
        assertThrows(IllegalArgumentException.class, () -> EventFormValidator.parseTime("abc"));
        assertThrows(IllegalArgumentException.class, () -> EventFormValidator.parseTime(""));
        assertThrows(IllegalArgumentException.class, () -> EventFormValidator.parseTime(null));
    }

    @Test
    void buildsNewAndEditedEventsIncludingMidnightWrap() {
        LocalDate date = LocalDate.of(2026, 11, 18);
        CalendarEvent created = EventFormValidator.buildEvent(
                null,
                "  Study Session  ",
                date,
                "9:00",
                "11:30",
                EventCategory.STUDY,
                null
        );
        assertEquals(0L, created.getId());
        assertEquals("Study Session", created.getTitle());
        assertEquals(LocalDateTime.of(2026, 11, 18, 9, 0), created.getStartTime());
        assertEquals(LocalDateTime.of(2026, 11, 18, 11, 30), created.getEndTime());
        assertEquals(EventCategory.STUDY, created.getCategory());
        assertEquals(EventSource.MANUAL, created.getSource());
        assertEquals("", created.getNotes());

        CalendarEvent existing = new CalendarEvent(
                42L,
                "uid-42",
                "Old",
                LocalDateTime.of(2026, 11, 18, 20, 0),
                LocalDateTime.of(2026, 11, 18, 21, 0),
                EventCategory.IMPORTED,
                EventSource.IMPORTED,
                ""
        );
        CalendarEvent edited = EventFormValidator.buildEvent(
                existing,
                "Night Shift",
                date,
                "22:00",
                "2:00",
                null,
                "Lab B"
        );
        assertEquals(42L, edited.getId());
        assertEquals("uid-42", edited.getUid());
        assertEquals(EventSource.IMPORTED, edited.getSource());
        assertEquals(EventCategory.GENERAL, edited.getCategory());
        assertEquals(LocalDateTime.of(2026, 11, 18, 22, 0), edited.getStartTime());
        assertEquals(LocalDateTime.of(2026, 11, 19, 2, 0), edited.getEndTime());
        assertEquals("Lab B", edited.getNotes());
    }

    @Test
    void rejectsEqualStartAndEndTimesBlankTitlesAndNullDate() {
        LocalDate date = LocalDate.of(2026, 11, 18);
        assertThrows(IllegalArgumentException.class, () ->
                EventFormValidator.buildEvent(null, "Standup", date, "9:00", "09:00", EventCategory.BUSY, ""));
        assertThrows(IllegalArgumentException.class, () ->
                EventFormValidator.buildEvent(null, "   ", date, "9:00", "10:00", EventCategory.BUSY, ""));
        assertThrows(IllegalArgumentException.class, () ->
                EventFormValidator.buildEvent(null, "Standup", null, "9:00", "10:00", EventCategory.BUSY, ""));
    }

    @Test
    void validatesAndBuildsRecurringRules() {
        RecurringRule existing = new RecurringRule(
                7L,
                "Work",
                EnumSet.of(DayOfWeek.MONDAY),
                LocalTime.of(7, 0),
                LocalTime.of(15, 0),
                EventCategory.WORK,
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 12, 20)
        );
        RecurringRule updated = EventFormValidator.buildRecurringRule(
                existing,
                "Lab Shift",
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                "8:30",
                "10:00",
                EventCategory.WORK
        );
        assertEquals(7L, updated.getId());
        assertEquals("Lab Shift", updated.getTitle());
        assertEquals(EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), updated.getDaysOfWeek());
        assertEquals(LocalTime.of(8, 30), updated.getStartTime());
        assertEquals(LocalTime.of(10, 0), updated.getEndTime());
        assertEquals(LocalDate.of(2026, 9, 1), updated.getStartDate());
        assertEquals(LocalDate.of(2026, 12, 20), updated.getEndDate());

        assertThrows(IllegalArgumentException.class, () ->
                EventFormValidator.buildRecurringRule(existing, "Lab", EnumSet.noneOf(DayOfWeek.class), "8:30", "10:00", EventCategory.WORK));
        assertThrows(IllegalArgumentException.class, () ->
                EventFormValidator.buildRecurringRule(existing, "Lab", EnumSet.of(DayOfWeek.MONDAY), "10:00", "10:00", EventCategory.WORK));
        assertThrows(IllegalArgumentException.class, () ->
                EventFormValidator.buildRecurringRule(existing, "", EnumSet.of(DayOfWeek.MONDAY), "8:30", "10:00", EventCategory.WORK));

        CalendarEvent layeredEvent = EventFormValidator.buildEvent(
                null,
                "Shift",
                LocalDate.of(2026, 11, 18),
                "07:00",
                "12:00",
                EventCategory.GENERAL,
                2L,
                EventColor.TEAL,
                "Front desk"
        );
        assertEquals(2L, layeredEvent.getCalendarId());
        assertEquals(EventColor.TEAL, layeredEvent.getColor());
        assertEquals("Front desk", layeredEvent.getNotes());

        RecurringRule layeredRule = EventFormValidator.buildRecurringRule(
                null,
                "CS Lecture",
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                "13:30",
                "14:20",
                EventCategory.GENERAL,
                3L,
                EventColor.PURPLE,
                "Building AQ 3150"
        );
        assertEquals(3L, layeredRule.getCalendarId());
        assertEquals(EventColor.PURPLE, layeredRule.getColor());
        assertEquals("Building AQ 3150", layeredRule.getNotes());
    }
}
