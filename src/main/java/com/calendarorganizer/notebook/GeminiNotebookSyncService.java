package com.calendarorganizer.notebook;

import com.calendarorganizer.course.Course;
import com.calendarorganizer.course.CourseItem;
import com.calendarorganizer.course.CourseManager;
import com.calendarorganizer.course.CourseSection;
import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.RecurringRule;
import com.calendarorganizer.model.ScheduleGoal;
import com.calendarorganizer.model.SchedulePreferences;
import com.calendarorganizer.service.CalendarService;
import com.calendarorganizer.storage.CalendarRepository;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class GeminiNotebookSyncService {

    public static final String PREF_PROJECT_ID = "notebook_project_id";
    public static final String PREF_CLIENT_ID = "notebook_client_id";
    public static final String PREF_CLIENT_SECRET = "notebook_client_secret";
    public static final String PREF_ACCESS_TOKEN = "notebook_access_token";
    public static final String PREF_REFRESH_TOKEN = "notebook_refresh_token";
    public static final String PREF_TOKEN_EXPIRES_AT = "notebook_token_expires_at";
    public static final String PREF_ACCOUNT_EMAIL = "notebook_account_email";
    public static final String PREF_SYNC_STATE = "notebook_sync_state";

    private static final String ENV_PROJECT_ID = "GEMINI_NOTEBOOK_PROJECT_ID";
    private static final String ENV_CLIENT_ID = "GEMINI_NOTEBOOK_CLIENT_ID";
    private static final String ENV_CLIENT_SECRET = "GEMINI_NOTEBOOK_CLIENT_SECRET";
    private static final String ENV_ACCESS_TOKEN = "GEMINI_NOTEBOOK_ACCESS_TOKEN";

    private static final DateTimeFormatter REVISION_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final Gson GSON = new Gson();
    private static final Type SYNC_STATE_TYPE = new TypeToken<Map<String, TargetSyncState>>() { }.getType();

    private static final class TargetSyncState {
        String notebookName = "";
        Map<String, String> sourceHashes = new LinkedHashMap<>();
    }

    @FunctionalInterface
    private interface AuthOperation<T> {
        T run(String accessToken, NotebookConfig config) throws IOException;
    }

    private final CalendarRepository repository;
    private final CalendarService calendarService;
    private final CourseManager courseManager;
    private final GeminiNotebookOAuth oauth;
    private final GeminiNotebookClient client;
    private final Clock clock;

    public GeminiNotebookSyncService(
            CalendarRepository repository,
            CalendarService calendarService,
            CourseManager courseManager
    ) {
        this(repository, calendarService, courseManager, new GeminiNotebookOAuth(), new GeminiNotebookClient(), Clock.systemDefaultZone());
    }

    public GeminiNotebookSyncService(
            CalendarRepository repository,
            CalendarService calendarService,
            CourseManager courseManager,
            GeminiNotebookOAuth oauth,
            GeminiNotebookClient client,
            Clock clock
    ) {
        this.repository = repository;
        this.calendarService = calendarService;
        this.courseManager = courseManager;
        this.oauth = oauth;
        this.client = client;
        this.clock = clock;
    }

    public NotebookConfig loadConfig() {
        String projectId = resolvePrefOrEnv(PREF_PROJECT_ID, ENV_PROJECT_ID);
        String clientId = resolvePrefOrEnv(PREF_CLIENT_ID, ENV_CLIENT_ID);
        String clientSecret = resolvePrefOrEnv(PREF_CLIENT_SECRET, ENV_CLIENT_SECRET);
        String accessToken = resolvePrefOrEnv(PREF_ACCESS_TOKEN, ENV_ACCESS_TOKEN);
        String refreshToken = repository.loadPreference(PREF_REFRESH_TOKEN, "");
        long expiresAt = parseLong(repository.loadPreference(PREF_TOKEN_EXPIRES_AT, "0"));
        String accountEmail = repository.loadPreference(PREF_ACCOUNT_EMAIL, "");
        return new NotebookConfig(projectId, clientId, clientSecret, accessToken, refreshToken, expiresAt, accountEmail);
    }

    public void saveClientConfig(String projectId, String clientId, String clientSecret) {
        saveOrDeletePref(PREF_PROJECT_ID, projectId);
        saveOrDeletePref(PREF_CLIENT_ID, clientId);
        saveOrDeletePref(PREF_CLIENT_SECRET, clientSecret);
    }

    public NotebookConfig.ClientCredentials importClientSecretJson(Path jsonFile) throws IOException {
        if (jsonFile == null || !Files.isRegularFile(jsonFile)) {
            throw new IllegalArgumentException("Selected OAuth client JSON file does not exist.");
        }
        String raw = Files.readString(jsonFile, StandardCharsets.UTF_8);
        NotebookConfig.ClientCredentials creds = NotebookConfig.parseGoogleClientSecretJson(raw);
        saveClientConfig(creds.getProjectId(), creds.getClientId(), creds.getClientSecret());
        return creds;
    }

    public NotebookConfig signInInteractive(AtomicBoolean cancelFlag) throws IOException {
        NotebookConfig config = loadConfig();
        if (!config.hasOAuthClient()) {
            throw new NotebookException(400, "Enter or import your Google Cloud Project ID and Desktop OAuth Client ID first.");
        }
        GeminiNotebookOAuth.OAuthTokens tokens = oauth.authorizeInBrowser(
                config.getClientId(),
                config.getClientSecret(),
                null,
                cancelFlag
        );
        saveTokens(tokens);
        return loadConfig();
    }

    public void disconnect() {
        repository.deletePreference(PREF_ACCESS_TOKEN);
        repository.deletePreference(PREF_REFRESH_TOKEN);
        repository.deletePreference(PREF_TOKEN_EXPIRES_AT);
        repository.deletePreference(PREF_ACCOUNT_EMAIL);
    }

    public void clearAllConfig() {
        disconnect();
        repository.deletePreference(PREF_PROJECT_ID);
        repository.deletePreference(PREF_CLIENT_ID);
        repository.deletePreference(PREF_CLIENT_SECRET);
        repository.deletePreference(PREF_SYNC_STATE);
    }

    public synchronized NotebookSyncResult syncCourse(Course course) throws IOException {
        if (course == null) {
            throw new IllegalArgumentException("Select a course to sync.");
        }
        return executeWithAuth((accessToken, config) -> syncCourseWithToken(accessToken, config, course));
    }

    public synchronized NotebookSyncResult syncSchedule(LocalDate weekStart) throws IOException {
        LocalDate resolvedWeek = weekStart;
        if (resolvedWeek == null) {
            resolvedWeek = calendarService.weekStartOf(LocalDate.now());
        }
        LocalDate effectiveWeek = resolvedWeek;
        return executeWithAuth((accessToken, config) -> syncScheduleWithToken(accessToken, config, effectiveWeek));
    }

    public synchronized List<NotebookSyncResult> syncAll(LocalDate weekStart) throws IOException {
        List<NotebookSyncResult> results = new ArrayList<>();
        results.add(syncSchedule(weekStart));
        List<Course> courses = courseManager.listCourses();
        for (Course course : courses) {
            results.add(syncCourse(course));
        }
        return results;
    }

    public static String formatBatchSummary(List<NotebookSyncResult> results) {
        int totalUploaded = 0;
        int totalSkipped = 0;
        int totalWarnings = 0;
        for (NotebookSyncResult res : results) {
            totalUploaded = totalUploaded + res.getUploadedCount();
            totalSkipped = totalSkipped + res.getSkippedCount();
            totalWarnings = totalWarnings + res.getWarnings().size();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Synced ").append(results.size()).append(" notebook(s): ")
                .append("uploaded ").append(totalUploaded)
                .append(", skipped ").append(totalSkipped).append(" unchanged");
        if (totalWarnings > 0) {
            sb.append(", ").append(totalWarnings).append(" warning(s)");
        }
        sb.append(".");
        return sb.toString();
    }

    private <T> T executeWithAuth(AuthOperation<T> operation) throws IOException {
        NotebookConfig config = loadConfig();
        if (!config.isSignedIn()) {
            throw new NotebookException(401, "Not connected to Gemini Notebook. Connect in Settings or the Notebook menu first.");
        }
        String token = resolveValidToken(config);
        try {
            return operation.run(token, loadConfig());
        } catch (NotebookException ex) {
            NotebookConfig latest = loadConfig();
            if (ex.getStatusCode() == 401 && !latest.getRefreshToken().isBlank() && latest.hasOAuthClient()) {
                GeminiNotebookOAuth.OAuthTokens refreshed = oauth.refreshAccessToken(
                        latest.getClientId(),
                        latest.getClientSecret(),
                        latest.getRefreshToken()
                );
                saveTokens(refreshed);
                return operation.run(refreshed.getAccessToken(), loadConfig());
            }
            throw ex;
        }
    }

    private String resolveValidToken(NotebookConfig config) throws IOException {
        long now = clock.instant().getEpochSecond();
        if (config.isAccessTokenValid(now)) {
            return config.getAccessToken();
        }
        if (!config.getRefreshToken().isBlank() && config.hasOAuthClient()) {
            GeminiNotebookOAuth.OAuthTokens refreshed = oauth.refreshAccessToken(
                    config.getClientId(),
                    config.getClientSecret(),
                    config.getRefreshToken()
            );
            saveTokens(refreshed);
            return refreshed.getAccessToken();
        }
        if (!config.getAccessToken().isBlank()) {
            return config.getAccessToken();
        }
        throw new NotebookException(401, "Google OAuth token expired. Please sign in again.");
    }

    private NotebookSyncResult syncCourseWithToken(
            String accessToken,
            NotebookConfig config,
            Course course
    ) throws IOException {
        Map<String, TargetSyncState> stateMap = loadSyncState();
        String targetKey = "course:" + course.getFolderName();
        String desiredTitle = "Calendar Organizer - " + course.getDisplayName();
        NotebookInfo notebook = resolveOrCreateNotebook(accessToken, config.getProjectId(), stateMap, targetKey, desiredTitle);

        TargetSyncState targetState = stateMap.computeIfAbsent(targetKey, k -> new TargetSyncState());
        targetState.notebookName = notebook.getName();

        int uploaded = 0;
        int skipped = 0;
        List<String> warnings = new ArrayList<>();

        String combinedNotes = buildCourseMarkdownBundle(course);
        if (!combinedNotes.isBlank()) {
            String notesHash = sha256Hex(combinedNotes.getBytes(StandardCharsets.UTF_8));
            String previousHash = targetState.sourceHashes.get("notes.md");
            if (notesHash.equals(previousHash)) {
                skipped++;
            } else {
                String sourceTitle = course.getDisplayName() + " - Notes.md";
                if (previousHash != null) {
                    String rev = LocalDateTime.now(clock).format(REVISION_FMT);
                    sourceTitle = course.getDisplayName() + " - Notes (" + rev + ").md";
                }
                client.addTextSource(
                        accessToken,
                        config.getProjectId(),
                        notebook.getName(),
                        sourceTitle,
                        "text/markdown",
                        combinedNotes
                );
                targetState.sourceHashes.put("notes.md", notesHash);
                saveSyncState(stateMap);
                uploaded++;
            }
        }

        for (CourseSection section : CourseSection.values()) {
            List<CourseItem> items = courseManager.listItems(course, section);
            for (CourseItem item : items) {
                if (item.isMarkdown()) {
                    continue;
                }
                String mimeType = resolveSupportedMimeType(item.getFileName());
                if (mimeType == null) {
                    warnings.add("Skipped unsupported file type: " + item.getRelativeDisplayPath());
                    skipped++;
                    continue;
                }
                long bytes = Files.size(item.getFilePath());
                if (bytes > GeminiNotebookClient.MAX_UPLOAD_BYTES) {
                    warnings.add("Skipped file over 200 MiB limit: " + item.getRelativeDisplayPath());
                    skipped++;
                    continue;
                }
                String sourceKey = item.getRelativeDisplayPath();
                String fileHash = sha256Hex(item.getFilePath());
                if (fileHash.equals(targetState.sourceHashes.get(sourceKey))) {
                    skipped++;
                    continue;
                }
                String sourceTitle = section.getLabel() + " - " + item.getFileName();
                client.uploadFileSource(
                        accessToken,
                        config.getProjectId(),
                        notebook.getName(),
                        sourceTitle,
                        mimeType,
                        item.getFilePath()
                );
                targetState.sourceHashes.put(sourceKey, fileHash);
                saveSyncState(stateMap);
                uploaded++;
            }
        }

        saveSyncState(stateMap);
        return new NotebookSyncResult(notebook.getName(), notebook.getDisplayName(), uploaded, skipped, warnings);
    }

    private NotebookSyncResult syncScheduleWithToken(
            String accessToken,
            NotebookConfig config,
            LocalDate weekStart
    ) throws IOException {
        Map<String, TargetSyncState> stateMap = loadSyncState();
        String targetKey = "schedule";
        String desiredTitle = "Calendar Organizer - Schedule";
        NotebookInfo notebook = resolveOrCreateNotebook(accessToken, config.getProjectId(), stateMap, targetKey, desiredTitle);

        TargetSyncState targetState = stateMap.computeIfAbsent(targetKey, k -> new TargetSyncState());
        targetState.notebookName = notebook.getName();

        String markdown = buildScheduleMarkdown(weekStart);
        String scheduleHash = sha256Hex(markdown.getBytes(StandardCharsets.UTF_8));
        String previousHash = targetState.sourceHashes.get("schedule.md");
        int uploaded = 0;
        int skipped = 0;

        if (scheduleHash.equals(previousHash)) {
            skipped = 1;
        } else {
            String title = "Schedule (" + weekStart + ").md";
            if (previousHash != null) {
                title = "Schedule (" + weekStart + ", updated " + LocalDateTime.now(clock).format(REVISION_FMT) + ").md";
            }
            client.addTextSource(
                    accessToken,
                    config.getProjectId(),
                    notebook.getName(),
                    title,
                    "text/markdown",
                    markdown
            );
            targetState.sourceHashes.put("schedule.md", scheduleHash);
            uploaded = 1;
        }

        saveSyncState(stateMap);
        return new NotebookSyncResult(notebook.getName(), notebook.getDisplayName(), uploaded, skipped, List.of());
    }

    private NotebookInfo resolveOrCreateNotebook(
            String accessToken,
            String projectId,
            Map<String, TargetSyncState> stateMap,
            String targetKey,
            String desiredTitle
    ) throws IOException {
        List<NotebookInfo> existing = client.listNotebooks(accessToken, projectId);
        TargetSyncState saved = stateMap.get(targetKey);
        if (saved != null && saved.notebookName != null && !saved.notebookName.isBlank()) {
            for (NotebookInfo nb : existing) {
                if (saved.notebookName.equals(nb.getName())) {
                    return nb;
                }
            }
            //remote notebook was deleted or moved; reset cached source hashes before recreating
            saved.sourceHashes.clear();
        }
        for (NotebookInfo nb : existing) {
            if (desiredTitle.equalsIgnoreCase(nb.getDisplayName())) {
                return nb;
            }
        }
        return client.createNotebook(accessToken, projectId, desiredTitle);
    }

    private String buildCourseMarkdownBundle(Course course) throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean hasContent = false;
        for (CourseSection section : CourseSection.values()) {
            List<CourseItem> items = courseManager.listItems(course, section);
            List<CourseItem> markdownItems = new ArrayList<>();
            for (CourseItem item : items) {
                if (item.isMarkdown()) {
                    markdownItems.add(item);
                }
            }
            if (markdownItems.isEmpty()) {
                continue;
            }
            if (!hasContent) {
                sb.append("# ").append(course.getDisplayName()).append(" Study Notes\n\n");
                hasContent = true;
            }
            sb.append("## ").append(section.getLabel()).append("\n\n");
            for (CourseItem item : markdownItems) {
                String content = courseManager.readItem(item).replace("\r\n", "\n").trim();
                sb.append("### ").append(item.getFileName()).append("\n\n");
                if (content.isEmpty()) {
                    sb.append("_(Empty note)_\n\n");
                } else {
                    sb.append(content).append("\n\n");
                }
            }
        }
        return sb.toString();
    }

    public String buildScheduleMarkdown(LocalDate weekStart) {
        LocalDate start = weekStart;
        if (start == null) {
            start = calendarService.weekStartOf(LocalDate.now(clock));
        }
        LocalDate end = start.plusDays(13);

        List<CalendarEvent> events = new ArrayList<>();
        events.addAll(calendarService.getEventsForWeek(start));
        events.addAll(calendarService.getEventsForWeek(start.plusWeeks(1)));
        events.sort(Comparator.comparing(CalendarEvent::getStartTime));

        StringBuilder sb = new StringBuilder();
        sb.append("# Calendar Schedule (").append(start).append(" to ").append(end).append(")\n\n");

        SchedulePreferences prefs = calendarService.loadPreferences();
        sb.append("## Sleep Window\n");
        sb.append("- Bedtime: ").append(prefs.getSleepTime().format(TIME_FMT))
                .append(" for ").append(prefs.getSleepDurationHours()).append(" hours\n\n");

        List<ScheduleGoal> goals = calendarService.findAllGoals();
        sb.append("## Weekly Goals\n");
        if (goals.isEmpty()) {
            sb.append("- None configured\n");
        } else {
            for (ScheduleGoal goal : goals) {
                sb.append("- ").append(goal.getTitle())
                        .append(" (").append(goal.getType().name()).append("): ")
                        .append(goal.totalTargetMinutes()).append(" min/week (")
                        .append(goal.getSessionsPerWeek()).append(" x ")
                        .append(goal.getSessionMinutes()).append(" min)\n");
            }
        }
        sb.append("\n");

        List<RecurringRule> rules = calendarService.findAllRecurringRules();
        sb.append("## Recurring Rules\n");
        if (rules.isEmpty()) {
            sb.append("- None configured\n");
        } else {
            for (RecurringRule rule : rules) {
                sb.append("- ").append(rule.getTitle())
                        .append(" [").append(formatDays(rule)).append(" ")
                        .append(rule.getStartTime().format(TIME_FMT)).append("-")
                        .append(rule.getEndTime().format(TIME_FMT)).append("]\n");
            }
        }
        sb.append("\n");

        sb.append("## Scheduled & Fixed Events\n");
        if (events.isEmpty()) {
            sb.append("- No events scheduled for these two weeks.\n");
        } else {
            LocalDate currentDay = null;
            for (CalendarEvent event : events) {
                LocalDate eventDay = event.getStartTime().toLocalDate();
                if (!eventDay.equals(currentDay)) {
                    currentDay = eventDay;
                    sb.append("\n### ").append(eventDay.format(DAY_FMT)).append("\n");
                }
                sb.append("- ")
                        .append(event.getStartTime().toLocalTime().format(TIME_FMT))
                        .append("–")
                        .append(event.getEndTime().toLocalTime().format(TIME_FMT))
                        .append(" | **").append(event.getTitle()).append("**")
                        .append(" (").append(event.getCategory().name()).append(")");
                if (!event.getNotes().isBlank()) {
                    sb.append(" — ").append(event.getNotes().replace('\n', ' ').trim());
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    public static String resolveSupportedMimeType(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) {
            return null;
        }
        String ext = lower.substring(dot + 1);
        switch (ext) {
            case "pdf":
                return "application/pdf";
            case "txt":
                return "text/plain";
            case "md":
                return "text/markdown";
            case "csv":
                return "text/csv";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "pptx":
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "gif":
                return "image/gif";
            case "mp3":
                return "audio/mpeg";
            case "wav":
                return "audio/wav";
            default:
                return null;
        }
    }

    private String formatDays(RecurringRule rule) {
        List<DayOfWeek> sorted = new ArrayList<>(rule.getDaysOfWeek());
        sorted.sort(Comparator.comparingInt(DayOfWeek::getValue));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(sorted.get(i).name().substring(0, 3));
        }
        return sb.toString();
    }

    private Map<String, TargetSyncState> loadSyncState() {
        String raw = repository.loadPreference(PREF_SYNC_STATE, "");
        if (raw == null || raw.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, TargetSyncState> parsed = GSON.fromJson(raw, SYNC_STATE_TYPE);
            if (parsed == null) {
                return new LinkedHashMap<>();
            }
            for (TargetSyncState state : parsed.values()) {
                if (state.sourceHashes == null) {
                    state.sourceHashes = new LinkedHashMap<>();
                }
            }
            return parsed;
        } catch (RuntimeException ex) {
            return new LinkedHashMap<>();
        }
    }

    private void saveSyncState(Map<String, TargetSyncState> stateMap) {
        repository.savePreference(PREF_SYNC_STATE, GSON.toJson(stateMap));
    }

    private void saveTokens(GeminiNotebookOAuth.OAuthTokens tokens) {
        saveOrDeletePref(PREF_ACCESS_TOKEN, tokens.getAccessToken());
        if (!tokens.getRefreshToken().isBlank()) {
            saveOrDeletePref(PREF_REFRESH_TOKEN, tokens.getRefreshToken());
        }
        repository.savePreference(PREF_TOKEN_EXPIRES_AT, Long.toString(tokens.getExpiresAtEpochSeconds()));
        if (!tokens.getAccountEmail().isBlank()) {
            saveOrDeletePref(PREF_ACCOUNT_EMAIL, tokens.getAccountEmail());
        }
    }

    private void saveOrDeletePref(String key, String value) {
        if (value == null || value.isBlank()) {
            repository.deletePreference(key);
        } else {
            repository.savePreference(key, value.trim());
        }
    }

    private String resolvePrefOrEnv(String prefKey, String envKey) {
        String saved = repository.loadPreference(prefKey, "");
        if (saved != null && !saved.isBlank()) {
            return saved.trim();
        }
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.isBlank()) {
            return envVal.trim();
        }
        return "";
    }

    private long parseLong(String text) {
        if (text == null || text.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16_384];
            try (InputStream is = Files.newInputStream(file)) {
                int read;
                while ((read = is.read(buffer)) >= 0) {
                    if (read > 0) {
                        md.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
