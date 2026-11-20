# calendar-organizer

A local-first Windows desktop app in two parts:

1. **Calendar Builder**: Enter plain sentences like `"i work mon-thurs 7-6pm"` or `"i want to work out and study, i'm busy at 8pm for 2 hours, and i want to sleep at 12"`. Fixed blocks, recurring blocks, sleep hours, and workout/study goals are saved locally in SQLite and scheduled deterministically without overlaps. You can also **manually add, edit, and adjust timings like Google Calendar**: click-and-drag (or double-click) any empty slot to create an event, drag existing events up/down to move them, drag an event's bottom edge to resize its duration, double-click any event or recurring rule to edit its details, and hold **`Ctrl + Scroll`** to zoom the calendar grid in and out. Supports importing Google Calendar `.ics` exports (from file or pasted text) and exporting back to `.ics`.
2. **Course Organizer**: Organizes courses numerically by course number (for example `MATH 99`, `CMPT 201`, `STAT 201`, `CMPT 1000`) with real disk folders (`courses/CMPT-201/lectures/`, `notes/`, `coursework/`). Supports writing markdown notes and **dragging & dropping PowerPoint (`.pptx`/`.ppt`), PDF (`.pdf`), or document files** directly into a course section to store them locally (with one-click **Open File** and **Open Folder** buttons). Exports a single course or all courses into a `.zip` of clean markdown (`README.md`, normalized per-section `.md` files, and `combined.md`) ready to pass to another LLM for studying.

## Screenshots

### Calendar Builder
![Calendar Builder View](docs/images/calendar-view.png)

### Course Organizer
![Course Organizer View](docs/images/courses-view.png)

## UI Themes, Cleanup & Settings

Open **Settings** from the top-right bar to:
- Switch themes live between **Charcoal (Dark)** (default), **Midnight (OLED Dark)**, and **Paper (Light)**.
- Inspect live SQLite counts, database disk size, and memory usage.
- Run **Clear Past Events**, **Clear Scheduled Sessions**, **Optimize & Compact Database** (`VACUUM` + `PRAGMA optimize`), or **Reset All Calendar History & Settings** (which leaves your course markdown folders untouched).

## Requirements

- Windows 10 or Windows 11 (x64)
- Java 21 JDK (including `jpackage`)
- Apache Maven 3.9+

## Build and Run Tests (PowerShell)

```powershell
mvn test
```

## Run the Application (PowerShell)

```powershell
mvn javafx:run
```

## Optional LLM Parsing Configuration (PowerShell)

The built-in `RuleParser` handles common schedule phrases offline with zero configuration. To enable LLM JSON extraction for complex sentences:

### Gemini API
```powershell
$env:GEMINI_API_KEY = "your-gemini-api-key"
mvn javafx:run
```
Optional model override (defaults to `gemini-flash-lite-latest`):
```powershell
$env:GEMINI_MODEL = "gemini-flash-lite-latest"
```

### Local Ollama
Start Ollama locally, then launch:
```powershell
$env:OLLAMA_HOST = "http://localhost:11434"
$env:OLLAMA_MODEL = "llama3.2"
mvn javafx:run
```

## Package for Windows with jpackage (PowerShell)

```powershell
.\scripts\package-windows.ps1
```

This runs `mvn clean package`, collects runtime dependencies in `target\jpackage-input`, and builds a self-contained Windows app image at:
```powershell
.\target\dist\CalendarOrganizer\CalendarOrganizer.exe
```

## Local Data Storage

All calendar events, recurring rules, goals, preferences, and course markdown folders are stored locally under `$env:USERPROFILE\.calendar-organizer`:
- `calendar.db` — SQLite database for calendar data
- `courses\` — plain markdown files organized as `<COURSE>\lectures\`, `notes\`, and `coursework\`

You can override the data folder via `-Dcalendar.organizer.home=...` or `$env:CALENDAR_ORGANIZER_HOME`.
