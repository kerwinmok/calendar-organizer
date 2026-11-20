package com.calendarorganizer.parser;

import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.EventSource;
import com.calendarorganizer.model.RecurringRule;
import com.calendarorganizer.model.ScheduleGoal;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class ParseResultMapper {

    public List<CalendarEvent> toEvents(ParseResult result, LocalDate referenceDate) {
        List<CalendarEvent> list = new ArrayList<>();
        for (ParsedEvent parsed : result.getEvents()) {
            LocalDate eventDate = parsed.getDate();
            if (eventDate == null) {
                eventDate = referenceDate;
            }
            LocalDateTime start = eventDate.atTime(parsed.getStartTime());
            LocalDate endDay = eventDate;
            if (!parsed.getEndTime().isAfter(parsed.getStartTime())) {
                endDay = eventDate.plusDays(1);
            }
            LocalDateTime end = endDay.atTime(parsed.getEndTime());
            list.add(CalendarEvent.create(parsed.getTitle(), start, end, parsed.getCategory(), EventSource.MANUAL));
        }
        return list;
    }

    public List<RecurringRule> toRecurringRules(ParseResult result) {
        List<RecurringRule> list = new ArrayList<>();
        for (ParsedRecurringRule parsed : result.getRecurringRules()) {
            list.add(new RecurringRule(
                    0L,
                    parsed.getTitle(),
                    parsed.getDaysOfWeek(),
                    parsed.getStartTime(),
                    parsed.getEndTime(),
                    parsed.getCategory(),
                    null,
                    null
            ));
        }
        return list;
    }

    public List<ScheduleGoal> toGoals(ParseResult result) {
        List<ScheduleGoal> list = new ArrayList<>();
        for (ParsedGoal parsed : result.getGoals()) {
            list.add(new ScheduleGoal(
                    0L,
                    parsed.getType(),
                    parsed.getTitle(),
                    parsed.getSessionsPerWeek(),
                    parsed.getSessionMinutes(),
                    parsed.getWeeklyMinutes(),
                    null,
                    null
            ));
        }
        return list;
    }
}
