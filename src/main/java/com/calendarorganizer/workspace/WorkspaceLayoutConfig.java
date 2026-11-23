package com.calendarorganizer.workspace;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class WorkspaceLayoutConfig {

    public static final int MIN_PANES = 2;
    public static final int MAX_PANES = 4;

    private static final Gson GSON = new Gson();

    public enum LayoutMode {
        HORIZONTAL("Side by Side"),
        VERTICAL("Top / Bottom"),
        GRID("2x2 Grid");

        private final String displayName;

        LayoutMode(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    public static class PaneState {
        private WorkspacePaneType type = WorkspacePaneType.LECTURE;
        private String targetCourse = "";
        private String externalPreset = "OneNote Desktop";
        private String customUrl = "";

        public PaneState() {
        }

        public PaneState(WorkspacePaneType type) {
            this.type = type;
        }

        public PaneState(WorkspacePaneType type, String targetCourse, String externalPreset, String customUrl) {
            if (type == null) {
                this.type = WorkspacePaneType.LECTURE;
            } else {
                this.type = type;
            }
            this.targetCourse = nonNull(targetCourse);
            if (externalPreset == null || externalPreset.isBlank()) {
                this.externalPreset = "OneNote Desktop";
            } else {
                this.externalPreset = externalPreset.trim();
            }
            this.customUrl = nonNull(customUrl);
        }

        public WorkspacePaneType getType() {
            return type;
        }

        public void setType(WorkspacePaneType type) {
            if (type == null) {
                this.type = WorkspacePaneType.LECTURE;
            } else {
                this.type = type;
            }
        }

        public String getTargetCourse() {
            return nonNull(targetCourse);
        }

        public void setTargetCourse(String targetCourse) {
            this.targetCourse = nonNull(targetCourse);
        }

        public String getExternalPreset() {
            if (externalPreset == null || externalPreset.isBlank()) {
                return "OneNote Desktop";
            }
            return externalPreset;
        }

        public void setExternalPreset(String externalPreset) {
            if (externalPreset == null || externalPreset.isBlank()) {
                this.externalPreset = "OneNote Desktop";
            } else {
                this.externalPreset = externalPreset.trim();
            }
        }

        public String getCustomUrl() {
            return nonNull(customUrl);
        }

        public void setCustomUrl(String customUrl) {
            this.customUrl = nonNull(customUrl);
        }

        public PaneState copy() {
            return new PaneState(type, targetCourse, externalPreset, customUrl);
        }

        private static String nonNull(String s) {
            if (s == null) {
                return "";
            }
            return s.trim();
        }
    }

    private LayoutMode layoutMode = LayoutMode.HORIZONTAL;
    private List<PaneState> panes = new ArrayList<>();
    private List<Double> primaryDividers = new ArrayList<>();
    private List<Double> firstGroupDividers = new ArrayList<>();
    private List<Double> secondGroupDividers = new ArrayList<>();

    public static WorkspaceLayoutConfig defaultConfig() {
        WorkspaceLayoutConfig cfg = new WorkspaceLayoutConfig();
        cfg.layoutMode = LayoutMode.HORIZONTAL;
        cfg.panes.add(new PaneState(WorkspacePaneType.LECTURE));
        cfg.panes.add(new PaneState(WorkspacePaneType.ONENOTE));
        cfg.primaryDividers.add(0.50);
        return cfg;
    }

    public static WorkspaceLayoutConfig studyTrioConfig() {
        WorkspaceLayoutConfig cfg = new WorkspaceLayoutConfig();
        cfg.layoutMode = LayoutMode.GRID;
        cfg.panes.add(new PaneState(WorkspacePaneType.LECTURE));
        cfg.panes.add(new PaneState(WorkspacePaneType.CALENDAR));
        cfg.panes.add(new PaneState(WorkspacePaneType.ONENOTE));
        cfg.primaryDividers.add(0.48);
        cfg.firstGroupDividers.add(0.62);
        return cfg;
    }

    public LayoutMode getLayoutMode() {
        if (layoutMode == null) {
            return LayoutMode.HORIZONTAL;
        }
        return layoutMode;
    }

    public void setLayoutMode(LayoutMode layoutMode) {
        if (layoutMode == null) {
            this.layoutMode = LayoutMode.HORIZONTAL;
        } else {
            this.layoutMode = layoutMode;
        }
    }

    public List<PaneState> getPanes() {
        return panes;
    }

    public void setPanes(List<PaneState> panes) {
        this.panes = new ArrayList<>();
        if (panes != null) {
            for (PaneState p : panes) {
                if (p != null) {
                    this.panes.add(p.copy());
                }
            }
        }
        normalize();
    }

    public List<Double> getPrimaryDividers() {
        return primaryDividers;
    }

    public void setPrimaryDividers(List<Double> primaryDividers) {
        this.primaryDividers = sanitizeDividers(primaryDividers);
    }

    public List<Double> getFirstGroupDividers() {
        return firstGroupDividers;
    }

    public void setFirstGroupDividers(List<Double> firstGroupDividers) {
        this.firstGroupDividers = sanitizeDividers(firstGroupDividers);
    }

    public List<Double> getSecondGroupDividers() {
        return secondGroupDividers;
    }

    public void setSecondGroupDividers(List<Double> secondGroupDividers) {
        this.secondGroupDividers = sanitizeDividers(secondGroupDividers);
    }

    public WorkspacePaneType nextSuggestedType() {
        Set<WorkspacePaneType> present = EnumSet.noneOf(WorkspacePaneType.class);
        for (PaneState state : panes) {
            present.add(state.getType());
        }
        WorkspacePaneType[] preferred = {
                WorkspacePaneType.CALENDAR,
                WorkspacePaneType.ONENOTE,
                WorkspacePaneType.NOTES,
                WorkspacePaneType.LECTURE
        };
        for (WorkspacePaneType candidate : preferred) {
            if (!present.contains(candidate)) {
                return candidate;
            }
        }
        return WorkspacePaneType.NOTES;
    }

    public void normalize() {
        if (layoutMode == null) {
            layoutMode = LayoutMode.HORIZONTAL;
        }
        if (panes == null) {
            panes = new ArrayList<>();
        }
        List<PaneState> clean = new ArrayList<>();
        for (PaneState p : panes) {
            if (p != null) {
                if (p.type == null) {
                    if (clean.isEmpty()) {
                        p.type = WorkspacePaneType.LECTURE;
                    } else {
                        p.type = WorkspacePaneType.ONENOTE;
                    }
                }
                clean.add(p);
                if (clean.size() >= MAX_PANES) {
                    break;
                }
            }
        }
        while (clean.size() < MIN_PANES) {
            if (clean.isEmpty()) {
                clean.add(new PaneState(WorkspacePaneType.LECTURE));
            } else if (clean.get(0).getType() != WorkspacePaneType.ONENOTE) {
                clean.add(new PaneState(WorkspacePaneType.ONENOTE));
            } else {
                clean.add(new PaneState(WorkspacePaneType.CALENDAR));
            }
        }
        this.panes = clean;
        this.primaryDividers = sanitizeDividers(this.primaryDividers);
        this.firstGroupDividers = sanitizeDividers(this.firstGroupDividers);
        this.secondGroupDividers = sanitizeDividers(this.secondGroupDividers);
    }

    public String toJson() {
        normalize();
        return GSON.toJson(this);
    }

    public static WorkspaceLayoutConfig fromJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaultConfig();
        }
        try {
            WorkspaceLayoutConfig parsed = GSON.fromJson(raw, WorkspaceLayoutConfig.class);
            if (parsed == null) {
                return defaultConfig();
            }
            parsed.normalize();
            return parsed;
        } catch (RuntimeException ex) {
            return defaultConfig();
        }
    }

    private static List<Double> sanitizeDividers(List<Double> raw) {
        List<Double> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (Double val : raw) {
            if (val != null && Double.isFinite(val)) {
                double clamped = Math.max(0.08, Math.min(0.92, val));
                result.add(clamped);
            }
        }
        return result;
    }
}
