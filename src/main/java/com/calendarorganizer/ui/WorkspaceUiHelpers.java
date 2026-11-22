package com.calendarorganizer.ui;

import com.calendarorganizer.course.Course;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;

import java.util.List;

final class WorkspaceUiHelpers {

    private WorkspaceUiHelpers() {
    }

    static void configureCourseBox(ComboBox<Course> courseBox, String emptyPrompt, double prefWidth) {
        courseBox.setPrefWidth(prefWidth);
        courseBox.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Course item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText("");
                } else {
                    setText(item.getDisplayName());
                }
            }
        });
        courseBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Course item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(emptyPrompt);
                } else {
                    setText(item.getDisplayName());
                }
            }
        });
    }

    static Course resolveTargetCourse(List<Course> courses, String savedFolder, Course preferredCourse) {
        if (courses == null || courses.isEmpty()) {
            return null;
        }
        if (savedFolder != null && !savedFolder.isBlank()) {
            for (Course c : courses) {
                if (savedFolder.equalsIgnoreCase(c.getFolderName())) {
                    return c;
                }
            }
        }
        if (preferredCourse != null) {
            for (Course c : courses) {
                if (preferredCourse.getFolderName().equalsIgnoreCase(c.getFolderName())) {
                    return c;
                }
            }
        }
        return courses.get(0);
    }
}
