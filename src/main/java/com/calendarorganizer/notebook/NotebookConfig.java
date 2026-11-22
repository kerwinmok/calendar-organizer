package com.calendarorganizer.notebook;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class NotebookConfig {

    public static final class ClientCredentials {
        private final String projectId;
        private final String clientId;
        private final String clientSecret;

        public ClientCredentials(String projectId, String clientId, String clientSecret) {
            this.projectId = clean(projectId);
            this.clientId = clean(clientId);
            this.clientSecret = clean(clientSecret);
        }

        public String getProjectId() {
            return projectId;
        }

        public String getClientId() {
            return clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }
    }

    private final String projectId;
    private final String clientId;
    private final String clientSecret;
    private final String accessToken;
    private final String refreshToken;
    private final long expiresAtEpochSeconds;
    private final String accountEmail;

    public NotebookConfig(
            String projectId,
            String clientId,
            String clientSecret,
            String accessToken,
            String refreshToken,
            long expiresAtEpochSeconds,
            String accountEmail
    ) {
        this.projectId = clean(projectId);
        this.clientId = clean(clientId);
        this.clientSecret = clean(clientSecret);
        this.accessToken = clean(accessToken);
        this.refreshToken = clean(refreshToken);
        this.expiresAtEpochSeconds = expiresAtEpochSeconds;
        this.accountEmail = clean(accountEmail);
    }

    public String getProjectId() {
        return projectId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public long getExpiresAtEpochSeconds() {
        return expiresAtEpochSeconds;
    }

    public String getAccountEmail() {
        return accountEmail;
    }

    public boolean hasOAuthClient() {
        return !projectId.isBlank() && !clientId.isBlank();
    }

    public boolean isSignedIn() {
        if (projectId.isBlank()) {
            return false;
        }
        return !accessToken.isBlank() || !refreshToken.isBlank();
    }

    public boolean isAccessTokenValid(long nowEpochSeconds) {
        if (accessToken.isBlank()) {
            return false;
        }
        if (expiresAtEpochSeconds <= 0L) {
            return true;
        }
        return expiresAtEpochSeconds > nowEpochSeconds + 60L;
    }

    public static ClientCredentials parseGoogleClientSecretJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new IllegalArgumentException("OAuth client JSON file is empty.");
        }
        JsonElement parsed = JsonParser.parseString(rawJson);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Invalid OAuth client JSON format.");
        }
        JsonObject root = parsed.getAsJsonObject();
        JsonObject block = null;
        if (root.has("installed") && root.get("installed").isJsonObject()) {
            block = root.getAsJsonObject("installed");
        } else if (root.has("web") && root.get("web").isJsonObject()) {
            block = root.getAsJsonObject("web");
        } else {
            block = root;
        }

        String projectId = readString(block, "project_id");
        if (projectId.isBlank()) {
            projectId = readString(root, "project_id");
        }
        String clientId = readString(block, "client_id");
        String clientSecret = readString(block, "client_secret");

        if (clientId.isBlank()) {
            throw new IllegalArgumentException("OAuth client JSON is missing 'client_id'. Create a Desktop app OAuth client.");
        }
        if (projectId.isBlank()) {
            throw new IllegalArgumentException("OAuth client JSON is missing 'project_id'.");
        }
        return new ClientCredentials(projectId, clientId, clientSecret);
    }

    public static String extractEmailFromIdToken(String idToken) {
        if (idToken == null || idToken.isBlank()) {
            return "";
        }
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) {
            return "";
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            String payloadJson = new String(decoded, StandardCharsets.UTF_8);
            JsonElement element = JsonParser.parseString(payloadJson);
            if (!element.isJsonObject()) {
                return "";
            }
            return readString(element.getAsJsonObject(), "email");
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static String readString(JsonObject obj, String field) {
        if (obj == null || !obj.has(field) || obj.get(field).isJsonNull()) {
            return "";
        }
        JsonElement el = obj.get(field);
        if (!el.isJsonPrimitive()) {
            return "";
        }
        return el.getAsString().trim();
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }
}
