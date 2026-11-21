package com.calendarorganizer.course;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

public class CourseItem {

    private final Course course;
    private final CourseSection section;
    private final String fileName;
    private final Path filePath;
    private final long lastModifiedMillis;

    public CourseItem(Course course, CourseSection section, String fileName, Path filePath) {
        this(course, section, fileName, filePath, 0L);
    }

    public CourseItem(Course course, CourseSection section, String fileName, Path filePath, long lastModifiedMillis) {
        this.course = course;
        this.section = section;
        this.fileName = fileName;
        this.filePath = filePath;
        this.lastModifiedMillis = lastModifiedMillis;
    }

    public String getFileName() {
        return fileName;
    }

    public Path getFilePath() {
        return filePath;
    }

    public long getLastModifiedMillis() {
        return lastModifiedMillis;
    }

    public boolean isMarkdown() {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".md");
    }

    public String getRelativeDisplayPath() {
        return "courses/" + course.getFolderName() + "/" + section.getFolderName() + "/" + fileName;
    }

    @Override
    public String toString() {
        return fileName;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof CourseItem)) {
            return false;
        }
        CourseItem other = (CourseItem) obj;
        return Objects.equals(filePath, other.filePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(filePath);
    }
}
