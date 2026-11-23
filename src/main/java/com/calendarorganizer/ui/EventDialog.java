package com.calendarorganizer.ui;

import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.CalendarLayer;
import com.calendarorganizer.model.EventCategory;
import com.calendarorganizer.model.EventColor;
import com.calendarorganizer.model.RecurringRule;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class EventDialog {

    public static final class EventDialogResult {
        private final CalendarEvent event;
        private final RecurringRule recurringRule;

        private EventDialogResult(CalendarEvent event, RecurringRule recurringRule) {
            this.event = event;
            this.recurringRule = recurringRule;
        }

        public static EventDialogResult singleEvent(CalendarEvent event) {
            return new EventDialogResult(event, null);
        }

        public static EventDialogResult recurringRule(RecurringRule rule) {
            return new EventDialogResult(null, rule);
        }

        public boolean isRecurring() {
            return recurringRule != null;
        }

        public CalendarEvent getEvent() {
            return event;
        }

        public RecurringRule getRecurringRule() {
            return recurringRule;
        }
    }

    public static Optional<CalendarEvent> showEventDialog(Window owner,
                                                          UiTheme activeTheme,
                                                          CalendarEvent existing,
                                                          LocalDate defaultDate,
                                                          LocalTime defaultStart,
                                                          LocalTime defaultEnd) {
        long defCalId = 1L;
        if (existing != null) {
            defCalId = existing.getCalendarId();
        }
        Optional<EventDialogResult> res = showUnifiedEventDialog(
                owner,
                activeTheme,
                existing,
                defaultDate,
                defaultStart,
                defaultEnd,
                List.of(),
                defCalId
        );
        if (res.isPresent() && !res.get().isRecurring()) {
            return Optional.ofNullable(res.get().getEvent());
        }
        return Optional.empty();
    }

    public static Optional<EventDialogResult> showUnifiedEventDialog(Window owner,
                                                                     UiTheme activeTheme,
                                                                     CalendarEvent existing,
                                                                     LocalDate defaultDate,
                                                                     LocalTime defaultStart,
                                                                     LocalTime defaultEnd,
                                                                     List<CalendarLayer> calendars,
                                                                     long defaultCalendarId) {
        Dialog<EventDialogResult> dialog = new Dialog<>();
        dialog.initOwner(owner);
        if (existing == null) {
            dialog.setTitle("New Event");
            dialog.setHeaderText("Add Calendar Event");
        } else {
            dialog.setTitle("Edit Event");
            dialog.setHeaderText("Edit Event Details & Timing");
        }

        DialogPane pane = dialog.getDialogPane();
        WindowsTitleBar.styleDialog(dialog, activeTheme);
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        pane.setPrefWidth(460);

        TextField titleField = new TextField();
        titleField.setPromptText("Event title (e.g. CMPT 210 Lecture)");

        long initialCalId = defaultCalendarId;
        if (existing != null) {
            initialCalId = existing.getCalendarId();
        }
        ComboBox<CalendarLayer> calendarBox = createCalendarComboBox(calendars, initialCalId);

        EventColor initialColor = EventColor.DEFAULT;
        if (existing != null && existing.getColor() != null) {
            initialColor = existing.getColor();
        }
        ComboBox<EventColor> colorBox = createColorComboBox(EventColor.values(), initialColor);

        DatePicker datePicker = new DatePicker();
        TextField startField = new TextField();
        startField.setPromptText("HH:mm (e.g. 09:00)");
        TextField endField = new TextField();
        endField.setPromptText("HH:mm (e.g. 10:30)");

        CheckBox repeatWeeklyCheck = new CheckBox("Repeat weekly");
        Map<DayOfWeek, CheckBox> dayChecks = new EnumMap<>(DayOfWeek.class);
        HBox daysRow = buildDaysRow(dayChecks);
        daysRow.setVisible(false);
        daysRow.setManaged(false);
        repeatWeeklyCheck.setVisible(existing == null);
        repeatWeeklyCheck.setManaged(existing == null);

        TextArea notesArea = new TextArea();
        notesArea.setPrefRowCount(4);
        notesArea.setWrapText(true);
        notesArea.setPromptText("Description, location, links, or notes...");

        Label errorLabel = new Label("");
        errorLabel.getStyleClass().add("caution-label");
        errorLabel.setWrapText(true);

        if (existing != null) {
            titleField.setText(existing.getTitle());
            datePicker.setValue(existing.getStartTime().toLocalDate());
            startField.setText(existing.getStartTime().toLocalTime().format(EventFormValidator.TIME_DISPLAY_FMT));
            endField.setText(existing.getEndTime().toLocalTime().format(EventFormValidator.TIME_DISPLAY_FMT));
            notesArea.setText(existing.getNotes());
            Platform.runLater(() -> {
                titleField.requestFocus();
                titleField.selectAll();
            });
        } else {
            datePicker.setValue(defaultDate);
            startField.setText(defaultStart.format(EventFormValidator.TIME_DISPLAY_FMT));
            endField.setText(defaultEnd.format(EventFormValidator.TIME_DISPLAY_FMT));
            Platform.runLater(titleField::requestFocus);
        }

        repeatWeeklyCheck.selectedProperty().addListener((obs, wasSelected, isNowSelected) -> {
            daysRow.setVisible(isNowSelected);
            daysRow.setManaged(isNowSelected);
            datePicker.setDisable(isNowSelected);
            if (isNowSelected && collectSelectedDays(dayChecks).isEmpty() && datePicker.getValue() != null) {
                CheckBox dayCb = dayChecks.get(datePicker.getValue().getDayOfWeek());
                if (dayCb != null) {
                    dayCb.setSelected(true);
                }
            }
            if (pane.getScene() != null && pane.getScene().getWindow() != null) {
                pane.getScene().getWindow().sizeToScene();
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        int row = 0;
        grid.add(new Label("Title"), 0, row);
        grid.add(titleField, 1, row++);
        if (!calendars.isEmpty()) {
            grid.add(new Label("Calendar"), 0, row);
            grid.add(calendarBox, 1, row++);
        }
        grid.add(new Label("Color"), 0, row);
        grid.add(colorBox, 1, row++);
        grid.add(new Label("Date"), 0, row);
        grid.add(datePicker, 1, row++);
        grid.add(new Label("Start (HH:mm)"), 0, row);
        grid.add(startField, 1, row++);
        grid.add(new Label("End (HH:mm)"), 0, row);
        grid.add(endField, 1, row++);
        if (existing == null) {
            grid.add(new Label("Recurrence"), 0, row);
            grid.add(new VBox(6, repeatWeeklyCheck, daysRow), 1, row++);
        }
        grid.add(new Label("Description"), 0, row);
        grid.add(notesArea, 1, row);
        GridPane.setHgrow(titleField, Priority.ALWAYS);
        GridPane.setHgrow(calendarBox, Priority.ALWAYS);
        GridPane.setHgrow(colorBox, Priority.ALWAYS);
        GridPane.setHgrow(notesArea, Priority.ALWAYS);

        VBox content = new VBox(8, grid, errorLabel);
        content.setPadding(new Insets(10));
        pane.setContent(content);

        final long fallbackCalId = initialCalId;
        final EventDialogResult[] builtHolder = new EventDialogResult[1];
        Button okBtn = (Button) pane.lookupButton(ButtonType.OK);
        okBtn.addEventFilter(ActionEvent.ACTION, ev -> {
            try {
                long chosenCalId = resolveCalendarId(calendarBox, fallbackCalId);
                EventColor chosenColor = colorBox.getValue();
                if (chosenColor == null) {
                    chosenColor = EventColor.DEFAULT;
                }
                EventCategory preservedCat = EventCategory.GENERAL;
                if (existing != null) {
                    preservedCat = existing.getCategory();
                }

                if (existing == null && repeatWeeklyCheck.isSelected()) {
                    RecurringRule rule = EventFormValidator.buildRecurringRule(
                            null,
                            titleField.getText(),
                            collectSelectedDays(dayChecks),
                            startField.getText(),
                            endField.getText(),
                            preservedCat,
                            chosenCalId,
                            chosenColor,
                            notesArea.getText()
                    );
                    builtHolder[0] = EventDialogResult.recurringRule(rule);
                } else {
                    CalendarEvent event = EventFormValidator.buildEvent(
                            existing,
                            titleField.getText(),
                            datePicker.getValue(),
                            startField.getText(),
                            endField.getText(),
                            preservedCat,
                            chosenCalId,
                            chosenColor,
                            notesArea.getText()
                    );
                    builtHolder[0] = EventDialogResult.singleEvent(event);
                }
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
        return showRecurringRuleDialog(owner, activeTheme, existing, List.of());
    }

    public static Optional<RecurringRule> showRecurringRuleDialog(Window owner,
                                                                  UiTheme activeTheme,
                                                                  RecurringRule existing,
                                                                  List<CalendarLayer> calendars) {
        Dialog<RecurringRule> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Edit Recurring Event");
        dialog.setHeaderText("Edit Recurring Days, Color & Timing");

        DialogPane pane = dialog.getDialogPane();
        WindowsTitleBar.styleDialog(dialog, activeTheme);
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        pane.setPrefWidth(460);

        TextField titleField = new TextField(existing.getTitle());
        ComboBox<CalendarLayer> calendarBox = createCalendarComboBox(calendars, existing.getCalendarId());
        ComboBox<EventColor> colorBox = createColorComboBox(EventColor.values(), existing.getColor());
        TextField startField = new TextField(existing.getStartTime().format(EventFormValidator.TIME_DISPLAY_FMT));
        TextField endField = new TextField(existing.getEndTime().format(EventFormValidator.TIME_DISPLAY_FMT));
        TextArea notesArea = new TextArea(existing.getNotes());
        notesArea.setPrefRowCount(3);
        notesArea.setWrapText(true);
        notesArea.setPromptText("Description, location, links, or notes...");

        Map<DayOfWeek, CheckBox> dayChecks = new EnumMap<>(DayOfWeek.class);
        HBox daysRow = buildDaysRow(dayChecks);
        for (Map.Entry<DayOfWeek, CheckBox> entry : dayChecks.entrySet()) {
            entry.getValue().setSelected(existing.getDaysOfWeek().contains(entry.getKey()));
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
        int row = 0;
        grid.add(new Label("Title"), 0, row);
        grid.add(titleField, 1, row++);
        if (!calendars.isEmpty()) {
            grid.add(new Label("Calendar"), 0, row);
            grid.add(calendarBox, 1, row++);
        }
        grid.add(new Label("Color"), 0, row);
        grid.add(colorBox, 1, row++);
        grid.add(new Label("Days"), 0, row);
        grid.add(daysRow, 1, row++);
        grid.add(new Label("Start (HH:mm)"), 0, row);
        grid.add(startField, 1, row++);
        grid.add(new Label("End (HH:mm)"), 0, row);
        grid.add(endField, 1, row++);
        grid.add(new Label("Description"), 0, row);
        grid.add(notesArea, 1, row);
        GridPane.setHgrow(titleField, Priority.ALWAYS);
        GridPane.setHgrow(calendarBox, Priority.ALWAYS);
        GridPane.setHgrow(colorBox, Priority.ALWAYS);
        GridPane.setHgrow(notesArea, Priority.ALWAYS);

        VBox content = new VBox(8, grid, errorLabel);
        content.setPadding(new Insets(10));
        pane.setContent(content);

        final RecurringRule[] builtHolder = new RecurringRule[1];
        Button okBtn = (Button) pane.lookupButton(ButtonType.OK);
        okBtn.addEventFilter(ActionEvent.ACTION, ev -> {
            try {
                long chosenCalId = resolveCalendarId(calendarBox, existing.getCalendarId());
                EventColor chosenColor = colorBox.getValue();
                if (chosenColor == null) {
                    chosenColor = EventColor.DEFAULT;
                }
                builtHolder[0] = EventFormValidator.buildRecurringRule(
                        existing,
                        titleField.getText(),
                        collectSelectedDays(dayChecks),
                        startField.getText(),
                        endField.getText(),
                        existing.getCategory(),
                        chosenCalId,
                        chosenColor,
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

    public static Optional<CalendarLayer> showCalendarLayerDialog(Window owner,
                                                                  UiTheme activeTheme,
                                                                  CalendarLayer existing) {
        Dialog<CalendarLayer> dialog = new Dialog<>();
        dialog.initOwner(owner);
        if (existing == null) {
            dialog.setTitle("New Calendar");
            dialog.setHeaderText("Add Calendar Layer");
        } else {
            dialog.setTitle("Edit Calendar");
            dialog.setHeaderText("Rename or Recolor Calendar Layer");
        }

        DialogPane pane = dialog.getDialogPane();
        WindowsTitleBar.styleDialog(dialog, activeTheme);
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        pane.setPrefWidth(380);

        TextField nameField = new TextField();
        nameField.setPromptText("Calendar name (e.g. Work, School, Personal)");
        EventColor initialColor = EventColor.BLUE;
        if (existing != null) {
            nameField.setText(existing.getName());
            initialColor = existing.getColor();
            Platform.runLater(() -> {
                nameField.requestFocus();
                nameField.selectAll();
            });
        } else {
            Platform.runLater(nameField::requestFocus);
        }

        ComboBox<EventColor> colorBox = createColorComboBox(EventColor.layerPalette(), initialColor);
        Label errorLabel = new Label("");
        errorLabel.getStyleClass().add("caution-label");
        errorLabel.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.add(new Label("Name"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("Color"), 0, 1);
        grid.add(colorBox, 1, 1);
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        GridPane.setHgrow(colorBox, Priority.ALWAYS);

        VBox content = new VBox(8, grid, errorLabel);
        content.setPadding(new Insets(10));
        pane.setContent(content);

        final CalendarLayer[] holder = new CalendarLayer[1];
        Button okBtn = (Button) pane.lookupButton(ButtonType.OK);
        okBtn.addEventFilter(ActionEvent.ACTION, ev -> {
            try {
                String rawName = nameField.getText();
                if (rawName == null || rawName.isBlank()) {
                    throw new IllegalArgumentException("Calendar name is required.");
                }
                EventColor chosenColor = colorBox.getValue();
                if (chosenColor == null || chosenColor == EventColor.DEFAULT) {
                    chosenColor = EventColor.BLUE;
                }
                if (existing == null) {
                    holder[0] = new CalendarLayer(0L, rawName, chosenColor, true);
                } else {
                    holder[0] = existing.withNameAndColor(rawName, chosenColor);
                }
                errorLabel.setText("");
            } catch (IllegalArgumentException ex) {
                holder[0] = null;
                errorLabel.setText(ex.getMessage());
                ev.consume();
            }
        });

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                return holder[0];
            }
            return null;
        });

        return dialog.showAndWait();
    }

    private static HBox buildDaysRow(Map<DayOfWeek, CheckBox> dayChecks) {
        HBox daysRow = new HBox(6);
        for (DayOfWeek day : DayOfWeek.values()) {
            CheckBox cb = new CheckBox(day.name().substring(0, 3));
            dayChecks.put(day, cb);
            daysRow.getChildren().add(cb);
        }
        return daysRow;
    }

    private static ComboBox<CalendarLayer> createCalendarComboBox(List<CalendarLayer> calendars, long preferredId) {
        ComboBox<CalendarLayer> box = new ComboBox<>();
        box.setMaxWidth(Double.MAX_VALUE);
        if (calendars != null && !calendars.isEmpty()) {
            box.getItems().setAll(calendars);
            CalendarLayer match = null;
            for (CalendarLayer layer : calendars) {
                if (layer.getId() == preferredId) {
                    match = layer;
                    break;
                }
            }
            if (match == null) {
                match = calendars.get(0);
            }
            box.setValue(match);
        }
        return box;
    }

    private static ComboBox<EventColor> createColorComboBox(EventColor[] palette, EventColor initial) {
        ComboBox<EventColor> box = new ComboBox<>();
        box.setMaxWidth(Double.MAX_VALUE);
        box.getItems().setAll(palette);
        if (initial != null) {
            box.setValue(initial);
        } else if (palette.length > 0) {
            box.setValue(palette[0]);
        }
        box.setCellFactory(list -> new ColorSwatchCell());
        box.setButtonCell(new ColorSwatchCell());
        return box;
    }

    private static long resolveCalendarId(ComboBox<CalendarLayer> calendarBox, long fallbackId) {
        CalendarLayer chosen = calendarBox.getValue();
        if (chosen != null && chosen.getId() > 0L) {
            return chosen.getId();
        }
        if (fallbackId > 0L) {
            return fallbackId;
        }
        return 1L;
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

    private static final class ColorSwatchCell extends ListCell<EventColor> {
        private final Region dot = new Region();
        private final Label nameLabel = new Label();
        private final HBox row = new HBox(8, dot, nameLabel);

        ColorSwatchCell() {
            dot.setMinSize(10, 10);
            dot.setPrefSize(10, 10);
            dot.setMaxSize(10, 10);
            row.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(EventColor item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
            } else {
                dot.setStyle("-fx-background-color: " + item.getSwatchHex() + "; -fx-background-radius: 3;");
                nameLabel.setText(item.getLabel());
                setGraphic(row);
                setText(null);
            }
        }
    }
}
