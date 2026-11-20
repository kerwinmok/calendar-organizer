package com.calendarorganizer.service;

import com.calendarorganizer.parser.ParseResult;
import com.calendarorganizer.scheduler.ScheduleResult;

public class ApplySentenceResult {

    private final ParseResult parseResult;
    private final int addedEvents;
    private final int addedRules;
    private final int updatedGoals;
    private final boolean updatedSleep;
    private final ScheduleResult scheduleResult;

    public ApplySentenceResult(ParseResult parseResult,
                               int addedEvents,
                               int addedRules,
                               int updatedGoals,
                               boolean updatedSleep,
                               ScheduleResult scheduleResult) {
        this.parseResult = parseResult;
        this.addedEvents = addedEvents;
        this.addedRules = addedRules;
        this.updatedGoals = updatedGoals;
        this.updatedSleep = updatedSleep;
        this.scheduleResult = scheduleResult;
    }

    public ParseResult getParseResult() {
        return parseResult;
    }

    public int getAddedEvents() {
        return addedEvents;
    }

    public int getAddedRules() {
        return addedRules;
    }

    public int getUpdatedGoals() {
        return updatedGoals;
    }

    public boolean isUpdatedSleep() {
        return updatedSleep;
    }

    public ScheduleResult getScheduleResult() {
        return scheduleResult;
    }
}
