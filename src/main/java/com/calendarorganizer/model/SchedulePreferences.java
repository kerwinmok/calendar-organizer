package com.calendarorganizer.model;

import java.time.LocalTime;

public class SchedulePreferences {

    public static final LocalTime DEFAULT_SLEEP_TIME = LocalTime.of(23, 0);
    public static final int DEFAULT_SLEEP_DURATION_HOURS = 7;

    private final LocalTime sleepTime;
    private final int sleepDurationHours;

    public SchedulePreferences() {
        this(DEFAULT_SLEEP_TIME, DEFAULT_SLEEP_DURATION_HOURS);
    }

    public SchedulePreferences(LocalTime sleepTime, int sleepDurationHours) {
        if (sleepTime == null) {
            this.sleepTime = DEFAULT_SLEEP_TIME;
        } else {
            this.sleepTime = sleepTime;
        }
        if (sleepDurationHours <= 0 || sleepDurationHours > 16) {
            this.sleepDurationHours = DEFAULT_SLEEP_DURATION_HOURS;
        } else {
            this.sleepDurationHours = sleepDurationHours;
        }
    }

    public LocalTime getSleepTime() {
        return sleepTime;
    }

    public int getSleepDurationHours() {
        return sleepDurationHours;
    }
}
