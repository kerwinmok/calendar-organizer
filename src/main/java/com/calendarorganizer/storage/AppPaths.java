package com.calendarorganizer.storage;

import java.nio.file.Path;
import java.nio.file.Paths;

public class AppPaths {

    public static final String HOME_PROPERTY = "calendar.organizer.home";
    public static final String HOME_ENV = "CALENDAR_ORGANIZER_HOME";

    private final Path dataDir;

    public AppPaths() {
        this.dataDir = resolveDataDir();
    }

    public AppPaths(Path dataDir) {
        this.dataDir = dataDir;
    }

    public static Path resolveDataDir() {
        String propHome = System.getProperty(HOME_PROPERTY);
        if (propHome != null && !propHome.isBlank()) {
            return Paths.get(propHome.trim());
        }
        String envHome = System.getenv(HOME_ENV);
        if (envHome != null && !envHome.isBlank()) {
            return Paths.get(envHome.trim());
        }
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, ".calendar-organizer");
    }

    public Path getDataDir() {
        return dataDir;
    }

    public Path getDatabasePath() {
        return dataDir.resolve("calendar.db");
    }

    public Path getCoursesDir() {
        return dataDir.resolve("courses");
    }
}
