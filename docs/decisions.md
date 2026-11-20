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

