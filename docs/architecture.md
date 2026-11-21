# Architecture

## Overview
`calendar-organizer` is a local-first Java 21 + JavaFX desktop application built with Maven, SQLite, Gson, and JUnit 5. All user data stays on local disk (`calendar.db` and `courses/`).

## Packages and Responsibilities
- `com.calendarorganizer`
  - `Launcher`: non-`Application` JAR/jpackage entry point delegating to `MainApp`.
  - `MainApp`: initializes `AppPaths`, `DatabaseManager`, `CalendarRepository`, `CalendarService`, `CourseManager`, `CourseExporter`, and opens `MainWindow`.
- `com.calendarorganizer.model`
  - `CalendarEvent`, `EventCategory`, `EventSource`: single events and categories (`WORK`, `BUSY`, `WORKOUT`, `STUDY`, `IMPORTED`, `GENERAL`).
  - `RecurringRule`: weekly repeating rules with `expandForRange(from, to)` handling midnight wrap and date bounds.
  - `ScheduleGoal`, `GoalType`, `SchedulePreferences`: workout/study targets and sleep window settings.
- `com.calendarorganizer.storage`
  - `AppPaths`: resolves `%USERPROFILE%\.calendar-organizer` (with property and environment overrides).
  - `DatabaseManager`: configures SQLite WAL mode, 2 MB cache cap (`cache_size = -2000`), in-memory temp store, and event range/source indexes (`idx_events_time`, `idx_events_source`).
  - `CalendarRepository`: CRUD, UID upsert for `.ics` imports, ISO-8601 range queries, generic key/value preference persistence (theme, Gemini key, Ollama host/model), past-event cleanup, full calendar reset, and `VACUUM`/`PRAGMA optimize` compaction.
  - `StorageStats`: immutable count and SQLite database byte-size snapshot.
- `com.calendarorganizer.ics`
  - `IcsParser`, `IcsParseResult`: parses `.ics` files and pasted text (`DTSTART`, `DTEND`, `DURATION`, `UTC`/`TZID`/`VALUE=DATE`, line unfolding, text unescaping, weekly `RRULE`).
  - `IcsExporter`: writes RFC 5545 CRLF `.ics` output with escaping and 75-character line folding.
- `com.calendarorganizer.parser`
  - `ParsedEvent`, `ParsedRecurringRule`, `ParsedGoal`, `ParseResult`, `ParseResultMapper`: intermediate parsed schedule structures and domain mappers.
  - `DayParser`, `TimeRangeParser`, `RuleParser`: offline rule-based parser for schedule sentences, ranges (`7-6pm`), relative dates, durations, goals, and sleep times.
- `com.calendarorganizer.llm`
  - `LlmProvider`, `LlmException`, `LlmPromptBuilder`, `LlmJsonParser`: provider interface and JSON-only extraction layer.
  - `OllamaProvider`: local Ollama HTTP client resolving saved preferences or `OLLAMA_HOST` / `OLLAMA_MODEL` dynamically.
  - `GeminiProvider`: Gemini HTTP client resolving saved preferences or `GEMINI_API_KEY` / `GEMINI_MODEL` dynamically.
  - `ParserMode`, `ScheduleParserService`: routes `AUTO`, `RULES`, `OLLAMA`, and `GEMINI` modes with fallback to `RuleParser`.
- `com.calendarorganizer.scheduler`
  - `TimeInterval`, `ScheduleResult`, `Scheduler`: deterministic Java scheduler that merges fixed events, expanded recurring rules, and sleep windows, then places workout and study sessions without overlaps.
- `com.calendarorganizer.service`
  - `CalendarService`, `ApplySentenceResult`, `IcsImportSummary`: orchestrates parsing, persistence, automatic rescheduling, and `.ics` import/export.
- `com.calendarorganizer.course`
  - `Course`, `CourseSection`, `CourseItem`, `CourseSortOrder`, `CourseManager`: disk-backed course folders sorted numerically by course number, with safe file naming, natural or timing-based file sorting (`NAME_ASC`, `NAME_DESC`, `NEWEST_FIRST`, `OLDEST_FIRST`), and monotonic timestamps on imported files.
  - `CourseFilePreviewer`: built-in zero-dependency preview extractor/renderer for PDFs (via Windows `Windows.Data.Pdf`), `.pptx` slides/notes/thumbnails, `.docx`, bounded text, and images.
  - `CourseExporter`: exports one course or all courses to a `.zip` with normalized `.md` files, `README.md`, and `combined.md`.
- `com.calendarorganizer.ui`
  - `UiTheme`: `CHARCOAL` (default dark), `MIDNIGHT` (near-black dark), and `PAPER` (light) theme definitions.
  - `MainWindow`: unified LightCraft-inspired chrome with top command bar, bottom status/navigation bar, collapsible panels, and `Calendar`/`Courses` mode switching.
  - `CalendarTabView`, `WeekGridView`, `CalendarGridMath`, `EventDialog`, `EventFormValidator`: 7-day x 24-hour calendar canvas with `Ctrl + Scroll` zooming, static hour gridlines, coalesced resize relayout, smooth wheel scrolling, current-time indicator, drag-to-create on empty slots, drag-to-move/resize on event cards, manual event and recurring-rule editing dialogs with form validation, `.ics` background tasks, and collapsible right inspector.
  - `CourseTabView`: three-pane course list with top-bar filtering, segmented section switch (`Lectures`, `Notes`, `Coursework`), persisted alphabetical/timing file sort selector, markdown editor with automatic flush on navigation/close, built-in PDF/PPTX/DOCX/image/text previewer, background drag-and-drop imports, native Windows open/folder launch, and background `.zip` exports.
  - `SettingsDialog`: theme switcher, optional in-app Gemini API key and Ollama configuration, storage and JVM memory readout, `Clear Past Events`, `Clear Scheduled Sessions`, `Optimize & Compact Database`, and `Reset All Calendar History & Settings`.
- Windows Packaging (`packaging/windows` & `scripts/package-windows.ps1`)
  - `SingleFileLauncher.cs` + `app-icon.ico`: compiles a standalone x64 WinForms stub with an appended compressed `jpackage` payload and 40-byte `CALORG01` footer into a single `target/dist/CalendarOrganizer.exe`, extracting once by content hash into `%LOCALAPPDATA%\CalendarOrganizer\app-<stamp>`, supporting optional `portable.txt` / `calendar-organizer-data`, and launching directly from cache thereafter.

## Data Flow
1. **Calendar Input -> Schedule**: Sentence input -> `CalendarService.applySentence` -> `ScheduleParserService` (`GeminiProvider` / `OllamaProvider` / `RuleParser`) -> `ParseResultMapper` -> SQLite (`CalendarRepository`) -> `Scheduler.scheduleWeek` -> non-overlapping `SCHEDULED` events persisted -> `WeekGridView` refreshes.
2. **ICS Sync**: `.ics` file or pasted text -> `IcsParser` -> `CalendarRepository.upsertEventByUid` & `saveRecurringRule`; export reads `CalendarRepository` -> `IcsExporter`.
3. **Course Material -> Study Zip**: Markdown edits save directly under `<dataDir>/courses/<COURSE>/<section>/*.md` via `CourseManager`; `CourseExporter` packages normalized markdown plus `README.md` and `combined.md` into `.zip`.
