package com.calendarorganizer.notebook;

import com.calendarorganizer.service.CalendarService;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class GeminiNotebookOAuth {

    public static final String DEFAULT_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    public static final String DEFAULT_TOKEN_URL = "https://oauth2.googleapis.com/token";
    public static final String SCOPE = "https://www.googleapis.com/auth/notebook https://www.googleapis.com/auth/userinfo.email openid";

    public static final class OAuthTokens {
        private final String accessToken;
        private final String refreshToken;
        private final long expiresAtEpochSeconds;
        private final String accountEmail;

        public OAuthTokens(String accessToken, String refreshToken, long expiresAtEpochSeconds, String accountEmail) {
            if (accessToken == null) {
                this.accessToken = "";
            } else {
                this.accessToken = accessToken;
            }
            if (refreshToken == null) {
                this.refreshToken = "";
            } else {
                this.refreshToken = refreshToken;
            }
            this.expiresAtEpochSeconds = expiresAtEpochSeconds;
            if (accountEmail == null) {
                this.accountEmail = "";
            } else {
                this.accountEmail = accountEmail;
            }
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
    }

    private final String authUrl;
    private final String tokenUrl;
    private final Supplier<HttpClient> httpSupplier;
    private final Consumer<String> browserLauncher;
    private final SecureRandom secureRandom = new SecureRandom();

    public GeminiNotebookOAuth() {
        this(DEFAULT_AUTH_URL, DEFAULT_TOKEN_URL, CalendarService::sharedHttpClient, GeminiNotebookOAuth::openBrowser);
    }

    public GeminiNotebookOAuth(
            String authUrl,
            String tokenUrl,
            Supplier<HttpClient> httpSupplier,
            Consumer<String> browserLauncher
    ) {
        this.authUrl = authUrl;
        this.tokenUrl = tokenUrl;
        this.httpSupplier = httpSupplier;
        this.browserLauncher = browserLauncher;
    }

    public OAuthTokens authorizeInBrowser(
            String clientId,
            String clientSecret,
            Duration timeout,
            AtomicBoolean cancelFlag
    ) throws IOException {
        if (clientId == null || clientId.isBlank()) {
            throw new NotebookException(0, "OAuth Client ID is required before signing in.");
        }

        String verifier = generateRandomUrlSafe(32);
        String challenge = computeS256Challenge(verifier);
        String state = generateRandomUrlSafe(16);

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        CountDownLatch callbackLatch = new CountDownLatch(1);
        AtomicReference<String> codeRef = new AtomicReference<>();
        AtomicReference<String> errorRef = new AtomicReference<>();

        server.createContext("/oauth2/callback", exchange -> handleCallback(exchange, state, codeRef, errorRef, callbackLatch));
        server.start();

        try {
            int port = server.getAddress().getPort();
            String redirectUri = "http://127.0.0.1:" + port + "/oauth2/callback";
            String authorizeUri = buildAuthorizeUri(clientId.trim(), redirectUri, challenge, state);

            try {
                browserLauncher.accept(authorizeUri);
            } catch (RuntimeException ex) {
                throw new NotebookException(0, "Could not launch browser for Google sign-in: " + ex.getMessage(), ex);
            }

            waitForCallback(callbackLatch, timeout, cancelFlag);

            String callbackError = errorRef.get();
            if (callbackError != null && !callbackError.isBlank()) {
                throw new NotebookException(400, callbackError);
            }
            String code = codeRef.get();
            if (code == null || code.isBlank()) {
                throw new NotebookException(0, "Google sign-in did not return an authorization code.");
            }
            return exchangeCodeForTokens(clientId.trim(), clientSecret, code, redirectUri, verifier);
        } finally {
            server.stop(0);
        }
    }

    public OAuthTokens refreshAccessToken(String clientId, String clientSecret, String refreshToken) throws IOException {
        if (clientId == null || clientId.isBlank()) {
            throw new NotebookException(0, "OAuth Client ID is required to refresh session.");
        }
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new NotebookException(401, "No refresh token stored — please sign in again.");
        }

        Map<String, String> params = new HashMap<>();
        params.put("client_id", clientId.trim());
        if (clientSecret != null && !clientSecret.isBlank()) {
            params.put("client_secret", clientSecret.trim());
        }
        params.put("refresh_token", refreshToken.trim());
        params.put("grant_type", "refresh_token");

        OAuthTokens refreshed = postTokenRequest(params);
        String nextRefresh = refreshed.getRefreshToken();
        if (nextRefresh.isBlank()) {
            nextRefresh = refreshToken.trim();
        }
        return new OAuthTokens(
                refreshed.getAccessToken(),
                nextRefresh,
                refreshed.getExpiresAtEpochSeconds(),
                refreshed.getAccountEmail()
        );
    }

    private OAuthTokens exchangeCodeForTokens(
            String clientId,
            String clientSecret,
            String code,
            String redirectUri,
            String codeVerifier
    ) throws IOException {
        Map<String, String> params = new HashMap<>();
        params.put("code", code);
        params.put("client_id", clientId);
        if (clientSecret != null && !clientSecret.isBlank()) {
            params.put("client_secret", clientSecret.trim());
        }
        params.put("redirect_uri", redirectUri);
        params.put("code_verifier", codeVerifier);
        params.put("grant_type", "authorization_code");
        return postTokenRequest(params);
    }

    private OAuthTokens postTokenRequest(Map<String, String> formParams) throws IOException {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : formParams.entrySet()) {
            if (!body.isEmpty()) {
                body.append('&');
            }
            body.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue()));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(tokenUrl))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpSupplier.get().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw buildTokenException(status, response.body());
            }
            return parseTokenResponse(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new NotebookException(0, "INTERRUPTED", "OAuth token request was interrupted.", ex);
        } catch (NotebookException ex) {
            throw ex;
        } catch (HttpTimeoutException ex) {
            throw new NotebookException(0, "TIMEOUT", "Timed out connecting to Google OAuth. Check your internet connection.", ex);
        } catch (UnknownHostException | ConnectException | UnresolvedAddressException ex) {
            throw new NotebookException(0, "OFFLINE", "Offline or unable to reach Google OAuth servers. Check your internet connection.", ex);
        } catch (IOException ex) {
            throw classifyTokenIoException(ex);
        }
    }

    private NotebookException classifyTokenIoException(IOException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof UnknownHostException
                || cause instanceof ConnectException
                || cause instanceof UnresolvedAddressException) {
            return new NotebookException(0, "OFFLINE", "Offline or unable to reach Google OAuth servers. Check your internet connection.", ex);
        }
        return new NotebookException(0, "NETWORK", "OAuth network error: " + ex.getMessage(), ex);
    }

    private OAuthTokens parseTokenResponse(String jsonBody) throws NotebookException {
        JsonElement parsed = JsonParser.parseString(jsonBody);
        if (!parsed.isJsonObject()) {
            throw new NotebookException(500, "Invalid OAuth token response.");
        }
        JsonObject root = parsed.getAsJsonObject();
        String accessToken = readJsonString(root, "access_token");
        if (accessToken.isBlank()) {
            throw new NotebookException(500, "OAuth response omitted access_token.");
        }
        String refreshToken = readJsonString(root, "refresh_token");
        long expiresIn = 3600L;
        if (root.has("expires_in") && root.get("expires_in").isJsonPrimitive()) {
            expiresIn = Math.max(60L, root.get("expires_in").getAsLong());
        }
        long expiresAt = Instant.now().getEpochSecond() + expiresIn;
        String email = NotebookConfig.extractEmailFromIdToken(readJsonString(root, "id_token"));
        return new OAuthTokens(accessToken, refreshToken, expiresAt, email);
    }

    private NotebookException buildTokenException(int status, String body) {
        String err = "";
        String desc = "";
        if (body != null && !body.isBlank()) {
            try {
                JsonElement el = JsonParser.parseString(body);
                if (el.isJsonObject()) {
                    JsonObject obj = el.getAsJsonObject();
                    desc = readJsonString(obj, "error_description");
                    err = readJsonString(obj, "error");
                }
            } catch (RuntimeException ignored) {
            }
        }
        String normalizedErr = err.toLowerCase(Locale.ROOT);
        if ("invalid_grant".equals(normalizedErr)) {
            return new NotebookException(status, "invalid_grant",
                    "Google sign-in session expired or was revoked. Open Connect Gemini Notebook and sign in again.");
        }
        if ("invalid_client".equals(normalizedErr) || "unauthorized_client".equals(normalizedErr)) {
            return new NotebookException(status, "invalid_client",
                    "Google OAuth Client ID or Client Secret was rejected (" + err + "). Re-check or re-import your credentials JSON.");
        }
        if (status == 429) {
            return new NotebookException(status, "rate_limited",
                    "Google OAuth rate limit reached. Wait a moment and try again.");
        }
        if (!desc.isBlank()) {
            return new NotebookException(status, err, "Google OAuth error (" + status + "): " + desc);
        }
        if (!err.isBlank()) {
            return new NotebookException(status, err, "Google OAuth error (" + status + "): " + err);
        }
        return new NotebookException(status, "", "Google OAuth failed with HTTP " + status + ".");
    }

    private void handleCallback(
            HttpExchange exchange,
            String expectedState,
            AtomicReference<String> codeRef,
            AtomicReference<String> errorRef,
            CountDownLatch latch
    ) throws IOException {
        try {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            String error = query.getOrDefault("error", "");
            String state = query.getOrDefault("state", "");
            String code = query.getOrDefault("code", "");

            if (!error.isBlank()) {
                errorRef.compareAndSet(null, "Google sign-in canceled or denied (" + error + ").");
                sendHtmlResponse(exchange, 200, "Sign-in Canceled", "You can close this browser tab and return to Calendar Organizer.");
            } else if (!expectedState.equals(state)) {
                errorRef.compareAndSet(null, "OAuth state mismatch. Please retry sign-in.");
                sendHtmlResponse(exchange, 400, "Sign-in Verification Failed", "State mismatch detected. Please close this tab and try again.");
            } else if (code.isBlank()) {
                errorRef.compareAndSet(null, "Google sign-in returned no authorization code.");
                sendHtmlResponse(exchange, 400, "Missing Code", "No authorization code received. Please close this tab and try again.");
            } else {
                codeRef.compareAndSet(null, code);
                sendHtmlResponse(exchange, 200, "Connected to Calendar Organizer", "Sign-in succeeded. You can close this browser tab now.");
            }
        } finally {
            latch.countDown();
        }
    }

    private void waitForCallback(
            CountDownLatch latch,
            Duration timeout,
            AtomicBoolean cancelFlag
    ) throws NotebookException {
        Duration effectiveTimeout = timeout;
        if (effectiveTimeout == null || effectiveTimeout.isNegative() || effectiveTimeout.isZero()) {
            effectiveTimeout = Duration.ofMinutes(3);
        }
        Instant deadline = Instant.now().plus(effectiveTimeout);
        while (true) {
            if (cancelFlag != null && cancelFlag.get()) {
                throw new NotebookException(0, "Google sign-in was canceled.");
            }
            if (Instant.now().isAfter(deadline)) {
                throw new NotebookException(0, "Timed out waiting for Google sign-in in browser.");
            }
            try {
                if (latch.await(200, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new NotebookException(0, "Google sign-in wait was interrupted.", ex);
            }
        }
    }

    private String buildAuthorizeUri(String clientId, String redirectUri, String challenge, String state) {
        StringBuilder uri = new StringBuilder(authUrl);
        if (authUrl.contains("?")) {
            uri.append('&');
        } else {
            uri.append('?');
        }
        uri.append("response_type=code")
                .append("&client_id=").append(urlEncode(clientId))
                .append("&redirect_uri=").append(urlEncode(redirectUri))
                .append("&scope=").append(urlEncode(SCOPE))
                .append("&code_challenge=").append(urlEncode(challenge))
                .append("&code_challenge_method=S256")
                .append("&state=").append(urlEncode(state))
                .append("&access_type=offline")
                .append("&prompt=consent");
        return uri.toString();
    }

    private String generateRandomUrlSafe(int numBytes) {
        byte[] bytes = new byte[numBytes];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String computeS256Challenge(String verifier) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return map;
        }
        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String val = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                map.put(key, val);
            }
        }
        return map;
    }

    private void sendHtmlResponse(HttpExchange exchange, int statusCode, String title, String message) throws IOException {
        String html = "<!doctype html><html><head><meta charset='utf-8'><title>" + escapeHtml(title)
                + "</title><style>body{font-family:Segoe UI,system-ui,sans-serif;background:#1c1c1c;color:#e2e2e2;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0;}"
                + ".card{background:#2d2d2d;border:1px solid #3c3c3c;border-radius:8px;padding:24px 28px;max-width:440px;}"
                + "h2{margin:0 0 8px 0;font-size:18px;}p{margin:0;color:#b8b8b8;line-height:1.5;}</style></head>"
                + "<body><div class='card'><h2>" + escapeHtml(title) + "</h2><p>" + escapeHtml(message) + "</p></div></body></html>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String readJsonString(JsonObject obj, String field) {
        if (obj == null || !obj.has(field) || obj.get(field).isJsonNull()) {
            return "";
        }
        JsonElement el = obj.get(field);
        if (!el.isJsonPrimitive()) {
            return "";
        }
        return el.getAsString().trim();
    }

    private static void openBrowser(String url) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                new ProcessBuilder("rundll32.exe", "url.dll,FileProtocolHandler", url).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (IOException ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }
}
