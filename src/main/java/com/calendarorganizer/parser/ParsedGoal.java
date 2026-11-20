package com.calendarorganizer.parser;

import com.calendarorganizer.model.GoalType;

public class ParsedGoal {

    private final GoalType type;
    private final String title;
    private final int sessionsPerWeek;
    private final int sessionMinutes;
    private final int weeklyMinutes;

    public ParsedGoal(GoalType type,
                      String title,
                      int sessionsPerWeek,
                      int sessionMinutes,
                      int weeklyMinutes) {
        this.type = type;
        this.title = title;
        this.sessionsPerWeek = sessionsPerWeek;
        this.sessionMinutes = sessionMinutes;
        this.weeklyMinutes = weeklyMinutes;
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
}
