package com.calendarorganizer.ui;

import com.calendarorganizer.service.CalendarService;
import com.calendarorganizer.storage.StorageStats;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.net.URL;
import java.time.LocalDate;
import java.util.Optional;
import java.util.function.Consumer;

public class SettingsDialog extends Dialog<Void> {

    private final CalendarService service;
    private final Consumer<UiTheme> onThemeChanged;
    private final Runnable onDataChanged;
    private final Consumer<String> onStatus;

    private final Label dbStatsLabel = new Label();
    private final Label memoryLabel = new Label();
    private final Label feedbackLabel = new Label("");
    private UiTheme activeTheme;

    public SettingsDialog(Window owner,
                          CalendarService service,
                          UiTheme currentTheme,
                          Consumer<UiTheme> onThemeChanged,
                          Runnable onDataChanged,
                          Consumer<String> onStatus) {
        this.service = service;
        this.activeTheme = currentTheme;
        this.onThemeChanged = onThemeChanged;
        this.onDataChanged = onDataChanged;
        this.onStatus = onStatus;

        initOwner(owner);
        setTitle("Settings");
        setHeaderText("Appearance, Storage & Cleanup");

        DialogPane pane = getDialogPane();
        applyDialogTheme(pane, currentTheme);
        pane.getButtonTypes().add(ButtonType.CLOSE);
        pane.setPrefWidth(480);

        pane.setContent(buildContent());
        refreshStats();
    }

    private VBox buildContent() {
        Label appearanceHeading = new Label("Appearance");
        appearanceHeading.getStyleClass().add("section-heading");

        Label themeLabel = new Label("Theme");
        ComboBox<UiTheme> themeBox = new ComboBox<>();
        themeBox.getItems().setAll(UiTheme.values());
        themeBox.setValue(activeTheme);
        themeBox.setOnAction(e -> {
            UiTheme chosen = themeBox.getValue();
            if (chosen != null) {
                activeTheme = chosen;
                service.saveTheme(chosen.getId());
                applyDialogTheme(getDialogPane(), chosen);
                onThemeChanged.accept(chosen);
                feedbackLabel.setText("Theme set to " + chosen.getLabel() + ".");
            }
        });

        HBox themeRow = new HBox(12, themeLabel, new Region(), themeBox);
        HBox.setHgrow(themeRow.getChildren().get(1), Priority.ALWAYS);
        themeRow.setAlignment(Pos.CENTER_LEFT);

        Label storageHeading = new Label("Calendar Storage & Performance");
        storageHeading.getStyleClass().add("section-heading");

        dbStatsLabel.setWrapText(true);
        memoryLabel.setWrapText(true);
        memoryLabel.getStyleClass().add("dim-label");

        Button clearPastBtn = new Button("Clear Past Events");
        clearPastBtn.setMaxWidth(Double.MAX_VALUE);
        clearPastBtn.setOnAction(e -> handleClearPast());

        Button clearScheduledBtn = new Button("Clear Scheduled Sessions");
        clearScheduledBtn.setMaxWidth(Double.MAX_VALUE);
        clearScheduledBtn.setOnAction(e -> handleClearScheduled());

        Button optimizeBtn = new Button("Optimize & Compact Database");
        optimizeBtn.setMaxWidth(Double.MAX_VALUE);
        optimizeBtn.setOnAction(e -> handleOptimize());

        Button resetBtn = new Button("Reset All Calendar History & Settings");
        resetBtn.getStyleClass().add("lc-btn-danger");
        resetBtn.setMaxWidth(Double.MAX_VALUE);
        resetBtn.setOnAction(e -> handleResetAll());

        Label noteLabel = new Label("Course folders and markdown files on disk are never deleted by calendar cleanup.");
        noteLabel.getStyleClass().add("dim-label");
        noteLabel.setWrapText(true);

        feedbackLabel.getStyleClass().add("status-label");
        feedbackLabel.setWrapText(true);

        VBox content = new VBox(10,
                appearanceHeading,
                themeRow,
                new Separator(),
                storageHeading,
                dbStatsLabel,
                memoryLabel,
                clearPastBtn,
                clearScheduledBtn,
                optimizeBtn,
                resetBtn,
                noteLabel,
                feedbackLabel
        );
        content.setPadding(new Insets(12));
        return content;
    }

    private void handleClearPast() {
        runBackground("Clearing past events...", () -> {
            int deleted = service.clearPastEvents(LocalDate.now());
            return "Removed " + deleted + " past events.";
        });
    }

    private void handleClearScheduled() {
        runBackground("Clearing scheduled sessions...", () -> {
            service.clearScheduledEvents();
            return "Cleared all auto-scheduled sessions.";
        });
    }

    private void handleOptimize() {
        runBackground("Compacting database...", () -> {
            StorageStats stats = service.optimizeAndCompact();
            System.gc();
            return "Database compacted (" + stats.formatSize() + ").";
        });
    }

    private void handleResetAll() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(getDialogPane().getScene().getWindow());
        confirm.setTitle("Reset Calendar History & Settings");
        confirm.setHeaderText("Delete all calendar events, recurring rules, goals, and sleep settings?");
        confirm.setContentText("Your markdown course folders will stay intact.");
        applyDialogTheme(confirm.getDialogPane(), activeTheme);

        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }

        runBackground("Resetting calendar data...", () -> {
            StorageStats stats = service.resetCalendarHistoryAndSettings();
            System.gc();
            return "Calendar history and settings deleted (" + stats.formatSize() + ").";
        });
    }

    private void runBackground(String startMsg, java.util.concurrent.Callable<String> work) {
        feedbackLabel.setText(startMsg);
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(ev -> {
            String message = task.getValue();
            feedbackLabel.setText(message);
            refreshStats();
            onDataChanged.run();
            onStatus.accept(message);
        });
        task.setOnFailed(ev -> {
            Throwable err = task.getException();
            if (err != null) {
                feedbackLabel.setText("Error: " + err.getMessage());
            } else {
                feedbackLabel.setText("Operation failed.");
            }
        });
        CalendarService.backgroundExecutor().execute(task);
    }

    private void refreshStats() {
        StorageStats stats = service.getStorageStats();
        dbStatsLabel.setText("Events: " + stats.getEventCount()
                + "   |   Rules: " + stats.getRecurringRuleCount()
                + "   |   Goals: " + stats.getGoalCount()
                + "   |   DB: " + stats.formatSize());

        Runtime rt = Runtime.getRuntime();
        long usedMb = Math.round((rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0));
        long allocMb = Math.round(rt.totalMemory() / (1024.0 * 1024.0));
        memoryLabel.setText("Memory usage: " + usedMb + " MB used / " + allocMb + " MB allocated");
    }

    private void applyDialogTheme(DialogPane pane, UiTheme theme) {
        URL cssUrl = SettingsDialog.class.getResource("/styles.css");
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
