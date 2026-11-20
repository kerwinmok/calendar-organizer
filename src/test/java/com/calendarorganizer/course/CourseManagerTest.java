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
    void rejectsPathTraversalInCourseAndItemNames() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> manager.createCourse("../escape"));
        assertThrows(IllegalArgumentException.class, () -> manager.createCourse("a/b"));

        Course cmpt = manager.createCourse("CMPT 201");
        assertThrows(IllegalArgumentException.class,
                () -> manager.createItem(cmpt, CourseSection.NOTES, "../secret", "x"));
    }
}
