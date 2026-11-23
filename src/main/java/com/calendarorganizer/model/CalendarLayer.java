package com.calendarorganizer.model;

import java.util.Objects;

public class CalendarLayer {

    private final long id;
    private final String name;
    private final EventColor color;
    private final boolean visible;

    public CalendarLayer(long id, String name, EventColor color, boolean visible) {
        this.id = id;
        String safeName = Objects.requireNonNull(name, "name").trim();
        if (safeName.isBlank()) {
            throw new IllegalArgumentException("Calendar name cannot be empty.");
        }
        this.name = safeName;
        if (color == null || color == EventColor.DEFAULT) {
            this.color = EventColor.BLUE;
        } else {
            this.color = color;
        }
        this.visible = visible;
    }

    public CalendarLayer withId(long newId) {
        return new CalendarLayer(newId, name, color, visible);
    }

    public CalendarLayer withVisible(boolean nextVisible) {
        return new CalendarLayer(id, name, color, nextVisible);
    }

    public CalendarLayer withNameAndColor(String nextName, EventColor nextColor) {
        return new CalendarLayer(id, nextName, nextColor, visible);
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public EventColor getColor() {
        return color;
    }

    public boolean isVisible() {
        return visible;
    }

    @Override
    public String toString() {
        return name;
    }
}
