# Future Deployment Login & Onboarding Mockup (`dev` branch)

> Status: UI & onboarding mockup on `dev`. Production direct sync (`notebook.googleapis.com/v1` with user-supplied Google Cloud Desktop OAuth credentials) lives on `main`.

## Goal for Future Public Releases

End users downloading Calendar Organizer should never have to visit Google Cloud Console, enable `notebook.googleapis.com`, or import a `client_secret_*.json` file. Instead, on first launch they see a clean **Connect Gemini Notebook** prompt (`NotebookDeploymentMockupDialog`) and finish connecting with one Google sign-in click.

## Mockup Flow Implemented on `dev`

1. **Step 1 — First-Launch Welcome Prompt (`NotebookDeploymentMockupDialog`)**:
   - Presents the value proposition and local-first privacy guarantees (`calendar.db` and `courses/` stay local unless the user explicitly connects).
   - Includes **Don't ask on startup**, **Not Now**, and **Continue with Google**.
2. **Step 2 — Simulated Google Sign-In & Consent Screen**:
   - Displays the exact OAuth scopes requested (`https://www.googleapis.com/auth/notebook` and email identity) with an editable demo account email.
3. **Step 3 — Content & Auto-Sync Picker**:
   - Populates checkboxes dynamically from the user's real local courses (`CourseManager.listCourses()`, falling back to sample courses if empty), plus **Weekly Calendar Schedule** and **Auto-sync on file drop / note save**.
4. **Step 4 — Animated Sync & Connected State**:
   - Simulates notebook creation and upload progress, persists onboarding state via `NotebookMockupPreferences` in SQLite `preferences`, updates the top-bar status pill (`Notebook: Demo`), and supports **Reset Demo**.

## Swapping in Real Production OAuth Before Shipping

When ready to publish an app-wide Google OAuth client for end users:

1. **Google Cloud & OAuth Consent Verification**:
   - Register the Desktop OAuth Client under your verified Google Cloud project (with the **Gemini Notebook API** enabled).
   - Complete Google OAuth app verification for the `https://www.googleapis.com/auth/notebook` scope so end users do not hit an "Unverified app" warning screen.
2. **Client Credential Distribution**:
   - Google treats Desktop ("Installed App") OAuth `client_id` values with PKCE (`S256`) as public clients. If Google requires omitting `client_secret` from the desktop binary or rotating credentials cleanly, pair the desktop PKCE loopback flow (`GeminiNotebookOAuth`) with a tiny stateless token-exchange proxy endpoint rather than embedding long-lived secrets in releases.
3. **One-Method Replacement**:
   - Replace Step 2's **"Allow & Connect"** transition in `NotebookDeploymentMockupDialog` with `GeminiNotebookSyncService.signInInteractive(cancelFlag)`, and replace Step 4's simulated `Timeline` with real `AppExecutors.ioExecutor()` calls to `syncSchedule` and `syncCourse` for each checked course.
