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
    private final long calendarId;
    private final EventColor color;

    public CalendarEvent(long id,
                         String uid,
                         String title,
                         LocalDateTime startTime,
                         LocalDateTime endTime,
                         EventCategory category,
                         EventSource source,
                         String notes) {
        this(id, uid, title, startTime, endTime, category, source, notes, 1L, EventColor.DEFAULT);
    }

    public CalendarEvent(long id,
                         String uid,
                         String title,
                         LocalDateTime startTime,
                         LocalDateTime endTime,
                         EventCategory category,
                         EventSource source,
                         String notes,
                         long calendarId,
                         EventColor color) {
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
        if (calendarId <= 0L) {
            this.calendarId = 1L;
        } else {
            this.calendarId = calendarId;
        }
        if (color == null) {
            this.color = EventColor.DEFAULT;
        } else {
            this.color = color;
        }
    }

    public static CalendarEvent create(String title,
                                       LocalDateTime startTime,
                                       LocalDateTime endTime,
                                       EventCategory category,
                                       EventSource source) {
        return new CalendarEvent(0L, null, title, startTime, endTime, category, source, "", 1L, EventColor.DEFAULT);
    }

    private static String ensureUid(String rawUid) {
        if (rawUid == null || rawUid.isBlank()) {
            return UUID.randomUUID() + "@calendar-organizer";
        }
        return rawUid.trim();
    }

    public CalendarEvent withId(long newId) {
        return new CalendarEvent(newId, uid, title, startTime, endTime, category, source, notes, calendarId, color);
    }

    public CalendarEvent withCalendarId(long newCalendarId) {
        return new CalendarEvent(id, uid, title, startTime, endTime, category, source, notes, newCalendarId, color);
    }

    public CalendarEvent withColor(EventColor newColor) {
        return new CalendarEvent(id, uid, title, startTime, endTime, category, source, notes, calendarId, newColor);
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

    public long getCalendarId() {
        return calendarId;
    }

    public EventColor getColor() {
        return color;
    }
}
