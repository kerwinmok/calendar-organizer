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
- `CalendarService`, `WeekGridView`, `CalendarTabView`, and `MainWindow` deliver week rendering, sentence input, `.ics` import/export, and sidebar management.

## Milestone 8: Course Organizer (Completed)
- `Course`, `CourseSection`, `CourseItem`, `CourseManager`, and `CourseTabView` store courses by course number with lectures, notes, and coursework markdown files.

## Milestone 9: Zip and Markdown Export (Completed)
- `CourseExporter` exports a single course or all courses into a `.zip` archive with normalized `.md` files, `README.md`, and `combined.md`, wired into `CourseTabView`.

## Milestone 10: Windows jpackage Build, Final Docs, and Review (Completed)
- **What works**:
  - `scripts/package-windows.ps1` builds a trimmed JDK 21 `jpackage` runtime image wrapped with `packaging/windows/SingleFileLauncher.cs` and custom `app-icon.ico` into a single all-in-one `target/dist/CalendarOrganizer.exe` (~45.3 MB) with bounded `G1GC` heap flags (`-Xms32m -Xmx256m`).
  - Double-clicking `CalendarOrganizer.exe` anywhere unpacks once into `%LOCALAPPDATA%\CalendarOrganizer` with a dark progress bar and launches straight from cache on subsequent runs, with optional portable data mode (`portable.txt`).
  - LightCraft-inspired single-chrome UI (`MainWindow`, `SettingsDialog` with optional in-app Gemini/Ollama configuration, `UiTheme` with Charcoal/Midnight/Paper themes), static-grid coalesced `WeekGridView`, SQLite WAL + indexes + compaction (`VACUUM` / `PRAGMA optimize`), and one-click reset of calendar history and settings.
  - `CalendarGridMath` and `EventFormValidator` back interactive calendar zooming, drag-to-create/move/resize, manual event editing, recurring rule editing, and course PDF/PPTX attachment storage.
  - Both `mvn javafx:run` and the standalone `CalendarOrganizer.exe` launch on Windows cleanly.
  - All 52 JUnit 5 tests pass.
- **What's next**:
  - Ready for everyday use; set `$env:GEMINI_API_KEY` or run local Ollama optionally for LLM sentence parsing.
