package com.calendarorganizer.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class DatabaseManager {

    private final Path dbPath;

    public DatabaseManager(Path dbPath) {
        this.dbPath = dbPath;
    }

    public Connection openConnection() throws SQLException {
        ensureParentDir();
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA cache_size = -1000");
            stmt.execute("PRAGMA temp_store = MEMORY");
            stmt.execute("PRAGMA busy_timeout = 3000");
        }
        return conn;
    }

    public void initialize() {
        try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode = WAL");
            stmt.execute("PRAGMA synchronous = NORMAL");
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS calendars ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                            + "name TEXT UNIQUE NOT NULL, "
                            + "color TEXT NOT NULL, "
                            + "visible INTEGER NOT NULL DEFAULT 1)"
            );
            seedDefaultCalendars(stmt);
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS events ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                            + "uid TEXT UNIQUE NOT NULL, "
                            + "title TEXT NOT NULL, "
                            + "start_time TEXT NOT NULL, "
                            + "end_time TEXT NOT NULL, "
                            + "category TEXT NOT NULL, "
                            + "source TEXT NOT NULL, "
                            + "notes TEXT, "
                            + "calendar_id INTEGER NOT NULL DEFAULT 1, "
                            + "color TEXT NOT NULL DEFAULT '')"
            );
            ensureColumnExists(stmt, "events", "calendar_id", "INTEGER NOT NULL DEFAULT 1");
            ensureColumnExists(stmt, "events", "color", "TEXT NOT NULL DEFAULT ''");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_events_time ON events(start_time, end_time)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_events_source ON events(source)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_events_calendar ON events(calendar_id)");
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS recurring_rules ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                            + "title TEXT NOT NULL, "
                            + "days_of_week TEXT NOT NULL, "
                            + "start_time TEXT NOT NULL, "
                            + "end_time TEXT NOT NULL, "
                            + "category TEXT NOT NULL, "
                            + "start_date TEXT, "
                            + "end_date TEXT, "
                            + "calendar_id INTEGER NOT NULL DEFAULT 1, "
                            + "color TEXT NOT NULL DEFAULT '', "
                            + "notes TEXT NOT NULL DEFAULT '')"
            );
            ensureColumnExists(stmt, "recurring_rules", "calendar_id", "INTEGER NOT NULL DEFAULT 1");
            ensureColumnExists(stmt, "recurring_rules", "color", "TEXT NOT NULL DEFAULT ''");
            ensureColumnExists(stmt, "recurring_rules", "notes", "TEXT NOT NULL DEFAULT ''");
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS goals ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                            + "type TEXT NOT NULL, "
                            + "title TEXT NOT NULL, "
                            + "sessions_per_week INTEGER NOT NULL, "
                            + "session_minutes INTEGER NOT NULL, "
                            + "weekly_minutes INTEGER NOT NULL, "
                            + "earliest_start TEXT, "
                            + "latest_end TEXT)"
            );
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS preferences ("
                            + "pref_key TEXT PRIMARY KEY, "
                            + "pref_value TEXT NOT NULL)"
            );
        } catch (SQLException e) {
            throw new IllegalStateException("failed to initialize database", e);
        }
    }

    private void seedDefaultCalendars(Statement stmt) throws SQLException {
        int existingCount = 0;
        try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM calendars")) {
            if (rs.next()) {
                existingCount = rs.getInt(1);
            }
        }
        if (existingCount == 0) {
            stmt.executeUpdate("INSERT INTO calendars (id, name, color, visible) VALUES (1, 'General', 'BLUE', 1)");
            stmt.executeUpdate("INSERT INTO calendars (name, color, visible) VALUES ('Work', 'TEAL', 1)");
            stmt.executeUpdate("INSERT INTO calendars (name, color, visible) VALUES ('School', 'AMBER', 1)");
        }
    }

    private void ensureColumnExists(Statement stmt,
                                    String tableName,
                                    String columnName,
                                    String definition) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rs.next()) {
                String existing = rs.getString("name");
                if (existing != null) {
                    columns.add(existing.toLowerCase(Locale.ROOT));
                }
            }
        }
        if (!columns.contains(columnName.toLowerCase(Locale.ROOT))) {
            stmt.executeUpdate("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
        }
    }

    private void ensureParentDir() {
        Path parent = dbPath.toAbsolutePath().getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new IllegalStateException("failed to create database directory", e);
            }
        }
    }

    public Path getDbPath() {
        return dbPath;
    }
}
