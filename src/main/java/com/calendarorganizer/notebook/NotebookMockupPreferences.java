package com.calendarorganizer.notebook;

import com.calendarorganizer.storage.CalendarRepository;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class NotebookMockupPreferences {

    public static final String PREF_DISMISSED = "notebook_mockup_dismissed";
    public static final String PREF_EMAIL = "notebook_mockup_email";
    public static final String PREF_SYNC_SCHEDULE = "notebook_mockup_sync_schedule";
    public static final String PREF_AUTO_SYNC = "notebook_mockup_auto_sync";
    public static final String PREF_COURSES = "notebook_mockup_courses";

    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<String>>() { }.getType();

    private final CalendarRepository repository;

    public NotebookMockupPreferences(CalendarRepository repository) {
        this.repository = repository;
    }

    public boolean isPromptDismissed() {
        return "true".equalsIgnoreCase(repository.loadPreference(PREF_DISMISSED, "false"));
    }

    public void setPromptDismissed(boolean dismissed) {
        repository.savePreference(PREF_DISMISSED, Boolean.toString(dismissed));
    }

    public String getConnectedEmail() {
        return repository.loadPreference(PREF_EMAIL, "");
    }

    public boolean isDemoConnected() {
        return !getConnectedEmail().isBlank();
    }

    public boolean isSyncScheduleEnabled() {
        return "true".equalsIgnoreCase(repository.loadPreference(PREF_SYNC_SCHEDULE, "true"));
    }

    public boolean isAutoSyncEnabled() {
        return "true".equalsIgnoreCase(repository.loadPreference(PREF_AUTO_SYNC, "true"));
    }

    public List<String> getSelectedCourses() {
        String raw = repository.loadPreference(PREF_COURSES, "");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = GSON.fromJson(raw, LIST_TYPE);
            if (parsed == null) {
                return List.of();
            }
            return List.copyOf(parsed);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    public void saveDemoConnection(
            String email,
            List<String> selectedCourses,
            boolean syncSchedule,
            boolean autoSync
    ) {
        String cleanEmail = email;
        if (cleanEmail == null || cleanEmail.isBlank()) {
            cleanEmail = "student@gmail.com";
        }
        repository.savePreference(PREF_EMAIL, cleanEmail.trim());
        repository.savePreference(PREF_SYNC_SCHEDULE, Boolean.toString(syncSchedule));
        repository.savePreference(PREF_AUTO_SYNC, Boolean.toString(autoSync));
        List<String> safeCourses = new ArrayList<>();
        if (selectedCourses != null) {
            safeCourses.addAll(selectedCourses);
        }
        repository.savePreference(PREF_COURSES, GSON.toJson(safeCourses));
        setPromptDismissed(true);
    }

    public void resetDemoState() {
        repository.deletePreference(PREF_DISMISSED);
        repository.deletePreference(PREF_EMAIL);
        repository.deletePreference(PREF_SYNC_SCHEDULE);
        repository.deletePreference(PREF_AUTO_SYNC);
        repository.deletePreference(PREF_COURSES);
    }
}
