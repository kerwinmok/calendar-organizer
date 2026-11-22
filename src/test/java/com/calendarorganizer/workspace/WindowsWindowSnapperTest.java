package com.calendarorganizer.workspace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsWindowSnapperTest {

    @Test
    void computesSymmetricAndAsymmetricSplitBoundsOnWorkArea() {
        WindowsWindowSnapper.Rect workArea = new WindowsWindowSnapper.Rect(0, 40, 1920, 1000);

        WindowsWindowSnapper.SplitPair pair = WindowsWindowSnapper.computeSplitBounds(workArea, 0.5, true);
        assertEquals(0, pair.getAppBounds().getX());
        assertEquals(40, pair.getAppBounds().getY());
        assertEquals(960, pair.getAppBounds().getWidth());
        assertEquals(1000, pair.getAppBounds().getHeight());

        assertEquals(960, pair.getPartnerBounds().getX());
        assertEquals(960, pair.getPartnerBounds().getWidth());

        WindowsWindowSnapper.SplitPair flipped = WindowsWindowSnapper.computeSplitBounds(workArea, 0.6, false);
        assertEquals(1152, flipped.getAppBounds().getX());
        assertEquals(768, flipped.getAppBounds().getWidth());
        assertEquals(0, flipped.getPartnerBounds().getX());
        assertEquals(1152, flipped.getPartnerBounds().getWidth());
    }

    @Test
    void matchesPartnerWindowsByPidAndKeywordsWhileIgnoringOwnAndShellWindows() {
        long ownPid = 100L;
        assertFalse(WindowsWindowSnapper.matchesPartnerCandidate("Calendar Organizer", ownPid, -1L, ownPid, List.of("calendar")));
        assertFalse(WindowsWindowSnapper.matchesPartnerCandidate("Program Manager", 200L, -1L, ownPid, List.of("program")));
        assertFalse(WindowsWindowSnapper.matchesPartnerCandidate("", 300L, 300L, ownPid, List.of("onenote")));

        assertTrue(WindowsWindowSnapper.matchesPartnerCandidate("Quick Notes - Microsoft OneNote", 400L, 400L, ownPid, List.of("onenote")));
        assertTrue(WindowsWindowSnapper.matchesPartnerCandidate("NotebookLM - Google Chrome", 500L, -1L, ownPid, List.of("notebooklm", "gemini notebook")));
        assertFalse(WindowsWindowSnapper.matchesPartnerCandidate("Random Folder", 600L, -1L, ownPid, List.of("onenote")));
    }
}
