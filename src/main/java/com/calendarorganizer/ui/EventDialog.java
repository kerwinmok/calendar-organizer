package com.calendarorganizer.ui;

import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.EventCategory;
import com.calendarorganizer.model.EventSource;
import com.calendarorganizer.model.RecurringRule;
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

import java.net.URL;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class EventDialog {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

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
        applyDialogTheme(pane, activeTheme);
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        pane.setPrefWidth(420);

        TextField titleField = new TextField();
        titleField.setPromptText("Event title");
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

        if (existing != null) {
            titleField.setText(existing.getTitle());
            datePicker.setValue(existing.getStartTime().toLocalDate());
            startField.setText(existing.getStartTime().toLocalTime().format(TIME_FMT));
            endField.setText(existing.getEndTime().toLocalTime().format(TIME_FMT));
            categoryBox.setValue(existing.getCategory());
            notesArea.setText(existing.getNotes());
        } else {
            titleField.setText("New Event");
            datePicker.setValue(defaultDate);
            startField.setText(defaultStart.format(TIME_FMT));
            endField.setText(defaultEnd.format(TIME_FMT));
            categoryBox.setValue(EventCategory.BUSY);
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

        Button okBtn = (Button) pane.lookupButton(ButtonType.OK);
        okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            CalendarEvent built = tryBuildEvent(existing, titleField, datePicker, startField, endField, categoryBox, notesArea, errorLabel);
            if (built == null) {
                ev.consume();
            }
        });

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                return tryBuildEvent(existing, titleField, datePicker, startField, endField, categoryBox, notesArea, errorLabel);
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
        applyDialogTheme(pane, activeTheme);
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        pane.setPrefWidth(440);

        TextField titleField = new TextField(existing.getTitle());
        TextField startField = new TextField(existing.getStartTime().format(TIME_FMT));
        TextField endField = new TextField(existing.getEndTime().format(TIME_FMT));
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

        Button okBtn = (Button) pane.lookupButton(ButtonType.OK);
        okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            RecurringRule built = tryBuildRule(existing, titleField, dayChecks, startField, endField, categoryBox, errorLabel);
            if (built == null) {
                ev.consume();
            }
        });

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                return tryBuildRule(existing, titleField, dayChecks, startField, endField, categoryBox, errorLabel);
            }
            return null;
        });

        return dialog.showAndWait();
    }

    private static CalendarEvent tryBuildEvent(CalendarEvent existing,
                                               TextField titleField,
                                               DatePicker datePicker,
                                               TextField startField,
                                               TextField endField,
                                               ComboBox<EventCategory> categoryBox,
                                               TextArea notesArea,
                                               Label errorLabel) {
        String title = titleField.getText();
        if (title == null || title.isBlank()) {
            errorLabel.setText("Title is required.");
            return null;
        }
        LocalDate date = datePicker.getValue();
        if (date == null) {
            errorLabel.setText("Date is required.");
            return null;
        }
        LocalTime start;
        LocalTime end;
        try {
            start = LocalTime.parse(startField.getText().trim(), TIME_FMT);
            end = LocalTime.parse(endField.getText().trim(), TIME_FMT);
        } catch (DateTimeParseException ex) {
            errorLabel.setText("Use HH:mm format for start and end times (e.g. 09:00).");
            return null;
        }
        LocalDateTime startDt = date.atTime(start);
        LocalDate endDay = date;
        if (!end.isAfter(start)) {
            endDay = date.plusDays(1);
        }
        LocalDateTime endDt = endDay.atTime(end);
        EventCategory category = categoryBox.getValue();
        String notes = notesArea.getText();

        if (existing == null) {
            return new CalendarEvent(0L, null, title.trim(), startDt, endDt, category, EventSource.MANUAL, notes);
        }
        return new CalendarEvent(
                existing.getId(),
                existing.getUid(),
                title.trim(),
                startDt,
                endDt,
                category,
                existing.getSource(),
                notes
        );
    }

    private static RecurringRule tryBuildRule(RecurringRule existing,
                                              TextField titleField,
                                              Map<DayOfWeek, CheckBox> dayChecks,
                                              TextField startField,
                                              TextField endField,
                                              ComboBox<EventCategory> categoryBox,
                                              Label errorLabel) {
        String title = titleField.getText();
        if (title == null || title.isBlank()) {
            errorLabel.setText("Title is required.");
            return null;
        }
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (Map.Entry<DayOfWeek, CheckBox> entry : dayChecks.entrySet()) {
            if (entry.getValue().isSelected()) {
                days.add(entry.getKey());
            }
        }
        if (days.isEmpty()) {
            errorLabel.setText("Select at least one day of the week.");
            return null;
        }
        LocalTime start;
        LocalTime end;
        try {
            start = LocalTime.parse(startField.getText().trim(), TIME_FMT);
            end = LocalTime.parse(endField.getText().trim(), TIME_FMT);
        } catch (DateTimeParseException ex) {
            errorLabel.setText("Use HH:mm format for start and end times (e.g. 07:00).");
            return null;
        }
        return new RecurringRule(
                existing.getId(),
                title.trim(),
                days,
                start,
                end,
                categoryBox.getValue(),
                existing.getStartDate(),
                existing.getEndDate()
        );
    }

    private static void applyDialogTheme(DialogPane pane, UiTheme theme) {
        URL cssUrl = EventDialog.class.getResource("/styles.css");
        if (cssUrl != null) {
            String urlStr = cssUrl.toExternalForm();
            if (!pane.getStylesheets().contains(urlStr)) {
                pane.getStylesheets().add(urlStr);
            }
        }
        for (UiTheme candidate : UiTheme.values()) {
            pane.getStyleClass().remove(candidate.getStyleClass());
        }
        pane.getStyleClass().add(theme.getStyleClass());
    }
}
