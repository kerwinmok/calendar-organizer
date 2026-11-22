package com.calendarorganizer.ui;

import com.calendarorganizer.course.Course;
import com.calendarorganizer.course.CourseManager;
import com.calendarorganizer.notebook.NotebookMockupPreferences;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class NotebookDeploymentMockupDialog extends Dialog<Boolean> {

    private final NotebookMockupPreferences mockupPrefs;
    private final CourseManager courseManager;

    private final Label stepBadgeLabel = new Label();
    private final Label demoBannerLabel = new Label("Deployment UX Preview (Simulated Login & Sync)");
    private final StackPane stepHost = new StackPane();

    private final CheckBox dontAskCheck = new CheckBox("Don't ask on startup");
    private final TextField emailField = new TextField();
    private final List<CheckBox> courseChecks = new ArrayList<>();
    private final CheckBox scheduleCheck = new CheckBox("Weekly Calendar Schedule (events, goals & sleep)");
    private final CheckBox autoSyncCheck = new CheckBox("Auto-sync when lecture PDFs are dropped or notes are saved");

    private final ProgressBar syncProgressBar = new ProgressBar(0.0);
    private final Label syncProgressLabel = new Label("Preparing sync...");
    private final Label connectedSummaryLabel = new Label("");
    private final Button doneBtn = new Button("Done");
    private final Button resetDemoBtn = new Button("Reset Demo");

    private final VBox step1Pane;
    private final VBox step2Pane;
    private final VBox step3Pane;
    private final VBox step4Pane;

    private Timeline activeAnimation;

    public NotebookDeploymentMockupDialog(
            Window owner,
            NotebookMockupPreferences mockupPrefs,
            CourseManager courseManager,
            UiTheme resolvedTheme
    ) {
        this.mockupPrefs = mockupPrefs;
        this.courseManager = courseManager;

        initOwner(owner);
        setTitle("Connect Gemini Notebook (Deploy Preview)");
        setHeaderText("Zero-Config Onboarding Preview");

        DialogPane pane = getDialogPane();
        WindowsTitleBar.styleDialog(this, resolvedTheme);
        pane.getButtonTypes().add(ButtonType.CLOSE);
        pane.setPrefSize(520, 500);

        this.step1Pane = buildStep1Welcome();
        this.step2Pane = buildStep2GoogleConsent();
        this.step3Pane = buildStep3SelectContent();
        this.step4Pane = buildStep4SyncAndSummary();
        stepHost.getChildren().setAll(step1Pane, step2Pane, step3Pane, step4Pane);

        stepBadgeLabel.getStyleClass().add("notebook-step-badge");
        demoBannerLabel.getStyleClass().add("dim-label");

        HBox topMeta = new HBox(10, stepBadgeLabel, demoBannerLabel);
        topMeta.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(12, topMeta, stepHost);
        root.setPadding(new Insets(12));

        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("lc-dialog-scroll");
        pane.setContent(scroll);

        setOnHidden(e -> stopSyncAnimation());
        setResultConverter(btn -> mockupPrefs.isDemoConnected());

        if (mockupPrefs.isDemoConnected()) {
            showExistingConnectedState();
        } else {
            showStep(1);
        }
    }

    private VBox buildStep1Welcome() {
        Label title = new Label("Bring your schedule and classes into Gemini Notebook");
        title.getStyleClass().add("section-heading");
        title.setWrapText(true);

        Label pitch = new Label(
                "In the deployed desktop release, end users never configure Google Cloud projects or JSON files. "
                        + "One click opens Google sign-in and keeps course notes, lecture slides, and your schedule synced automatically."
        );
        pitch.setWrapText(true);

        Label privacyHeading = new Label("Local-First Privacy");
        privacyHeading.getStyleClass().add("section-heading");
        Label privacyBody = new Label(
                "• Your local SQLite calendar and course files stay on this computer.\n"
                        + "• Only the courses and schedule you choose below are shared with your personal Google account.\n"
                        + "• You can disconnect or pause auto-sync anytime from Settings."
        );
        privacyBody.getStyleClass().add("dim-label");
        privacyBody.setWrapText(true);

        VBox privacyCard = new VBox(6, privacyHeading, privacyBody);
        privacyCard.getStyleClass().add("notebook-card");
        privacyCard.setPadding(new Insets(10, 12, 10, 12));

        dontAskCheck.setSelected(mockupPrefs.isPromptDismissed());

        Button notNowBtn = new Button("Not Now");
        notNowBtn.setOnAction(e -> {
            mockupPrefs.setPromptDismissed(dontAskCheck.isSelected());
            close();
        });

        Button continueBtn = new Button("Continue with Google");
        continueBtn.getStyleClass().add("lc-btn-primary");
        continueBtn.setOnAction(e -> showStep(2));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, notNowBtn, spacer, continueBtn);
        buttons.setAlignment(Pos.CENTER_LEFT);

        return new VBox(12, title, pitch, privacyCard, dontAskCheck, buttons);
    }

    private VBox buildStep2GoogleConsent() {
        Label title = new Label("Sign in with Google");
        title.getStyleClass().add("section-heading");

        Label subtitle = new Label("Simulated OAuth 2.0 PKCE browser consent screen:");
        subtitle.getStyleClass().add("dim-label");

        String initialEmail = mockupPrefs.getConnectedEmail();
        if (initialEmail.isBlank()) {
            initialEmail = "student@gmail.com";
        }
        emailField.setText(initialEmail);
        emailField.setPromptText("Google account email");

        Label scopeHeading = new Label("Calendar Organizer wants access to:");
        scopeHeading.getStyleClass().add("section-heading");
        Label scopes = new Label(
                "✓ Create and manage your Gemini Notebooks (auth/notebook)\n"
                        + "✓ Upload selected course notes, PDFs, and weekly schedules\n"
                        + "✓ View your Google account email address"
        );
        scopes.getStyleClass().add("dim-label");
        scopes.setWrapText(true);

        VBox consentCard = new VBox(6, scopeHeading, scopes);
        consentCard.getStyleClass().add("notebook-card");
        consentCard.setPadding(new Insets(10, 12, 10, 12));

        Button backBtn = new Button("Back");
        backBtn.setOnAction(e -> showStep(1));

        Button allowBtn = new Button("Allow & Connect");
        allowBtn.getStyleClass().add("lc-btn-primary");
        allowBtn.setOnAction(e -> showStep(3));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, backBtn, spacer, allowBtn);
        buttons.setAlignment(Pos.CENTER_LEFT);

        return new VBox(10, title, subtitle, new Label("Google Account"), emailField, consentCard, buttons);
    }

    private VBox buildStep3SelectContent() {
        Label title = new Label("Choose what to sync to Gemini Notebook");
        title.getStyleClass().add("section-heading");

        VBox coursesList = new VBox(6);
        populateCourseCheckboxes(coursesList);
        VBox coursesCard = new VBox(8, new Label("Courses"), coursesList);
        coursesCard.getStyleClass().add("notebook-card");
        coursesCard.setPadding(new Insets(10, 12, 10, 12));

        scheduleCheck.setSelected(mockupPrefs.isSyncScheduleEnabled());
        autoSyncCheck.setSelected(mockupPrefs.isAutoSyncEnabled());

        Button backBtn = new Button("Back");
        backBtn.setOnAction(e -> showStep(2));

        Button startSyncBtn = new Button("Start Sync");
        startSyncBtn.getStyleClass().add("lc-btn-primary");
        startSyncBtn.setOnAction(e -> runSimulatedSync());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, backBtn, spacer, startSyncBtn);
        buttons.setAlignment(Pos.CENTER_LEFT);

        return new VBox(10, title, coursesCard, scheduleCheck, autoSyncCheck, buttons);
    }

    private VBox buildStep4SyncAndSummary() {
        Label title = new Label("Syncing Your Study Workspace");
        title.getStyleClass().add("section-heading");

        syncProgressBar.setMaxWidth(Double.MAX_VALUE);
        syncProgressLabel.setWrapText(true);

        connectedSummaryLabel.setWrapText(true);
        connectedSummaryLabel.getStyleClass().add("dim-label");

        VBox summaryCard = new VBox(6, connectedSummaryLabel);
        summaryCard.getStyleClass().add("notebook-card");
        summaryCard.setPadding(new Insets(10, 12, 10, 12));

        resetDemoBtn.setOnAction(e -> {
            stopSyncAnimation();
            mockupPrefs.resetDemoState();
            dontAskCheck.setSelected(false);
            showStep(1);
        });

        doneBtn.getStyleClass().add("lc-btn-primary");
        doneBtn.setOnAction(e -> close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, resetDemoBtn, spacer, doneBtn);
        buttons.setAlignment(Pos.CENTER_LEFT);

        return new VBox(10, title, syncProgressBar, syncProgressLabel, summaryCard, buttons);
    }

    private void populateCourseCheckboxes(VBox container) {
        courseChecks.clear();
        List<String> names = new ArrayList<>();
        try {
            List<Course> realCourses = courseManager.listCourses();
            for (Course course : realCourses) {
                names.add(course.getDisplayName());
            }
        } catch (IOException ignored) {
        }
        if (names.isEmpty()) {
            names.add("CMPT 201");
            names.add("STAT 201");
        }
        for (String name : names) {
            CheckBox cb = new CheckBox(name);
            cb.setSelected(true);
            courseChecks.add(cb);
            container.getChildren().add(cb);
        }
    }

    private void runSimulatedSync() {
        List<String> selectedCourses = collectSelectedCourses();
        String email = emailField.getText();
        boolean syncSchedule = scheduleCheck.isSelected();
        boolean autoSync = autoSyncCheck.isSelected();
        mockupPrefs.saveDemoConnection(email, selectedCourses, syncSchedule, autoSync);

        showStep(4);
        stopSyncAnimation();

        doneBtn.setDisable(true);
        syncProgressBar.setProgress(0.05);
        syncProgressLabel.setText("Authorizing Google session for " + mockupPrefs.getConnectedEmail() + "...");
        connectedSummaryLabel.setText("Sync in progress...");

        String firstCourse = "study materials";
        if (!selectedCourses.isEmpty()) {
            firstCourse = selectedCourses.get(0);
        }
        String finalFirstCourse = firstCourse;

        Timeline timeline = new Timeline(
                new KeyFrame(Duration.millis(450), ev -> {
                    syncProgressBar.setProgress(0.35);
                    syncProgressLabel.setText("Creating Gemini Notebook for " + finalFirstCourse + "...");
                }),
                new KeyFrame(Duration.millis(950), ev -> {
                    syncProgressBar.setProgress(0.72);
                    syncProgressLabel.setText("Uploading course notes and lecture PDFs...");
                }),
                new KeyFrame(Duration.millis(1450), ev -> {
                    syncProgressBar.setProgress(1.0);
                    syncProgressLabel.setText("Connected & synced!");
                    doneBtn.setDisable(false);
                    updateSummaryText();
                })
        );
        this.activeAnimation = timeline;
        timeline.play();
    }

    private void showExistingConnectedState() {
        showStep(4);
        syncProgressBar.setProgress(1.0);
        syncProgressLabel.setText("Demo workspace connected.");
        doneBtn.setDisable(false);
        updateSummaryText();
    }

    private void updateSummaryText() {
        String email = mockupPrefs.getConnectedEmail();
        List<String> courses = mockupPrefs.getSelectedCourses();
        String coursesSummary = "None selected";
        if (!courses.isEmpty()) {
            coursesSummary = String.join(", ", courses);
        }
        String scheduleMode = "Disabled";
        if (mockupPrefs.isSyncScheduleEnabled()) {
            scheduleMode = "Enabled (weekly schedule markdown)";
        }
        String autoMode = "Manual sync";
        if (mockupPrefs.isAutoSyncEnabled()) {
            autoMode = "Automatic on file drop / note save";
        }
        connectedSummaryLabel.setText(
                "Account: " + email + "\n"
                        + "Synced courses: " + coursesSummary + "\n"
                        + "Calendar schedule: " + scheduleMode + "\n"
                        + "Sync mode: " + autoMode + "\n\n"
                        + "Production wiring notes: docs/notebook-deployment-mockup.md"
        );
    }

    private List<String> collectSelectedCourses() {
        List<String> selected = new ArrayList<>();
        for (CheckBox cb : courseChecks) {
            if (cb.isSelected()) {
                selected.add(cb.getText());
            }
        }
        return selected;
    }

    private void showStep(int step) {
        stepBadgeLabel.setText("Step " + step + " of 4");
        VBox[] panes = new VBox[]{step1Pane, step2Pane, step3Pane, step4Pane};
        for (int i = 0; i < panes.length; i++) {
            boolean match = (i + 1) == step;
            panes[i].setVisible(match);
            panes[i].setManaged(match);
        }
    }

    private void stopSyncAnimation() {
        if (activeAnimation != null) {
            activeAnimation.stop();
            activeAnimation = null;
        }
    }
}
