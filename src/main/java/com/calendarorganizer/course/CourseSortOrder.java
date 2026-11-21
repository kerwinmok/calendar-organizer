package com.calendarorganizer.course;

public enum CourseSortOrder {
    NAME_ASC("name_asc", "Name (A-Z)"),
    NAME_DESC("name_desc", "Name (Z-A)"),
    NEWEST_FIRST("newest", "Newest first"),
    OLDEST_FIRST("oldest", "Oldest first");

    private final String id;
    private final String label;

    CourseSortOrder(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public static CourseSortOrder fromId(String rawId) {
        if (rawId != null) {
            for (CourseSortOrder order : values()) {
                if (order.id.equalsIgnoreCase(rawId.trim())) {
                    return order;
                }
            }
        }
        return NAME_ASC;
    }

    @Override
    public String toString() {
        return label;
    }
}
