# Architecture

## Overview
`calendar-organizer` is a local-first Java 21 + JavaFX desktop application split into two core workflows: a deterministic calendar builder and a file-backed course organizer.

## Modules
- `com.calendarorganizer` (`MainApp`, `Launcher`): JavaFX entry point and main window shell.
- `com.calendarorganizer.model` *(planned)*: Calendar events, recurring rules, goals, and schedule preferences.
- `com.calendarorganizer.storage` (`AppPaths`, planned `DatabaseManager` and `CalendarRepository`): Data directory resolution and SQLite persistence.
- `com.calendarorganizer.ics` *(planned)*: RFC 5545 `.ics` importer (file and text) and exporter.
- `com.calendarorganizer.parser` *(planned)*: Rule-based schedule sentence parser and result mapper.
- `com.calendarorganizer.llm` *(planned)*: `LlmProvider` interface with `OllamaProvider` and `GeminiProvider`.
- `com.calendarorganizer.scheduler` *(planned)*: Deterministic slot finder for workouts and study blocks around fixed events and sleep windows.
- `com.calendarorganizer.course` *(planned)*: Disk-backed course directories, markdown file operations, and zip+markdown study export.
- `com.calendarorganizer.ui` *(planned)*: Week calendar view and three-pane course workspace.

## Planned Data Flow
1. User enters a sentence in the calendar view.
2. `ScheduleParserService` invokes the selected parser (`GeminiProvider`, `OllamaProvider`, or `RuleParser`) to produce structured JSON/objects.
3. Events, recurring rules, goals, and sleep preferences are saved to SQLite (`calendar.db`).
4. `Scheduler` computes non-overlapping workout and study blocks across the week and stores them in SQLite.
5. Course notes live as plain markdown files under `<dataDir>/courses/<COURSE>/lectures|notes|coursework/*.md` and export cleanly to `.zip`.
