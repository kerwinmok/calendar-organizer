package com.calendarorganizer.workspace;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Docks external Windows top-level application windows (such as OneNote) into a
 * JavaFX pane region using Win32 owned-window overlay (GWLP_HWNDPARENT + DWM
 * frame-compensated SetWindowPos) via JNA.
 */
public final class WindowsWindowDocker {

    private static final int GWLP_HWNDPARENT = -8;
    private static final int GWL_EXSTYLE = -20;
    private static final int WS_EX_TOOLWINDOW = 0x00000080;
    private static final int SW_RESTORE = 9;
    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOZORDER = 0x0004;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_SHOWWINDOW = 0x0040;
    private static final int SWP_ASYNCWINDOWPOS = 0x4000;
    private static final int DWMWA_EXTENDED_FRAME_BOUNDS = 9;
    private static final int VK_LBUTTON = 0x01;

    private static final Set<DockedSession> ACTIVE_SESSIONS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    static {
        Runtime.getRuntime().addShutdownHook(
                new Thread(WindowsWindowDocker::detachAllOnExit, "onenote-dock-cleanup")
        );
    }

    public static final class WindowCandidate {
        private final Pointer hwnd;
        private final long pid;
        private final String title;
        private final boolean oneNote;

        public WindowCandidate(Pointer hwnd, long pid, String title, boolean oneNote) {
            this.hwnd = hwnd;
            this.pid = pid;
            this.title = title;
            this.oneNote = oneNote;
        }

        public Pointer getHwnd() {
            return hwnd;
        }

        public long getPid() {
            return pid;
        }

        public String getTitle() {
            return title;
        }

        public boolean isOneNote() {
            return oneNote;
        }

        @Override
        public String toString() {
            if (oneNote) {
                return "[OneNote] " + title;
            }
            return title;
        }
    }

    public static final class DragProbe {
        private final boolean leftButtonDown;
        private final boolean overZone;
        private final Pointer hwnd;
        private final String title;

        public DragProbe(boolean leftButtonDown, boolean overZone, Pointer hwnd, String title) {
            this.leftButtonDown = leftButtonDown;
            this.overZone = overZone;
            this.hwnd = hwnd;
            if (title == null) {
                this.title = "";
            } else {
                this.title = title;
            }
        }

        public boolean isLeftButtonDown() {
            return leftButtonDown;
        }

        public boolean isOverZone() {
            return overZone;
        }

        public Pointer getHwnd() {
            return hwnd;
        }

        public String getTitle() {
            return title;
        }
    }

    public static final class DockedSession {
        private final Pointer hwnd;
        private final Pointer originalOwner;
        private final WindowsWindowSnapper.Rect originalRect;
        private final String title;
        private volatile boolean attached;
        private volatile boolean active;

        DockedSession(
                Pointer hwnd,
                Pointer originalOwner,
                WindowsWindowSnapper.Rect originalRect,
                String title
        ) {
            this.hwnd = hwnd;
            this.originalOwner = originalOwner;
            this.originalRect = originalRect;
            this.title = title;
            this.attached = true;
            this.active = true;
        }

        public Pointer getHwnd() {
            return hwnd;
        }

        public Pointer getOriginalOwner() {
            return originalOwner;
        }

        public WindowsWindowSnapper.Rect getOriginalRect() {
            return originalRect;
        }

        public String getTitle() {
            return title;
        }

        public boolean isAttached() {
            return attached;
        }

        public boolean isActive() {
            return active;
        }
    }

    private WindowsWindowDocker() {
    }

    public static boolean isWindows() {
        return WindowsWindowSnapper.isWindows();
    }

    public static WindowsWindowSnapper.Rect computeHostPhysicalRect(
            int clientOriginX,
            int clientOriginY,
            double sceneMinX,
            double sceneMinY,
            double sceneWidth,
            double sceneHeight,
            double scaleX,
            double scaleY
    ) {
        double safeScaleX = Math.max(0.5, scaleX);
        double safeScaleY = Math.max(0.5, scaleY);
        int x = clientOriginX + (int) Math.round(sceneMinX * safeScaleX);
        int y = clientOriginY + (int) Math.round(sceneMinY * safeScaleY);
        int w = Math.max(120, (int) Math.round(sceneWidth * safeScaleX));
        int h = Math.max(100, (int) Math.round(sceneHeight * safeScaleY));
        return new WindowsWindowSnapper.Rect(x, y, w, h);
    }

    public static WindowsWindowSnapper.Rect computeFrameCompensatedRect(
            WindowsWindowSnapper.Rect targetVisible,
            WindowsWindowSnapper.Rect currentFullRect,
            WindowsWindowSnapper.Rect currentExtendedFrame
    ) {
        if (targetVisible == null) {
            return new WindowsWindowSnapper.Rect(0, 0, 640, 480);
        }
        if (currentFullRect == null || currentExtendedFrame == null) {
            return targetVisible;
        }
        int padLeft = clampPad(currentExtendedFrame.getX() - currentFullRect.getX());
        int padTop = clampPad(currentExtendedFrame.getY() - currentFullRect.getY());
        int fullRight = currentFullRect.getX() + currentFullRect.getWidth();
        int extRight = currentExtendedFrame.getX() + currentExtendedFrame.getWidth();
        int padRight = clampPad(fullRight - extRight);
        int fullBottom = currentFullRect.getY() + currentFullRect.getHeight();
        int extBottom = currentExtendedFrame.getY() + currentExtendedFrame.getHeight();
        int padBottom = clampPad(fullBottom - extBottom);

        return new WindowsWindowSnapper.Rect(
                targetVisible.getX() - padLeft,
                targetVisible.getY() - padTop,
                targetVisible.getWidth() + padLeft + padRight,
                targetVisible.getHeight() + padTop + padBottom
        );
    }

    private static int clampPad(int raw) {
        if (raw < 0) {
            return 0;
        }
        if (raw > 24) {
            return 24;
        }
        return raw;
    }

    public static boolean isPointInsideRect(int x, int y, WindowsWindowSnapper.Rect rect) {
        if (rect == null) {
            return false;
        }
        return x >= rect.getX()
                && x <= rect.getX() + rect.getWidth()
                && y >= rect.getY()
                && y <= rect.getY() + rect.getHeight();
    }

    public static boolean hasMovedOutsideThreshold(
            WindowsWindowSnapper.Rect actualVisible,
            WindowsWindowSnapper.Rect expectedVisible,
            int thresholdPx
    ) {
        if (actualVisible == null || expectedVisible == null) {
            return false;
        }
        int dx = Math.abs(actualVisible.getX() - expectedVisible.getX());
        int dy = Math.abs(actualVisible.getY() - expectedVisible.getY());
        int dw = Math.abs(actualVisible.getWidth() - expectedVisible.getWidth());
        int dh = Math.abs(actualVisible.getHeight() - expectedVisible.getHeight());
        return dx > thresholdPx || dy > thresholdPx || (dw > thresholdPx && dh > thresholdPx);
    }

    public static boolean isLeftMouseButtonPressed() {
        if (!isWindows()) {
            return false;
        }
        short state = User32Dock.INSTANCE.GetAsyncKeyState(VK_LBUTTON);
        return (state & 0x8000) != 0;
    }

    public static Pointer findAppWindow(long ownPid) {
        if (!isWindows()) {
            return null;
        }
        List<Pointer> matches = new ArrayList<>();
        User32Dock.INSTANCE.EnumWindows((hwnd, data) -> {
            if (!User32Dock.INSTANCE.IsWindowVisible(hwnd)) {
                return true;
            }
            IntByReference pidRef = new IntByReference();
            User32Dock.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef);
            if ((long) pidRef.getValue() != ownPid) {
                return true;
            }
            int exStyle = User32Dock.INSTANCE.GetWindowLongW(hwnd, GWL_EXSTYLE);
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

    public static int[] getClientOriginScreen(Pointer appHwnd) {
        if (!isWindows() || appHwnd == null) {
            return new int[]{0, 0};
        }
        NativePoint pt = new NativePoint();
        pt.x = 0;
        pt.y = 0;
        if (User32Dock.INSTANCE.ClientToScreen(appHwnd, pt)) {
            return new int[]{pt.x, pt.y};
        }
        return new int[]{0, 0};
    }

    public static List<WindowCandidate> listDockableWindows(long ownPid) {
        List<WindowCandidate> oneNoteMatches = new ArrayList<>();
        List<WindowCandidate> others = new ArrayList<>();
        if (!isWindows()) {
            return others;
        }
        User32Dock.INSTANCE.EnumWindows((hwnd, data) -> {
            if (!User32Dock.INSTANCE.IsWindowVisible(hwnd)) {
                return true;
            }
            int exStyle = User32Dock.INSTANCE.GetWindowLongW(hwnd, GWL_EXSTYLE);
            if ((exStyle & WS_EX_TOOLWINDOW) != 0) {
                return true;
            }
            IntByReference pidRef = new IntByReference();
            User32Dock.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef);
            long pid = pidRef.getValue();
            if (pid <= 0 || pid == ownPid) {
                return true;
            }
            String title = readWindowTitle(hwnd);
            if (!isDockableTitleAndClass(hwnd, title)) {
                return true;
            }
            boolean oneNote = isOneNoteWindow(title, hwnd);
            WindowCandidate candidate = new WindowCandidate(hwnd, pid, title, oneNote);
            if (oneNote) {
                oneNoteMatches.add(candidate);
            } else {
                others.add(candidate);
            }
            return true;
        }, null);
        oneNoteMatches.addAll(others);
        return oneNoteMatches;
    }

    public static DragProbe pollDraggedExternalWindow(
            long ownPid,
            WindowsWindowSnapper.Rect dropZonePhysical,
            boolean oneNoteOnly
    ) {
        if (!isWindows() || dropZonePhysical == null) {
            return new DragProbe(false, false, null, "");
        }
        boolean pressed = isLeftMouseButtonPressed();
        if (!pressed) {
            return new DragProbe(false, false, null, "");
        }
        Pointer fg = User32Dock.INSTANCE.GetForegroundWindow();
        if (fg == null) {
            return new DragProbe(true, false, null, "");
        }
        IntByReference pidRef = new IntByReference();
        User32Dock.INSTANCE.GetWindowThreadProcessId(fg, pidRef);
        long fgPid = pidRef.getValue();
        if (fgPid <= 0 || fgPid == ownPid) {
            return new DragProbe(true, false, null, "");
        }
        String title = readWindowTitle(fg);
        if (!isDockableTitleAndClass(fg, title)) {
            return new DragProbe(true, false, null, "");
        }
        if (oneNoteOnly && !isOneNoteWindow(title, fg)) {
            return new DragProbe(true, false, null, "");
        }
        NativePoint cursor = new NativePoint();
        if (!User32Dock.INSTANCE.GetCursorPos(cursor)) {
            return new DragProbe(true, false, fg, title);
        }
        boolean inside = isPointInsideRect(cursor.x, cursor.y, dropZonePhysical);
        return new DragProbe(true, inside, fg, title);
    }

    public static DockedSession dockWindowIntoHost(
            Pointer targetHwnd,
            Pointer appHwnd,
            WindowsWindowSnapper.Rect targetPhysicalRect
    ) {
        if (!isWindows() || targetHwnd == null || appHwnd == null || targetPhysicalRect == null) {
            return null;
        }
        if (!User32Dock.INSTANCE.IsWindow(targetHwnd)) {
            return null;
        }
        WindowsWindowSnapper.Rect originalRect = readFullWindowRect(targetHwnd);
        Pointer originalOwner = User32Dock.INSTANCE.GetWindowLongPtrW(targetHwnd, GWLP_HWNDPARENT);
        String title = readWindowTitle(targetHwnd);
        if (title.isBlank()) {
            title = "External Window";
        }

        User32Dock.INSTANCE.ShowWindow(targetHwnd, SW_RESTORE);
        User32Dock.INSTANCE.SetWindowLongPtrW(targetHwnd, GWLP_HWNDPARENT, appHwnd);

        DockedSession session = new DockedSession(targetHwnd, originalOwner, originalRect, title);
        ACTIVE_SESSIONS.add(session);

        syncBounds(session, targetPhysicalRect);
        return session;
    }

    public static boolean syncBounds(DockedSession session, WindowsWindowSnapper.Rect targetPhysicalRect) {
        if (!isWindows() || session == null || !session.active || !session.attached || targetPhysicalRect == null) {
            return false;
        }
        if (!User32Dock.INSTANCE.IsWindow(session.hwnd)) {
            session.active = false;
            session.attached = false;
            ACTIVE_SESSIONS.remove(session);
            return false;
        }
        if (User32Dock.INSTANCE.IsIconic(session.hwnd)) {
            User32Dock.INSTANCE.ShowWindow(session.hwnd, SW_RESTORE);
        }
        WindowsWindowSnapper.Rect fullRect = readFullWindowRect(session.hwnd);
        WindowsWindowSnapper.Rect extRect = readExtendedFrameRect(session.hwnd);
        WindowsWindowSnapper.Rect compensated = computeFrameCompensatedRect(targetPhysicalRect, fullRect, extRect);
        return User32Dock.INSTANCE.SetWindowPos(
                session.hwnd,
                null,
                compensated.getX(),
                compensated.getY(),
                compensated.getWidth(),
                compensated.getHeight(),
                SWP_NOZORDER | SWP_NOACTIVATE | SWP_SHOWWINDOW | SWP_ASYNCWINDOWPOS
        );
    }

    public static WindowsWindowSnapper.Rect readVisibleBounds(Pointer hwnd) {
        if (!isWindows() || hwnd == null || !User32Dock.INSTANCE.IsWindow(hwnd)) {
            return null;
        }
        WindowsWindowSnapper.Rect ext = readExtendedFrameRect(hwnd);
        if (ext != null) {
            return ext;
        }
        return readFullWindowRect(hwnd);
    }

    public static boolean isWindowAlive(Pointer hwnd) {
        if (!isWindows() || hwnd == null) {
            return false;
        }
        return User32Dock.INSTANCE.IsWindow(hwnd);
    }

    public static void detachTemporarily(DockedSession session, Pointer appHwnd) {
        if (!isWindows() || session == null || !session.active) {
            return;
        }
        session.attached = false;
        if (User32Dock.INSTANCE.IsWindow(session.hwnd)) {
            User32Dock.INSTANCE.SetWindowLongPtrW(session.hwnd, GWLP_HWNDPARENT, session.originalOwner);
            User32Dock.INSTANCE.SetWindowPos(
                    session.hwnd,
                    null,
                    0,
                    0,
                    0,
                    0,
                    SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE
            );
        }
        if (appHwnd != null && User32Dock.INSTANCE.IsWindow(appHwnd)) {
            User32Dock.INSTANCE.SetForegroundWindow(appHwnd);
        }
    }

    public static boolean reattach(
            DockedSession session,
            Pointer appHwnd,
            WindowsWindowSnapper.Rect targetPhysicalRect
    ) {
        if (!isWindows() || session == null || !session.active || appHwnd == null) {
            return false;
        }
        if (!User32Dock.INSTANCE.IsWindow(session.hwnd)) {
            session.active = false;
            session.attached = false;
            ACTIVE_SESSIONS.remove(session);
            return false;
        }
        User32Dock.INSTANCE.SetWindowLongPtrW(session.hwnd, GWLP_HWNDPARENT, appHwnd);
        session.attached = true;
        return syncBounds(session, targetPhysicalRect);
    }

    public static void undock(DockedSession session, boolean restoreOriginalBounds) {
        if (session == null) {
            return;
        }
        ACTIVE_SESSIONS.remove(session);
        boolean wasActive = session.active;
        session.active = false;
        session.attached = false;
        if (!wasActive || !isWindows()) {
            return;
        }
        if (!User32Dock.INSTANCE.IsWindow(session.hwnd)) {
            return;
        }
        User32Dock.INSTANCE.SetWindowLongPtrW(session.hwnd, GWLP_HWNDPARENT, session.originalOwner);
        if (restoreOriginalBounds && session.originalRect != null) {
            User32Dock.INSTANCE.SetWindowPos(
                    session.hwnd,
                    null,
                    session.originalRect.getX(),
                    session.originalRect.getY(),
                    session.originalRect.getWidth(),
                    session.originalRect.getHeight(),
                    SWP_NOZORDER | SWP_NOACTIVATE | SWP_SHOWWINDOW | SWP_ASYNCWINDOWPOS
            );
        } else {
            User32Dock.INSTANCE.SetWindowPos(
                    session.hwnd,
                    null,
                    0,
                    0,
                    0,
                    0,
                    SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_SHOWWINDOW
            );
        }
    }

    public static void detachAllOnExit() {
        List<DockedSession> copy = new ArrayList<>(ACTIVE_SESSIONS);
        for (DockedSession session : copy) {
            undock(session, false);
        }
    }

    private static boolean isDockableTitleAndClass(Pointer hwnd, String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        String lowerTitle = title.toLowerCase(Locale.ROOT);
        if ("program manager".equals(lowerTitle) || "windows input experience".equals(lowerTitle)) {
            return false;
        }
        String className = readWindowClassName(hwnd).toLowerCase(Locale.ROOT);
        if ("shell_traywnd".equals(className) || "workerw".equals(className) || "progman".equals(className)) {
            return false;
        }
        WindowsWindowSnapper.Rect bounds = readFullWindowRect(hwnd);
        if (bounds == null || bounds.getWidth() < 120 || bounds.getHeight() < 100) {
            return false;
        }
        return true;
    }

    private static boolean isOneNoteWindow(String title, Pointer hwnd) {
        String lowerTitle = "";
        if (title != null) {
            lowerTitle = title.toLowerCase(Locale.ROOT);
        }
        if (lowerTitle.contains("onenote")) {
            return true;
        }
        String cls = readWindowClassName(hwnd).toLowerCase(Locale.ROOT);
        return cls.contains("onenote");
    }

    private static WindowsWindowSnapper.Rect readFullWindowRect(Pointer hwnd) {
        NativeRect rect = new NativeRect();
        if (!User32Dock.INSTANCE.GetWindowRect(hwnd, rect)) {
            return null;
        }
        int w = rect.right - rect.left;
        int h = rect.bottom - rect.top;
        if (w <= 0 || h <= 0) {
            return null;
        }
        return new WindowsWindowSnapper.Rect(rect.left, rect.top, w, h);
    }

    private static WindowsWindowSnapper.Rect readExtendedFrameRect(Pointer hwnd) {
        try {
            NativeRect rect = new NativeRect();
            int hr = DwmapiDock.INSTANCE.DwmGetWindowAttribute(
                    hwnd,
                    DWMWA_EXTENDED_FRAME_BOUNDS,
                    rect.getPointer(),
                    rect.size()
            );
            if (hr != 0) {
                return null;
            }
            rect.read();
            int w = rect.right - rect.left;
            int h = rect.bottom - rect.top;
            if (w <= 0 || h <= 0) {
                return null;
            }
            return new WindowsWindowSnapper.Rect(rect.left, rect.top, w, h);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readWindowTitle(Pointer hwnd) {
        char[] buf = new char[512];
        int len = User32Dock.INSTANCE.GetWindowTextW(hwnd, buf, buf.length);
        if (len <= 0) {
            return "";
        }
        return new String(buf, 0, Math.min(len, buf.length)).trim();
    }

    private static String readWindowClassName(Pointer hwnd) {
        char[] buf = new char[256];
        int len = User32Dock.INSTANCE.GetClassNameW(hwnd, buf, buf.length);
        if (len <= 0) {
            return "";
        }
        return new String(buf, 0, Math.min(len, buf.length)).trim();
    }

    @Structure.FieldOrder({"left", "top", "right", "bottom"})
    public static class NativeRect extends Structure {
        public int left;
        public int top;
        public int right;
        public int bottom;
    }

    @Structure.FieldOrder({"x", "y"})
    public static class NativePoint extends Structure {
        public int x;
        public int y;
    }

    private interface User32Dock extends StdCallLibrary {
        User32Dock INSTANCE = Native.load("user32", User32Dock.class, W32APIOptions.UNICODE_OPTIONS);

        interface WndEnumProc extends StdCallCallback {
            boolean callback(Pointer hwnd, Pointer data);
        }

        boolean EnumWindows(WndEnumProc lpEnumFunc, Pointer data);

        boolean IsWindow(Pointer hwnd);

        boolean IsWindowVisible(Pointer hwnd);

        boolean IsIconic(Pointer hwnd);

        int GetWindowThreadProcessId(Pointer hwnd, IntByReference pid);

        int GetWindowTextW(Pointer hwnd, char[] text, int maxCount);

        int GetClassNameW(Pointer hwnd, char[] className, int maxCount);

        int GetWindowLongW(Pointer hwnd, int index);

        Pointer GetWindowLongPtrW(Pointer hwnd, int index);

        Pointer SetWindowLongPtrW(Pointer hwnd, int index, Pointer newLong);

        boolean GetWindowRect(Pointer hwnd, NativeRect rect);

        boolean ClientToScreen(Pointer hwnd, NativePoint point);

        boolean GetCursorPos(NativePoint point);

        Pointer GetForegroundWindow();

        short GetAsyncKeyState(int vKey);

        boolean ShowWindow(Pointer hwnd, int cmdShow);

        boolean SetWindowPos(Pointer hwnd, Pointer insertAfter, int x, int y, int cx, int cy, int flags);

        boolean SetForegroundWindow(Pointer hwnd);
    }

    private interface DwmapiDock extends StdCallLibrary {
        DwmapiDock INSTANCE = Native.load("dwmapi", DwmapiDock.class, W32APIOptions.DEFAULT_OPTIONS);

        int DwmGetWindowAttribute(Pointer hwnd, int dwAttribute, Pointer pvAttribute, int cbAttribute);
    }
}
