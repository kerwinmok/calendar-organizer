package com.calendarorganizer.ui;

import com.calendarorganizer.course.Course;
import com.calendarorganizer.service.CalendarService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.List;
import java.util.function.Consumer;

public class CalendarWorkspacePane extends BorderPane implements WorkspacePaneContent {

    private final CalendarTabView calendarView;
    private final Label weekLabel = new Label();

    public CalendarWorkspacePane(
            CalendarService calendarService,
            Consumer<String> onStatusChanged,
            Runnable onDataChanged
    ) {
        this.calendarView = new CalendarTabView(calendarService);
        // Collapse the inspector by default so the 7-day grid fits cleanly inside a split pane.
        this.calendarView.toggleInspector();

        this.calendarView.setOnStatusChanged(msg -> {
            if (onStatusChanged != null) {
                onStatusChanged.accept(msg);
            }
        });
        this.calendarView.setOnDataChanged(() -> {
            weekLabel.setText(calendarView.getWeekRangeLabel());
            if (onDataChanged != null) {
                onDataChanged.run();
            }
        });

        Button prevBtn = new Button("<");
        prevBtn.setOnAction(e -> calendarView.shiftWeek(-1));
        Button todayBtn = new Button("Today");
        todayBtn.setOnAction(e -> calendarView.goToday());
        Button nextBtn = new Button(">");
        nextBtn.setOnAction(e -> calendarView.shiftWeek(1));

        weekLabel.getStyleClass().add("section-heading");
        weekLabel.setText(calendarView.getWeekRangeLabel());

        Button newEventBtn = new Button("+ Event");
        newEventBtn.setOnAction(e -> calendarView.openNewEventDialogDefault());
        Button panelBtn = new Button("Panel");
        panelBtn.setOnAction(e -> calendarView.toggleInspector());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox subBar = new HBox(6, prevBtn, todayBtn, nextBtn, weekLabel, spacer, newEventBtn, panelBtn);
        subBar.getStyleClass().add("workspace-subbar");
        subBar.setAlignment(Pos.CENTER_LEFT);
        subBar.setPadding(new Insets(5, 8, 5, 8));

        setTop(subBar);
        setCenter(calendarView);
    }

    public void refreshCalendar() {
        calendarView.refreshView();
    }

    @Override
    public Region getView() {
        return this;
    }

    @Override
    public void refreshCourses(List<Course> courses, Course preferredCourse) {
        // Calendar pane does not depend on course folders; StudyWorkspaceView.onTabActivated() refreshes it once.
    }

    @Override
    public void flushPendingEdits() {
    }

    @Override
    public void releaseResources() {
        calendarView.clearSelection();
    }

    @Override
    public void setActiveTheme(UiTheme resolvedTheme) {
        if (resolvedTheme != null) {
            calendarView.setActiveTheme(resolvedTheme);
        }
    }
}
