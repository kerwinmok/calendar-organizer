package com.calendarorganizer.course;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Course implements Comparable<Course> {

    private static final Pattern CODE_PATTERN = Pattern.compile("^([A-Za-z]+)[\\s\\-_]*(\\d+)([A-Za-z0-9]*)$");
    private static final Pattern FIRST_NUMBER_PATTERN = Pattern.compile("(\\d+)");

    private final String folderName;
    private final String displayName;
    private final String department;
    private final Integer courseNumber;
    private final String suffix;
    private final Path directory;

    private Course(String folderName,
                   String displayName,
                   String department,
                   Integer courseNumber,
                   String suffix,
                   Path directory) {
        this.folderName = folderName;
        this.displayName = displayName;
        this.department = department;
        this.courseNumber = courseNumber;
        this.suffix = suffix;
        this.directory = directory;
    }

    public static Course fromDirectory(Path directory) {
        String folderName = directory.getFileName().toString();
        String displayName = folderName.replace('-', ' ').replace('_', ' ').replaceAll("\\s+", " ").trim();

        String dept = displayName.toUpperCase(Locale.ROOT);
        Integer number = null;
        String suffix = "";

        Matcher codeMatcher = CODE_PATTERN.matcher(displayName);
        if (codeMatcher.matches()) {
            dept = codeMatcher.group(1).toUpperCase(Locale.ROOT);
            number = Integer.valueOf(codeMatcher.group(2));
            suffix = codeMatcher.group(3).toUpperCase(Locale.ROOT);
        } else {
            Matcher numMatcher = FIRST_NUMBER_PATTERN.matcher(displayName);
            if (numMatcher.find()) {
                number = Integer.valueOf(numMatcher.group(1));
            }
        }
        return new Course(folderName, displayName, dept, number, suffix, directory);
    }

    @Override
    public int compareTo(Course other) {
        if (this.courseNumber != null && other.courseNumber == null) {
            return -1;
        }
        if (this.courseNumber == null && other.courseNumber != null) {
            return 1;
        }
        if (this.courseNumber != null && other.courseNumber != null) {
            int numCmp = Integer.compare(this.courseNumber, other.courseNumber);
            if (numCmp != 0) {
                return numCmp;
            }
            int deptCmp = this.department.compareToIgnoreCase(other.department);
            if (deptCmp != 0) {
                return deptCmp;
            }
            int sufCmp = this.suffix.compareToIgnoreCase(other.suffix);
            if (sufCmp != 0) {
                return sufCmp;
            }
        }
        return this.displayName.compareToIgnoreCase(other.displayName);
    }

    public String getFolderName() {
        return folderName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDepartment() {
        return department;
    }

    public Integer getCourseNumber() {
        return courseNumber;
    }

    public String getSuffix() {
        return suffix;
    }

    public Path getDirectory() {
        return directory;
    }

    @Override
    public String toString() {
        return displayName;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Course)) {
            return false;
        }
        Course other = (Course) obj;
        return Objects.equals(folderName, other.folderName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(folderName);
    }
}
