package com.calendarorganizer.llm;

import com.calendarorganizer.model.EventCategory;
import com.calendarorganizer.model.GoalType;
import com.calendarorganizer.parser.ParseResult;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmJsonParserTest {

    private final LlmJsonParser parser = new LlmJsonParser();
    private final LocalDate ref = LocalDate.of(2026, 11, 16);

    @Test
    void parsesMarkdownFencedJsonResponse() throws LlmException {
        String json = "```json\n"
                + "{\n"
                + "  \"events\": [{\"title\": \"Busy\", \"date\": \"2026-11-16\", \"startTime\": \"20:00\", \"endTime\": \"22:00\", \"category\": \"BUSY\"}],\n"
                + "  \"recurringRules\": [{\"title\": \"Work\", \"daysOfWeek\": [\"MONDAY\", \"TUESDAY\", \"WEDNESDAY\", \"THURSDAY\"], \"startTime\": \"07:00\", \"endTime\": \"18:00\", \"category\": \"WORK\"}],\n"
                + "  \"goals\": [{\"type\": \"WORKOUT\", \"title\": \"Workout\", \"sessionsPerWeek\": 3, \"sessionMinutes\": 60, \"weeklyMinutes\": 0}],\n"
                + "  \"sleepTime\": \"00:00\"\n"
                + "}\n"
                + "```";

        ParseResult result = parser.parse(json, "gemini", ref);
        assertEquals("gemini", result.getParserUsed());
        assertEquals(1, result.getEvents().size());
        assertEquals(EventCategory.BUSY, result.getEvents().get(0).getCategory());
        assertEquals(1, result.getRecurringRules().size());
        assertEquals(EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
                result.getRecurringRules().get(0).getDaysOfWeek());
        assertEquals(1, result.getGoals().size());
        assertEquals(GoalType.WORKOUT, result.getGoals().get(0).getType());
        assertEquals(LocalTime.MIDNIGHT, result.getSleepTime());
    }

    @Test
    void recordsWarningsForInvalidItemsAndRejectsNonJson() throws LlmException {
        String partial = "{\"events\": [{\"title\": \"Bad\", \"startTime\": \"bad\", \"endTime\": \"12:00\"}], \"goals\": [{\"type\": \"UNKNOWN\"}]}";
        ParseResult res = parser.parse(partial, "ollama", ref);
        assertFalse(res.getWarnings().isEmpty());

        assertThrows(LlmException.class, () -> parser.parse("not json at all", "ollama", ref));
    }
}
