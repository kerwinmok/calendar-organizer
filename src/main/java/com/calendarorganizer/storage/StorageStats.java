package com.calendarorganizer.storage;

import java.util.Locale;

public class StorageStats {

    private final int eventCount;
    private final int recurringRuleCount;
    private final int goalCount;
    private final long databaseBytes;

    public StorageStats(int eventCount, int recurringRuleCount, int goalCount, long databaseBytes) {
        this.eventCount = eventCount;
        this.recurringRuleCount = recurringRuleCount;
        this.goalCount = goalCount;
        this.databaseBytes = databaseBytes;
    }

    public int getEventCount() {
        return eventCount;
    }

    public int getRecurringRuleCount() {
        return recurringRuleCount;
    }

    public int getGoalCount() {
        return goalCount;
    }

    public long getDatabaseBytes() {
        return databaseBytes;
    }

    public String formatSize() {
        if (databaseBytes < 1024L) {
            return databaseBytes + " B";
        }
        if (databaseBytes < 1024L * 1024L) {
            long kb = Math.round(databaseBytes / 1024.0);
            return kb + " KB";
        }
        double mb = databaseBytes / (1024.0 * 1024.0);
        return String.format(Locale.US, "%.1f MB", mb);
    }
}
