# Progress Log

## Milestone 1: Project Skeleton and Empty JavaFX Window (Completed)
- Maven build configured for Java 21, JavaFX 21, SQLite JDBC, Gson, and JUnit 5.
- `MainApp` and `Launcher` open an empty JavaFX window titled `Calendar Organizer`.
- `AppPaths` resolves the local data directory with overrides, covered by `AppPathsTest`.

## Milestone 2: Data Model and SQLite Storage (Completed)
- `CalendarEvent`, `RecurringRule`, `ScheduleGoal`, `SchedulePreferences`, `EventCategory`, `EventSource`, and `GoalType`.
- `DatabaseManager` and `CalendarRepository` persist and query events, recurring rules, goals, and preferences in SQLite.

## Milestone 3: ICS Import and Export (Completed)
- **What works**:
  - `IcsParser` supports both `.ics` files and pasted text, RFC 5545 line unfolding, text unescaping, UTC/`TZID`/floating/`VALUE=DATE` timestamps, `DURATION` fallback, and weekly `RRULE` (`BYDAY`, `UNTIL`).
  - `IcsExporter` emits valid RFC 5545 CRLF output with text escaping, 75-character line folding, single events, and weekly `RRULE` rules.
  - `IcsParserTest` and `IcsExporterTest` pass including full export/re-parse round-trips.
- **What's next**:
  - Milestone 4: Rule-based parser for natural language schedule phrases with unit tests.
