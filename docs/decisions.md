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
