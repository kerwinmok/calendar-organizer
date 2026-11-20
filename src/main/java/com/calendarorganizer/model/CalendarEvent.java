package com.calendarorganizer.model;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public class CalendarEvent {

    private final long id;
    private final String uid;
    private final String title;
    private final LocalDateTime startTime;
    private final LocalDateTime endTime;
    private final EventCategory category;
    private final EventSource source;
    private final String notes;

    public CalendarEvent(long id,
                         String uid,
                         String title,
                         LocalDateTime startTime,
                         LocalDateTime endTime,
                         EventCategory category,
                         EventSource source,
                         String notes) {
        this.id = id;
        this.uid = ensureUid(uid);
        this.title = Objects.requireNonNull(title, "title");
        this.startTime = Objects.requireNonNull(startTime, "startTime");
        this.endTime = Objects.requireNonNull(endTime, "endTime");
        if (category == null) {
            this.category = EventCategory.GENERAL;
        } else {
            this.category = category;
        }
        if (source == null) {
            this.source = EventSource.MANUAL;
        } else {
            this.source = source;
        }
        if (notes == null) {
            this.notes = "";
        } else {
            this.notes = notes;
        }
    }

    public static CalendarEvent create(String title,
                                       LocalDateTime startTime,
                                       LocalDateTime endTime,
                                       EventCategory category,
                                       EventSource source) {
        return new CalendarEvent(0L, null, title, startTime, endTime, category, source, "");
    }

    private static String ensureUid(String rawUid) {
        if (rawUid == null || rawUid.isBlank()) {
            return UUID.randomUUID() + "@calendar-organizer";
        }
        return rawUid.trim();
    }

    public CalendarEvent withId(long newId) {
        return new CalendarEvent(newId, uid, title, startTime, endTime, category, source, notes);
    }

    public long getId() {
        return id;
    }

    public String getUid() {
        return uid;
    }

    public String getTitle() {
        return title;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public EventCategory getCategory() {
        return category;
    }

    public EventSource getSource() {
        return source;
    }

    public String getNotes() {
        return notes;
    }
}
