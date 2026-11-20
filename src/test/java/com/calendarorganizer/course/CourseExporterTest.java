package com.calendarorganizer.course;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CourseExporterTest {

    private CourseManager manager;
    private CourseExporter exporter;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        manager = new CourseManager(tempDir.resolve("courses"));
        exporter = new CourseExporter(manager);
    }

    @Test
    void exportsSingleCourseWithCleanMarkdownAndSkipsNonMarkdownFiles(@TempDir Path tempDir) throws IOException {
        Course cmpt = manager.createCourse("CMPT 201");
        manager.createItem(cmpt, CourseSection.LECTURES, "lecture-01", "# Lecture 1\r\nPointers   \r\n\r\n");
        manager.createItem(cmpt, CourseSection.NOTES, "exam-review", "# Review\nMemory layout");
        manager.createItem(cmpt, CourseSection.COURSEWORK, "lab-1", "# Lab 1\nMalloc");
        Files.writeString(cmpt.getDirectory().resolve("lectures/ignore.txt"), "junk", StandardCharsets.UTF_8);

        Path zipPath = tempDir.resolve("cmpt201.zip");
        exporter.exportCourse(cmpt, zipPath);

        assertTrue(Files.exists(zipPath));
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            assertNotNull(zip.getEntry("README.md"));
            assertNotNull(zip.getEntry("combined.md"));
            assertNotNull(zip.getEntry("CMPT-201/lectures/lecture-01.md"));
            assertNotNull(zip.getEntry("CMPT-201/notes/exam-review.md"));
            assertNotNull(zip.getEntry("CMPT-201/coursework/lab-1.md"));
            assertEquals(null, zip.getEntry("CMPT-201/lectures/ignore.txt"));

            String lecture = readEntry(zip, "CMPT-201/lectures/lecture-01.md");
            assertEquals("# Lecture 1\nPointers\n", lecture);
            assertFalse(lecture.contains("\r"));

            String combined = readEntry(zip, "combined.md");
            assertTrue(combined.contains("# CMPT 201"));
            assertTrue(combined.contains("## Lectures"));
            assertTrue(combined.contains("Pointers"));
            assertTrue(combined.contains("Malloc"));
        }
    }

    @Test
    void exportsAllCoursesSortedByCourseNumberAndHandlesEmptyCourse(@TempDir Path tempDir) throws IOException {
        Course c1000 = manager.createCourse("CMPT 1000");
        Course c99 = manager.createCourse("MATH 99");
        manager.createCourse("Empty 101");

        manager.createItem(c1000, CourseSection.NOTES, "topic", "# Distributed Systems");
        manager.createItem(c99, CourseSection.LECTURES, "intro", "# Calculus");

        Path zipPath = tempDir.resolve("all.zip");
        exporter.exportAllCourses(zipPath);

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            String combined = readEntry(zip, "combined.md");
            int idx99 = combined.indexOf("# MATH 99");
            int idx101 = combined.indexOf("# Empty 101");
            int idx1000 = combined.indexOf("# CMPT 1000");

            assertTrue(idx99 >= 0);
            assertTrue(idx101 > idx99);
            assertTrue(idx1000 > idx101);
        }
    }

    private String readEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        assertNotNull(entry, "Missing zip entry: " + name);
        try (InputStream is = zip.getInputStream(entry)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
