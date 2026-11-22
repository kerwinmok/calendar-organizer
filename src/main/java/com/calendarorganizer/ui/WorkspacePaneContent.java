package com.calendarorganizer.ui;

import com.calendarorganizer.course.Course;
import javafx.scene.layout.Region;

import java.util.List;

public interface WorkspacePaneContent {

    Region getView();

    void refreshCourses(List<Course> courses, Course preferredCourse);

    void flushPendingEdits();

    void releaseResources();

    void setActiveTheme(UiTheme resolvedTheme);
}
