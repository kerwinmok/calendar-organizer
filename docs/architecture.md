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
  - `DatabaseManager`: configures SQLite WAL mode, 1 MB cache cap (`cache_size = -1000`), in-memory temp store, and event range/source indexes (`idx_events_time`, `idx_events_source`).
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
  - `AppExecutors`: bounded core-expiring daemon pools (`ioExecutor` and `renderExecutor`) with application-exit `shutdown()`.
  - `CalendarService`, `ApplySentenceResult`, `IcsImportSummary`: orchestrates parsing, persistence, automatic rescheduling, and `.ics` import/export.
- `com.calendarorganizer.course`
  - `Course`, `CourseSection`, `CourseItem`, `CourseSortOrder`, `CourseManager`: disk-backed course folders sorted numerically by course number, with safe file naming, natural or timing-based file sorting (`NAME_ASC`, `NAME_DESC`, `NEWEST_FIRST`, `OLDEST_FIRST`), and monotonic timestamps on imported files.
  - `PdfPageCache`, `PdfRenderService`, `LectureRenderModel`, `CourseFilePreviewer`: built-in preview extractor/renderer for PDFs (in-process Apache PDFBox 3.0.8 with `setupTempFileOnly`, 1 open-document handle, 8 MB LRU `WritableImage` page cache, headless AWT, and aspect-ratio page slot metadata), `.pptx` slides/notes/thumbnails, `.docx`, bounded text, and images.
  - `CourseExporter`: exports one course or all courses to a `.zip` with normalized `.md` files, `README.md`, and `combined.md`.
- `com.calendarorganizer.workspace`
  - `WorkspacePaneType`, `WorkspaceLayoutConfig`: 2–4 pane split-screen layout model (`HORIZONTAL`, `VERTICAL`, `GRID`, plus `studyTrioConfig()`), per-pane type/course/dock preset metadata, legacy type normalization, and JSON persistence under `workspace_layout` in SQLite `preferences`.
  - `WindowsWindowDocker`: Win32 JNA (`User32` / `Dwmapi`) owned-overlay window docker (`GWLP_HWNDPARENT`, `ClientToScreen`, `DWMWA_EXTENDED_FRAME_BOUNDS` frame compensation, non-blocking `SWP_ASYNCWINDOWPOS` bounds sync, drag-in/drag-out cursor polling, safe temporary detach on tab switch, and JVM shutdown hook to release all docked owners cleanly).
  - `WindowsWindowSnapper`: detects local `ONENOTE.EXE` (`onenote:` fallback) and computes Windows monitor work-area split bounds (`MonitorFromWindow` / `GetMonitorInfoW`) for side-by-side snapping.
- `com.calendarorganizer.ui`
  - `UiTheme`, `WindowsThemeDetector`, `WindowsTitleBar`: `SYSTEM` (default Windows dark/light auto-follower), `CHARCOAL`, `MIDNIGHT`, and `PAPER` themes with JNA DWM native title bar styling.
  - `MainWindow`: unified LightCraft-inspired chrome with top navigation bar (`Calendar`, `Courses`, `Workspace`), bottom status/navigation bar, lazy `CourseTabView` and `StudyWorkspaceView` initialization, side-by-side `LectureRenderPane` host, `F11` / `Esc` full screen for Render and Workspace, temporary dock detach around modal dialogs, and transient preview cleanup.
  - `StudyWorkspaceView`, `WorkspacePaneContent`, `LectureWorkspacePane`, `CalendarWorkspacePane`, `OneNoteDockPane`, `NotesWorkspacePane`, `WorkspaceUiHelpers`: resizable 2–4 pane `SplitPane` workspace (`Horizontal`, `Vertical`, `2x2 Grid`, and 1-click `Study Trio` with `Lectures + Calendar` stacked left and full-height `OneNote Dock` right), `+ Add Pane`, draggable header tabs (`⋮⋮`) to swap slots, type swapper, close (`×` down to 2 panes), debounced layout persistence, and autosaving course markdown notes.
  - `CalendarTabView`, `WeekGridView`, `CalendarGridMath`, `EventDialog`, `EventFormValidator`: 7-day x 24-hour calendar canvas with `Ctrl + Scroll` zooming, static hour gridlines, coalesced resize relayout, smooth wheel scrolling, current-time indicator, drag-to-create on empty slots, drag-to-move/resize on event cards, manual event and recurring-rule editing dialogs with form validation, `.ics` background tasks, and collapsible right inspector.
  - `CourseTabView`, `LectureRenderPane`: lazily loaded three-pane course browser with top-bar filtering, segmented section switch (`Lectures`, `Notes`, `Coursework`), persisted alphabetical/timing file sort selector, markdown editor with automatic flush on navigation/close, lazy virtualized PDF page viewer (`PdfPageSlot` + `-` / `+` / `Fit` / `Ctrl + Scroll` zoom and automatic cache release on switch/exit), side-by-side / full-screen render view, `.pptx`, `.docx`, image, and text previewer, background drag-and-drop imports, native Windows open/folder launch, and background `.zip` exports.
  - `SettingsDialog`: theme switcher, optional in-app Gemini API key and Ollama configuration, storage and JVM memory readout, `Clear Past Events`, `Clear Scheduled Sessions`, `Optimize & Compact Database`, and `Reset All Calendar History & Settings`.
- Windows Packaging (`packaging/windows` & `scripts/package-windows.ps1`)
  - `app-icon.ico` + `scripts/package-windows.ps1`: builds a trimmed JDK 21 `jpackage --type app-image` bundle with Direct3D-accelerated Prism (`-Dprism.order=d3d,sw`), portable `target/dist/CalendarOrganizer-windows-x64.zip`, optional `-Installer` `.exe`, and optional `-Install -DesktopShortcut` deployment to `%LOCALAPPDATA%\Programs\CalendarOrganizer\`.

## Data Flow
1. **Calendar Input -> Schedule**: Sentence input -> `CalendarService.applySentence` -> `ScheduleParserService` (`GeminiProvider` / `OllamaProvider` / `RuleParser`) -> `ParseResultMapper` -> SQLite (`CalendarRepository`) -> `Scheduler.scheduleWeek` -> non-overlapping `SCHEDULED` events persisted -> `WeekGridView` refreshes.
2. **ICS Sync**: `.ics` file or pasted text -> `IcsParser` -> `CalendarRepository.upsertEventByUid` & `saveRecurringRule`; export reads `CalendarRepository` -> `IcsExporter`.
3. **Course Material -> Study Zip**: Markdown edits save directly under `<dataDir>/courses/<COURSE>/<section>/*.md` via `CourseManager`; `CourseExporter` packages normalized markdown plus `README.md` and `combined.md` into `.zip`.
