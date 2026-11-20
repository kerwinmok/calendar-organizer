package com.calendarorganizer.ics;

import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.RecurringRule;

import java.util.Collections;
import java.util.List;

public class IcsParseResult {

    private final List<CalendarEvent> events;
    private final List<RecurringRule> recurringRules;
    private final List<String> warnings;

    public IcsParseResult(List<CalendarEvent> events,
                          List<RecurringRule> recurringRules,
                          List<String> warnings) {
        this.events = Collections.unmodifiableList(events);
        this.recurringRules = Collections.unmodifiableList(recurringRules);
        this.warnings = Collections.unmodifiableList(warnings);
    }

    public List<CalendarEvent> getEvents() {
        return events;
    }

    public List<RecurringRule> getRecurringRules() {
        return recurringRules;
    }

    public List<String> getWarnings() {
        return warnings;
    }
}
