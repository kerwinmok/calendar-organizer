package com.calendarorganizer.course;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class CourseManager {

    private final Path coursesRoot;

    public CourseManager(Path coursesRoot) {
        this.coursesRoot = coursesRoot.toAbsolutePath().normalize();
    }

    public List<Course> listCourses() throws IOException {
        Files.createDirectories(coursesRoot);
        List<Course> courses = new ArrayList<>();
        try (Stream<Path> stream = Files.list(coursesRoot)) {
            List<Path> dirs = stream.filter(Files::isDirectory).toList();
            for (Path dir : dirs) {
                String name = dir.getFileName().toString();
                if (!name.startsWith(".")) {
                    courses.add(Course.fromDirectory(dir));
                }
            }
        }
        Collections.sort(courses);
        return courses;
    }

    public Course createCourse(String rawName) throws IOException {
        String folderName = normalizeCourseFolderName(rawName);
        Path courseDir = resolveSafeChild(coursesRoot, folderName);
        for (CourseSection section : CourseSection.values()) {
            Files.createDirectories(courseDir.resolve(section.getFolderName()));
        }
        return Course.fromDirectory(courseDir);
    }

    public void deleteCourse(Course course) throws IOException {
        Path target = course.getDirectory().toAbsolutePath().normalize();
        if (!target.startsWith(coursesRoot) || target.equals(coursesRoot)) {
            throw new IllegalArgumentException("Course path outside courses root");
        }
        if (!Files.exists(target)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(target)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    public List<CourseItem> listItems(Course course, CourseSection section) throws IOException {
        Path sectionDir = course.getDirectory().resolve(section.getFolderName());
        if (!Files.isDirectory(sectionDir)) {
            return List.of();
        }
        List<CourseItem> items = new ArrayList<>();
        try (Stream<Path> stream = Files.list(sectionDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .toList();
            for (Path file : files) {
                items.add(new CourseItem(course, section, file.getFileName().toString(), file));
            }
        }
        items.sort((a, b) -> compareNatural(a.getFileName(), b.getFileName()));
        return items;
    }

    public CourseItem importExternalFile(Course course,
                                         CourseSection section,
                                         Path sourceFile) throws IOException {
        if (sourceFile == null || !Files.isRegularFile(sourceFile)) {
            throw new IllegalArgumentException("Source file does not exist");
        }
        Path sectionDir = course.getDirectory().resolve(section.getFolderName());
        Files.createDirectories(sectionDir);

        String rawName = sourceFile.getFileName().toString();
        String safeName = sanitizeAttachmentFileName(rawName);
        Path target = resolveSafeChild(sectionDir, safeName);
        Files.copy(sourceFile, target, StandardCopyOption.REPLACE_EXISTING);
        return new CourseItem(course, section, safeName, target);
    }

    public CourseItem createItem(Course course,
                                 CourseSection section,
                                 String rawTitle,
                                 String initialContent) throws IOException {
        Path sectionDir = course.getDirectory().resolve(section.getFolderName());
        Files.createDirectories(sectionDir);

        String fileName;
        if (rawTitle == null || rawTitle.isBlank()) {
            fileName = nextNumberedFileName(sectionDir, section.getDefaultPrefix());
        } else {
            fileName = normalizeMarkdownFileName(rawTitle);
        }

        Path target = resolveSafeChild(sectionDir, fileName);
        String body = initialContent;
        if (body == null) {
            String heading = fileName.substring(0, fileName.length() - 3).replace('-', ' ');
            body = "# " + heading + "\n\n";
        }
        Files.writeString(target, body, StandardCharsets.UTF_8);
        return new CourseItem(course, section, fileName, target);
    }

    public String readItem(CourseItem item) throws IOException {
        return Files.readString(item.getFilePath(), StandardCharsets.UTF_8);
    }

    public void saveItem(CourseItem item, String content) throws IOException {
        Path parent = item.getFilePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String text = content;
        if (text == null) {
            text = "";
        }
        Files.writeString(item.getFilePath(), text, StandardCharsets.UTF_8);
    }

    public void deleteItem(CourseItem item) throws IOException {
        Path target = item.getFilePath().toAbsolutePath().normalize();
        if (!target.startsWith(coursesRoot)) {
            throw new IllegalArgumentException("Item path outside courses root");
        }
        Files.deleteIfExists(target);
    }

    public String normalizeCourseFolderName(String rawName) {
        validateRawSegment(rawName);
        String cleaned = rawName.trim().replaceAll("\\s+", "-").replaceAll("-{2,}", "-");
        while (cleaned.startsWith("-")) {
            cleaned = cleaned.substring(1);
        }
        while (cleaned.endsWith("-")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Course name cannot be empty");
        }
        return cleaned;
    }

    private String normalizeMarkdownFileName(String rawTitle) {
        validateRawSegment(rawTitle);
        String cleaned = rawTitle.trim().toLowerCase(Locale.ROOT);
        if (cleaned.endsWith(".md")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.replaceAll("[^a-z0-9\\-_\\s]", "");
        cleaned = cleaned.trim().replaceAll("\\s+", "-").replaceAll("-{2,}", "-");
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("File name must contain letters or digits");
        }
        return cleaned + ".md";
    }

    private String sanitizeAttachmentFileName(String rawFileName) {
        validateRawSegment(rawFileName);
        String cleaned = rawFileName.trim().replaceAll("[^A-Za-z0-9._\\-\\s]", "");
        cleaned = cleaned.trim().replaceAll("\\s+", "-").replaceAll("-{2,}", "-");
        if (cleaned.isEmpty() || cleaned.startsWith(".")) {
            throw new IllegalArgumentException("Invalid attachment file name");
        }
        return cleaned;
    }

    private void validateRawSegment(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Name cannot be blank");
        }
        String trimmed = raw.trim();
        if (trimmed.contains("..") || trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains(":")) {
            throw new IllegalArgumentException("Invalid path characters in name: " + raw);
        }
        if (trimmed.startsWith(".")) {
            throw new IllegalArgumentException("Name cannot start with a dot");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isISOControl(trimmed.charAt(i))) {
                throw new IllegalArgumentException("Control characters are not allowed");
            }
        }
    }

    private Path resolveSafeChild(Path parent, String childName) {
        Path resolved = parent.resolve(childName).normalize();
        if (!resolved.startsWith(parent.normalize()) || resolved.equals(parent.normalize())) {
            throw new IllegalArgumentException("Unsafe path: " + childName);
        }
        return resolved;
    }

    private String nextNumberedFileName(Path sectionDir, String prefix) {
        int index = 1;
        while (true) {
            String candidate = String.format("%s-%02d.md", prefix, index);
            if (!Files.exists(sectionDir.resolve(candidate))) {
                return candidate;
            }
            index++;
        }
    }

    private int compareNatural(String a, String b) {
        //compares embedded numeric sequences by integer value
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int iEnd = i;
                while (iEnd < a.length() && Character.isDigit(a.charAt(iEnd))) {
                    iEnd++;
                }
                int jEnd = j;
                while (jEnd < b.length() && Character.isDigit(b.charAt(jEnd))) {
                    jEnd++;
                }
                long na = Long.parseLong(a.substring(i, iEnd));
                long nb = Long.parseLong(b.substring(j, jEnd));
                int numCmp = Long.compare(na, nb);
                if (numCmp != 0) {
                    return numCmp;
                }
                i = iEnd;
                j = jEnd;
            } else {
                int charCmp = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (charCmp != 0) {
                    return charCmp;
                }
                i++;
                j++;
            }
        }
        return Integer.compare(a.length(), b.length());
    }
}
