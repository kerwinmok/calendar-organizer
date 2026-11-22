package com.calendarorganizer.notebook;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowsCredentialStoreTest {

    @Test
    void storesReadsOverwritesAndDeletesSecretsInWindowsVault() {
        Assumptions.assumeTrue(WindowsCredentialStore.isSupportedPlatform());

        String prefix = "CalendarOrganizerTest/" + UUID.randomUUID() + "/";
        WindowsCredentialStore store = new WindowsCredentialStore(prefix);
        String key = "refresh_token";
        try {
            assertEquals("", store.get(key));

            store.put(key, "tok-alpha-123");
            assertEquals("tok-alpha-123", store.get(key));

            store.put(key, "tok-beta-456");
            assertEquals("tok-beta-456", store.get(key));

            store.delete(key);
            assertEquals("", store.get(key));
        } finally {
            store.delete(key);
        }
    }
}
