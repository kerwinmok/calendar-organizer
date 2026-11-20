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
- `DayParser`, `TimeRangeParser`, `RuleParser`, and `ParseResultMapper` parse plain schedule phrases offline.

## Milestone 5: LLM Provider Interface, Ollama, and Gemini (Completed)
- `LlmProvider` interface with `OllamaProvider`, `GeminiProvider`, `ScheduleParserService`, and `RuleParser` fallback.

## Milestone 6: Deterministic Scheduler (Completed)
- **What works**:
  - `TimeInterval` handles clipping, interval merging, and subtraction against day windows.
  - `Scheduler` places workout and study sessions deterministically with zero overlaps across fixed events, recurring rules, midnight-crossing blocks, and sleep windows.
  - `SchedulerTest` covers normal weeks, midnight-crossing events, completely booked days, full weeks, split study blocks, and varying sleep times.
- **What's next**:
  - Milestone 7: `CalendarService` and JavaFX week calendar UI with add-by-sentence, `.ics` import/export, and sidebar controls.
