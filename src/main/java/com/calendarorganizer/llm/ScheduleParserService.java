package com.calendarorganizer.llm;

import com.calendarorganizer.parser.ParseResult;
import com.calendarorganizer.parser.RuleParser;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class ScheduleParserService {

    private final RuleParser ruleParser;
    private final LlmProvider ollamaProvider;
    private final LlmProvider geminiProvider;

    public ScheduleParserService(RuleParser ruleParser,
                                 LlmProvider ollamaProvider,
                                 LlmProvider geminiProvider) {
        this.ruleParser = ruleParser;
        this.ollamaProvider = ollamaProvider;
        this.geminiProvider = geminiProvider;
    }

    public ParseResult parse(String input, LocalDate referenceDate, ParserMode mode) {
        ParserMode effectiveMode = mode;
        if (effectiveMode == null) {
            effectiveMode = ParserMode.AUTO;
        }

        List<String> extraWarnings = new ArrayList<>();
        if (effectiveMode == ParserMode.RULES) {
            return ruleParser.parse(input, referenceDate);
        }

        if (effectiveMode == ParserMode.GEMINI) {
            ParseResult result = tryProvider(geminiProvider, input, referenceDate, extraWarnings);
            if (result != null) {
                return result;
            }
            return fallbackToRules(input, referenceDate, extraWarnings);
        }

        if (effectiveMode == ParserMode.OLLAMA) {
            ParseResult result = tryProvider(ollamaProvider, input, referenceDate, extraWarnings);
            if (result != null) {
                return result;
            }
            return fallbackToRules(input, referenceDate, extraWarnings);
        }

        if (geminiProvider.isAvailable()) {
            ParseResult result = tryProvider(geminiProvider, input, referenceDate, extraWarnings);
            if (result != null && !result.isEmpty()) {
                return result;
            }
        }
        if (ollamaProvider.isAvailable()) {
            ParseResult result = tryProvider(ollamaProvider, input, referenceDate, extraWarnings);
            if (result != null && !result.isEmpty()) {
                return result;
            }
        }
        return fallbackToRules(input, referenceDate, extraWarnings);
    }

    private ParseResult tryProvider(LlmProvider provider,
                                    String input,
                                    LocalDate referenceDate,
                                    List<String> extraWarnings) {
        if (!provider.isAvailable()) {
            extraWarnings.add(provider.getName() + " is not available; used rule parser.");
            return null;
        }
        try {
            ParseResult parsed = provider.parse(input, referenceDate);
            if (parsed.isEmpty()) {
                extraWarnings.add(provider.getName() + " returned no items; used rule parser.");
                return null;
            }
            return parsed;
        } catch (LlmException e) {
            extraWarnings.add(provider.getName() + " failed (" + e.getMessage() + "); used rule parser.");
            return null;
        }
    }

    private ParseResult fallbackToRules(String input, LocalDate referenceDate, List<String> extraWarnings) {
        ParseResult ruleResult = ruleParser.parse(input, referenceDate);
        if (extraWarnings.isEmpty()) {
            return ruleResult;
        }
        List<String> merged = new ArrayList<>(extraWarnings);
        merged.addAll(ruleResult.getWarnings());
        return new ParseResult(
                ruleResult.getEvents(),
                ruleResult.getRecurringRules(),
                ruleResult.getGoals(),
                ruleResult.getSleepTime(),
                ruleResult.getParserUsed(),
                merged
        );
    }
}
