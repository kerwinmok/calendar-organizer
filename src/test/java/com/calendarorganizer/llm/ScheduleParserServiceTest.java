package com.calendarorganizer.llm;

import com.calendarorganizer.model.EventCategory;
import com.calendarorganizer.parser.ParseResult;
import com.calendarorganizer.parser.ParsedEvent;
import com.calendarorganizer.parser.RuleParser;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ScheduleParserServiceTest {

    private static final LocalDate REF = LocalDate.of(2026, 11, 16);

    @Test
    void prefersGeminiInAutoModeAndFallsBackToOllamaThenRules() {
        LlmProvider unavailable = new StubProvider("gemini", false, false, null);
        LlmProvider workingOllama = new StubProvider("ollama", true, false,
                new ParseResult(
                        List.of(new ParsedEvent("Study Session", REF, LocalTime.of(19, 0), LocalTime.of(20, 0), EventCategory.STUDY)),
                        List.of(),
                        List.of(),
                        null,
                        "ollama",
                        List.of()
                ));

        ScheduleParserService service = new ScheduleParserService(new RuleParser(), workingOllama, unavailable);
        ParseResult res = service.parse("study tonight", REF, ParserMode.AUTO);
        assertEquals("ollama", res.getParserUsed());
        assertEquals(1, res.getEvents().size());

        LlmProvider failingGemini = new StubProvider("gemini", true, true, null);
        ScheduleParserService fallbackService = new ScheduleParserService(new RuleParser(), unavailable, failingGemini);
        ParseResult fallbackRes = fallbackService.parse("i work mon-thurs 7-6pm", REF, ParserMode.AUTO);
        assertEquals("rules", fallbackRes.getParserUsed());
        assertEquals(1, fallbackRes.getRecurringRules().size());
        assertFalse(fallbackRes.getWarnings().isEmpty());
    }

    @Test
    void respectsExplicitRulesModeEvenWhenProvidersAreAvailable() {
        LlmProvider workingGemini = new StubProvider("gemini", true, false,
                new ParseResult(List.of(), List.of(), List.of(), LocalTime.of(22, 0), "gemini", List.of()));
        LlmProvider unavailableOllama = new StubProvider("ollama", false, false, null);

        ScheduleParserService service = new ScheduleParserService(new RuleParser(), unavailableOllama, workingGemini);
        ParseResult res = service.parse("i work mon-thurs 7-6pm", REF, ParserMode.RULES);
        assertEquals("rules", res.getParserUsed());
        assertEquals(1, res.getRecurringRules().size());
    }

    private static class StubProvider implements LlmProvider {

        private final String name;
        private final boolean available;
        private final boolean throwError;
        private final ParseResult result;

        StubProvider(String name, boolean available, boolean throwError, ParseResult result) {
            this.name = name;
            this.available = available;
            this.throwError = throwError;
            this.result = result;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public ParseResult parse(String input, LocalDate referenceDate) throws LlmException {
            if (throwError) {
                throw new LlmException("simulated failure");
            }
            return result;
        }
    }
}
