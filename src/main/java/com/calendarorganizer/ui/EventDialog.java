package com.calendarorganizer.ui;

import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.EventCategory;
import com.calendarorganizer.model.RecurringRule;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class EventDialog {

    public static Optional<CalendarEvent> showEventDialog(Window owner,
                                                          UiTheme activeTheme,
                                                          CalendarEvent existing,
                                                          LocalDate defaultDate,
                                                          LocalTime defaultStart,
                                                          LocalTime defaultEnd) {
        Dialog<CalendarEvent> dialog = new Dialog<>();
        dialog.initOwner(owner);
        if (existing == null) {
            dialog.setTitle("New Event");
            dialog.setHeaderText("Add Calendar Event");
        } else {
            dialog.setTitle("Edit Event");
            dialog.setHeaderText("Edit Event Details & Timing");
        }

        DialogPane pane = dialog.getDialogPane();
        activeTheme.applyToDialog(pane);
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        pane.setPrefWidth(420);

        TextField titleField = new TextField();
        titleField.setPromptText("Event title (e.g. Study Session)");
        DatePicker datePicker = new DatePicker();
        TextField startField = new TextField();
        startField.setPromptText("HH:mm (e.g. 09:00)");
        TextField endField = new TextField();
        endField.setPromptText("HH:mm (e.g. 10:30)");
        ComboBox<EventCategory> categoryBox = new ComboBox<>();
        categoryBox.getItems().setAll(EventCategory.values());
        TextArea notesArea = new TextArea();
        notesArea.setPrefRowCount(3);
        notesArea.setPromptText("Optional notes");
        Label errorLabel = new Label("");
        errorLabel.getStyleClass().add("caution-label");
        errorLabel.setWrapText(true);

        if (existing != null) {
            titleField.setText(existing.getTitle());
            datePicker.setValue(existing.getStartTime().toLocalDate());
            startField.setText(existing.getStartTime().toLocalTime().format(EventFormValidator.TIME_DISPLAY_FMT));
            endField.setText(existing.getEndTime().toLocalTime().format(EventFormValidator.TIME_DISPLAY_FMT));
            categoryBox.setValue(existing.getCategory());
            notesArea.setText(existing.getNotes());
            Platform.runLater(() -> {
                titleField.requestFocus();
                titleField.selectAll();
            });
        } else {
            datePicker.setValue(defaultDate);
            startField.setText(defaultStart.format(EventFormValidator.TIME_DISPLAY_FMT));
            endField.setText(defaultEnd.format(EventFormValidator.TIME_DISPLAY_FMT));
            categoryBox.setValue(EventCategory.BUSY);
            Platform.runLater(titleField::requestFocus);
        }

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.add(new Label("Title"), 0, 0);
        grid.add(titleField, 1, 0);
        grid.add(new Label("Date"), 0, 1);
        grid.add(datePicker, 1, 1);
        grid.add(new Label("Start (HH:mm)"), 0, 2);
        grid.add(startField, 1, 2);
        grid.add(new Label("End (HH:mm)"), 0, 3);
        grid.add(endField, 1, 3);
        grid.add(new Label("Category"), 0, 4);
        grid.add(categoryBox, 1, 4);
        grid.add(new Label("Notes"), 0, 5);
        grid.add(notesArea, 1, 5);
        GridPane.setHgrow(titleField, Priority.ALWAYS);
        GridPane.setHgrow(notesArea, Priority.ALWAYS);

        VBox content = new VBox(8, grid, errorLabel);
        content.setPadding(new Insets(10));
        pane.setContent(content);

        final CalendarEvent[] builtHolder = new CalendarEvent[1];
        Button okBtn = (Button) pane.lookupButton(ButtonType.OK);
        okBtn.addEventFilter(ActionEvent.ACTION, ev -> {
            try {
                builtHolder[0] = EventFormValidator.buildEvent(
                        existing,
                        titleField.getText(),
                        datePicker.getValue(),
                        startField.getText(),
                        endField.getText(),
                        categoryBox.getValue(),
                        notesArea.getText()
                );
                errorLabel.setText("");
            } catch (IllegalArgumentException ex) {
                builtHolder[0] = null;
                errorLabel.setText(ex.getMessage());
                ev.consume();
            }
        });

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                return builtHolder[0];
            }
            return null;
        });

        return dialog.showAndWait();
    }

    public static Optional<RecurringRule> showRecurringRuleDialog(Window owner,
                                                                  UiTheme activeTheme,
                                                                  RecurringRule existing) {
        Dialog<RecurringRule> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Edit Recurring Rule");
        dialog.setHeaderText("Edit Recurring Days & Timing");

        DialogPane pane = dialog.getDialogPane();
        activeTheme.applyToDialog(pane);
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        pane.setPrefWidth(440);

        TextField titleField = new TextField(existing.getTitle());
        TextField startField = new TextField(existing.getStartTime().format(EventFormValidator.TIME_DISPLAY_FMT));
        TextField endField = new TextField(existing.getEndTime().format(EventFormValidator.TIME_DISPLAY_FMT));
        ComboBox<EventCategory> categoryBox = new ComboBox<>();
        categoryBox.getItems().setAll(EventCategory.values());
        categoryBox.setValue(existing.getCategory());

        Map<DayOfWeek, CheckBox> dayChecks = new EnumMap<>(DayOfWeek.class);
        HBox daysRow = new HBox(6);
        for (DayOfWeek day : DayOfWeek.values()) {
            CheckBox cb = new CheckBox(day.name().substring(0, 3));
            cb.setSelected(existing.getDaysOfWeek().contains(day));
            dayChecks.put(day, cb);
            daysRow.getChildren().add(cb);
        }

        Label errorLabel = new Label("");
        errorLabel.getStyleClass().add("caution-label");
        errorLabel.setWrapText(true);

        Platform.runLater(() -> {
            titleField.requestFocus();
            titleField.selectAll();
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.add(new Label("Title"), 0, 0);
        grid.add(titleField, 1, 0);
        grid.add(new Label("Days"), 0, 1);
        grid.add(daysRow, 1, 1);
        grid.add(new Label("Start (HH:mm)"), 0, 2);
        grid.add(startField, 1, 2);
        grid.add(new Label("End (HH:mm)"), 0, 3);
        grid.add(endField, 1, 3);
        grid.add(new Label("Category"), 0, 4);
        grid.add(categoryBox, 1, 4);
        GridPane.setHgrow(titleField, Priority.ALWAYS);

        VBox content = new VBox(8, grid, errorLabel);
        content.setPadding(new Insets(10));
        pane.setContent(content);

        final RecurringRule[] builtHolder = new RecurringRule[1];
        Button okBtn = (Button) pane.lookupButton(ButtonType.OK);
        okBtn.addEventFilter(ActionEvent.ACTION, ev -> {
            try {
                builtHolder[0] = EventFormValidator.buildRecurringRule(
                        existing,
                        titleField.getText(),
                        collectSelectedDays(dayChecks),
                        startField.getText(),
                        endField.getText(),
                        categoryBox.getValue()
                );
                errorLabel.setText("");
            } catch (IllegalArgumentException ex) {
                builtHolder[0] = null;
                errorLabel.setText(ex.getMessage());
                ev.consume();
            }
        });

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                return builtHolder[0];
            }
            return null;
        });

        return dialog.showAndWait();
    }

    private static Set<DayOfWeek> collectSelectedDays(Map<DayOfWeek, CheckBox> dayChecks) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (Map.Entry<DayOfWeek, CheckBox> entry : dayChecks.entrySet()) {
            if (entry.getValue().isSelected()) {
                days.add(entry.getKey());
            }
        }
        return days;
    }
}
