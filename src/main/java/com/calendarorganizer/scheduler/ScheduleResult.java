package com.calendarorganizer.scheduler;

import com.calendarorganizer.model.CalendarEvent;

import java.util.Collections;
import java.util.List;

public class ScheduleResult {

    private final List<CalendarEvent> scheduledEvents;
    private final List<String> unscheduledNotes;

    public ScheduleResult(List<CalendarEvent> scheduledEvents, List<String> unscheduledNotes) {
        this.scheduledEvents = Collections.unmodifiableList(scheduledEvents);
        this.unscheduledNotes = Collections.unmodifiableList(unscheduledNotes);
    }

    public List<CalendarEvent> getScheduledEvents() {
        return scheduledEvents;
    }

    public List<String> getUnscheduledNotes() {
        return unscheduledNotes;
    }
}
