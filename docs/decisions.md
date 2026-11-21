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

## All-in-one standalone Windows executable (`CalendarOrganizer.exe`)
- **Decision**: Stage `jpackage --type app-image` into a compressed zip payload appended after a native `.NET Framework 4.x` (`csc.exe` x64 WinForms) stub executable with a 40-byte SHA-256-stamped footer (`CALORG01`), producing a single portable `target/dist/CalendarOrganizer.exe` (~45 MB) with custom icon (`app-icon.ico`).
- **Reason**: Built-in Windows `.NET Framework 4.x` and JDK 21 `jpackage` work without requiring WiX, third-party installers, or admin rights; downloading a single `.exe` works anywhere, unpacks once into `%LOCALAPPDATA%\CalendarOrganizer\app-<stamp>`, launches straight from cache on subsequent runs (~0.4s overhead), exits the wrapper immediately so zero extra background processes stay open, and supports optional `portable.txt` beside the `.exe`.

## Optional in-app AI settings with environment-variable fallback
- **Decision**: Resolve the Gemini API key and Ollama host/model dynamically via suppliers in `GeminiProvider` and `OllamaProvider`, reading SQLite `preferences` first and falling back to `GEMINI_API_KEY`, `OLLAMA_HOST`, and `OLLAMA_MODEL` environment variables.
- **Reason**: Users running the standalone `.exe` by double-clicking can optionally paste a Gemini API key or set Ollama settings inside **Settings** without opening PowerShell, while terminal/environment-variable workflows keep working unchanged.

## Default Gemini model
- **Decision**: Default `GeminiProvider` to `gemini-flash-lite-latest` (configurable via `GEMINI_MODEL`) and skip any `"thought": true` response parts when extracting JSON text.
- **Reason**: `gemini-2.5-flash` returns HTTP 404 for new API keys on `generativelanguage.googleapis.com/v1beta`, whereas `gemini-flash-lite-latest` responds cleanly and quickly with structured JSON.

## LightCraft-inspired UI chrome, themes, and memory footprint
- **Decision**: Adopt a single-chrome top bar and bottom status/navigation bar with Charcoal (`#2d2d2d` / `#1c1c1c`), Midnight, and Paper themes; keep `WeekGridView` gridlines static with coalesced resize relayouts; run UI background tasks on `SHARED_BG_EXECUTOR` while keeping `HttpClient` lazy on an idle-expiring `HTTP_EXECUTOR` pool; open external files via native Windows `explorer.exe`/`rundll32.exe` instead of bootstrapping AWT/Swing; batch SQLite writes in single transactions with WAL, 1 MB cache cap (`cache_size = -1000`), and indexes; trim `jpackage` to required JDK modules; and run the packaged app with `-Xms16m -Xmx128m`, `-XX:+UseSerialGC`, `-XX:TieredStopAtLevel=1`, `-XX:ReservedCodeCacheSize=20m`, `-Xss384k`, and software JavaFX Prism (`-Dprism.order=sw`).
- **Reason**: Eliminates Direct3D GPU surface allocations, C2 compiler arenas, G1 GC remembered sets, and unused AWT/Swing threads — cutting desktop private memory usage sharply while keeping frame rendering smooth and avoiding single-thread `HttpClient` deadlocks.

## Course file sorting and built-in lecture preview
- **Decision**: Support persisted alphabetical (`Name A-Z` / `Name Z-A`) and timing-based (`Newest first` / `Oldest first`) sorting with monotonic timestamps applied at import time, alongside built-in previews (`CourseFilePreviewer`) using Windows built-in `Windows.Data.Pdf` for PDF page rendering, XXE-safe XML extraction for `.pptx` slides/notes/thumbnails and `.docx` documents, bounded reads for text files, and bounded decoding for images.
- **Reason**: Keeps newly dropped files such as `appendix.pdf` ordered by arrival when desired and lets students skim slides and lecture PDFs directly inside the Courses tab without bundling heavy PDF/Office Java libraries into the executable.

