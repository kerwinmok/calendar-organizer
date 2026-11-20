# calendar-organizer

A local-first Windows desktop application for building weekly schedules from plain language or `.ics` calendars and organizing university courses as plain markdown files with clean study exports.

## Requirements
- Windows 10/11 (x64)
- Java 21 (with `jpackage` included)
- Maven 3.9+

## Build and Test (PowerShell)
```powershell
mvn test
```

## Run the App (PowerShell)
```powershell
mvn javafx:run
```

## Optional LLM Configuration (PowerShell)
By default, the built-in rule parser works offline with no external model. To use an LLM for complex sentences:

### Gemini API
```powershell
$env:GEMINI_API_KEY = "your-api-key"
mvn javafx:run
```

### Local Ollama
```powershell
$env:OLLAMA_HOST = "http://localhost:11434"
$env:OLLAMA_MODEL = "llama3.2"
mvn javafx:run
```

## Local Data Location
By default, data is stored in `$env:USERPROFILE\.calendar-organizer`:
- `calendar.db` (SQLite calendar data)
- `courses\` (plain markdown course folders)
