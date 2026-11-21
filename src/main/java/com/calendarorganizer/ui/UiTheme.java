package com.calendarorganizer.ui;

import javafx.scene.control.DialogPane;

import java.net.URL;

public enum UiTheme {
    SYSTEM("system", "System (Auto)", "theme-charcoal", true),
    CHARCOAL("charcoal", "Charcoal (Dark)", "theme-charcoal", true),
    MIDNIGHT("midnight", "Midnight (OLED Dark)", "theme-midnight", true),
    PAPER("paper", "Paper (Light)", "theme-paper", false);

    private final String id;
    private final String label;
    private final String styleClass;
    private final boolean dark;

    UiTheme(String id, String label, String styleClass, boolean dark) {
        this.id = id;
        this.label = label;
        this.styleClass = styleClass;
        this.dark = dark;
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

    public String getStyleClass(boolean systemDark) {
        return resolve(systemDark).styleClass;
    }

    public boolean isSystem() {
        return this == SYSTEM;
    }

    public boolean isDark() {
        return dark;
    }

    public UiTheme resolve(boolean systemDark) {
        return this == SYSTEM ? (systemDark ? CHARCOAL : PAPER) : this;
    }

    public boolean isEffectiveDark(boolean systemDark) {
        return resolve(systemDark).isDark();
    }

    public void applyToDialog(DialogPane pane) {
        applyToDialog(pane, true);
    }

    public void applyToDialog(DialogPane pane, boolean systemDark) {
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
        pane.getStyleClass().add(resolve(systemDark).getStyleClass());
    }

    public static UiTheme fromId(String rawId) {
        if (rawId != null) {
            for (UiTheme theme : values()) {
                if (theme.id.equalsIgnoreCase(rawId.trim())) {
                    return theme;
                }
            }
        }
        return SYSTEM;
    }

    @Override
    public String toString() {
        return label;
    }
}
