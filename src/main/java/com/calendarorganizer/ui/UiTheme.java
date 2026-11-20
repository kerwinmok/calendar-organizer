package com.calendarorganizer.ui;

import javafx.scene.control.DialogPane;

import java.net.URL;

public enum UiTheme {
    CHARCOAL("charcoal", "Charcoal (Dark)", "theme-charcoal"),
    MIDNIGHT("midnight", "Midnight (OLED Dark)", "theme-midnight"),
    PAPER("paper", "Paper (Light)", "theme-paper");

    private final String id;
    private final String label;
    private final String styleClass;

    UiTheme(String id, String label, String styleClass) {
        this.id = id;
        this.label = label;
        this.styleClass = styleClass;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public String getStyleClass() {
        return styleClass;
    }

    public void applyToDialog(DialogPane pane) {
        URL cssUrl = UiTheme.class.getResource("/styles.css");
        if (cssUrl != null) {
            String urlStr = cssUrl.toExternalForm();
            if (!pane.getStylesheets().contains(urlStr)) {
                pane.getStylesheets().add(urlStr);
            }
        }
        for (UiTheme candidate : values()) {
            pane.getStyleClass().remove(candidate.getStyleClass());
        }
        pane.getStyleClass().add(styleClass);
    }

    public static UiTheme fromId(String rawId) {
        if (rawId != null) {
            for (UiTheme theme : values()) {
                if (theme.id.equalsIgnoreCase(rawId.trim())) {
                    return theme;
                }
            }
        }
        return CHARCOAL;
    }

    @Override
    public String toString() {
        return label;
    }
}
