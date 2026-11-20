package com.calendarorganizer.parser;

import java.time.LocalTime;
import java.util.Collections;
import java.util.List;

public class ParseResult {

    private final List<ParsedEvent> events;
    private final List<ParsedRecurringRule> recurringRules;
    private final List<ParsedGoal> goals;
    private final LocalTime sleepTime;
    private final String parserUsed;
    private final List<String> warnings;

    public ParseResult(List<ParsedEvent> events,
                       List<ParsedRecurringRule> recurringRules,
                       List<ParsedGoal> goals,
                       LocalTime sleepTime,
                       String parserUsed,
                       List<String> warnings) {
        this.events = Collections.unmodifiableList(events);
        this.recurringRules = Collections.unmodifiableList(recurringRules);
        this.goals = Collections.unmodifiableList(goals);
        this.sleepTime = sleepTime;
        this.parserUsed = parserUsed;
        this.warnings = Collections.unmodifiableList(warnings);
    }

    public boolean isEmpty() {
        return events.isEmpty() && recurringRules.isEmpty() && goals.isEmpty() && sleepTime == null;
    }

    public List<ParsedEvent> getEvents() {
        return events;
    }

    public List<ParsedRecurringRule> getRecurringRules() {
        return recurringRules;
    }

    public List<ParsedGoal> getGoals() {
        return goals;
    }

    public LocalTime getSleepTime() {
        return sleepTime;
    }

    public String getParserUsed() {
        return parserUsed;
    }

    public List<String> getWarnings() {
        return warnings;
    }
}
