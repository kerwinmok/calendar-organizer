# Design Decisions

## Java 21 non-modular project with `Launcher` entry point
- **Decision**: Build as a standard classpath Java 21 project without `module-info.java`, using `com.calendarorganizer.Launcher` as the JAR main class that delegates to `MainApp.launchApp(args)`.
- **Reason**: `sqlite-jdbc` loads native JNI libraries and has non-modular dependencies, and JavaFX `Application` subclasses require a separate non-`Application` main class when launched from a classpath JAR or `jpackage` app-image.

## Local data storage directory
- **Decision**: Store SQLite database (`calendar.db`) and course folders (`courses/`) under `%USERPROFILE%\.calendar-organizer` by default, with overrides via `-Dcalendar.organizer.home` or `CALENDAR_ORGANIZER_HOME`.
- **Reason**: Keeps user data outside the repository working tree and packaged application directory so upgrades or rebuilds never overwrite local schedules or notes.

## LLM provider interface with rule-based fallback
- **Decision**: Restrict LLM integration to converting natural language sentences into structured JSON via a `LlmProvider` interface with `OllamaProvider` (local) and `GeminiProvider` (`GEMINI_API_KEY`), backed by `RuleParser` when no model is available.
- **Reason**: Keeps all calendar placement deterministic in Java, prevents secrets from being stored in config files, and guarantees offline operation without any model running.

## Default sleep duration
- **Decision**: Default sleep duration to 7 hours starting at the configured `sleepTime` (default `23:00`).
- **Reason**: Saying "sleep at 12" maps to `00:00`–`07:00`, which aligns cleanly with a 7:00 AM workday start.

## Default goal targets when quantities are omitted
- **Decision**: When a user writes `"i want to work out and study"` without numbers, default workout goals to 3 sessions per week of 60 minutes, and study goals to 5 sessions per week of 90 minutes (450 minutes/week).
- **Reason**: Gives sensible weekly totals out of the box while allowing explicit quantities (`"4 times a week for 45 minutes"`, `"10 hours a week"`) to override them.

## Deterministic scheduling order and study chunking
- **Decision**: Schedule `WORKOUT` goals before `STUDY` goals (preferring one workout per day on days with the most free time and a 15-minute buffer when available), then distribute `STUDY` blocks round-robin across days with the fewest scheduled study minutes, allowing chunks down to 30 minutes when gaps are tight.
- **Reason**: Keeps workouts spread out without crowding busy workdays, balances study load evenly across the week, and guarantees identical results across repeated runs.

## Replacing goals by type on sentence input
- **Decision**: When a parsed sentence adds a `WORKOUT` or `STUDY` goal, replace any existing saved goal of the same `GoalType` and automatically reschedule the active week.
- **Reason**: Allows updating weekly workout or study targets from the sentence bar without stacking duplicate goals in SQLite.

## Course number sorting and folder naming
- **Decision**: Normalize course folder names by replacing spaces with hyphens (for example `CMPT 201` -> `CMPT-201`), and sort courses numerically by extracted course number ascending (`MATH 99` before `CMPT 201` before `CMPT 1000`), breaking ties by department alphabetically and placing unnumbered folders last.
- **Reason**: Matches the `courses/CMPT-201/lectures/lecture-01.md` layout while ensuring multi-digit course numbers sort numerically rather than lexicographically.

## Zip and markdown study export bundle layout
- **Decision**: Export both individual normalized `.md` files under `<COURSE-FOLDER>/<section>/<file.md>` and a top-level `README.md` index plus a merged `combined.md` file inside each exported `.zip`.
- **Reason**: Lets you upload either individual course files or a single combined markdown bundle directly into another LLM to study from, while stripping non-markdown files and normalizing line endings.

## Standalone Windows app bundle (`jpackage` app-image + portable zip)
- **Decision**: Package the application using the official JDK 21 `jpackage --type app-image` bundle with a custom icon (`packaging/windows/app-icon.ico`) and a portable `CalendarOrganizer-windows-x64.zip` archive (~48.7 MB), plus optional `-Install -DesktopShortcut` deployment in `scripts/package-windows.ps1` to `%LOCALAPPDATA%\Programs\CalendarOrganizer\` and a Desktop `.lnk` shortcut.
- **Reason**: Official `jpackage` launchers run cleanly under Windows 11 Smart App Control without requiring WiX, administrator privileges, or a commercial code-signing certificate (whereas unsigned custom self-extracting wrappers are blocked), avoid duplicate runtime extraction caches or wrapper startup delay, and still run self-contained with zero system Java requirement.

## Optional in-app AI settings with environment-variable fallback
- **Decision**: Resolve the Gemini API key and Ollama host/model dynamically via suppliers in `GeminiProvider` and `OllamaProvider`, reading SQLite `preferences` first and falling back to `GEMINI_API_KEY`, `OLLAMA_HOST`, and `OLLAMA_MODEL` environment variables.
- **Reason**: Users running the standalone `.exe` by double-clicking can optionally paste a Gemini API key or set Ollama settings inside **Settings** without opening PowerShell, while terminal/environment-variable workflows keep working unchanged.

## Default Gemini model
- **Decision**: Default `GeminiProvider` to `gemini-flash-lite-latest` (configurable via `GEMINI_MODEL`) and skip any `"thought": true` response parts when extracting JSON text.
- **Reason**: `gemini-2.5-flash` returns HTTP 404 for new API keys on `generativelanguage.googleapis.com/v1beta`, whereas `gemini-flash-lite-latest` responds cleanly and quickly with structured JSON.

## LightCraft-inspired UI chrome, themes, and responsive rendering
- **Decision**: Adopt a single-chrome top bar and bottom status/navigation bar with System, Charcoal (`#2d2d2d` / `#1c1c1c`), Midnight, and Paper themes; load `CourseTabView` and `StudyWorkspaceView` lazily and release transient preview caches on tab switch; keep `WeekGridView` gridlines static with coalesced resize relayouts and filter `LectureRenderPane` resize refreshes with a `>=8px` threshold; run background work on bounded `AppExecutors` (`ioExecutor` and `renderExecutor`); batch SQLite writes with WAL and 1 MB cache cap (`cache_size = -1000`); trim `jpackage` modules (`java.base`, `java.sql`, `java.net.http`, `java.desktop`, `java.logging`, `java.xml`, `jdk.crypto.ec`, `jdk.unsupported`); and run with Direct3D GPU hardware acceleration (`-Dprism.order=d3d,sw`, `-Dprism.maxvram=64m`, `-Dprism.targetvram=32m`) plus `-Xms16m -Xmx192m`, `-XX:+UseSerialGC`, `-XX:ReservedCodeCacheSize=32m`, `-Xss384k`, `-XX:SoftRefLRUPolicyMSPerMB=50`, `-Djava.awt.headless=true`, and `--enable-native-access=ALL-UNNAMED`.
- **Reason**: Hardware-accelerated Direct3D rendering (`d3d,sw`) and full C2 JIT compilation eliminate UI sluggishness during high-DPI split-pane resizing and PDF scrolling while maintaining low memory usage and instant startup.

## Course file sorting and built-in lecture preview
- **Decision**: Support persisted alphabetical (`Name A-Z` / `Name Z-A`) and timing-based (`Newest first` / `Oldest first`) sorting with monotonic timestamps applied at import time, alongside built-in previews (`CourseFilePreviewer`, `PdfRenderService`, `PdfPageCache`, `LectureRenderModel`) using in-process Apache PDFBox 3.0.8 with headless AWT, temp-file stream buffering (`setupTempFileOnly`), 1 open-document handle, an 8 MB LRU `WritableImage` page cache, and lazy virtualized PDF scrolling with `-` / `+` / `Fit` / `Ctrl + Scroll` zoom — plus XXE-safe XML extraction for `.pptx` slides/notes/thumbnails and `.docx`, bounded text reads, and bounded image decoding.
- **Reason**: Keeps newly dropped files such as `appendix.pdf` ordered by arrival when desired, renders PDFs smoothly in-process without spawning external subprocesses or accumulating temp PNGs, and bounds heap/cache usage even across large multi-page slide decks.

## Native JavaFX Study Workspace and clean Render toggle (no embedded WebView or external window docking)
- **Decision**: Build `StudyWorkspaceView` with purely in-app JavaFX `SplitPane` panes (`LectureWorkspacePane`, `CalendarWorkspacePane`, `NotesWorkspacePane`) supporting draggable `⋮⋮` header tabs, 2–4 resizable splits, and `F11` / `Esc` full screen, alongside a toggleable **Render** / **Close Render** host and non-truncating **`✕ Close`** / zoom controls on `LectureRenderPane`. Archive experimental Gemini Notebook sync on the separate `test` git branch rather than keeping it on `main`.
- **Reason**: An empirical probe of `javafx-web` showed OneNote Web rendering blank (`TITLE=null`) while spiking RAM to `574.8 MB` and adding `36.90 MB`, and cross-process Win32 overlay docking adds complexity when standard Windows Snap (`Win + Left/Right`) already positions OneNote cleanly beside Calendar Organizer. Keeping only native JavaFX panes keeps `main` fast, predictable, and clutter-free while preventing split-pane button truncation.

