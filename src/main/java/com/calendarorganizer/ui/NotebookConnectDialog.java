package com.calendarorganizer.ui;

import com.calendarorganizer.notebook.GeminiNotebookSyncService;
import com.calendarorganizer.notebook.NotebookConfig;
import com.calendarorganizer.service.AppExecutors;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class NotebookConnectDialog extends Dialog<Boolean> {

    private final GeminiNotebookSyncService syncService;
    private final UiTheme resolvedTheme;

    private final Label statusPill = new Label();
    private final Label accountDetailLabel = new Label();
    private final TextField projectIdField = new TextField();
    private final TextField clientIdField = new TextField();
    private final PasswordField clientSecretField = new PasswordField();

    private final Button importJsonBtn = new Button("Import client_secret.json...");
    private final Button saveCredsBtn = new Button("Save Credentials");
    private final Button signInBtn = new Button("Sign in with Google");
    private final Button cancelSignInBtn = new Button("Cancel Sign-In");
    private final Button disconnectBtn = new Button("Disconnect");
    private final Label feedbackLabel = new Label("");

    private final AtomicBoolean activeCancelFlag = new AtomicBoolean(false);
    private boolean connectedDuringSession;

    public NotebookConnectDialog(
            Window owner,
            GeminiNotebookSyncService syncService,
            UiTheme resolvedTheme
    ) {
        this.syncService = syncService;
        if (resolvedTheme == null) {
            this.resolvedTheme = UiTheme.CHARCOAL;
        } else {
            this.resolvedTheme = resolvedTheme;
        }

        initOwner(owner);
        setTitle("Connect Gemini Notebook");
        setHeaderText("Direct Sync with Google Gemini Notebook (OAuth 2.0)");

        DialogPane pane = getDialogPane();
        WindowsTitleBar.styleDialog(this, this.resolvedTheme);
        pane.getButtonTypes().add(ButtonType.CLOSE);
        pane.setPrefSize(540, 560);

        ScrollPane scroll = new ScrollPane(buildContent());
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("lc-dialog-scroll");
        pane.setContent(scroll);

        setOnHidden(e -> activeCancelFlag.set(true));
        setResultConverter(btn -> connectedDuringSession || syncService.loadConfig().isSignedIn());

        loadCurrentState();
    }

    private VBox buildContent() {
        HBox statusRow = new HBox(10, statusPill, accountDetailLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(accountDetailLabel, Priority.ALWAYS);
        accountDetailLabel.getStyleClass().add("dim-label");
        accountDetailLabel.setWrapText(true);

        VBox guideCard = buildSetupGuideCard();

        Label credsHeading = new Label("Google Desktop OAuth Credentials");
        credsHeading.getStyleClass().add("section-heading");

        projectIdField.setPromptText("Google Cloud Project ID (e.g. my-notebook-project)");
        clientIdField.setPromptText("Desktop OAuth Client ID (...apps.googleusercontent.com)");
        clientSecretField.setPromptText("Optional OAuth Client Secret");

        importJsonBtn.setOnAction(e -> handleImportJson());
        saveCredsBtn.setOnAction(e -> handleSaveCredentials());
        HBox credsBtnRow = new HBox(8, importJsonBtn, new Region(), saveCredsBtn);
        HBox.setHgrow(credsBtnRow.getChildren().get(1), Priority.ALWAYS);
        credsBtnRow.setAlignment(Pos.CENTER_LEFT);

        signInBtn.getStyleClass().add("lc-btn-primary");
        signInBtn.setOnAction(e -> handleSignIn());

        cancelSignInBtn.setOnAction(e -> {
            activeCancelFlag.set(true);
            feedbackLabel.setText("Canceling browser sign-in...");
        });
        cancelSignInBtn.setVisible(false);
        cancelSignInBtn.setManaged(false);

        disconnectBtn.setOnAction(e -> handleDisconnect());

        HBox authBtnRow = new HBox(8, signInBtn, cancelSignInBtn, disconnectBtn);
        authBtnRow.setAlignment(Pos.CENTER_LEFT);

        feedbackLabel.getStyleClass().add("status-label");
        feedbackLabel.setWrapText(true);

        VBox content = new VBox(
                10,
                statusRow,
                guideCard,
                credsHeading,
                labeledField("Project ID", projectIdField),
                labeledField("Client ID", clientIdField),
                labeledField("Client Secret", clientSecretField),
                credsBtnRow,
                authBtnRow,
                feedbackLabel
        );
        content.setPadding(new Insets(12));
        return content;
    }

    private VBox buildSetupGuideCard() {
        Label heading = new Label("One-Time Google Cloud Setup (Personal Google Account)");
        heading.getStyleClass().add("section-heading");

        Label step1 = new Label("1. In Google Cloud Console, enable the Gemini Notebook API (notebook.googleapis.com).");
        Label step2 = new Label("2. Create a Desktop app OAuth Client ID and download client_secret_*.json (personal account, signed into notebooklm.google.com once).");
        Label step3 = new Label("3. Click 'Import client_secret.json...' below, then click 'Sign in with Google'.");

        step1.setWrapText(true);
        step2.setWrapText(true);
        step3.setWrapText(true);
        step1.getStyleClass().add("dim-label");
        step2.getStyleClass().add("dim-label");
        step3.getStyleClass().add("dim-label");

        VBox card = new VBox(5, heading, step1, step2, step3);
        card.getStyleClass().add("notebook-card");
        card.setPadding(new Insets(10, 12, 10, 12));
        return card;
    }

    private VBox labeledField(String title, TextField field) {
        Label label = new Label(title);
        label.getStyleClass().add("dim-label");
        return new VBox(4, label, field);
    }

    private void loadCurrentState() {
        NotebookConfig config = syncService.loadConfig();
        projectIdField.setText(config.getProjectId());
        clientIdField.setText(config.getClientId());
        clientSecretField.setText(config.getClientSecret());

        statusPill.getStyleClass().removeAll("notebook-pill", "notebook-pill-connected");
        if (config.isSignedIn()) {
            statusPill.setText("Connected");
            statusPill.getStyleClass().add("notebook-pill-connected");
            String detail = "Project: " + config.getProjectId();
            if (!config.getAccountEmail().isBlank()) {
                detail = config.getAccountEmail() + "  |  " + detail;
            }
            accountDetailLabel.setText(detail);
            disconnectBtn.setDisable(false);
        } else {
            statusPill.setText("Not Connected");
            statusPill.getStyleClass().add("notebook-pill");
            accountDetailLabel.setText("Tokens stay in your local SQLite database only.");
            disconnectBtn.setDisable(true);
        }
    }

    private void handleImportJson() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Google Desktop OAuth client_secret.json");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON files (*.json)", "*.json"));
        File chosen = chooser.showOpenDialog(getDialogPane().getScene().getWindow());
        if (chosen == null) {
            return;
        }
        try {
            syncService.importClientSecretJson(chosen.toPath());
            loadCurrentState();
            feedbackLabel.setText("Imported OAuth client credentials from " + chosen.getName() + ".");
        } catch (Exception ex) {
            feedbackLabel.setText("Could not import JSON: " + ex.getMessage());
        }
    }

    private boolean saveEnteredCredentials() {
        String projectId = projectIdField.getText();
        String clientId = clientIdField.getText();
        String clientSecret = clientSecretField.getText();
        if (projectId == null || projectId.isBlank() || clientId == null || clientId.isBlank()) {
            feedbackLabel.setText("Enter both Google Cloud Project ID and Client ID (or import client_secret.json).");
            return false;
        }
        syncService.saveClientConfig(projectId, clientId, clientSecret);
        return true;
    }

    private void handleSaveCredentials() {
        if (saveEnteredCredentials()) {
            loadCurrentState();
            feedbackLabel.setText("OAuth client credentials saved locally.");
        }
    }

    private void handleSignIn() {
        if (!saveEnteredCredentials()) {
            return;
        }
        activeCancelFlag.set(false);
        setAuthBusy(true);
        feedbackLabel.setText("Opening browser for Google sign-in... Complete consent in the opened tab.");

        AppExecutors.ioExecutor().execute(() -> {
            try {
                NotebookConfig updated = syncService.signInInteractive(activeCancelFlag);
                Platform.runLater(() -> {
                    setAuthBusy(false);
                    connectedDuringSession = updated.isSignedIn();
                    loadCurrentState();
                    String who = "Connected to Gemini Notebook.";
                    if (!updated.getAccountEmail().isBlank()) {
                        who = "Connected as " + updated.getAccountEmail() + ".";
                    }
                    feedbackLabel.setText(who);
                });
            } catch (IOException ex) {
                Platform.runLater(() -> {
                    setAuthBusy(false);
                    feedbackLabel.setText(ex.getMessage());
                });
            }
        });
    }

    private void handleDisconnect() {
        syncService.disconnect();
        connectedDuringSession = false;
        loadCurrentState();
        feedbackLabel.setText("Disconnected Google account tokens (client config kept).");
    }

    private void setAuthBusy(boolean busy) {
        signInBtn.setDisable(busy);
        importJsonBtn.setDisable(busy);
        saveCredsBtn.setDisable(busy);
        disconnectBtn.setDisable(busy || !syncService.loadConfig().isSignedIn());
        cancelSignInBtn.setVisible(busy);
        cancelSignInBtn.setManaged(busy);
    }
}
