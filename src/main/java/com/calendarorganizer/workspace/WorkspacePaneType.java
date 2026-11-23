package com.calendarorganizer.workspace;

import java.util.Locale;

public enum WorkspacePaneType {
    LECTURE("lecture", "Lecture Viewer"),
    ONENOTE("onenote", "OneNote Dock"),
    CALENDAR("calendar", "Calendar"),
    NOTES("notes", "Notes Editor");

    private final String id;
    private final String displayName;

    WorkspacePaneType(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static WorkspacePaneType fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return LECTURE;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (WorkspacePaneType type : values()) {
            if (type.id.equals(normalized) || type.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return type;
            }
        }
        return LECTURE;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
