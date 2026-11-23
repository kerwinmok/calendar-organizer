package com.calendarorganizer.model;

public enum EventColor {
    DEFAULT("Calendar Default", "", "#6c8ebf"),
    BLUE("Blue", "event-color-blue", "#4882de"),
    TEAL("Teal", "event-color-teal", "#38a8c4"),
    GREEN("Green", "event-color-green", "#58b05c"),
    AMBER("Amber", "event-color-amber", "#e8c43a"),
    RED("Red", "event-color-red", "#de4848"),
    PURPLE("Purple", "event-color-purple", "#9b6be0"),
    PINK("Pink", "event-color-pink", "#e066a6"),
    SLATE("Slate", "event-color-slate", "#8e99a8");

    private final String label;
    private final String styleClass;
    private final String swatchHex;

    EventColor(String label, String styleClass, String swatchHex) {
        this.label = label;
        this.styleClass = styleClass;
        this.swatchHex = swatchHex;
    }

    public String getLabel() {
        return label;
    }

    public String getStyleClass() {
        return styleClass;
    }

    public String getSwatchHex() {
        return swatchHex;
    }

    @Override
    public String toString() {
        return label;
    }

    public static EventColor fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        String normalized = raw.trim().toUpperCase();
        for (EventColor color : values()) {
            if (color.name().equals(normalized)) {
                return color;
            }
        }
        return DEFAULT;
    }

    public static EventColor[] layerPalette() {
        return new EventColor[]{BLUE, TEAL, GREEN, AMBER, RED, PURPLE, PINK, SLATE};
    }

    public static EventColor fallbackForCategory(EventCategory category) {
        if (category == null) {
            return SLATE;
        }
        switch (category) {
            case WORK:
                return BLUE;
            case BUSY:
                return RED;
            case WORKOUT:
                return GREEN;
            case STUDY:
                return AMBER;
            case IMPORTED:
                return TEAL;
            default:
                return SLATE;
        }
    }
}
