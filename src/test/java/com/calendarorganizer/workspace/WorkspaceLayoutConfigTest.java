package com.calendarorganizer.workspace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkspaceLayoutConfigTest {

    @Test
    void roundTripsJsonAndEnforcesTwoToFourPaneBounds() {
        WorkspaceLayoutConfig defaults = WorkspaceLayoutConfig.defaultConfig();
        assertEquals(2, defaults.getPanes().size());
        assertEquals(WorkspacePaneType.LECTURE, defaults.getPanes().get(0).getType());
        assertEquals(WorkspacePaneType.NOTES, defaults.getPanes().get(1).getType());
        assertEquals(WorkspacePaneType.NOTEBOOK, defaults.nextSuggestedType());

        WorkspaceLayoutConfig custom = new WorkspaceLayoutConfig();
        custom.setLayoutMode(WorkspaceLayoutConfig.LayoutMode.GRID);
        custom.setPanes(List.of(
                new WorkspaceLayoutConfig.PaneState(WorkspacePaneType.LECTURE, "CMPT 201", "", ""),
                new WorkspaceLayoutConfig.PaneState(WorkspacePaneType.NOTES, "CMPT 201", "", ""),
                new WorkspaceLayoutConfig.PaneState(WorkspacePaneType.NOTEBOOK, "CMPT 201", "", ""),
                new WorkspaceLayoutConfig.PaneState(WorkspacePaneType.EXTERNAL, "", "OneNote Web", "https://www.onenote.com/notebooks"),
                new WorkspaceLayoutConfig.PaneState(WorkspacePaneType.LECTURE, "EXTRA", "", "")
        ));
        custom.setPrimaryDividers(List.of(0.52));
        custom.setFirstGroupDividers(List.of(0.60));
        custom.setSecondGroupDividers(List.of(0.45));

        String json = custom.toJson();
        WorkspaceLayoutConfig restored = WorkspaceLayoutConfig.fromJson(json);

        assertEquals(WorkspaceLayoutConfig.LayoutMode.GRID, restored.getLayoutMode());
        assertEquals(4, restored.getPanes().size());
        assertEquals("CMPT 201", restored.getPanes().get(1).getTargetCourse());
        assertEquals("OneNote Web", restored.getPanes().get(3).getExternalPreset());
        assertEquals(0.52, restored.getPrimaryDividers().get(0), 0.001);
        assertEquals(0.60, restored.getFirstGroupDividers().get(0), 0.001);
        assertEquals(0.45, restored.getSecondGroupDividers().get(0), 0.001);

        WorkspaceLayoutConfig malformed = WorkspaceLayoutConfig.fromJson("{broken json");
        assertEquals(2, malformed.getPanes().size());
    }
}
