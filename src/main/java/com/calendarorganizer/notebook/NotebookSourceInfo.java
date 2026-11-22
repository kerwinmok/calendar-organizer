package com.calendarorganizer.notebook;

public class NotebookSourceInfo {

    private final String name;
    private final String displayName;

    public NotebookSourceInfo(String name, String displayName) {
        if (name == null) {
            this.name = "";
        } else {
            this.name = name;
        }
        if (displayName == null) {
            this.displayName = "";
        } else {
            this.displayName = displayName;
        }
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }
}
