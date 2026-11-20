package com.calendarorganizer.course;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CourseManagerTest {

    private Path coursesRoot;
    private CourseManager manager;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        coursesRoot = tempDir.resolve("courses");
        manager = new CourseManager(coursesRoot);
    }

    @Test
    void createsCourseFoldersAndSortsByCourseNumber() throws IOException {
        manager.createCourse("CMPT 1000");
        manager.createCourse("General");
        manager.createCourse("STAT 201");
        manager.createCourse("CMPT 201");
        manager.createCourse("MATH 99");

        assertTrue(Files.isDirectory(coursesRoot.resolve("CMPT-201/lectures")));
        assertTrue(Files.isDirectory(coursesRoot.resolve("CMPT-201/notes")));
        assertTrue(Files.isDirectory(coursesRoot.resolve("CMPT-201/coursework")));

        List<Course> sorted = manager.listCourses();
        assertEquals(List.of("MATH 99", "CMPT 201", "STAT 201", "CMPT 1000", "General"),
                sorted.stream().map(Course::getDisplayName).toList());
    }

    @Test
    void managesMarkdownItemsWithNaturalOrderingAndAutoNumbering() throws IOException {
        Course cmpt = manager.createCourse("CMPT 201");
        CourseItem first = manager.createItem(cmpt, CourseSection.LECTURES, "", "# Lecture 1\nIntro");
        assertEquals("lecture-01.md", first.getFileName());
        assertEquals("courses/CMPT-201/lectures/lecture-01.md", first.getRelativeDisplayPath());

        manager.createItem(cmpt, CourseSection.LECTURES, "lecture-10", "# Ten");
        manager.createItem(cmpt, CourseSection.LECTURES, "lecture-2", "# Two");

        List<CourseItem> lectures = manager.listItems(cmpt, CourseSection.LECTURES);
        assertEquals(List.of("lecture-01.md", "lecture-2.md", "lecture-10.md"),
                lectures.stream().map(CourseItem::getFileName).toList());

        manager.saveItem(first, "# Updated Lecture 1");
        assertEquals("# Updated Lecture 1", manager.readItem(first));

        manager.deleteItem(first);
        assertFalse(Files.exists(first.getFilePath()));
    }

    @Test
    void importsExternalPdfAndPowerPointFilesIntoCourseSection(@TempDir Path tempDir) throws IOException {
        Course cmpt = manager.createCourse("CMPT 201");
        Path samplePdf = tempDir.resolve("Week 01 Slides.pdf");
        Path samplePptx = tempDir.resolve("Lecture_02.pptx");
        Files.writeString(samplePdf, "%PDF-1.7 sample");
        Files.writeString(samplePptx, "PK-pptx-sample");

        CourseItem pdfItem = manager.importExternalFile(cmpt, CourseSection.LECTURES, samplePdf);
        CourseItem pptxItem = manager.importExternalFile(cmpt, CourseSection.LECTURES, samplePptx);

        assertFalse(pdfItem.isMarkdown());
        assertFalse(pptxItem.isMarkdown());
        assertEquals("Week-01-Slides.pdf", pdfItem.getFileName());
        assertEquals("Lecture_02.pptx", pptxItem.getFileName());
        assertTrue(Files.exists(coursesRoot.resolve("CMPT-201/lectures/Week-01-Slides.pdf")));
        assertTrue(Files.exists(coursesRoot.resolve("CMPT-201/lectures/Lecture_02.pptx")));

        List<CourseItem> items = manager.listItems(cmpt, CourseSection.LECTURES);
        assertEquals(2, items.size());
    }

    @Test
    void rejectsPathTraversalInCourseAndItemNames() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> manager.createCourse("../escape"));
        assertThrows(IllegalArgumentException.class, () -> manager.createCourse("a/b"));

        Course cmpt = manager.createCourse("CMPT 201");
        assertThrows(IllegalArgumentException.class,
                () -> manager.createItem(cmpt, CourseSection.NOTES, "../secret", "x"));
    }
}
