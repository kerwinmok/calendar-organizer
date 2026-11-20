package com.calendarorganizer.storage;

import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.EventCategory;
import com.calendarorganizer.model.EventSource;
import com.calendarorganizer.model.GoalType;
import com.calendarorganizer.model.RecurringRule;
import com.calendarorganizer.model.ScheduleGoal;
import com.calendarorganizer.model.SchedulePreferences;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

public class CalendarRepository {

    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final String PREF_SLEEP_TIME = "sleep_time";
    private static final String PREF_SLEEP_HOURS = "sleep_duration_hours";
    private static final String PREF_THEME = "ui_theme";

    private final DatabaseManager dbManager;

    public CalendarRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public CalendarEvent saveEvent(CalendarEvent event) {
        try (Connection conn = dbManager.openConnection()) {
            return insertEvent(conn, event);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to save event", e);
        }
    }

    public void saveEvents(List<CalendarEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        try (Connection conn = dbManager.openConnection()) {
            conn.setAutoCommit(false);
            try {
                for (CalendarEvent event : events) {
                    insertEvent(conn, event);
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to batch save events", e);
        }
    }

    private CalendarEvent insertEvent(Connection conn, CalendarEvent event) throws SQLException {
        String sql = "INSERT INTO events (uid, title, start_time, end_time, category, source, notes) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, event.getUid());
            ps.setString(2, event.getTitle());
            ps.setString(3, event.getStartTime().format(DT_FORMAT));
            ps.setString(4, event.getEndTime().format(DT_FORMAT));
            ps.setString(5, event.getCategory().name());
            ps.setString(6, event.getSource().name());
            ps.setString(7, event.getNotes());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return event.withId(keys.getLong(1));
                }
            }
            return event;
        }
    }

    public CalendarEvent upsertEventByUid(CalendarEvent event) {
        try (Connection conn = dbManager.openConnection()) {
            return upsertEventOnConnection(conn, event);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to upsert event", e);
        }
    }

    public void updateEvent(CalendarEvent event) {
        if (event.getId() <= 0) {
            throw new IllegalArgumentException("event id must be positive");
        }
        try (Connection conn = dbManager.openConnection()) {
            updateExistingEvent(conn, event.getId(), event);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to update event", e);
        }
    }

    public void upsertEventsByUid(List<CalendarEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        try (Connection conn = dbManager.openConnection()) {
            conn.setAutoCommit(false);
            try {
                for (CalendarEvent event : events) {
                    upsertEventOnConnection(conn, event);
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to batch upsert events", e);
        }
    }

    private CalendarEvent upsertEventOnConnection(Connection conn, CalendarEvent event) throws SQLException {
        String findSql = "SELECT id FROM events WHERE uid = ?";
        try (PreparedStatement findPs = conn.prepareStatement(findSql)) {
            findPs.setString(1, event.getUid());
            try (ResultSet rs = findPs.executeQuery()) {
                if (rs.next()) {
                    long existingId = rs.getLong("id");
                    updateExistingEvent(conn, existingId, event);
                    return event.withId(existingId);
                }
            }
        }
        return insertEvent(conn, event);
    }

    private void updateExistingEvent(Connection conn, long id, CalendarEvent event) throws SQLException {
        String updateSql = "UPDATE events SET title = ?, start_time = ?, end_time = ?, "
                + "category = ?, source = ?, notes = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setString(1, event.getTitle());
            ps.setString(2, event.getStartTime().format(DT_FORMAT));
            ps.setString(3, event.getEndTime().format(DT_FORMAT));
            ps.setString(4, event.getCategory().name());
            ps.setString(5, event.getSource().name());
            ps.setString(6, event.getNotes());
            ps.setLong(7, id);
            ps.executeUpdate();
        }
    }

    public List<CalendarEvent> findAllEvents() {
        String sql = "SELECT id, uid, title, start_time, end_time, category, source, notes "
                + "FROM events ORDER BY start_time ASC, id ASC";
        List<CalendarEvent> results = new ArrayList<>();
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(mapEvent(rs));
            }
            return results;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to load events", e);
        }
    }

    public List<CalendarEvent> findEventsInRange(LocalDateTime rangeStart, LocalDateTime rangeEnd) {
        String sql = "SELECT id, uid, title, start_time, end_time, category, source, notes "
                + "FROM events WHERE start_time < ? AND end_time > ? ORDER BY start_time ASC, id ASC";
        List<CalendarEvent> results = new ArrayList<>();
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, rangeEnd.format(DT_FORMAT));
            ps.setString(2, rangeStart.format(DT_FORMAT));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapEvent(rs));
                }
            }
            return results;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to query events in range", e);
        }
    }

    public void deleteEvent(long id) {
        String sql = "DELETE FROM events WHERE id = ?";
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to delete event", e);
        }
    }

    public void deleteEventsBySource(EventSource source) {
        String sql = "DELETE FROM events WHERE source = ?";
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, source.name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to delete events by source", e);
        }
    }

    public void deleteScheduledEventsInRange(LocalDateTime rangeStart, LocalDateTime rangeEnd) {
        String sql = "DELETE FROM events WHERE source = ? AND start_time < ? AND end_time > ?";
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, EventSource.SCHEDULED.name());
            ps.setString(2, rangeEnd.format(DT_FORMAT));
            ps.setString(3, rangeStart.format(DT_FORMAT));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to delete scheduled events in range", e);
        }
    }

    public RecurringRule saveRecurringRule(RecurringRule rule) {
        try (Connection conn = dbManager.openConnection()) {
            return insertRecurringRule(conn, rule);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to save recurring rule", e);
        }
    }

    public void saveRecurringRules(List<RecurringRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return;
        }
        try (Connection conn = dbManager.openConnection()) {
            conn.setAutoCommit(false);
            try {
                for (RecurringRule rule : rules) {
                    insertRecurringRule(conn, rule);
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to batch save recurring rules", e);
        }
    }

    private RecurringRule insertRecurringRule(Connection conn, RecurringRule rule) throws SQLException {
        String sql = "INSERT INTO recurring_rules "
                + "(title, days_of_week, start_time, end_time, category, start_date, end_date) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, rule.getTitle());
            ps.setString(2, encodeDays(rule.getDaysOfWeek()));
            ps.setString(3, rule.getStartTime().format(TIME_FORMAT));
            ps.setString(4, rule.getEndTime().format(TIME_FORMAT));
            ps.setString(5, rule.getCategory().name());
            if (rule.getStartDate() == null) {
                ps.setString(6, null);
            } else {
                ps.setString(6, rule.getStartDate().format(DATE_FORMAT));
            }
            if (rule.getEndDate() == null) {
                ps.setString(7, null);
            } else {
                ps.setString(7, rule.getEndDate().format(DATE_FORMAT));
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return rule.withId(keys.getLong(1));
                }
            }
            return rule;
        }
    }

    public List<RecurringRule> findAllRecurringRules() {
        String sql = "SELECT id, title, days_of_week, start_time, end_time, category, start_date, end_date "
                + "FROM recurring_rules ORDER BY id ASC";
        List<RecurringRule> rules = new ArrayList<>();
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rules.add(mapRecurringRule(rs));
            }
            return rules;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to load recurring rules", e);
        }
    }

    public void deleteRecurringRule(long id) {
        String sql = "DELETE FROM recurring_rules WHERE id = ?";
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to delete recurring rule", e);
        }
    }

    public void updateRecurringRule(RecurringRule rule) {
        if (rule.getId() <= 0) {
            throw new IllegalArgumentException("recurring rule id must be positive");
        }
        String sql = "UPDATE recurring_rules SET title = ?, days_of_week = ?, start_time = ?, "
                + "end_time = ?, category = ?, start_date = ?, end_date = ? WHERE id = ?";
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, rule.getTitle());
            ps.setString(2, encodeDays(rule.getDaysOfWeek()));
            ps.setString(3, rule.getStartTime().format(TIME_FORMAT));
            ps.setString(4, rule.getEndTime().format(TIME_FORMAT));
            ps.setString(5, rule.getCategory().name());
            if (rule.getStartDate() == null) {
                ps.setString(6, null);
            } else {
                ps.setString(6, rule.getStartDate().format(DATE_FORMAT));
            }
            if (rule.getEndDate() == null) {
                ps.setString(7, null);
            } else {
                ps.setString(7, rule.getEndDate().format(DATE_FORMAT));
            }
            ps.setLong(8, rule.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to update recurring rule", e);
        }
    }

    public ScheduleGoal saveGoal(ScheduleGoal goal) {
        String sql = "INSERT INTO goals "
                + "(type, title, sessions_per_week, session_minutes, weekly_minutes, earliest_start, latest_end) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, goal.getType().name());
            ps.setString(2, goal.getTitle());
            ps.setInt(3, goal.getSessionsPerWeek());
            ps.setInt(4, goal.getSessionMinutes());
            ps.setInt(5, goal.getWeeklyMinutes());
            if (goal.getEarliestStart() == null) {
                ps.setString(6, null);
            } else {
                ps.setString(6, goal.getEarliestStart().format(TIME_FORMAT));
            }
            if (goal.getLatestEnd() == null) {
                ps.setString(7, null);
            } else {
                ps.setString(7, goal.getLatestEnd().format(TIME_FORMAT));
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return goal.withId(keys.getLong(1));
                }
            }
            return goal;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to save goal", e);
        }
    }

    public List<ScheduleGoal> findAllGoals() {
        String sql = "SELECT id, type, title, sessions_per_week, session_minutes, weekly_minutes, earliest_start, latest_end "
                + "FROM goals ORDER BY id ASC";
        List<ScheduleGoal> goals = new ArrayList<>();
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                goals.add(mapGoal(rs));
            }
            return goals;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to load goals", e);
        }
    }

    public void deleteGoal(long id) {
        String sql = "DELETE FROM goals WHERE id = ?";
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to delete goal", e);
        }
    }

    public void deleteGoalsByType(GoalType type) {
        String sql = "DELETE FROM goals WHERE type = ?";
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type.name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to delete goals by type", e);
        }
    }

    public SchedulePreferences loadPreferences() {
        LocalTime sleepTime = SchedulePreferences.DEFAULT_SLEEP_TIME;
        int sleepHours = SchedulePreferences.DEFAULT_SLEEP_DURATION_HOURS;
        String sql = "SELECT pref_key, pref_value FROM preferences";
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String key = rs.getString("pref_key");
                String val = rs.getString("pref_value");
                if (PREF_SLEEP_TIME.equals(key)) {
                    sleepTime = LocalTime.parse(val, TIME_FORMAT);
                } else if (PREF_SLEEP_HOURS.equals(key)) {
                    sleepHours = Integer.parseInt(val);
                }
            }
            return new SchedulePreferences(sleepTime, sleepHours);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to load preferences", e);
        }
    }

    public void savePreferences(SchedulePreferences prefs) {
        String sql = "INSERT INTO preferences (pref_key, pref_value) VALUES (?, ?) "
                + "ON CONFLICT(pref_key) DO UPDATE SET pref_value = excluded.pref_value";
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, PREF_SLEEP_TIME);
            ps.setString(2, prefs.getSleepTime().format(TIME_FORMAT));
            ps.executeUpdate();

            ps.setString(1, PREF_SLEEP_HOURS);
            ps.setString(2, Integer.toString(prefs.getSleepDurationHours()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to save preferences", e);
        }
    }

    public String loadTheme() {
        String sql = "SELECT pref_value FROM preferences WHERE pref_key = ?";
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, PREF_THEME);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("pref_value");
                }
            }
            return "charcoal";
        } catch (SQLException e) {
            throw new IllegalStateException("failed to load theme", e);
        }
    }

    public void saveTheme(String themeId) {
        String sql = "INSERT INTO preferences (pref_key, pref_value) VALUES (?, ?) "
                + "ON CONFLICT(pref_key) DO UPDATE SET pref_value = excluded.pref_value";
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, PREF_THEME);
            ps.setString(2, themeId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to save theme", e);
        }
    }

    public int deleteEventsEndingBefore(LocalDateTime cutoff) {
        String sql = "DELETE FROM events WHERE end_time < ?";
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cutoff.format(DT_FORMAT));
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to delete past events", e);
        }
    }

    public StorageStats clearAllCalendarData() {
        try (Connection conn = dbManager.openConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement();
                 PreparedStatement prefPs = conn.prepareStatement(
                         "DELETE FROM preferences WHERE pref_key IN (?, ?)")) {
                stmt.executeUpdate("DELETE FROM events");
                stmt.executeUpdate("DELETE FROM recurring_rules");
                stmt.executeUpdate("DELETE FROM goals");
                prefPs.setString(1, PREF_SLEEP_TIME);
                prefPs.setString(2, PREF_SLEEP_HOURS);
                prefPs.executeUpdate();
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to clear calendar data", e);
        }
        return optimizeDatabase();
    }

    public StorageStats getStorageStats() {
        String sql = "SELECT "
                + "(SELECT COUNT(*) FROM events), "
                + "(SELECT COUNT(*) FROM recurring_rules), "
                + "(SELECT COUNT(*) FROM goals)";
        try (Connection conn = dbManager.openConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            int events = 0;
            int rules = 0;
            int goals = 0;
            if (rs.next()) {
                events = rs.getInt(1);
                rules = rs.getInt(2);
                goals = rs.getInt(3);
            }
            long bytes = computeDatabaseBytes();
            return new StorageStats(events, rules, goals, bytes);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to collect storage stats", e);
        }
    }

    public StorageStats optimizeDatabase() {
        try (Connection conn = dbManager.openConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            stmt.execute("VACUUM");
            stmt.execute("PRAGMA optimize");
        } catch (SQLException e) {
            throw new IllegalStateException("failed to optimize database", e);
        }
        return getStorageStats();
    }

    private long computeDatabaseBytes() {
        long total = 0L;
        Path db = dbManager.getDbPath().toAbsolutePath();
        total = total + fileSizeOrZero(db);
        total = total + fileSizeOrZero(Path.of(db + "-wal"));
        total = total + fileSizeOrZero(Path.of(db + "-shm"));
        return total;
    }

    private long fileSizeOrZero(Path path) {
        try {
            if (Files.isRegularFile(path)) {
                return Files.size(path);
            }
        } catch (IOException ignored) {
            return 0L;
        }
        return 0L;
    }

    private CalendarEvent mapEvent(ResultSet rs) throws SQLException {
        return new CalendarEvent(
                rs.getLong("id"),
                rs.getString("uid"),
                rs.getString("title"),
                LocalDateTime.parse(rs.getString("start_time"), DT_FORMAT),
                LocalDateTime.parse(rs.getString("end_time"), DT_FORMAT),
                EventCategory.valueOf(rs.getString("category")),
                EventSource.valueOf(rs.getString("source")),
                rs.getString("notes")
        );
    }

    private RecurringRule mapRecurringRule(ResultSet rs) throws SQLException {
        String startDateStr = rs.getString("start_date");
        String endDateStr = rs.getString("end_date");
        LocalDate startDate = null;
        LocalDate endDate = null;
        if (startDateStr != null && !startDateStr.isBlank()) {
            startDate = LocalDate.parse(startDateStr, DATE_FORMAT);
        }
        if (endDateStr != null && !endDateStr.isBlank()) {
            endDate = LocalDate.parse(endDateStr, DATE_FORMAT);
        }
        return new RecurringRule(
                rs.getLong("id"),
                rs.getString("title"),
                decodeDays(rs.getString("days_of_week")),
                LocalTime.parse(rs.getString("start_time"), TIME_FORMAT),
                LocalTime.parse(rs.getString("end_time"), TIME_FORMAT),
                EventCategory.valueOf(rs.getString("category")),
                startDate,
                endDate
        );
    }

    private ScheduleGoal mapGoal(ResultSet rs) throws SQLException {
        String earliestStr = rs.getString("earliest_start");
        String latestStr = rs.getString("latest_end");
        LocalTime earliest = null;
        LocalTime latest = null;
        if (earliestStr != null && !earliestStr.isBlank()) {
            earliest = LocalTime.parse(earliestStr, TIME_FORMAT);
        }
        if (latestStr != null && !latestStr.isBlank()) {
            latest = LocalTime.parse(latestStr, TIME_FORMAT);
        }
        return new ScheduleGoal(
                rs.getLong("id"),
                GoalType.valueOf(rs.getString("type")),
                rs.getString("title"),
                rs.getInt("sessions_per_week"),
                rs.getInt("session_minutes"),
                rs.getInt("weekly_minutes"),
                earliest,
                latest
        );
    }

    private String encodeDays(Set<DayOfWeek> days) {
        StringJoiner joiner = new StringJoiner(",");
        for (DayOfWeek day : days) {
            joiner.add(day.name());
        }
        return joiner.toString();
    }

    private Set<DayOfWeek> decodeDays(String raw) {
        EnumSet<DayOfWeek> set = EnumSet.noneOf(DayOfWeek.class);
        if (raw == null || raw.isBlank()) {
            return set;
        }
        String[] parts = raw.split(",");
        for (String part : parts) {
            if (!part.isBlank()) {
                set.add(DayOfWeek.valueOf(part.trim()));
            }
        }
        return set;
    }
}
