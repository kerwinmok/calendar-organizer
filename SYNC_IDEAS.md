# Multi-Device Sync Design Ideas (Proposal Only)

> Status: Design notes only — not implemented yet. Waiting on a choice of approach before writing code.

## Goal

If you drag `Lesson 21.pdf` (or edit notes or add events) into Calendar Organizer on your laptop, then later open the app on your desktop while online, the desktop downloads `Lesson 21.pdf` and shows the same course notes and the same calendar.

## Privacy & Local-First Boundary

**What must stay strictly on your own machines (never synced, never checked into git):**
- Passwords, OAuth tokens, and API keys (including the optional Gemini API key currently stored in SQLite `preferences`).
- Application logs, crash dumps, and temporary PDF render buffers.
- Local SQLite database/cache files (`calendar.db`, `calendar.db-wal`, `calendar.db-shm`) and machine-local settings (such as a local Ollama endpoint or window geometry).

**What cannot be strictly local (stated plainly):**
1. **Async sync when your laptop is asleep or closed:** If your laptop is off when your desktop comes online, *something* has to stay reachable as a relay/mailbox — either a cloud drive/hosted backend run by a third party (Options B or D) or an always-on machine you operate (Option C or a third always-on Syncthing peer). Pure two-laptop P2P only transfers files when both devices are online at the same time.
2. **Third-party storage in Option B / D:** Dropping unencrypted files into OneDrive, Google Drive, Dropbox, Supabase, or Firebase means that provider holds copies of your PDFs, markdown notes, and calendar JSON. (For Option B, you can mitigate this with client-side encryption such as Cryptomator around the synced folder, at the cost of extra unlock friction.)
3. **Coordination plane:** Syncthing can run with global discovery & relays turned off over LAN/Tailscale; Tailscale itself uses a third-party coordination server unless you also self-host Headscale.

---

## Option Comparison

| Option | Privacy / Locality | Async When Laptop Is Asleep? | Setup Effort | Ongoing Cost |
| :--- | :--- | :--- | :--- | :--- |
| **a) Peer-to-peer sync (Syncthing)** | Highest (device-to-device TLS, zero cloud storage; relay/discovery optional) | Only if you add a 3rd always-on device (e.g. Raspberry Pi / NAS / always-on desktop) | Medium (install Syncthing on each computer + pair device IDs once) | \$0 |
| **b) Cloud drive folder (OneDrive / Google Drive / Dropbox) + plain files** | Medium (provider stores files unless wrapped in Cryptomator; zero cloud creds in app) | Yes | Low (point data folder inside your existing synced drive) | \$0 within existing free/student quota |
| **c) Self-hosted server over Tailscale** | High (data stays on hardware you control; WireGuard mesh, no public ports) | Yes (server/VPS is always online) | High (maintain server service, TLS/auth, backups, Tailscale node) | \$0 on home hardware (electricity) or ~\$4–\$6/mo VPS |
| **d) Hosted backend (Supabase / Firebase) + auth** | Lowest (third party stores calendar rows, notes, PDFs, and auth records) | Yes (plus potential web/mobile clients later) | Very high (auth flow, object storage, row-level security rules, offline queue) | \$0 free tier, then storage/egress billing as PDFs grow |

### Detailed Tradeoffs

#### a) Peer-to-Peer Folder Sync (e.g. Syncthing)
- **Pros:** No cloud account, no storage quotas, end-to-end TLS between your own devices, handles large lecture PDFs cleanly with block-level delta transfers, and requires zero sync protocol or credential code inside Calendar Organizer.
- **Cons:** Both computers must be awake and online at the same time to converge (unless you add a cheap third always-on peer). Non-technical users still need to install and pair Syncthing once per machine.
- **Setup effort:** Medium (one-time desktop install + folder share acceptance).
- **Cost:** \$0.

#### b) Cloud Drive Folder You Already Have (OneDrive, Google Drive, Dropbox) with Plain Files
- **Pros:** True async sync even if your laptop closes seconds after dropping `Lesson 21.pdf`. Almost zero code complexity in Calendar Organizer because OS drive clients handle uploads, downloads, offline queueing, retries, and account login outside the app. All data stays human-readable (`courses/.../*.md`, `.pdf`, `.json`/`.ics`).
- **Cons:** The cloud provider hosts your files unless encrypted client-side. On Windows, "Files On-Demand" / smart-sync stubs can delay reads or trigger errors unless the folder is pinned as **"Always keep on this device"**. File watchers inside the app must debounce partial writes while the sync client updates files.
- **Setup effort:** Very low (install/sign into OneDrive/Drive/Dropbox if not already active; select a synced folder path).
- **Cost:** \$0 if your PDF collection fits in existing storage.

#### c) Small Self-Hosted Server Over a Private Network (e.g. Tailscale)
- **Pros:** Full control of storage on a Raspberry Pi, home server, or private VPS without exposing ports to the public internet. Supports async sync cleanly.
- **Cons:** Heavy operational burden compared to Options A/B: requires writing or deploying a sync API/WebDAV/S3 server, TLS/token management, server uptime monitoring, and backups. If you rent a VPS or use hosted Tailscale control, an external provider is still involved for hosting or mesh coordination.
- **Setup effort:** High.
- **Cost:** \$0 hardware reuse or ~\$4–\$6/month for a small VPS.

#### d) Hosted Backend (e.g. Supabase or Firebase) with Authentication
- **Pros:** Instant multi-device state without desktop folder tools; opens the door to phone/browser access later.
- **Cons:** Highest development and maintenance complexity (OAuth/password login, refresh tokens, S3/bucket uploads for PDFs, conflict resolution, offline queue in SQLite, security rules). Third party hosts all user data and accounts, and large slide decks quickly consume object-storage/egress caps.
- **Setup effort:** Very high.
- **Cost:** \$0 free tier initially; paid tiers typically start around \$20–\$25/month if usage exceeds quotas.

---

## Deep Dive: Top Two Options (Option B & Option A)

Option B (Cloud Drive + Plain Files) and Option A (Syncthing) both synchronize a plain folder on disk. That means **both can share the exact same plain-file workspace format inside Calendar Organizer**.

### 1. Why Current `calendar.db` Cannot Be Folder-Synced As-Is
Today, `%USERPROFILE%\.calendar-organizer\calendar.db` stores events, recurring rules, goals, and preferences — including `gemini_api_key` — in one binary SQLite WAL database (`calendar.db`, `-wal`, `-shm`). Syncing a live SQLite database across OneDrive or Syncthing risks corrupting the WAL log or producing irreconcilable `calendar (conflicted copy).db` files whenever two machines run at once, and it would also leak the locally saved API key into the synced folder.

To support either Option A or Option B safely, Calendar Organizer only needs to treat plain files in the synced folder as the source of truth and keep SQLite purely as a machine-local index/cache.

### 2. Proposed Data Layout

```text
<SyncedWorkspace>/                          # Inside OneDrive / Google Drive / Syncthing folder
├── sync-meta.json                          # Schema version
├── calendar/
│   ├── events/
│   │   └── <uid>.json                      # One small JSON file per CalendarEvent (with updatedAt + deviceId)
│   ├── recurring-rules.json                # Recurring rules array (per-rule UUID + updatedAt)
│   ├── goals.json                          # Workout & study goals
│   ├── schedule-prefs.json                 # Sleep window preferences (shareable across machines)
│   └── deleted/
│       └── <uid>.json                      # Tombstones { uid, deletedAt, deviceId } (pruned after 90 days)
└── courses/
    └── <COURSE-FOLDER>/                    # Already plain folders today (e.g. CMPT-201/)
        ├── lectures/                       # Lesson 21.pdf, slides (.pptx), etc.
        ├── notes/                          # Plain .md notes
        └── coursework/                     # Assignments, rubrics, notes

%LOCALAPPDATA%\CalendarOrganizer\           # Strictly machine-local (NEVER synced)
├── cache/
│   └── calendar.db                         # Rebuilt/reconciled from <SyncedWorkspace>/calendar/
├── device.json                             # Random per-machine deviceId, local UI/Ollama preferences
├── logs/                                   # Diagnostic logs
└── tmp/                                    # PDFBox temp buffers & export staging
```

### 3. Conflict Handling (Two Computers Editing Offline)
- **Markdown notes (`*.md`):** Never overwrite silently. If both machines edit `notes/01-intro.md` offline, Syncthing/cloud clients automatically preserve the remote version as a sibling conflict file (e.g. `01-intro (Laptop conflicted copy).md`). Calendar Organizer should also inspect mtime/hash before saving, write a sibling `.conflict-<device>-<timestamp>.md` if the file changed underneath it on disk, and surface a subtle banner listing conflict files so you can review and remove the duplicate.
- **Calendar events (`events/<uid>.json`):** Storing **one JSON file per event UID** instead of one giant `calendar.json` eliminates almost all cross-event conflicts: adding an event on Laptop and another on Desktop creates two distinct files that merge automatically. If the *same* event UID is edited offline on both computers, resolve deterministically on load using Last-Write-Wins by UTC `updatedAt` (with `deviceId` as deterministic tie-breaker).
- **PDFs & lecture files:** Treated as immutable blobs using the existing collision-safe import naming (`lesson-21.pdf`, `lesson-21-2.pdf`). No in-place binary edits occur.
- **Note for Windows Cloud Drives:** Prompt or document pinning `<SyncedWorkspace>` as **"Always keep on this device"** (OneDrive/Dropbox) so PDFs such as `Lesson 21.pdf` are proactively downloaded onto the second machine rather than sitting as zero-byte online placeholders.

### 4. Delete Sync
- **Course files & notes:** Deleted normally via the OS filesystem (or moved to Recycle Bin/Trash), which OneDrive and Syncthing propagate as deletions to online peers.
- **Calendar events:** Deleting `<uid>.json` alone is unsafe if an offline computer reconnects and re-emits its older local copy. Instead, deletion removes `events/<uid>.json` **and** writes a tiny tombstone file `calendar/deleted/<uid>.json` recording `{ "uid": "...", "deletedAt": "<UTC ISO-8601>", "deviceId": "..." }`. Any machine reconciling its local SQLite cache sees the tombstone timestamp beats its cached event's `updatedAt`, removes the event locally, and prunes tombstones older than 90 days.

### 5. Credential Storage (Zero Secrets in Sync or Git)
- **AI API keys (`gemini_api_key`, any future tokens):** Move out of SQLite `preferences` into the **OS keychain** — on Windows, **Windows Credential Manager** (`CredWriteW` / `CredReadW` / `CredDeleteW` via JNA `Advapi32` already in the project, or user-scoped DPAPI fallback), and Keychain / Secret Service on other OSes. Keys never touch disk inside `<SyncedWorkspace>`, never appear in logs, and never enter git.
- **Sync transport credentials:** Because Options A and B delegate syncing to Syncthing or your desktop cloud-drive client, Calendar Organizer never prompts for, transmits, or stores Google/Microsoft/Dropbox passwords or OAuth tokens at all.

---

## Recommendation

1. **First, adopt the decoupled plain-file workspace + local SQLite cache + Windows Credential Manager key storage** shown above (useful regardless of transport, and protects your API key immediately).
2. **Use Option B (your existing OneDrive / Google Drive / Dropbox folder with plain files)** if you want **effortless async sync** when dragging `Lesson 21.pdf` on a laptop right before closing the lid, zero server maintenance, and zero sync-auth code in the app.
3. **Pick Option A (Syncthing)** instead if **never storing course PDFs or calendar data on any cloud provider** is more important than offline/asleep relay convenience — because both options use the exact same plain-file folder layout, switching between Option B and Option A later is just changing the workspace folder path in Settings.

Let me know which option you want to go with before we implement anything.
