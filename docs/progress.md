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
- `TimeInterval` and `Scheduler` place workout and study sessions deterministically around fixed blocks, recurring rules, midnight-crossing events, and sleep hours.

## Milestone 7: Calendar UI (Completed)
- **What works**:
  - `CalendarService` coordinates parsing, SQLite storage, automatic rescheduling, and `.ics` import/export, covered by `CalendarServiceTest`.
  - `WeekGridView` renders a 7-day Mon–Sun 24-hour calendar grid with color-coded event blocks, midnight split rendering, and side-by-side overlap layout.
  - `CalendarTabView` provides the sentence input bar (`Auto`/`Rules`/`Ollama`/`Gemini`), week navigation, `.ics` file import, paste-`.ics` modal dialog, `.ics` export, sleep editor, rules/goals management, and selected-event deletion.
  - `MainWindow` and `MainApp` launch cleanly on Windows.
- **What's next**:
  - Milestone 8: Course organizer with numeric course sorting and disk-backed markdown lectures, notes, and coursework.
