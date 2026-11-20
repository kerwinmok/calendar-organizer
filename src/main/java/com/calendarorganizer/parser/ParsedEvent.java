package com.calendarorganizer.parser;

import com.calendarorganizer.model.EventCategory;

import java.time.LocalDate;
import java.time.LocalTime;

public class ParsedEvent {

    private final String title;
    private final LocalDate date;
    private final LocalTime startTime;
    private final LocalTime endTime;
    private final EventCategory category;

    public ParsedEvent(String title,
                       LocalDate date,
                       LocalTime startTime,
                       LocalTime endTime,
                       EventCategory category) {
        this.title = title;
        this.date = date;
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

    public LocalDate getDate() {
        return date;
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
