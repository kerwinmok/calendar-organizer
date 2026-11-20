package com.calendarorganizer.parser;

import com.calendarorganizer.model.EventCategory;
import com.calendarorganizer.model.GoalType;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleParserTest {

    private static final LocalDate REF_MONDAY = LocalDate.of(2026, 11, 16);

    private final RuleParser parser = new RuleParser();

    @Test
    void parsesRecurringWorkSchedule() {
        ParseResult result = parser.parse("i work mon-thurs 7-6pm", REF_MONDAY);

        assertTrue(result.getEvents().isEmpty());
        assertEquals(1, result.getRecurringRules().size());

        ParsedRecurringRule rule = result.getRecurringRules().get(0);
        assertEquals("Work", rule.getTitle());
        assertEquals(
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
                rule.getDaysOfWeek()
        );
        assertEquals(LocalTime.of(7, 0), rule.getStartTime());
        assertEquals(LocalTime.of(18, 0), rule.getEndTime());
        assertEquals(EventCategory.WORK, rule.getCategory());
    }

    @Test
    void parsesCombinedGoalsBusyBlockAndSleepTime() {
        ParseResult result = parser.parse(
                "i want to work out and study, i'm busy at 8pm for 2 hours, and i want to sleep at 12",
                REF_MONDAY
        );

        assertEquals(2, result.getGoals().size());
        assertEquals(GoalType.WORKOUT, result.getGoals().get(0).getType());
        assertEquals(GoalType.STUDY, result.getGoals().get(1).getType());

        assertEquals(1, result.getEvents().size());
        ParsedEvent busy = result.getEvents().get(0);
        assertEquals(EventCategory.BUSY, busy.getCategory());
        assertEquals(REF_MONDAY, busy.getDate());
        assertEquals(LocalTime.of(20, 0), busy.getStartTime());
        assertEquals(LocalTime.of(22, 0), busy.getEndTime());

        assertEquals(LocalTime.MIDNIGHT, result.getSleepTime());
    }

    @Test
    void parsesOvernightShiftAndSlashDaysAndTomorrowEvent() {
        ParseResult nightShift = parser.parse("night shift fri-sat 10pm-6am", REF_MONDAY);
        assertEquals(1, nightShift.getRecurringRules().size());
        ParsedRecurringRule shiftRule = nightShift.getRecurringRules().get(0);
        assertEquals(EnumSet.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), shiftRule.getDaysOfWeek());
        assertEquals(LocalTime.of(22, 0), shiftRule.getStartTime());
        assertEquals(LocalTime.of(6, 0), shiftRule.getEndTime());

        ParseResult lecture = parser.parse("class m/w/f 9-10:30am", REF_MONDAY);
        assertEquals(1, lecture.getRecurringRules().size());
        assertEquals(
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                lecture.getRecurringRules().get(0).getDaysOfWeek()
        );
        assertEquals(LocalTime.of(9, 0), lecture.getRecurringRules().get(0).getStartTime());
        assertEquals(LocalTime.of(10, 30), lecture.getRecurringRules().get(0).getEndTime());

        ParseResult tomorrow = parser.parse("busy tomorrow at 3pm for 90 minutes", REF_MONDAY);
        assertEquals(1, tomorrow.getEvents().size());
        assertEquals(REF_MONDAY.plusDays(1), tomorrow.getEvents().get(0).getDate());
        assertEquals(LocalTime.of(15, 0), tomorrow.getEvents().get(0).getStartTime());
        assertEquals(LocalTime.of(16, 30), tomorrow.getEvents().get(0).getEndTime());
    }

    @Test
    void parsesCustomFrequenciesAndSleepVariations() {
        ParseResult gymGoal = parser.parse("gym 4 times a week for 45 minutes", REF_MONDAY);
        assertEquals(1, gymGoal.getGoals().size());
        assertEquals(4, gymGoal.getGoals().get(0).getSessionsPerWeek());
        assertEquals(45, gymGoal.getGoals().get(0).getSessionMinutes());

        ParseResult studyGoal = parser.parse("study 10 hours a week", REF_MONDAY);
        assertEquals(1, studyGoal.getGoals().size());
        assertEquals(600, studyGoal.getGoals().get(0).getWeeklyMinutes());

        assertEquals(LocalTime.of(23, 0), parser.parse("sleep at 11", REF_MONDAY).getSleepTime());
        assertEquals(LocalTime.of(1, 0), parser.parse("sleep at 1am", REF_MONDAY).getSleepTime());
    }

    @Test
    void returnsEmptyWithWarningOnUnrecognizedPhrase() {
        ParseResult result = parser.parse("hello world", REF_MONDAY);
        assertTrue(result.isEmpty());
        assertFalse(result.getWarnings().isEmpty());
    }
}
