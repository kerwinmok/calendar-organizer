# Progress Log

## Milestone 1: Project Skeleton and Empty JavaFX Window (Completed)
- Maven build configured for Java 21, JavaFX 21, SQLite JDBC, Gson, and JUnit 5.
- `MainApp` and `Launcher` open an empty JavaFX window titled `Calendar Organizer`.
- `AppPaths` resolves the local data directory with overrides, covered by `AppPathsTest`.

## Milestone 2: Data Model and SQLite Storage (Completed)
- **What works**:
  - `CalendarEvent`, `RecurringRule` (including midnight-crossing expansion), `ScheduleGoal`, `SchedulePreferences`, `EventCategory`, `EventSource`, and `GoalType`.
  - `DatabaseManager` initializes SQLite schema (`events`, `recurring_rules`, `goals`, `preferences`).
  - `CalendarRepository` supports CRUD, UID-based upsert for imports, ISO-8601 range queries, and preferences persistence.
  - Unit tests in `RecurringRuleTest` and `CalendarRepositoryTest` pass.
- **What's next**:
  - Milestone 3: `.ics` import (file and pasted text) and `.ics` export with unit tests.
