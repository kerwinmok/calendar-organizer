package com.calendarorganizer.ui;

import com.calendarorganizer.course.CourseExporter;
import com.calendarorganizer.course.CourseManager;
import com.calendarorganizer.service.CalendarService;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;

public class MainWindow extends BorderPane {

    private final TabPane tabPane;
    private final CourseTabView courseTabView;

    public MainWindow(CalendarService calendarService, CourseManager courseManager, CourseExporter courseExporter) {
        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab calendarTab = new Tab("Calendar", new CalendarTabView(calendarService));
        courseTabView = new CourseTabView(courseManager, courseExporter);
        Tab coursesTab = new Tab("Courses", courseTabView);

        tabPane.getTabs().addAll(calendarTab, coursesTab);
        setCenter(tabPane);
    }

    public CourseTabView getCourseTabView() {
        return courseTabView;
    }
}
