package com.calendarorganizer.ui;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.StdCallLibrary.StdCallCallback;
import com.sun.jna.win32.W32APIOptions;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;

import java.util.ArrayList;
import java.util.List;

public final class WindowsTitleBar {

    static final int DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19;
    static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    static final int DWMWA_CAPTION_COLOR = 35;
    static final int DWMWA_TEXT_COLOR = 36;

    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOZORDER = 0x0004;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_FRAMECHANGED = 0x0020;

    private static final int RDW_INVALIDATE = 0x0001;
    private static final int RDW_UPDATENOW = 0x0100;
    private static final int RDW_FRAME = 0x0400;

    private WindowsTitleBar() {
    }

    static int rgbToColorRef(int r, int g, int b) {
        return ((b & 0xFF) << 16) | ((g & 0xFF) << 8) | (r & 0xFF);
    }

    private static int captionColorFor(UiTheme resolvedTheme) {
        return switch (resolvedTheme) {
            case MIDNIGHT -> rgbToColorRef(0x1e, 0x1e, 0x1e);
            case PAPER -> rgbToColorRef(0xfa, 0xfa, 0xfa);
            case CHARCOAL, SYSTEM -> rgbToColorRef(0x2d, 0x2d, 0x2d);
        };
    }

    private static int textColorFor(UiTheme resolvedTheme) {
        if (resolvedTheme == UiTheme.PAPER) {
            return rgbToColorRef(0x16, 0x16, 0x16);
        }
        return rgbToColorRef(0xe2, 0xe2, 0xe2);
    }

    private static UiTheme resolveIfNeeded(UiTheme theme) {
        if (theme == null) {
            return UiTheme.CHARCOAL;
        }
        if (theme.isSystem()) {
            return theme.resolve(WindowsThemeDetector.isSystemDarkMode());
        }
        return theme;
    }

    public static int applyToAllProcessWindows(UiTheme theme) {
        if (!WindowsThemeDetector.isWindows()) {
            return 0;
        }
        UiTheme resolved = resolveIfNeeded(theme);
        int applied = 0;
        try {
            List<Pointer> handles = findProcessWindows(null);
            for (Pointer hwnd : handles) {
                if (applyToHwnd(hwnd, resolved)) {
                    applied++;
                }
            }
        } catch (Throwable ignored) {
        }
        return applied;
    }

    public static boolean applyToWindowTitle(String title, UiTheme theme) {
        if (!WindowsThemeDetector.isWindows() || title == null || title.isBlank()) {
            return false;
        }
        UiTheme resolved = resolveIfNeeded(theme);
        try {
            boolean any = false;
            for (Pointer hwnd : findProcessWindows(title)) {
                if (applyToHwnd(hwnd, resolved)) {
                    any = true;
                }
            }
            return any;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void styleDialog(Dialog<?> dialog, UiTheme theme) {
        if (dialog == null) {
            return;
        }
        UiTheme resolved = resolveIfNeeded(theme);
        resolved.applyToDialog(dialog.getDialogPane());
        EventHandler<DialogEvent> previousShown = dialog.getOnShown();
        dialog.setOnShown(ev -> {
            if (previousShown != null) {
                previousShown.handle(ev);
            }
            applyToAllProcessWindows(resolved);
            Platform.runLater(() -> applyToAllProcessWindows(resolved));
        });
    }

    private static List<Pointer> findProcessWindows(String requiredTitle) {
        List<Pointer> matches = new ArrayList<>();
        int currentPid = Kernel32Holder.INSTANCE.GetCurrentProcessId();
        User32Holder.INSTANCE.EnumWindows((hwnd, lParam) -> {
            IntByReference pidRef = new IntByReference();
            User32Holder.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef);
            if (pidRef.getValue() != currentPid) {
                return true;
            }
            char[] buf = new char[256];
            int len = User32Holder.INSTANCE.GetWindowText(hwnd, buf, buf.length);
            if (len <= 0) {
                return true;
            }
            String title = new String(buf, 0, len);
            if (requiredTitle == null || requiredTitle.equals(title)) {
                matches.add(hwnd);
            }
            return true;
        }, null);
        return matches;
    }

    private static boolean applyToHwnd(Pointer hwnd, UiTheme resolvedTheme) {
        if (hwnd == null) {
            return false;
        }
        int darkFlag = resolvedTheme.isDark() ? 1 : 0;
        IntByReference darkValue = new IntByReference(darkFlag);
        int hr = DwmapiHolder.INSTANCE.DwmSetWindowAttribute(
                hwnd,
                DWMWA_USE_IMMERSIVE_DARK_MODE,
                darkValue,
                Integer.BYTES
        );
        if (hr != 0) {
            hr = DwmapiHolder.INSTANCE.DwmSetWindowAttribute(
                    hwnd,
                    DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1,
                    darkValue,
                    Integer.BYTES
            );
        }

        IntByReference captionColor = new IntByReference(captionColorFor(resolvedTheme));
        DwmapiHolder.INSTANCE.DwmSetWindowAttribute(
                hwnd,
                DWMWA_CAPTION_COLOR,
                captionColor,
                Integer.BYTES
        );

        IntByReference textColor = new IntByReference(textColorFor(resolvedTheme));
        DwmapiHolder.INSTANCE.DwmSetWindowAttribute(
                hwnd,
                DWMWA_TEXT_COLOR,
                textColor,
                Integer.BYTES
        );

        User32Holder.INSTANCE.SetWindowPos(
                hwnd,
                null,
                0,
                0,
                0,
                0,
                SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED
        );
        User32Holder.INSTANCE.RedrawWindow(
                hwnd,
                null,
                null,
                RDW_FRAME | RDW_INVALIDATE | RDW_UPDATENOW
        );
        return hr == 0;
    }

    static Integer readDarkModeAttribute(String title) {
        if (!WindowsThemeDetector.isWindows() || title == null || title.isBlank()) {
            return null;
        }
        List<Pointer> handles = findProcessWindows(title);
        if (handles.isEmpty()) {
            return null;
        }
        IntByReference out = new IntByReference();
        int hr = DwmapiHolder.INSTANCE.DwmGetWindowAttribute(
                handles.get(0),
                DWMWA_USE_IMMERSIVE_DARK_MODE,
                out,
                Integer.BYTES
        );
        if (hr != 0) {
            return null;
        }
        return out.getValue();
    }

    private static final class DwmapiHolder {
        private static final Dwmapi INSTANCE =
                Native.load("dwmapi", Dwmapi.class, W32APIOptions.UNICODE_OPTIONS);
    }

    private static final class User32Holder {
        private static final User32 INSTANCE =
                Native.load("user32", User32.class, W32APIOptions.UNICODE_OPTIONS);
    }

    private static final class Kernel32Holder {
        private static final Kernel32 INSTANCE =
                Native.load("kernel32", Kernel32.class, W32APIOptions.UNICODE_OPTIONS);
    }

    public interface Dwmapi extends StdCallLibrary {
        int DwmSetWindowAttribute(Pointer hwnd, int dwAttribute, IntByReference pvAttribute, int cbAttribute);

        int DwmGetWindowAttribute(Pointer hwnd, int dwAttribute, IntByReference pvAttribute, int cbAttribute);
    }

    public interface User32 extends StdCallLibrary {
        interface WndEnumProc extends StdCallCallback {
            boolean callback(Pointer hWnd, Pointer lParam);
        }

        boolean EnumWindows(WndEnumProc lpEnumFunc, Pointer lParam);

        int GetWindowThreadProcessId(Pointer hWnd, IntByReference lpdwProcessId);

        int GetWindowText(Pointer hWnd, char[] lpString, int nMaxCount);

        boolean SetWindowPos(Pointer hWnd, Pointer hWndInsertAfter, int x, int y, int cx, int cy, int uFlags);

        boolean RedrawWindow(Pointer hWnd, Pointer lprcUpdate, Pointer hrgnUpdate, int flags);
    }

    public interface Kernel32 extends StdCallLibrary {
        int GetCurrentProcessId();
    }
}
