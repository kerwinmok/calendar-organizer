package com.calendarorganizer.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiThemeTest {

    @Test
    void resolvesSystemThemeAndDefaultsToSystem() {
        assertTrue(UiTheme.SYSTEM.isSystem());
        assertFalse(UiTheme.CHARCOAL.isSystem());
        assertFalse(UiTheme.PAPER.isDark());
        assertTrue(UiTheme.CHARCOAL.isDark());
        assertTrue(UiTheme.MIDNIGHT.isDark());

        assertEquals(UiTheme.CHARCOAL, UiTheme.SYSTEM.resolve(true));
        assertEquals(UiTheme.PAPER, UiTheme.SYSTEM.resolve(false));
        assertEquals(UiTheme.MIDNIGHT, UiTheme.MIDNIGHT.resolve(false));
        assertEquals(UiTheme.PAPER, UiTheme.PAPER.resolve(true));

        assertTrue(UiTheme.SYSTEM.isEffectiveDark(true));
        assertFalse(UiTheme.SYSTEM.isEffectiveDark(false));
        assertFalse(UiTheme.PAPER.isEffectiveDark(true));

        assertEquals(UiTheme.SYSTEM, UiTheme.fromId(null));
        assertEquals(UiTheme.SYSTEM, UiTheme.fromId("unknown-theme"));
        assertEquals(UiTheme.SYSTEM, UiTheme.fromId("system"));
        assertEquals(UiTheme.PAPER, UiTheme.fromId("paper"));
        assertEquals(UiTheme.MIDNIGHT, UiTheme.fromId("midnight"));

        assertEquals(0x002d2d2d, WindowsTitleBar.rgbToColorRef(0x2d, 0x2d, 0x2d));
        assertEquals(0x00dd6501, WindowsTitleBar.rgbToColorRef(0x01, 0x65, 0xdd));
        assertEquals(0x00fafafa, WindowsTitleBar.rgbToColorRef(0xfa, 0xfa, 0xfa));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void detectsAndWatchesLiveRegistryThemeChanges() throws Exception {
        WindowsThemeDetector.isSystemDarkMode();

        String subkey = "Software\\CalendarOrganizerTestThemeWatch_" + UUID.randomUUID();
        String regPath = "HKCU\\" + subkey;
        runReg("add", regPath, "/v", WindowsThemeDetector.DEFAULT_VALUE_NAME,
                "/t", "REG_DWORD", "/d", "0", "/f");

        BlockingQueue<Boolean> events = new LinkedBlockingQueue<>();
        try (WindowsThemeDetector detector = new WindowsThemeDetector(
                subkey,
                WindowsThemeDetector.DEFAULT_VALUE_NAME,
                events::add
        )) {
            assertTrue(detector.isDarkMode());
            detector.start();

            runReg("add", regPath, "/v", WindowsThemeDetector.DEFAULT_VALUE_NAME,
                    "/t", "REG_DWORD", "/d", "1", "/f");
            Boolean lightUpdate = events.poll(3, TimeUnit.SECONDS);
            assertNotNull(lightUpdate);
            assertFalse(lightUpdate);
            assertFalse(detector.isDarkMode());

            runReg("add", regPath, "/v", WindowsThemeDetector.DEFAULT_VALUE_NAME,
                    "/t", "REG_DWORD", "/d", "0", "/f");
            Boolean darkUpdate = events.poll(3, TimeUnit.SECONDS);
            assertNotNull(darkUpdate);
            assertTrue(darkUpdate);
            assertTrue(detector.isDarkMode());
        } finally {
            runRegIgnoreError("delete", regPath, "/f");
        }
    }

    private static void runReg(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "reg.exe";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] output = p.getInputStream().readAllBytes();
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("reg.exe exited with " + code + ": " + new String(output));
        }
    }

    private static void runRegIgnoreError(String... args) {
        try {
            runReg(args);
        } catch (Exception ignored) {
        }
    }
}
