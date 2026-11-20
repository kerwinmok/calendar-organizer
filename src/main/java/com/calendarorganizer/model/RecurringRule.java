package com.calendarorganizer.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class RecurringRule {

    private final long id;
    private final String title;
    private final Set<DayOfWeek> daysOfWeek;
    private final LocalTime startTime;
    private final LocalTime endTime;
    private final EventCategory category;
    private final LocalDate startDate;
    private final LocalDate endDate;

    public RecurringRule(long id,
                         String title,
                         Set<DayOfWeek> daysOfWeek,
                         LocalTime startTime,
                         LocalTime endTime,
                         EventCategory category,
                         LocalDate startDate,
                         LocalDate endDate) {
        this.id = id;
        this.title = Objects.requireNonNull(title, "title");
        if (daysOfWeek == null || daysOfWeek.isEmpty()) {
            this.daysOfWeek = Collections.emptySet();
        } else {
            this.daysOfWeek = Collections.unmodifiableSet(EnumSet.copyOf(daysOfWeek));
        }
        this.startTime = Objects.requireNonNull(startTime, "startTime");
        this.endTime = Objects.requireNonNull(endTime, "endTime");
        if (category == null) {
            this.category = EventCategory.GENERAL;
        } else {
            this.category = category;
        }
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public RecurringRule withId(long newId) {
        return new RecurringRule(newId, title, daysOfWeek, startTime, endTime, category, startDate, endDate);
    }

    public List<CalendarEvent> expandForRange(LocalDate from, LocalDate to) {
        List<CalendarEvent> expanded = new ArrayList<>();
        if (daysOfWeek.isEmpty() || to.isBefore(from)) {
            return expanded;
        }
        LocalDateTime rangeStart = from.atStartOfDay();
        LocalDateTime rangeEnd = to.plusDays(1).atStartOfDay();

        //handles midnight wrap from day before range start
        LocalDate cursor = from.minusDays(1);
        while (!cursor.isAfter(to)) {
            if (appliesOnDate(cursor)) {
                CalendarEvent occurrence = buildOccurrence(cursor);
                if (occurrence.getStartTime().isBefore(rangeEnd) && occurrence.getEndTime().isAfter(rangeStart)) {
                    expanded.add(occurrence);
                }
            }
            cursor = cursor.plusDays(1);
        }
        return expanded;
    }

    private boolean appliesOnDate(LocalDate date) {
        if (!daysOfWeek.contains(date.getDayOfWeek())) {
            return false;
        }
        if (startDate != null && date.isBefore(startDate)) {
            return false;
        }
        if (endDate != null && date.isAfter(endDate)) {
            return false;
        }
        return true;
    }

    private CalendarEvent buildOccurrence(LocalDate date) {
        LocalDateTime start = date.atTime(startTime);
        LocalDate endDay = date;
        if (!endTime.isAfter(startTime)) {
            endDay = date.plusDays(1);
        }
        LocalDateTime end = endDay.atTime(endTime);
        String syntheticUid = "rule-" + id + "-" + date + "@calendar-organizer";
        return new CalendarEvent(0L, syntheticUid, title, start, end, category, EventSource.RECURRING, "");
    }

    public long getId() {
        return id;
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

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }
}
