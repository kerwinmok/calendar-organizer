package com.calendarorganizer.parser;

import com.calendarorganizer.model.EventCategory;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public class ParsedRecurringRule {

    private final String title;
    private final Set<DayOfWeek> daysOfWeek;
    private final LocalTime startTime;
    private final LocalTime endTime;
    private final EventCategory category;

    public ParsedRecurringRule(String title,
                               Set<DayOfWeek> daysOfWeek,
                               LocalTime startTime,
                               LocalTime endTime,
                               EventCategory category) {
        this.title = title;
        if (daysOfWeek == null || daysOfWeek.isEmpty()) {
            this.daysOfWeek = Collections.emptySet();
        } else {
            this.daysOfWeek = Collections.unmodifiableSet(EnumSet.copyOf(daysOfWeek));
        }
        this.startTime = startTime;
        this.endTime = endTime;
        if (category == null) {
            this.category = EventCategory.GENERAL;
        } else {
            this.category = category;
        }
    }

    public String getTitle() {
        return title;
    }

    public Set<DayOfWeek> getDaysOfWeek() {
        return daysOfWeek;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public EventCategory getCategory() {
        return category;
    }
}
