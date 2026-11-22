package com.calendarorganizer.notebook;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public interface SecretStore {

    String get(String key);

    void put(String key, String value);

    void delete(String key);

    static SecretStore systemDefault() {
        if (WindowsCredentialStore.isSupportedPlatform()) {
            return new WindowsCredentialStore();
        }
        return inMemory();
    }

    static SecretStore inMemory() {
        return new InMemorySecretStore();
    }

    final class InMemorySecretStore implements SecretStore {
        private final Map<String, String> entries = new ConcurrentHashMap<>();

        @Override
        public String get(String key) {
            if (key == null || key.isBlank()) {
                return "";
            }
            return entries.getOrDefault(key, "");
        }

        @Override
        public void put(String key, String value) {
            if (key == null || key.isBlank()) {
                return;
            }
            if (value == null || value.isBlank()) {
                entries.remove(key);
            } else {
                entries.put(key, value.trim());
            }
        }

        @Override
        public void delete(String key) {
            if (key != null && !key.isBlank()) {
                entries.remove(key);
            }
        }
    }
}
