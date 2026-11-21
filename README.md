# calendar-organizer

A local-first Windows desktop app in two parts:

1. **Calendar Builder**: Enter plain sentences like `"i work mon-thurs 7-6pm"` or `"i want to work out and study, i'm busy at 8pm for 2 hours, and i want to sleep at 12"`. Fixed blocks, recurring blocks, sleep hours, and workout/study goals are saved locally in SQLite and scheduled deterministically without overlaps. You can also **manually add, edit, and adjust timings like Google Calendar**: click-and-drag (or double-click) any empty slot to create an event, drag existing events up/down to move them, drag an event's bottom edge to resize its duration, double-click any event or recurring rule to edit its details, and hold **`Ctrl + Scroll`** to zoom the calendar grid in and out. Supports importing Google Calendar `.ics` exports (from file or pasted text) and exporting back to `.ics`.
2. **Course Organizer**: Organizes courses numerically by course number (for example `MATH 99`, `CMPT 201`, `STAT 201`, `CMPT 1000`) with real disk folders (`courses/CMPT-201/lectures/`, `notes/`, `coursework/`). Supports writing markdown notes, **dragging & dropping PowerPoint (`.pptx`/`.ppt`), PDF (`.pdf`), Word (`.docx`), or image files** directly into a course section, sorting files **alphabetically (A-Z / Z-A) or by timing (Newest first / Oldest first)**, **in-app built-in previews** (lazy virtualized PDF page scrolling with `< Prev` / `Next >` navigation, `-` / `+` / `Fit` and `Ctrl + Scroll` zoom, and automatic cache release on tab switch or exit, plus extracted `.pptx` slides + speaker notes + thumbnail, `.docx`/text preview, and image preview), a side-by-side **Render** study view for all supported files in a course or section (with a course/section/file picker, page-by-page lazy rendering, zoom, and a **Full Screen** / `F11` / `Esc` toggle), plus one-click **Open File** and **Open Folder** buttons. Exports a single course or all courses into a `.zip` of clean markdown (`README.md`, normalized per-section `.md` files, and `combined.md`) ready to pass to another LLM for studying.

## Download & Run (Windows 10/11 — No Java Required)

Pre-built Windows bundles are published on the [**Releases page**](https://github.com/kerwinmok/calendar-organizer/releases/latest) so anyone can download and double-click to run with zero setup:

- **Option 1 — Windows Installer (Recommended)**
  1. Download `CalendarOrganizer-<version>.exe` from the [latest release](https://github.com/kerwinmok/calendar-organizer/releases/latest).
  2. Double-click the installer to install per-user (no administrator rights required) and create Desktop and Start Menu shortcuts.
  3. Double-click **Calendar Organizer** to launch.
- **Option 2 — Portable Zip (No Install)**
  1. Download `CalendarOrganizer-windows-x64.zip` from the [latest release](https://github.com/kerwinmok/calendar-organizer/releases/latest).
  2. Right-click -> **Extract All...**.
  3. Open the extracted `CalendarOrganizer` folder and double-click `CalendarOrganizer.exe`.

> **SmartScreen note:** Downloaded releases are unsigned, so Windows Defender SmartScreen may show *"Windows protected your PC"* on the first launch (click **More info** -> **Run anyway**). If Windows 11 Smart App Control is set to strict enforcement on your machine, build locally from source with `.\scripts\package-windows.ps1 -Install -DesktopShortcut`.

## Screenshots

### Calendar Builder
![Calendar Builder View](docs/images/calendar-view.png)

### Course Organizer
![Course Organizer View](docs/images/courses-view.png)

## UI Themes, Settings & Cleanup

Open **Settings** from the top-right bar to:
- Switch themes live between **System (Auto)** (default, follows Windows light/dark mode automatically), **Charcoal (Dark)**, **Midnight (OLED Dark)**, and **Paper (Light)**; the native Windows title bar updates to match both app dialogs and the active theme.
- Optionally save a **Gemini API key** (stored locally, masked input) or configure a local **Ollama** host and model without touching terminal environment variables.
- Inspect live SQLite counts, database disk size, and memory usage.
- Run **Clear Past Events**, **Clear Scheduled Sessions**, **Optimize & Compact Database** (`VACUUM` + `PRAGMA optimize`), or **Reset All Calendar History & Settings** (which leaves your course folders and saved preferences untouched).

## Run & Build from Source (Windows PowerShell)

### Requirements
- Windows 10 or Windows 11 (x64)
- Java 21+ JDK (including `jpackage`)
- Apache Maven 3.9+
- *(Optional)* WiX Toolset v3 on `PATH` (only needed when passing `-Installer` to build the `.exe` installer locally)

### Clone, Test, and Run
```powershell
git clone https://github.com/kerwinmok/calendar-organizer.git
cd calendar-organizer
mvn test
mvn javafx:run
```

### Package Standalone Bundle or Installer Locally
- **Build portable app-image and `.zip`:**
  ```powershell
  .\scripts\package-windows.ps1
  ```
  Outputs `target\dist\CalendarOrganizer\CalendarOrganizer.exe` and `target\dist\CalendarOrganizer-windows-x64.zip`.
- **Build, install per-user to `%LOCALAPPDATA%\Programs\CalendarOrganizer\`, and create a Desktop shortcut:**
  ```powershell
  .\scripts\package-windows.ps1 -Install -DesktopShortcut
  ```
- **Build the `.exe` installer locally (requires WiX Toolset v3 on `PATH`):**
  ```powershell
  .\scripts\package-windows.ps1 -Installer
  ```

### Publish a New Downloadable GitHub Release
Push a version tag (or run **Actions -> Release Windows App -> Run workflow** on GitHub):
```powershell
git tag v1.0.1
git push origin v1.0.1
```
GitHub Actions builds the Windows `.exe` installer, portable `.zip`, and `SHA256SUMS.txt` on `windows-2025` and attaches them to the release automatically.

## Optional LLM Parsing Configuration

The built-in `RuleParser` handles common schedule phrases offline with zero configuration. You can enable LLM JSON extraction either inside **Settings** in the app, or via PowerShell environment variables:

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
Start Ollama locally, then launch (or set Host/Model in **Settings**):
```powershell
$env:OLLAMA_HOST = "http://localhost:11434"
$env:OLLAMA_MODEL = "llama3.2"
mvn javafx:run
```

## Local Data Storage

All calendar events, recurring rules, goals, preferences, and course markdown folders are stored locally under `$env:USERPROFILE\.calendar-organizer`:
- `calendar.db` — SQLite database for calendar data
- `courses\` — plain markdown files organized as `<COURSE>\lectures\`, `notes\`, and `coursework\`

You can override the data folder via `-Dcalendar.organizer.home=...` or `$env:CALENDAR_ORGANIZER_HOME`.

## Future Implementations (Roadmap)

- **Multi-Device Sync via Self-Hosted Server (Planned — Not Yet Implemented):** Support for syncing course PDFs, markdown notes, and calendar data across separate devices (for example laptop and desktop PC) against a user-operated self-hosted server (such as over a private Tailscale network), keeping files, credentials, and logs off third-party cloud providers. See [SYNC_IDEAS.md](SYNC_IDEAS.md) for architecture options, conflict/delete handling, and local keychain credential notes.
