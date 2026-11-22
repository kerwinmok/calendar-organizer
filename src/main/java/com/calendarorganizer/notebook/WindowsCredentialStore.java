package com.calendarorganizer.notebook;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Stores Gemini Notebook OAuth secrets in Windows Credential Manager via Advapi32.
 */
public final class WindowsCredentialStore implements SecretStore {

    public static final String DEFAULT_TARGET_PREFIX = "CalendarOrganizer/notebook/";

    private static final int CRED_TYPE_GENERIC = 1;
    private static final int CRED_PERSIST_LOCAL_MACHINE = 2;

    private final String targetPrefix;

    public WindowsCredentialStore() {
        this(DEFAULT_TARGET_PREFIX);
    }

    public WindowsCredentialStore(String targetPrefix) {
        if (targetPrefix == null || targetPrefix.isBlank()) {
            this.targetPrefix = DEFAULT_TARGET_PREFIX;
        } else {
            this.targetPrefix = targetPrefix;
        }
    }

    public static boolean isSupportedPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    @Override
    public String get(String key) {
        if (!isSupportedPlatform() || key == null || key.isBlank()) {
            return "";
        }
        PointerByReference credPtr = new PointerByReference();
        String targetName = targetNameFor(key);
        boolean ok = Advapi32Cred.INSTANCE.CredReadW(new WString(targetName), CRED_TYPE_GENERIC, 0, credPtr);
        if (!ok || credPtr.getValue() == null) {
            return "";
        }
        try {
            CREDENTIALW cred = new CREDENTIALW(credPtr.getValue());
            cred.read();
            if (cred.CredentialBlobSize <= 0 || cred.CredentialBlob == null) {
                return "";
            }
            byte[] bytes = cred.CredentialBlob.getByteArray(0, cred.CredentialBlobSize);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            Advapi32Cred.INSTANCE.CredFree(credPtr.getValue());
        }
    }

    @Override
    public void put(String key, String value) {
        if (!isSupportedPlatform() || key == null || key.isBlank()) {
            return;
        }
        if (value == null || value.isBlank()) {
            delete(key);
            return;
        }
        byte[] blob = value.trim().getBytes(StandardCharsets.UTF_8);
        Memory blobMem = new Memory(blob.length);
        blobMem.write(0, blob, 0, blob.length);

        CREDENTIALW cred = new CREDENTIALW();
        cred.Flags = 0;
        cred.Type = CRED_TYPE_GENERIC;
        cred.TargetName = new WString(targetNameFor(key));
        cred.Comment = new WString("Calendar Organizer OAuth credential");
        cred.ftLowDateTime = 0;
        cred.ftHighDateTime = 0;
        cred.CredentialBlobSize = blob.length;
        cred.CredentialBlob = blobMem;
        cred.Persist = CRED_PERSIST_LOCAL_MACHINE;
        cred.AttributeCount = 0;
        cred.Attributes = null;
        cred.TargetAlias = null;
        cred.UserName = new WString("CalendarOrganizer");

        boolean written = Advapi32Cred.INSTANCE.CredWriteW(cred, 0);
        if (!written) {
            int err = Native.getLastError();
            throw new IllegalStateException("Failed to write credential to Windows Credential Manager (Win32 error " + err + ").");
        }
    }

    @Override
    public void delete(String key) {
        if (!isSupportedPlatform() || key == null || key.isBlank()) {
            return;
        }
        Advapi32Cred.INSTANCE.CredDeleteW(new WString(targetNameFor(key)), CRED_TYPE_GENERIC, 0);
    }

    String targetNameFor(String key) {
        return targetPrefix + key.trim();
    }

    @Structure.FieldOrder({
            "Flags",
            "Type",
            "TargetName",
            "Comment",
            "ftLowDateTime",
            "ftHighDateTime",
            "CredentialBlobSize",
            "CredentialBlob",
            "Persist",
            "AttributeCount",
            "Attributes",
            "TargetAlias",
            "UserName"
    })
    public static class CREDENTIALW extends Structure {
        public int Flags;
        public int Type;
        public WString TargetName;
        public WString Comment;
        public int ftLowDateTime;
        public int ftHighDateTime;
        public int CredentialBlobSize;
        public Pointer CredentialBlob;
        public int Persist;
        public int AttributeCount;
        public Pointer Attributes;
        public WString TargetAlias;
        public WString UserName;

        public CREDENTIALW() {
            super();
        }

        public CREDENTIALW(Pointer p) {
            super(p);
        }
    }

    private interface Advapi32Cred extends StdCallLibrary {
        Advapi32Cred INSTANCE = Native.load("advapi32", Advapi32Cred.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean CredWriteW(CREDENTIALW credential, int flags);

        boolean CredReadW(WString targetName, int type, int flags, PointerByReference credential);

        boolean CredDeleteW(WString targetName, int type, int flags);

        void CredFree(Pointer buffer);
    }
}
