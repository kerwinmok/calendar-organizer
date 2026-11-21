package com.calendarorganizer.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

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
                    "CREATE TABLE IF NOT EXISTS events ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                            + "uid TEXT UNIQUE NOT NULL, "
                            + "title TEXT NOT NULL, "
                            + "start_time TEXT NOT NULL, "
                            + "end_time TEXT NOT NULL, "
                            + "category TEXT NOT NULL, "
                            + "source TEXT NOT NULL, "
                            + "notes TEXT)"
            );
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_events_time ON events(start_time, end_time)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_events_source ON events(source)");
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS recurring_rules ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                            + "title TEXT NOT NULL, "
                            + "days_of_week TEXT NOT NULL, "
                            + "start_time TEXT NOT NULL, "
                            + "end_time TEXT NOT NULL, "
                            + "category TEXT NOT NULL, "
                            + "start_date TEXT, "
                            + "end_date TEXT)"
            );
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
