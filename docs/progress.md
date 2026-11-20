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
- **What works**:
  - `LlmProvider` interface, `LlmPromptBuilder`, `LlmJsonParser`, and `LlmException`.
  - `OllamaProvider` (`OLLAMA_HOST`, `OLLAMA_MODEL`) and `GeminiProvider` (`GEMINI_API_KEY`, `GEMINI_MODEL`).
  - `ScheduleParserService` with `ParserMode` (`AUTO`, `RULES`, `OLLAMA`, `GEMINI`) and automatic fallback to `RuleParser`.
  - `LlmJsonParserTest`, `OllamaProviderTest`, `GeminiProviderTest`, and `ScheduleParserServiceTest` pass using local HTTP server stubs.
- **What's next**:
  - Milestone 6: Deterministic `Scheduler` for workout, study, sleep, and fixed/recurring events with edge-case unit tests.
