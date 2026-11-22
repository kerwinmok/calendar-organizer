package com.calendarorganizer.ui;

import com.calendarorganizer.notebook.GeminiNotebookSyncService;
import com.calendarorganizer.notebook.NotebookConfig;
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
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.LocalDate;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class SettingsDialog extends Dialog<Void> {

    private final CalendarService service;
    private final GeminiNotebookSyncService notebookSyncService;
    private final BooleanSupplier systemDarkSupplier;
    private final Consumer<UiTheme> onThemeChanged;
    private final Runnable onDataChanged;
    private final Consumer<String> onStatus;

    private final Label dbStatsLabel = new Label();
    private final Label memoryLabel = new Label();
    private final Label geminiStatusLabel = new Label();
    private final Label notebookStatusLabel = new Label();
    private final Button clearKeyBtn = new Button("Clear Key");
    private final Label feedbackLabel = new Label("");
    private UiTheme activeTheme;

    public SettingsDialog(Window owner,
                          CalendarService service,
                          GeminiNotebookSyncService notebookSyncService,
                          UiTheme currentTheme,
                          BooleanSupplier systemDarkSupplier,
                          Consumer<UiTheme> onThemeChanged,
                          Runnable onDataChanged,
                          Consumer<String> onStatus) {
        this.service = service;
        this.notebookSyncService = notebookSyncService;
        this.activeTheme = currentTheme;
        if (systemDarkSupplier == null) {
            this.systemDarkSupplier = () -> true;
        } else {
            this.systemDarkSupplier = systemDarkSupplier;
        }
        this.onThemeChanged = onThemeChanged;
        this.onDataChanged = onDataChanged;
        this.onStatus = onStatus;

        initOwner(owner);
        setTitle("Settings");
        setHeaderText("Appearance, AI Parsing & Cleanup");

        DialogPane pane = getDialogPane();
        WindowsTitleBar.styleDialog(this, currentTheme.resolve(this.systemDarkSupplier.getAsBoolean()));
        pane.getButtonTypes().add(ButtonType.CLOSE);
        pane.setPrefSize(500, 610);

        ScrollPane scroll = new ScrollPane(buildContent());
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("lc-dialog-scroll");
        pane.setContent(scroll);
        refreshAiStatus();
        refreshNotebookStatus();
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
                chosen.applyToDialog(getDialogPane(), systemDarkSupplier.getAsBoolean());
                onThemeChanged.accept(chosen);
                feedbackLabel.setText("Theme set to " + chosen.getLabel() + ".");
            }
        });

        HBox themeRow = new HBox(12, themeLabel, new Region(), themeBox);
        HBox.setHgrow(themeRow.getChildren().get(1), Priority.ALWAYS);
        themeRow.setAlignment(Pos.CENTER_LEFT);

        Label aiHeading = new Label("AI Sentence Parsing (Optional)");
        aiHeading.getStyleClass().add("section-heading");

        geminiStatusLabel.getStyleClass().add("dim-label");
        geminiStatusLabel.setWrapText(true);

        PasswordField geminiKeyField = new PasswordField();
        geminiKeyField.setPromptText("Paste Gemini API key (optional)");
        HBox.setHgrow(geminiKeyField, Priority.ALWAYS);

        Button saveKeyBtn = new Button("Save Key");
        saveKeyBtn.setOnAction(e -> {
            String raw = geminiKeyField.getText();
            if (raw == null || raw.isBlank()) {
                feedbackLabel.setText("Enter a Gemini API key first (or click Clear Key).");
                return;
            }
            service.saveGeminiApiKey(raw);
            geminiKeyField.clear();
            refreshAiStatus();
            feedbackLabel.setText("Gemini API key saved locally.");
            onStatus.accept("Gemini API key saved.");
        });

        clearKeyBtn.setOnAction(e -> {
            service.clearGeminiApiKey();
            geminiKeyField.clear();
            refreshAiStatus();
            feedbackLabel.setText("Saved Gemini API key cleared.");
            onStatus.accept("Saved Gemini API key cleared.");
        });

        HBox geminiRow = new HBox(6, geminiKeyField, saveKeyBtn, clearKeyBtn);
        geminiRow.setAlignment(Pos.CENTER_LEFT);

        TextField ollamaHostField = new TextField(service.loadOllamaHost());
        ollamaHostField.setPromptText("Ollama host");
        HBox.setHgrow(ollamaHostField, Priority.ALWAYS);

        TextField ollamaModelField = new TextField(service.loadOllamaModel());
        ollamaModelField.setPromptText("Model");
        ollamaModelField.setPrefWidth(115);

        Button saveOllamaBtn = new Button("Save Ollama");
        saveOllamaBtn.setOnAction(e -> {
            service.saveOllamaSettings(ollamaHostField.getText(), ollamaModelField.getText());
            ollamaHostField.setText(service.loadOllamaHost());
            ollamaModelField.setText(service.loadOllamaModel());
            feedbackLabel.setText("Ollama settings updated.");
            onStatus.accept("Ollama settings updated.");
        });

        HBox ollamaRow = new HBox(6, ollamaHostField, ollamaModelField, saveOllamaBtn);
        ollamaRow.setAlignment(Pos.CENTER_LEFT);

        Label notebookHeading = new Label("Gemini Notebook Sync (Direct OAuth)");
        notebookHeading.getStyleClass().add("section-heading");

        notebookStatusLabel.getStyleClass().add("dim-label");
        notebookStatusLabel.setWrapText(true);

        Button connectNotebookBtn = new Button("Connect / Configure Notebook...");
        connectNotebookBtn.setMaxWidth(Double.MAX_VALUE);
        connectNotebookBtn.setOnAction(e -> {
            UiTheme resolved = activeTheme.resolve(systemDarkSupplier.getAsBoolean());
            NotebookConnectDialog dialog = new NotebookConnectDialog(
                    getDialogPane().getScene().getWindow(),
                    notebookSyncService,
                    resolved
            );
            dialog.showAndWait();
            refreshNotebookStatus();
        });

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
                aiHeading,
                geminiStatusLabel,
                geminiRow,
                ollamaRow,
                new Separator(),
                notebookHeading,
                notebookStatusLabel,
                connectNotebookBtn,
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
        WindowsTitleBar.styleDialog(confirm, activeTheme.resolve(systemDarkSupplier.getAsBoolean()));

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

    public void refreshResolvedTheme(boolean systemDark) {
        activeTheme.applyToDialog(getDialogPane(), systemDark);
    }

    private void refreshAiStatus() {
        boolean hasSaved = service.hasSavedGeminiApiKey();
        if (hasSaved) {
            geminiStatusLabel.setText("Gemini: Active (saved in app)");
        } else if (service.hasEnvGeminiApiKey()) {
            geminiStatusLabel.setText("Gemini: Active (GEMINI_API_KEY env)");
        } else {
            geminiStatusLabel.setText("Gemini: Not set — offline parser active");
        }
        clearKeyBtn.setDisable(!hasSaved);
    }

    private void refreshNotebookStatus() {
        if (notebookSyncService == null) {
            notebookStatusLabel.setText("Gemini Notebook sync unavailable.");
            return;
        }
        NotebookConfig cfg = notebookSyncService.loadConfig();
        if (cfg.isSignedIn()) {
            String account = "Connected";
            if (!cfg.getAccountEmail().isBlank()) {
                account = "Connected as " + cfg.getAccountEmail();
            }
            notebookStatusLabel.setText(account + " (Project: " + cfg.getProjectId() + ")");
        } else if (cfg.hasOAuthClient()) {
            notebookStatusLabel.setText("OAuth client saved (Project: " + cfg.getProjectId() + ") — sign in to connect.");
        } else {
            notebookStatusLabel.setText("Not connected — click below to import OAuth JSON & sign in.");
        }
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
}
