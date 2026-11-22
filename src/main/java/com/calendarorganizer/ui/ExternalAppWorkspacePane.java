package com.calendarorganizer.ui;

import com.calendarorganizer.course.Course;
import com.calendarorganizer.service.AppExecutors;
import com.calendarorganizer.workspace.WindowsWindowSnapper;
import com.calendarorganizer.workspace.WorkspaceLayoutConfig;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public class ExternalAppWorkspacePane extends VBox implements WorkspacePaneContent {

    private static final String PRESET_ONENOTE_DESKTOP = "OneNote Desktop";
    private static final String PRESET_ONENOTE_WEB = "OneNote Web";
    private static final String PRESET_NOTEBOOK_WEB = "Gemini Notebook Web";
    private static final String PRESET_CUSTOM_URL = "Custom URL";

    private static final String SIDE_RIGHT = "Partner Window on Right";
    private static final String SIDE_LEFT = "Partner Window on Left";

    private static final String RATIO_50 = "50 / 50";
    private static final String RATIO_60 = "60 / 40 (App wider)";
    private static final String RATIO_40 = "40 / 60 (Partner wider)";

    private final WorkspaceLayoutConfig.PaneState paneState;
    private final Consumer<String> onStatusChanged;
    private final Runnable onLayoutStateChanged;

    private final ComboBox<String> presetBox = new ComboBox<>();
    private final TextField customUrlField = new TextField();
    private final ComboBox<String> sideBox = new ComboBox<>();
    private final ComboBox<String> ratioBox = new ComboBox<>();
    private final Button snapBesideBtn = new Button("Snap Beside App");
    private final Button snapExistingBtn = new Button("Snap Existing Window");
    private final Button openOnlyBtn = new Button("Open Only");
    private final Button restoreFullBtn = new Button("Restore App Full");
    private final Label statusBanner = new Label("Choose OneNote or any site, then click Snap Beside App.");
    private final Label diagnosticLabel = new Label();

    private boolean suppressingEvents;

    public ExternalAppWorkspacePane(
            WorkspaceLayoutConfig.PaneState paneState,
            Consumer<String> onStatusChanged,
            Runnable onLayoutStateChanged
    ) {
        this.paneState = paneState;
        this.onStatusChanged = onStatusChanged;
        this.onLayoutStateChanged = onLayoutStateChanged;

        setSpacing(10);
        setPadding(new Insets(10));

        VBox launcherCard = buildLauncherCard();
        VBox infoCard = buildInfoCard();

        statusBanner.setWrapText(true);
        statusBanner.setMaxWidth(Double.MAX_VALUE);
        statusBanner.getStyleClass().add("workspace-status-banner");

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(launcherCard, statusBanner, infoCard, spacer);
    }

    private VBox buildLauncherCard() {
        Label heading = new Label("External App / Side-by-Side Window");
        heading.getStyleClass().add("section-heading");

        presetBox.getItems().setAll(PRESET_ONENOTE_DESKTOP, PRESET_ONENOTE_WEB, PRESET_NOTEBOOK_WEB, PRESET_CUSTOM_URL);
        String savedPreset = paneState.getExternalPreset();
        if (!presetBox.getItems().contains(savedPreset)) {
            savedPreset = PRESET_ONENOTE_DESKTOP;
        }
        suppressingEvents = true;
        try {
            presetBox.setValue(savedPreset);
            customUrlField.setText(paneState.getCustomUrl());
        } finally {
            suppressingEvents = false;
        }

        customUrlField.setPromptText("https://example.com");
        customUrlField.setMaxWidth(Double.MAX_VALUE);
        updateCustomUrlVisibility();

        presetBox.setOnAction(e -> {
            if (suppressingEvents) {
                return;
            }
            paneState.setExternalPreset(presetBox.getValue());
            updateCustomUrlVisibility();
            notifyLayoutChanged();
        });
        customUrlField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (suppressingEvents) {
                return;
            }
            paneState.setCustomUrl(newVal);
            notifyLayoutChanged();
        });

        sideBox.getItems().setAll(SIDE_RIGHT, SIDE_LEFT);
        sideBox.setValue(SIDE_RIGHT);

        ratioBox.getItems().setAll(RATIO_50, RATIO_60, RATIO_40);
        ratioBox.setValue(RATIO_50);

        HBox presetRow = new HBox(8, new Label("Target"), presetBox);
        presetRow.setAlignment(Pos.CENTER_LEFT);

        HBox layoutRow = new HBox(8, new Label("Snap"), sideBox, ratioBox);
        layoutRow.setAlignment(Pos.CENTER_LEFT);

        snapBesideBtn.getStyleClass().add("lc-btn-primary");
        snapBesideBtn.setOnAction(e -> handleLaunchAndSnap(true));
        snapExistingBtn.setOnAction(e -> handleSnapExisting());
        openOnlyBtn.setOnAction(e -> handleLaunchAndSnap(false));
        restoreFullBtn.setOnAction(e -> handleRestoreFull());

        FlowPane actions = new FlowPane(6, 6, snapBesideBtn, snapExistingBtn, openOnlyBtn, restoreFullBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, heading, presetRow, customUrlField, layoutRow, actions);
        card.getStyleClass().add("workspace-card");
        card.setPadding(new Insets(10));
        return card;
    }

    private VBox buildInfoCard() {
        Path oneNoteExe = WindowsWindowSnapper.findOneNoteExecutable();
        String pathText;
        if (oneNoteExe != null) {
            pathText = "Detected OneNote Desktop: " + oneNoteExe;
        } else {
            pathText = "OneNote Desktop exe not found in standard Office paths; will use onenote: protocol.";
        }
        diagnosticLabel.setText(
                pathText + "\n\n"
                        + "Why side-by-side JNA snapping instead of embedded WebView?\n"
                        + "JavaFX 21 WebView rendered OneNote Web (onenote.cloud.microsoft) as a blank page (~575 MB process RAM, +36.9 MB runtime) "
                        + "and isolates Google/Microsoft logins. JNA snapping places your real OneNote or browser cleanly beside this window."
        );
        diagnosticLabel.getStyleClass().add("dim-label");
        diagnosticLabel.setWrapText(true);

        VBox card = new VBox(6, diagnosticLabel);
        card.getStyleClass().add("workspace-card");
        card.setPadding(new Insets(10));
        return card;
    }

    private void updateCustomUrlVisibility() {
        boolean custom = PRESET_CUSTOM_URL.equals(presetBox.getValue());
        customUrlField.setVisible(custom);
        customUrlField.setManaged(custom);
    }

    private void handleLaunchAndSnap(boolean snap) {
        String preset = presetBox.getValue();
        boolean partnerOnRight = SIDE_RIGHT.equals(sideBox.getValue());
        double ratio = selectedRatio();
        String customUrl = customUrlField.getText();
        if (customUrl != null) {
            customUrl = customUrl.trim();
        } else {
            customUrl = "";
        }

        if (PRESET_CUSTOM_URL.equals(preset) && !isValidHttpUrl(customUrl)) {
            setBannerError("Enter a valid http:// or https:// URL first.");
            return;
        }

        String finalCustomUrl = customUrl;
        setButtonsBusy(true);
        setBannerInfo("Launching " + preset + "...");

        AppExecutors.ioExecutor().execute(() -> {
            try {
                if (PRESET_ONENOTE_DESKTOP.equals(preset)) {
                    if (snap) {
                        WindowsWindowSnapper.SnapOutcome out =
                                WindowsWindowSnapper.launchAndSnapOneNoteDesktop(ratio, partnerOnRight);
                        finishOnFx(out.getMessage(), false);
                    } else {
                        Path exe = WindowsWindowSnapper.findOneNoteExecutable();
                        if (exe != null) {
                            new ProcessBuilder(exe.toString()).start();
                        } else {
                            WindowsWindowSnapper.openUrlInDefaultBrowser("onenote:");
                        }
                        finishOnFx("Opened OneNote Desktop.", false);
                    }
                    return;
                }

                String url;
                List<String> keywords;
                String label;
                if (PRESET_ONENOTE_WEB.equals(preset)) {
                    url = "https://www.onenote.com/notebooks";
                    keywords = List.of("onenote", "microsoft");
                    label = "OneNote Web";
                } else if (PRESET_NOTEBOOK_WEB.equals(preset)) {
                    url = "https://notebooklm.google.com/";
                    keywords = List.of("notebooklm", "gemini notebook", "notebook.google");
                    label = "Gemini Notebook";
                } else {
                    url = finalCustomUrl;
                    keywords = extractHostKeyword(finalCustomUrl);
                    label = "Browser Window";
                }

                if (snap) {
                    WindowsWindowSnapper.SnapOutcome out =
                            WindowsWindowSnapper.launchUrlAndSnapBrowser(url, keywords, ratio, partnerOnRight, label);
                    finishOnFx(out.getMessage(), false);
                } else {
                    WindowsWindowSnapper.openUrlInDefaultBrowser(url);
                    finishOnFx("Opened " + label + " in default browser.", false);
                }
            } catch (Exception ex) {
                finishOnFx("Failed to launch " + preset + ": " + ex.getMessage(), true);
            }
        });
    }

    private void handleSnapExisting() {
        String preset = presetBox.getValue();
        boolean partnerOnRight = SIDE_RIGHT.equals(sideBox.getValue());
        double ratio = selectedRatio();
        List<String> keywords;
        if (PRESET_ONENOTE_DESKTOP.equals(preset) || PRESET_ONENOTE_WEB.equals(preset)) {
            keywords = List.of("onenote");
        } else if (PRESET_NOTEBOOK_WEB.equals(preset)) {
            keywords = List.of("notebooklm", "gemini notebook", "notebook.google");
        } else {
            keywords = extractHostKeyword(customUrlField.getText());
        }

        setButtonsBusy(true);
        setBannerInfo("Positioning existing " + preset + " window...");
        AppExecutors.ioExecutor().execute(() -> {
            WindowsWindowSnapper.SnapOutcome out =
                    WindowsWindowSnapper.snapExistingPartnerWindow(keywords, ratio, partnerOnRight, preset);
            finishOnFx(out.getMessage(), !out.isPartnerSnapped());
        });
    }

    private void handleRestoreFull() {
        AppExecutors.ioExecutor().execute(() -> {
            boolean ok = WindowsWindowSnapper.restoreAppWindowToWorkArea();
            Platform.runLater(() -> {
                if (ok) {
                    setBannerInfo("Restored Calendar Organizer across full monitor work area.");
                } else {
                    setBannerInfo("Window restore is available on Windows desktop.");
                }
            });
        });
    }

    private void finishOnFx(String message, boolean error) {
        Platform.runLater(() -> {
            setButtonsBusy(false);
            if (error) {
                setBannerError(message);
            } else {
                setBannerInfo(message);
            }
            if (onStatusChanged != null && message != null && !message.isBlank()) {
                onStatusChanged.accept(message);
            }
        });
    }

    private void setButtonsBusy(boolean busy) {
        snapBesideBtn.setDisable(busy);
        snapExistingBtn.setDisable(busy);
        openOnlyBtn.setDisable(busy);
        restoreFullBtn.setDisable(busy);
    }

    private double selectedRatio() {
        String val = ratioBox.getValue();
        if (RATIO_60.equals(val)) {
            return 0.60;
        }
        if (RATIO_40.equals(val)) {
            return 0.40;
        }
        return 0.50;
    }

    private static boolean isValidHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase();
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    private static List<String> extractHostKeyword(String url) {
        if (url == null || url.isBlank()) {
            return List.of("edge", "chrome", "firefox");
        }
        try {
            String host = java.net.URI.create(url.trim()).getHost();
            if (host != null && !host.isBlank()) {
                return List.of(host, "edge", "chrome");
            }
        } catch (IllegalArgumentException ignored) {
        }
        return List.of("edge", "chrome", "firefox");
    }

    private void notifyLayoutChanged() {
        if (onLayoutStateChanged != null) {
            onLayoutStateChanged.run();
        }
    }

    private void setBannerInfo(String message) {
        statusBanner.getStyleClass().remove("workspace-error-banner");
        if (!statusBanner.getStyleClass().contains("workspace-status-banner")) {
            statusBanner.getStyleClass().add("workspace-status-banner");
        }
        statusBanner.setText(message);
    }

    private void setBannerError(String message) {
        statusBanner.getStyleClass().remove("workspace-status-banner");
        if (!statusBanner.getStyleClass().contains("workspace-error-banner")) {
            statusBanner.getStyleClass().add("workspace-error-banner");
        }
        statusBanner.setText(message);
    }

    @Override
    public Region getView() {
        return this;
    }

    @Override
    public void refreshCourses(List<Course> courses, Course preferredCourse) {
    }

    @Override
    public void flushPendingEdits() {
    }

    @Override
    public void releaseResources() {
    }

    @Override
    public void setActiveTheme(UiTheme resolvedTheme) {
    }
}
