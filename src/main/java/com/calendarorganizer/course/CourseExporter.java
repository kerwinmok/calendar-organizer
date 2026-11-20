package com.calendarorganizer.course;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class CourseExporter {

    private final CourseManager courseManager;

    public CourseExporter(CourseManager courseManager) {
        this.courseManager = courseManager;
    }

    public Path exportCourse(Course course, Path zipFile) throws IOException {
        return writeZip(List.of(course), zipFile);
    }

    public Path exportAllCourses(Path zipFile) throws IOException {
        List<Course> courses = courseManager.listCourses();
        return writeZip(courses, zipFile);
    }

    private Path writeZip(List<Course> courses, Path zipFile) throws IOException {
        Path absZip = zipFile.toAbsolutePath().normalize();
        Path parent = absZip.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        StringBuilder readme = new StringBuilder("# Course Study Export\n\n");
        StringBuilder combined = new StringBuilder("# Combined Study Material\n\n");

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(absZip), StandardCharsets.UTF_8)) {
            for (Course course : courses) {
                readme.append("## ").append(course.getDisplayName()).append("\n");
                combined.append("# ").append(course.getDisplayName()).append("\n\n");

                for (CourseSection section : CourseSection.values()) {
                    List<CourseItem> items = courseManager.listItems(course, section).stream()
                            .filter(CourseItem::isMarkdown)
                            .toList();
                    if (items.isEmpty()) {
                        continue;
                    }
                    readme.append("- **").append(section.getLabel()).append("**:\n");
                    combined.append("## ").append(section.getLabel()).append("\n\n");

                    for (CourseItem item : items) {
                        String entryPath = course.getFolderName() + "/" + section.getFolderName() + "/" + item.getFileName();
                        String cleanContent = normalizeMarkdown(courseManager.readItem(item));

                        writeZipEntry(zos, entryPath, cleanContent);
                        readme.append("  - `").append(entryPath).append("`\n");
                        combined.append("### ").append(item.getFileName()).append("\n\n");
                        combined.append(cleanContent).append("\n");
                    }
                }
                readme.append("\n");
            }

            if (courses.isEmpty()) {
                readme.append("No courses exported.\n");
                combined.append("No courses exported.\n");
            }

            writeZipEntry(zos, "README.md", normalizeMarkdown(readme.toString()));
            writeZipEntry(zos, "combined.md", normalizeMarkdown(combined.toString()));
        }

        return absZip;
    }

    private void writeZipEntry(ZipOutputStream zos, String entryName, String content) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        zos.write(bytes);
        zos.closeEntry();
    }

    private String normalizeMarkdown(String raw) {
        if (raw == null) {
            return "\n";
        }
        String lf = raw.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = lf.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String trimmedRight = lines[i].replaceAll("[ \\t]+$", "");
            out.append(trimmedRight);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        String cleaned = out.toString().replaceAll("\\n+$", "");
        if (cleaned.isEmpty()) {
            return "";
        }
        return cleaned + "\n";
    }
}
