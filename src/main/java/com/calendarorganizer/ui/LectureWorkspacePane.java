package com.calendarorganizer.ui;

import com.calendarorganizer.course.Course;
import com.calendarorganizer.course.CourseFilePreviewer;
import com.calendarorganizer.course.CourseManager;
import com.calendarorganizer.course.CourseSection;
import com.calendarorganizer.course.CourseSortOrder;
import com.calendarorganizer.workspace.WorkspaceLayoutConfig;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.List;
import java.util.function.Consumer;

public class LectureWorkspacePane extends BorderPane implements WorkspacePaneContent {

    private final WorkspaceLayoutConfig.PaneState paneState;
    private final LectureRenderPane renderPane;
    private final ComboBox<Course> courseBox = new ComboBox<>();
    private final Runnable onLayoutStateChanged;
    private boolean updatingCourses;

    public LectureWorkspacePane(
            CourseManager courseManager,
            CourseFilePreviewer previewer,
            WorkspaceLayoutConfig.PaneState paneState,
            Consumer<Boolean> onFullScreenToggle,
            Runnable onCloseRequested,
            Consumer<String> onStatusChanged,
            Runnable onLayoutStateChanged
    ) {
        this.paneState = paneState;
        this.onLayoutStateChanged = onLayoutStateChanged;
        this.renderPane = new LectureRenderPane(courseManager, previewer);
        this.renderPane.setOnFullScreenToggle(onFullScreenToggle);
        this.renderPane.setOnCloseRequested(onCloseRequested);
        this.renderPane.setOnStatusChanged(onStatusChanged);

        configureCourseBox();

        Label courseLbl = new Label("Course");
        courseLbl.getStyleClass().add("dim-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox subBar = new HBox(8, courseLbl, courseBox, spacer);
        subBar.getStyleClass().add("workspace-subbar");
        subBar.setAlignment(Pos.CENTER_LEFT);
        subBar.setPadding(new Insets(6, 10, 6, 10));

        setTop(subBar);
        setCenter(renderPane);
    }

    private void configureCourseBox() {
        WorkspaceUiHelpers.configureCourseBox(courseBox, "Select course...", 210);
        courseBox.setOnAction(e -> {
            if (updatingCourses) {
                return;
            }
            Course chosen = courseBox.getValue();
            if (chosen != null) {
                paneState.setTargetCourse(chosen.getFolderName());
                if (onLayoutStateChanged != null) {
                    onLayoutStateChanged.run();
                }
                loadCourse(chosen);
            }
        });
    }

    private void loadCourse(Course course) {
        if (course == null) {
            return;
        }
        renderPane.loadCourseContext(course, CourseSection.LECTURES, null, CourseSortOrder.NAME_ASC);
    }

    public void setFullScreenState(boolean fullScreen) {
        renderPane.setFullScreenState(fullScreen);
    }

    @Override
    public Region getView() {
        return this;
    }

    @Override
    public void refreshCourses(List<Course> courses, Course preferredCourse) {
        updatingCourses = true;
        try {
            courseBox.getItems().setAll(courses);
            Course target = WorkspaceUiHelpers.resolveTargetCourse(courses, paneState.getTargetCourse(), preferredCourse);
            courseBox.setValue(target);
            if (target != null) {
                loadCourse(target);
            }
        } finally {
            updatingCourses = false;
        }
    }

    @Override
    public void flushPendingEdits() {
    }

    @Override
    public void releaseResources() {
        renderPane.releaseResources();
    }

    @Override
    public void setActiveTheme(UiTheme resolvedTheme) {
    }
}
