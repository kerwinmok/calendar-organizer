package com.calendarorganizer.notebook;

import com.calendarorganizer.storage.CalendarRepository;
import com.calendarorganizer.storage.DatabaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotebookMockupPreferencesTest {

    @Test
    void persistsAndResetsDeploymentMockupOnboardingState(@TempDir Path tempDir) {
        DatabaseManager dbManager = new DatabaseManager(tempDir.resolve("calendar.db"));
        dbManager.initialize();
        CalendarRepository repository = new CalendarRepository(dbManager);

        NotebookMockupPreferences prefs = new NotebookMockupPreferences(repository);
        assertFalse(prefs.isPromptDismissed());
        assertFalse(prefs.isDemoConnected());
        assertTrue(prefs.getSelectedCourses().isEmpty());

        prefs.saveDemoConnection(
                "kerwin@gmail.com",
                List.of("CMPT 201", "STAT 201"),
                true,
                false
        );

        assertTrue(prefs.isPromptDismissed());
        assertTrue(prefs.isDemoConnected());
        assertEquals("kerwin@gmail.com", prefs.getConnectedEmail());
        assertEquals(List.of("CMPT 201", "STAT 201"), prefs.getSelectedCourses());
        assertTrue(prefs.isSyncScheduleEnabled());
        assertFalse(prefs.isAutoSyncEnabled());

        prefs.resetDemoState();
        assertFalse(prefs.isPromptDismissed());
        assertFalse(prefs.isDemoConnected());
        assertEquals("", prefs.getConnectedEmail());
        assertTrue(prefs.getSelectedCourses().isEmpty());
    }
}
