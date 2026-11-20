package com.calendarorganizer.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppPathsTest {

    @Test
    void resolvesCustomPropertyOverDefault(@TempDir Path tempDir) {
        String previous = System.getProperty(AppPaths.HOME_PROPERTY);
        try {
            System.setProperty(AppPaths.HOME_PROPERTY, tempDir.toString());
            AppPaths paths = new AppPaths();
            assertEquals(tempDir, paths.getDataDir());
            assertEquals(tempDir.resolve("calendar.db"), paths.getDatabasePath());
            assertEquals(tempDir.resolve("courses"), paths.getCoursesDir());
        } finally {
            if (previous == null) {
                System.clearProperty(AppPaths.HOME_PROPERTY);
            } else {
                System.setProperty(AppPaths.HOME_PROPERTY, previous);
            }
        }
    }

    @Test
    void explicitDataDirBuildsExpectedSubpaths(@TempDir Path tempDir) {
        AppPaths paths = new AppPaths(tempDir);
        assertEquals(tempDir, paths.getDataDir());
        assertEquals(tempDir.resolve("calendar.db"), paths.getDatabasePath());
        assertEquals(tempDir.resolve("courses"), paths.getCoursesDir());
    }
}
