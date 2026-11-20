package com.calendarorganizer.course;

public enum CourseSection {
    LECTURES("lectures", "Lectures", "lecture"),
    NOTES("notes", "Notes", "note"),
    COURSEWORK("coursework", "Coursework", "coursework");

    private final String folderName;
    private final String label;
    private final String defaultPrefix;

    CourseSection(String folderName, String label, String defaultPrefix) {
        this.folderName = folderName;
        this.label = label;
        this.defaultPrefix = defaultPrefix;
    }

    public String getFolderName() {
        return folderName;
    }

    public String getLabel() {
        return label;
    }

    public String getDefaultPrefix() {
        return defaultPrefix;
    }
}
