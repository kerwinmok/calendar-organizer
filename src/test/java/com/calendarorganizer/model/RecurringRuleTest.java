package com.calendarorganizer.model;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecurringRuleTest {

    @Test
    void expandsWeekdayRuleWithinDateBounds() {
        RecurringRule rule = new RecurringRule(
                1L,
                "Work",
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
                LocalTime.of(7, 0),
                LocalTime.of(18, 0),
                EventCategory.WORK,
                LocalDate.of(2026, 11, 17),
                LocalDate.of(2026, 11, 19)
        );

        List<CalendarEvent> events = rule.expandForRange(
                LocalDate.of(2026, 11, 16),
                LocalDate.of(2026, 11, 22)
        );

        assertEquals(3, events.size());
        assertEquals(LocalDateTime.of(2026, 11, 17, 7, 0), events.get(0).getStartTime());
        assertEquals(LocalDateTime.of(2026, 11, 17, 18, 0), events.get(0).getEndTime());
        assertEquals(LocalDateTime.of(2026, 11, 19, 7, 0), events.get(2).getStartTime());
    }

    @Test
    void expandsMidnightCrossingRuleFromPreviousDayIntoRange() {
        RecurringRule nightShift = new RecurringRule(
                2L,
                "Night Shift",
                EnumSet.of(DayOfWeek.SUNDAY),
                LocalTime.of(22, 0),
                LocalTime.of(6, 0),
                EventCategory.WORK,
                null,
                null
        );

        List<CalendarEvent> events = nightShift.expandForRange(
                LocalDate.of(2026, 11, 16),
                LocalDate.of(2026, 11, 16)
        );

        assertEquals(1, events.size());
        assertEquals(LocalDateTime.of(2026, 11, 15, 22, 0), events.get(0).getStartTime());
        assertEquals(LocalDateTime.of(2026, 11, 16, 6, 0), events.get(0).getEndTime());
    }
}
