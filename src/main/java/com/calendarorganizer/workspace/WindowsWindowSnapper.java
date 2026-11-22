package com.calendarorganizer.workspace;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Launches external study applications or URLs and snaps them side-by-side with
 * Calendar Organizer on Windows using Win32 User32 monitor work-area bounds via JNA.
 */
public final class WindowsWindowSnapper {

    private static final int SW_RESTORE = 9;
    private static final int SWP_NOZORDER = 0x0004;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_SHOWWINDOW = 0x0040;
    private static final int MONITOR_DEFAULTTONEAREST = 2;
    private static final int GWL_EXSTYLE = -20;
    private static final int WS_EX_TOOLWINDOW = 0x00000080;

    public static final class Rect {
        private final int x;
        private final int y;
        private final int width;
        private final int height;

        public Rect(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }
    }

    public static final class SplitPair {
        private final Rect appBounds;
        private final Rect partnerBounds;

        public SplitPair(Rect appBounds, Rect partnerBounds) {
            this.appBounds = appBounds;
            this.partnerBounds = partnerBounds;
        }

        public Rect getAppBounds() {
            return appBounds;
        }

        public Rect getPartnerBounds() {
            return partnerBounds;
        }
    }

    public static final class SnapOutcome {
        private final boolean appSnapped;
        private final boolean partnerSnapped;
        private final String message;

        public SnapOutcome(boolean appSnapped, boolean partnerSnapped, String message) {
            this.appSnapped = appSnapped;
            this.partnerSnapped = partnerSnapped;
            this.message = message;
        }

        public boolean isAppSnapped() {
            return appSnapped;
        }

        public boolean isPartnerSnapped() {
            return partnerSnapped;
        }

        public String getMessage() {
            return message;
        }
    }

    private WindowsWindowSnapper() {
    }

    public static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    public static SplitPair computeSplitBounds(Rect workArea, double appRatio, boolean partnerOnRight) {
        double safeRatio = Math.max(0.25, Math.min(0.75, appRatio));
        int leftWidth = (int) Math.round(workArea.getWidth() * safeRatio);
        int rightWidth = Math.max(200, workArea.getWidth() - leftWidth);
        int leftX = workArea.getX();
        int rightX = leftX + leftWidth;
        Rect left = new Rect(leftX, workArea.getY(), leftWidth, workArea.getHeight());
        Rect right = new Rect(rightX, workArea.getY(), rightWidth, workArea.getHeight());
        if (partnerOnRight) {
            return new SplitPair(left, right);
        }
        return new SplitPair(right, left);
    }

    public static Path findOneNoteExecutable() {
        List<String> candidates = new ArrayList<>();
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        if (programFiles != null && !programFiles.isBlank()) {
            candidates.add(programFiles + "\\Microsoft Office\\Root\\Office16\\ONENOTE.EXE");
            candidates.add(programFiles + "\\Microsoft Office\\Office16\\ONENOTE.EXE");
            candidates.add(programFiles + "\\Microsoft Office\\Office15\\ONENOTE.EXE");
        }
        if (programFilesX86 != null && !programFilesX86.isBlank()) {
            candidates.add(programFilesX86 + "\\Microsoft Office\\Root\\Office16\\ONENOTE.EXE");
            candidates.add(programFilesX86 + "\\Microsoft Office\\Office16\\ONENOTE.EXE");
            candidates.add(programFilesX86 + "\\Microsoft Office\\Office15\\ONENOTE.EXE");
        }
        candidates.add("C:\\Program Files\\Microsoft Office\\Root\\Office16\\ONENOTE.EXE");
        candidates.add("C:\\Program Files (x86)\\Microsoft Office\\Root\\Office16\\ONENOTE.EXE");
        for (String candidate : candidates) {
            try {
                Path p = Path.of(candidate);
                if (Files.isRegularFile(p)) {
                    return p;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    public static void openUrlInDefaultBrowser(String url) throws IOException {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL cannot be empty.");
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            new ProcessBuilder("rundll32.exe", "url.dll,FileProtocolHandler", url.trim()).start();
        } else if (os.contains("mac")) {
            new ProcessBuilder("open", url.trim()).start();
        } else {
            new ProcessBuilder("xdg-open", url.trim()).start();
        }
    }

    public static SnapOutcome launchAndSnapOneNoteDesktop(double appWidthRatio, boolean partnerOnRight) throws IOException {
        if (!isWindows()) {
            openUrlInDefaultBrowser("https://www.onenote.com/notebooks");
            return new SnapOutcome(false, false, "Opened OneNote web in default browser (window snapping is Windows-only).");
        }
        Path exe = findOneNoteExecutable();
        long launchedPid = -1L;
        if (exe != null) {
            Process proc = new ProcessBuilder(exe.toString()).start();
            launchedPid = proc.pid();
        } else {
            openUrlInDefaultBrowser("onenote:");
        }
        return snapByPidOrKeywords(
                launchedPid,
                List.of("onenote"),
                appWidthRatio,
                partnerOnRight,
                "OneNote Desktop"
        );
    }

    public static SnapOutcome launchUrlAndSnapBrowser(
            String url,
            List<String> titleKeywords,
            double appWidthRatio,
            boolean partnerOnRight,
            String label
    ) throws IOException {
        openUrlInDefaultBrowser(url);
        if (!isWindows()) {
            return new SnapOutcome(false, false, "Opened " + label + " in default browser.");
        }
        return snapByPidOrKeywords(-1L, titleKeywords, appWidthRatio, partnerOnRight, label);
    }

    public static SnapOutcome snapExistingPartnerWindow(
            List<String> titleKeywords,
            double appWidthRatio,
            boolean partnerOnRight,
            String label
    ) {
        if (!isWindows()) {
            return new SnapOutcome(false, false, "Window snapping requires Windows.");
        }
        return snapWithAttempts(-1L, titleKeywords, appWidthRatio, partnerOnRight, label, 2);
    }

    public static boolean restoreAppWindowToWorkArea() {
        if (!isWindows()) {
            return false;
        }
        long ownPid = ProcessHandle.current().pid();
        Pointer appHwnd = findMainProcessWindow(ownPid);
        if (appHwnd == null) {
            return false;
        }
        Rect work = getMonitorWorkArea(appHwnd);
        User32Snap.INSTANCE.ShowWindow(appHwnd, SW_RESTORE);
        return User32Snap.INSTANCE.SetWindowPos(
                appHwnd,
                null,
                work.getX(),
                work.getY(),
                work.getWidth(),
                work.getHeight(),
                SWP_NOZORDER | SWP_SHOWWINDOW
        );
    }

    static boolean matchesPartnerCandidate(
            String title,
            long windowPid,
            long expectedPid,
            long ownPid,
            List<String> keywords
    ) {
        if (windowPid <= 0 || windowPid == ownPid) {
            return false;
        }
        if (expectedPid > 0 && windowPid == expectedPid && title != null && !title.isBlank()) {
            return true;
        }
        if (title == null || title.isBlank()) {
            return false;
        }
        String lowerTitle = title.toLowerCase(Locale.ROOT);
        if ("program manager".equals(lowerTitle)) {
            return false;
        }
        if (keywords != null) {
            for (String keyword : keywords) {
                if (keyword != null && !keyword.isBlank()) {
                    if (lowerTitle.contains(keyword.toLowerCase(Locale.ROOT))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static SnapOutcome snapByPidOrKeywords(
            long expectedPid,
            List<String> keywords,
            double appWidthRatio,
            boolean partnerOnRight,
            String label
    ) {
        return snapWithAttempts(expectedPid, keywords, appWidthRatio, partnerOnRight, label, 24);
    }

    private static SnapOutcome snapWithAttempts(
            long expectedPid,
            List<String> keywords,
            double appWidthRatio,
            boolean partnerOnRight,
            String label,
            int maxAttempts
    ) {
        long ownPid = ProcessHandle.current().pid();
        Pointer appHwnd = findMainProcessWindow(ownPid);
        if (appHwnd == null) {
            return new SnapOutcome(false, false, "Could not locate Calendar Organizer main window.");
        }

        Rect workArea = getMonitorWorkArea(appHwnd);
        SplitPair pair = computeSplitBounds(workArea, appWidthRatio, partnerOnRight);

        User32Snap.INSTANCE.ShowWindow(appHwnd, SW_RESTORE);
        boolean appMoved = moveWindowAdjusted(appHwnd, pair.getAppBounds(), true);

        Pointer partnerHwnd = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            partnerHwnd = findPartnerWindow(expectedPid, ownPid, keywords);
            if (partnerHwnd != null) {
                break;
            }
            if (attempt < maxAttempts - 1) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (partnerHwnd == null) {
            return new SnapOutcome(
                    appMoved,
                    false,
                    "Snapped Calendar Organizer to one half. Launched " + label
                            + ", waiting for its window — click Snap Windows Again once " + label + " finishes opening."
            );
        }

        User32Snap.INSTANCE.ShowWindow(partnerHwnd, SW_RESTORE);
        boolean partnerMoved = moveWindowAdjusted(partnerHwnd, pair.getPartnerBounds(), false);
        User32Snap.INSTANCE.SetForegroundWindow(appHwnd);
        if (appMoved && partnerMoved) {
            return new SnapOutcome(true, true, "Snapped Calendar Organizer and " + label + " side by side.");
        }
        return new SnapOutcome(appMoved, partnerMoved, "Positioned Calendar Organizer alongside " + label + ".");
    }

    private static boolean moveWindowAdjusted(Pointer hwnd, Rect target, boolean activate) {
        int flags = SWP_NOZORDER | SWP_SHOWWINDOW;
        if (!activate) {
            flags = flags | SWP_NOACTIVATE;
        }
        // DWM extended frame bounds often include ~7px invisible resize shadows on left/right/bottom.
        int padX = 0;
        int padBottom = 0;
        Win32Rect winRect = new Win32Rect();
        if (User32Snap.INSTANCE.GetWindowRect(hwnd, winRect)) {
            int fullW = winRect.right - winRect.left;
            if (fullW > 200) {
                padX = 7;
                padBottom = 7;
            }
        }
        return User32Snap.INSTANCE.SetWindowPos(
                hwnd,
                null,
                target.getX() - padX,
                target.getY(),
                target.getWidth() + (padX * 2),
                target.getHeight() + padBottom,
                flags
        );
    }

    private static Rect getMonitorWorkArea(Pointer hwnd) {
        Pointer monitor = User32Snap.INSTANCE.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
        MONITORINFO info = new MONITORINFO();
        info.cbSize = info.size();
        if (monitor != null && User32Snap.INSTANCE.GetMonitorInfoW(monitor, info)) {
            int w = Math.max(640, info.rcWork.right - info.rcWork.left);
            int h = Math.max(480, info.rcWork.bottom - info.rcWork.top);
            return new Rect(info.rcWork.left, info.rcWork.top, w, h);
        }
        return new Rect(0, 0, 1280, 720);
    }

    private static Pointer findMainProcessWindow(long ownPid) {
        List<Pointer> matches = new ArrayList<>();
        User32Snap.INSTANCE.EnumWindows((hwnd, data) -> {
            if (!User32Snap.INSTANCE.IsWindowVisible(hwnd)) {
                return true;
            }
            IntByReference pidRef = new IntByReference();
            User32Snap.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef);
            if ((long) pidRef.getValue() != ownPid) {
                return true;
            }
            int exStyle = User32Snap.INSTANCE.GetWindowLongW(hwnd, GWL_EXSTYLE);
            if ((exStyle & WS_EX_TOOLWINDOW) != 0) {
                return true;
            }
            String title = readWindowTitle(hwnd);
            if (title.contains("Calendar Organizer")) {
                matches.add(0, hwnd);
            } else if (!title.isBlank()) {
                matches.add(hwnd);
            }
            return true;
        }, null);
        if (matches.isEmpty()) {
            return null;
        }
        return matches.get(0);
    }

    private static Pointer findPartnerWindow(long expectedPid, long ownPid, List<String> keywords) {
        List<Pointer> exactPidMatches = new ArrayList<>();
        List<Pointer> keywordMatches = new ArrayList<>();
        User32Snap.INSTANCE.EnumWindows((hwnd, data) -> {
            if (!User32Snap.INSTANCE.IsWindowVisible(hwnd)) {
                return true;
            }
            int exStyle = User32Snap.INSTANCE.GetWindowLongW(hwnd, GWL_EXSTYLE);
            if ((exStyle & WS_EX_TOOLWINDOW) != 0) {
                return true;
            }
            IntByReference pidRef = new IntByReference();
            User32Snap.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef);
            long winPid = pidRef.getValue();
            if (winPid <= 0 || winPid == ownPid) {
                return true;
            }
            String title = readWindowTitle(hwnd);
            if (expectedPid > 0 && winPid == expectedPid && !title.isBlank()) {
                exactPidMatches.add(hwnd);
                return true;
            }
            if (matchesPartnerCandidate(title, winPid, expectedPid, ownPid, keywords)) {
                keywordMatches.add(hwnd);
            }
            return true;
        }, null);

        if (!exactPidMatches.isEmpty()) {
            return exactPidMatches.get(0);
        }
        if (!keywordMatches.isEmpty()) {
            return keywordMatches.get(0);
        }
        return null;
    }

    private static String readWindowTitle(Pointer hwnd) {
        char[] buf = new char[512];
        int len = User32Snap.INSTANCE.GetWindowTextW(hwnd, buf, buf.length);
        if (len <= 0) {
            return "";
        }
        return new String(buf, 0, Math.min(len, buf.length)).trim();
    }

    @Structure.FieldOrder({"left", "top", "right", "bottom"})
    public static class Win32Rect extends Structure {
        public int left;
        public int top;
        public int right;
        public int bottom;
    }

    @Structure.FieldOrder({"cbSize", "rcMonitor", "rcWork", "dwFlags"})
    public static class MONITORINFO extends Structure {
        public int cbSize;
        public Win32Rect rcMonitor;
        public Win32Rect rcWork;
        public int dwFlags;
    }

    private interface User32Snap extends StdCallLibrary {
        User32Snap INSTANCE = Native.load("user32", User32Snap.class, W32APIOptions.UNICODE_OPTIONS);

        interface WndEnumProc extends StdCallCallback {
            boolean callback(Pointer hwnd, Pointer data);
        }

        boolean EnumWindows(WndEnumProc lpEnumFunc, Pointer data);

        boolean IsWindowVisible(Pointer hwnd);

        int GetWindowThreadProcessId(Pointer hwnd, IntByReference pid);

        int GetWindowTextW(Pointer hwnd, char[] text, int maxCount);

        int GetWindowLongW(Pointer hwnd, int index);

        boolean GetWindowRect(Pointer hwnd, Win32Rect rect);

        boolean ShowWindow(Pointer hwnd, int cmdShow);

        boolean SetWindowPos(Pointer hwnd, Pointer insertAfter, int x, int y, int cx, int cy, int flags);

        boolean SetForegroundWindow(Pointer hwnd);

        Pointer MonitorFromWindow(Pointer hwnd, int flags);

        boolean GetMonitorInfoW(Pointer hMonitor, MONITORINFO info);
    }
}
