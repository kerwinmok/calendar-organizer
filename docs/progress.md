# Progress Log

## Milestone 1: Project Skeleton and Empty JavaFX Window (Completed)
- **What works**:
  - Maven build configured for Java 21, JavaFX 21, SQLite JDBC, Gson, and JUnit 5.
  - `MainApp` and `Launcher` open an empty JavaFX window titled `Calendar Organizer`.
  - `AppPaths` resolves the local data directory with system-property and environment-variable overrides, covered by `AppPathsTest`.
  - Documentation skeleton initialized (`README.md`, `docs/decisions.md`, `docs/architecture.md`, `docs/progress.md`).
- **What's next**:
  - Milestone 2: Data model (`CalendarEvent`, `RecurringRule`, `ScheduleGoal`, `SchedulePreferences`) and SQLite storage (`DatabaseManager`, `CalendarRepository`) with unit tests.
