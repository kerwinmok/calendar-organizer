package com.calendarorganizer.model;

import java.time.LocalTime;
import java.util.Objects;

public class ScheduleGoal {

    private final long id;
    private final GoalType type;
    private final String title;
    private final int sessionsPerWeek;
    private final int sessionMinutes;
    private final int weeklyMinutes;
    private final LocalTime earliestStart;
    private final LocalTime latestEnd;

    public ScheduleGoal(long id,
                        GoalType type,
                        String title,
                        int sessionsPerWeek,
                        int sessionMinutes,
                        int weeklyMinutes,
                        LocalTime earliestStart,
                        LocalTime latestEnd) {
        this.id = id;
        this.type = Objects.requireNonNull(type, "type");
        this.title = Objects.requireNonNull(title, "title");
        this.sessionsPerWeek = sessionsPerWeek;
        this.sessionMinutes = sessionMinutes;
        this.weeklyMinutes = weeklyMinutes;
        this.earliestStart = earliestStart;
        this.latestEnd = latestEnd;
    }

    public ScheduleGoal withId(long newId) {
        return new ScheduleGoal(newId, type, title, sessionsPerWeek, sessionMinutes, weeklyMinutes, earliestStart, latestEnd);
    }

    public int totalTargetMinutes() {
        if (weeklyMinutes > 0) {
            return weeklyMinutes;
        }
        return sessionsPerWeek * sessionMinutes;
    }

    public long getId() {
        return id;
    }

    public GoalType getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public int getSessionsPerWeek() {
        return sessionsPerWeek;
    }

    public int getSessionMinutes() {
        return sessionMinutes;
    }

    public int getWeeklyMinutes() {
        return weeklyMinutes;
    }

    public LocalTime getEarliestStart() {
        return earliestStart;
    }

    public LocalTime getLatestEnd() {
        return latestEnd;
    }
}
