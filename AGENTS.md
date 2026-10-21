# AGENTS.md — Maestri Remote Android

Native Kotlin + Jetpack Compose client for the **Maestri Wire** v1 protocol (`app.maestri.remote`), targeting parity with the Maestri Remote iOS companion app.

## 1. Toolchain & Build Rules

- **SDK & Gradle**: JDK 21, Gradle 9.5.1, AGP 9.1.1 (built-in Kotlin 2.4.10 + Compose plugin), Compose BOM `2025.11.01`, `minSdk = 29`, `targetSdk = 36`, `compileSdk = 37`.
- **Serialized Build Lock (HARD RULE)**: Multiple agents and Maestri floors share this machine. Before running any Gradle build, acquire the machine-wide directory lock at `/tmp/maestri-build.lock`:
  ```bash
  mkdir /tmp/maestri-build.lock
  trap 'rmdir /tmp/maestri-build.lock 2>/dev/null' EXIT
  ./gradlew :app:assembleDebug
  ```
- **Ground vs. Floor Worktrees**:
  - `./gate.sh` hardcodes `cd ~/Projects/maestri-remote-android`. Run `./gate.sh` **only** when verifying the ground checkout.
  - Inside a Maestri floor worktree (`~/Projects/.maestri/floors/...` or sibling worktrees), always run `./gradlew :app:assembleDebug` from your own worktree root while holding `/tmp/maestri-build.lock`.
- **No Unapproved Dependencies**: Do not add new external libraries to `app/build.gradle.kts` without explicit user approval. JSON parsing uses `org.json`; networking uses `okhttp3`; persistence uses `datastore-preferences` + AndroidKeyStore.

## 2. Architecture & Package Layout

- `app/src/main/java/app/maestri/remote/` — `MainActivity.kt` (root `NavHost`, pairing vs. workspaces switch, `authLost` collector, activity-result request-code ownership), `MaestriApp.kt` (OkHttp singleton + `WireSession`), `NavRestoreStore.kt`.
- `app/src/main/java/app/maestri/wire/` — Wire v1 transport & models:
  - `Pinning.kt`: `PinnedTrustManager` (`SHA-256(SPKI DER) == stored pin`), HTTP/1.1 forced, no CA/hostname check, HTTPS/WSS on port `7434`.
  - `WireClient.kt`: `Authorization: Bearer <token>` on `/api/*`, `probeInfo()`, `pair()`, persistent `401` surfacing.
  - `Models.kt`: Defensive `org.json` snapshot parsing (`opt*` + `!isNull(...)`; unknown enum values fall back gracefully; UUIDs preserved uppercase verbatim).
  - `FeedSocket.kt`: Live feed (`WS /api/feed/stream?ws=…`), `(epoch, sequence)` strict mutation dedupe, full snapshot resync on epoch change, JSON ping/backoff.
  - `TerminalStreamSocket.kt`: Live terminal (`WS /api/terminals/{id}/stream`), first `{type:geometry}` resets emulator, binary frames carry raw PTY bytes into `frameSink`, `input` / `inputBytes` / `resize` (clamped `20..250 × 5..120`).
  - Feature APIs & sockets: `CanvasWrites.kt` (optimistic overlay `CanvasOptimism` keyed by `(epoch, sequence)`), `NotesApi.kt`, `FloorsApi.kt`, `ChatApi.kt`, `RoutinesApi.kt`, `PartiturasApi.kt`, `AgentUsageSocket.kt`, `Presence.kt`, `NotificationsRouter.kt`, `NetworkRecovery.kt`, `SnapshotMedia.kt`.
- `app/src/main/java/app/maestri/store/PairingStore.kt` — DataStore + AndroidKeyStore AES/GCM token encryption, per-workspace camera (`camera.<serverKey>.<wsId>`), per-server Follow-Mac (`followmac.<serverKey>`).
- `app/src/main/java/app/maestri/ui/` — Jetpack Compose screens, navigation, and design tokens:
  - `screens/`: `PairingScreen.kt`, `WorkspacesScreen.kt`, `FeedScreen.kt`, `CanvasScreen.kt`, `CanvasWritesUi.kt`, `TerminalScreen.kt`, `TerminalWebView.kt`, `TerminalKeyRail.kt`, `ChatScreens.kt`, `PartiturasScreen.kt`, `RoutinesScreen.kt`, `UsageScreen.kt`.
  - `notes/`: `NoteEditorScreen.kt`, `FicharioTabRail.kt`, `NotesDrafts.kt`.
  - `settings/`: `SettingsScreen.kt`, `LockGate.kt`, `AppLockStore.kt`, `AgentSettingsStore.kt`, `TerminalFontStore.kt`, `TerminalScrollSpeedStore.kt`, `TerminalViewModeStore.kt`.
  - `Style/`: `Tokens.kt`, `MaestriColors.kt`, `Type.kt`, `Radii.kt`, `Depth.kt`, `Chrome.kt`, `GlyphMapper.kt`, `GlyphButtons.kt`, `TerminalThemes.kt`.
- `app/src/main/assets/terminal/` — Offline vendored `xterm.js` + `xterm-fit.js` + `index.html`. The WebView never sees the auth token; bytes arrive via `TerminalStreamSocket.frameSink`. Touch scrolling must use `term.scrollLines(..., 1)` (xterm buffer path), never raw DOM `scrollTop` mutation.
- `app/src/main/java/app/maestri/live/` — Pi Relay live session client, composer, attachments, and approvals.
- `app/src/main/java/app/maestri/update/` — Self-update checker against GitHub Releases `latest.json` (`versionCode > BuildConfig.VERSION_CODE` + SHA-256 verification).

## 3. Device Verification & QA Discipline

- **Android CLI First**: Use `~/.local/bin/android` (`android-cli` skill) as the default entry point for SDK/AVD/device inspection, and `adb` for md5 attestation, logcat forensics, and raw input injection.
- **Explicit Device Serial (`-s`)**: Multiple devices may be attached simultaneously (e.g., Redmi tablet `rkb66p7xaahatk89`, OPPO Reno14 5G `CPH2737` over wireless TLS, or `Pixel_8` AVD). **Never run a bare `adb` command** — always pass `adb -s <serial>`.
- **Artifact Attestation**: A green build is not proof of behavior. Attest the `md5` of the built APK against the installed package before running UI/device checks, and capture before/after screenshots or hierarchy dumps as evidence.
- **Production Safety**: Never unpair a paired device, never mutate or delete production workspaces/terminals/notes on the host during testing — create and tear down a disposable sandbox workspace (`404`-verified after cleanup).

## 4. Git, Remotes & Release Pipeline

- **Remote Topology Trap (`github` vs `origin`)**:
  - `github` (`https://github.com/Jardelvorpagel/maestri-remote-android.git`) is the **real GitHub network remote**.
  - `origin` points to the local filesystem path (`/Users/jardelvorpagel/Projects/maestri-remote-android`). Pushing to or running `ls-remote` against `origin` does **not** touch GitHub. Always push upstream to `github` (`git push github main`).
- **Commit & Push Discipline**:
  - Commit or push only when explicitly requested by the user.
  - Never include ticket numbers or AI co-authorship/attribution in commit messages.
  - Keep code self-documenting without inline comments unless documenting a non-obvious protocol or platform invariant.
- **CI Release Flow**: Pushing to `main` on `github` triggers `.github/workflows/release.yml`, which derives `versionCode` (`git rev-list --count HEAD`) and `versionName` (`0.2.1-<short-sha>`), builds a signed release APK, and publishes `latest.json` + APK to GitHub Releases.
