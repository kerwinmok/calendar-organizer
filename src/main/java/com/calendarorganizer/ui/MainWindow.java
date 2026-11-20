package com.calendarorganizer.ui;

import com.calendarorganizer.service.CalendarService;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;

public class MainWindow extends BorderPane {

    private final TabPane tabPane;
    private final Tab coursesTab;

    public MainWindow(CalendarService calendarService) {
        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab calendarTab = new Tab("Calendar", new CalendarTabView(calendarService));
        coursesTab = new Tab("Courses", new Label("Course organizer loading..."));

        tabPane.getTabs().addAll(calendarTab, coursesTab);
        setCenter(tabPane);
    }

    public void setCoursesContent(javafx.scene.Node content) {
        coursesTab.setContent(content);
    }
}
