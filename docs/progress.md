# Progress Log

## Milestone 1: Project Skeleton and Empty JavaFX Window (Completed)
- Maven build configured for Java 21, JavaFX 21, SQLite JDBC, Gson, and JUnit 5.
- `MainApp` and `Launcher` open an empty JavaFX window titled `Calendar Organizer`.
- `AppPaths` resolves the local data directory with overrides, covered by `AppPathsTest`.

## Milestone 2: Data Model and SQLite Storage (Completed)
- `CalendarEvent`, `RecurringRule`, `ScheduleGoal`, `SchedulePreferences`, `EventCategory`, `EventSource`, and `GoalType`.
- `DatabaseManager` and `CalendarRepository` persist and query events, recurring rules, goals, and preferences in SQLite.

## Milestone 3: ICS Import and Export (Completed)
- `IcsParser` supports `.ics` files and pasted text with UTC/`TZID`/date handling, `DURATION`, line unfolding, and weekly `RRULE`.
- `IcsExporter` produces RFC 5545 output with escaping and 75-character folding, verified by round-trip tests.

## Milestone 4: Rule-Based Parser for Schedule Phrases (Completed)
- **What works**:
  - `ParsedEvent`, `ParsedRecurringRule`, `ParsedGoal`, `ParseResult`, and `ParseResultMapper`.
  - `DayParser` handles ranges (`mon-thurs`), slash lists (`m/w/f`), `weekdays`/`weekends`/`daily`, and relative days (`today`, `tomorrow`).
  - `TimeRangeParser` handles shorthand ranges (`7-6pm`, `9-5`, `2-4pm`), overnight shifts (`10pm-6am`), `at X for Y hours/minutes`, and sleep times (`sleep at 12`, `sleep at 11`, `sleep at 1am`).
  - `RuleParser` parses multi-clause sentences into recurring blocks, fixed events, workout/study goals, and sleep preferences with `RuleParserTest` passing.
- **What's next**:
  - Milestone 5: `LlmProvider` interface with `OllamaProvider`, `GeminiProvider`, and fallback orchestration.
