package com.calendarorganizer.ui;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

public final class WindowsThemeDetector implements AutoCloseable {

    static final String DEFAULT_SUBKEY =
            "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
    static final String DEFAULT_VALUE_NAME = "AppsUseLightTheme";

    private static final Pointer HKEY_CURRENT_USER = Pointer.createConstant(0xFFFFFFFF80000001L);
    private static final int ERROR_SUCCESS = 0;
    private static final int KEY_READ = 0x20019;
    private static final int REG_DWORD = 4;
    private static final int REG_NOTIFY_CHANGE_LAST_SET = 0x00000004;
    private static final int WAIT_OBJECT_0 = 0;
    private static final int INFINITE = 0xFFFFFFFF;

    private final String subkey;
    private final String valueName;
    private final Consumer<Boolean> onSystemDarkChanged;
    private final Object lock = new Object();

    private volatile boolean darkMode;
    private boolean started;
    private boolean closed;
    private Pointer stopEvent;
    private Thread watcherThread;

    public WindowsThemeDetector(Consumer<Boolean> onSystemDarkChanged) {
        this(DEFAULT_SUBKEY, DEFAULT_VALUE_NAME, onSystemDarkChanged);
    }

    WindowsThemeDetector(String subkey, String valueName, Consumer<Boolean> onSystemDarkChanged) {
        this.subkey = Objects.requireNonNull(subkey, "subkey must not be null");
        this.valueName = Objects.requireNonNull(valueName, "valueName must not be null");
        this.onSystemDarkChanged = onSystemDarkChanged;
        this.darkMode = isSystemDarkMode(this.subkey, this.valueName);
    }

    public static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win");
    }

    public static boolean isSystemDarkMode() {
        return isSystemDarkMode(DEFAULT_SUBKEY, DEFAULT_VALUE_NAME);
    }

    static boolean isSystemDarkMode(String subkey, String valueName) {
        if (!isWindows()) {
            return true;
        }
        Pointer hKey = null;
        try {
            PointerByReference keyRef = new PointerByReference();
            int openRc = Advapi32Holder.INSTANCE.RegOpenKeyEx(
                    HKEY_CURRENT_USER, subkey, 0, KEY_READ, keyRef
            );
            if (openRc != ERROR_SUCCESS) {
                return true;
            }
            hKey = keyRef.getValue();
            Boolean queried = queryDarkModeFlag(hKey, valueName);
            return queried == null ? true : queried;
        } catch (Throwable ignored) {
            return true;
        } finally {
            if (hKey != null) {
                try {
                    Advapi32Holder.INSTANCE.RegCloseKey(hKey);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public boolean isDarkMode() {
        return darkMode;
    }

    public void start() {
        if (!isWindows()) {
            return;
        }
        synchronized (lock) {
            if (started || closed) {
                return;
            }
            Pointer createdStop = Kernel32Holder.INSTANCE.CreateEvent(null, true, false, null);
            if (createdStop == null) {
                return;
            }
            this.stopEvent = createdStop;
            this.started = true;
            Thread thread = new Thread(this::watchLoop, "windows-theme-watcher");
            thread.setDaemon(true);
            this.watcherThread = thread;
            thread.start();
        }
    }

    private void watchLoop() {
        Pointer hKey = null;
        Pointer regEvent = null;
        try {
            PointerByReference keyRef = new PointerByReference();
            int openRc = Advapi32Holder.INSTANCE.RegOpenKeyEx(
                    HKEY_CURRENT_USER, subkey, 0, KEY_READ, keyRef
            );
            if (openRc != ERROR_SUCCESS) {
                return;
            }
            hKey = keyRef.getValue();
            regEvent = Kernel32Holder.INSTANCE.CreateEvent(null, true, false, null);
            if (regEvent == null) {
                return;
            }

            syncAndNotify(hKey);

            Pointer localStop;
            synchronized (lock) {
                if (closed) {
                    return;
                }
                localStop = stopEvent;
            }
            Pointer[] handles = new Pointer[]{localStop, regEvent};

            while (!closed) {
                Kernel32Holder.INSTANCE.ResetEvent(regEvent);
                int notifyRc = Advapi32Holder.INSTANCE.RegNotifyChangeKeyValue(
                        hKey,
                        false,
                        REG_NOTIFY_CHANGE_LAST_SET,
                        regEvent,
                        true
                );
                if (notifyRc != ERROR_SUCCESS) {
                    break;
                }
                int waitRc = Kernel32Holder.INSTANCE.WaitForMultipleObjects(
                        handles.length,
                        handles,
                        false,
                        INFINITE
                );
                if (waitRc == WAIT_OBJECT_0) {
                    break;
                }
                if (waitRc == WAIT_OBJECT_0 + 1) {
                    syncAndNotify(hKey);
                } else {
                    break;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (hKey != null) {
                try {
                    Advapi32Holder.INSTANCE.RegCloseKey(hKey);
                } catch (Throwable ignored) {
                }
            }
            if (regEvent != null) {
                try {
                    Kernel32Holder.INSTANCE.CloseHandle(regEvent);
                } catch (Throwable ignored) {
                }
            }
            Pointer handleToClose;
            synchronized (lock) {
                handleToClose = stopEvent;
                stopEvent = null;
            }
            if (handleToClose != null) {
                try {
                    Kernel32Holder.INSTANCE.CloseHandle(handleToClose);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void syncAndNotify(Pointer hKey) {
        Boolean queried = queryDarkModeFlag(hKey, valueName);
        if (queried == null) {
            return;
        }
        boolean nextDark = queried;
        if (nextDark != darkMode) {
            darkMode = nextDark;
            if (onSystemDarkChanged != null && !closed) {
                try {
                    onSystemDarkChanged.accept(nextDark);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Boolean queryDarkModeFlag(Pointer hKey, String valueName) {
        byte[] data = new byte[4];
        IntByReference size = new IntByReference(data.length);
        IntByReference type = new IntByReference();
        int rc = Advapi32Holder.INSTANCE.RegQueryValueEx(
                hKey, valueName, null, type, data, size
        );
        if (rc != ERROR_SUCCESS || type.getValue() != REG_DWORD || size.getValue() < 4) {
            return null;
        }
        int appsUseLight = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt();
        return appsUseLight == 0;
    }

    @Override
    public void close() {
        Thread toJoin;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            if (stopEvent != null) {
                try {
                    Kernel32Holder.INSTANCE.SetEvent(stopEvent);
                } catch (Throwable ignored) {
                }
            }
            toJoin = watcherThread;
        }
        if (toJoin != null && toJoin != Thread.currentThread()) {
            try {
                toJoin.join(500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class Advapi32Holder {
        private static final Advapi32 INSTANCE =
                Native.load("advapi32", Advapi32.class, W32APIOptions.UNICODE_OPTIONS);
    }

    private static final class Kernel32Holder {
        private static final Kernel32 INSTANCE =
                Native.load("kernel32", Kernel32.class, W32APIOptions.UNICODE_OPTIONS);
    }

    public interface Advapi32 extends StdCallLibrary {
        int RegOpenKeyEx(Pointer hKey, String lpSubKey, int ulOptions, int samDesired, PointerByReference phkResult);

        int RegQueryValueEx(Pointer hKey, String lpValueName, Pointer lpReserved, IntByReference lpType,
                            byte[] lpData, IntByReference lpcbData);

        int RegNotifyChangeKeyValue(Pointer hKey, boolean bWatchSubtree, int dwNotifyFilter,
                                    Pointer hEvent, boolean fAsynchronous);

        int RegCloseKey(Pointer hKey);
    }

    public interface Kernel32 extends StdCallLibrary {
        Pointer CreateEvent(Pointer lpEventAttributes, boolean bManualReset, boolean bInitialState, String lpName);

        boolean SetEvent(Pointer hEvent);

        boolean ResetEvent(Pointer hEvent);

        int WaitForMultipleObjects(int nCount, Pointer[] lpHandles, boolean bWaitAll, int dwMilliseconds);

        boolean CloseHandle(Pointer hObject);
    }
}
