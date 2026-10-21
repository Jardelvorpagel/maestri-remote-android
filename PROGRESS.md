# PROGRESS — Maestri Remote Android client

- 2026-10-16 02:20 — Contract studied: wire-protocol-brief.md + vendor docs + wire-probe.py verified; JSON shapes locked in (FeedItem kinds, TerminalCard, mutations, terminal stream frames).
- 2026-10-16 02:21 — Toolchain verified: JDK 21, build-tools 36.0.0, platforms android-36/37, Gradle 9.5.1, Pixel_8 AVD exists.
- 2026-10-16 02:22 — Scaffolded Kotlin/Compose project: AGP 9.1.1 (built-in Kotlin) + compose BOM, wrapper bootstrapped, minSdk 29 / targetSdk 36.
- 2026-10-16 02:31 — `./gradlew assembleDebug` GREEN (app-debug.apk). Wire layer: SPKI pin TrustManager, OrgJSON defensive models, WireClient (HTTP/1.1, Bearer, 401 surfacing), FeedSocket (epoch/sequence dedupe + epoch resync + JSON ping + backoff), TerminalStreamSocket, MiniTerm ANSI-lite emulator, Keystore-encrypted token in DataStore.
- 2026-10-16 02:44 — Pixel_8 AVD e2e vs live host (10.0.2.2:7434): token reuse → SPKI SHA-256 shown & matched pin → trust → Workspaces with real attention badges (Knowledge/Protector fleet + activity dot) → Feed "Android MVP test" live (epoch FB39B082, floor chips incl. Térreo status line, real preview lines) → Terminal mvp-cat: prompt round-trip echoed in live feed snapshot; Live view: geometry 75×23 rendered, typed "live-echo" + ⏎ chip → echo on screen r7c10. No crashes.
- 2026-10-16 02:51 — Post-test polish: SF-Symbol icon names → neutral glyph helper; authLost now closes sockets + forces re-pair UX ("Forget this server" path); rebuilt + reinstalled clean.
- 2026-10-16 02:56 — Sandbox cleanup: test workspace deleted via DELETE /api/workspaces/{ws} (owner), list updated live in app; production workspaces/terminals untouched. Stretch (live terminal) done; README.md written.
- STATUS: Definition of done MET (assembleDebug green; installs on Pixel_8; pairs/reuses token; live workspaces + feed; prompt to own test terminal; 401→re-pair path). SGR styling parsed but not rendered (renderer is plain text) — documented in README.
- 2026-10-21 — Device QA of the fichário wiring (floor Notes worker Sonde, release v0.2.1-cd96973 / versionCode 165, sha256 26802e9d…): all 8 wiring assertions (a)–(h) PASS on the tablet, incl. the review-blocker regression test — 130px long-press drag on a rail tab reordered the stack (host feed-verified) while the card bounds stayed `[1669,368][2091,913]` and no selection fired; both sandboxes torn down (404-verified), production feeds byte-identical to baseline. Evidence: `~/knowledge/sources/evidence/2026-10-21-notes-lane-device/` (45 files) + report `~/knowledge/sources/2026-10-21-notes-lane-device.md`. New known issues (3): rail drag threshold divides px by the dp constant 64 (`FicharioTabRail.kt:186` — <64px drags are silent no-ops, full tab width over-shoots 2 positions; fix = divide by `RAIL_TAB_WIDTH_DP.dp.roundToPx()`); canvas header node count stays stale until the canvas is re-entered after "New Fichário" (createFromSelection refresh path; (e)/(f) refreshed live); the release pipeline published the tag v0.2.1-cd96973 twice with different APK bytes (versionCode uniqueness not enforced).

## Known issues (not fixed) — from the QA sweep, 2026-10-16

Deliberately out of scope for the B1+B2 corrective wave; recorded so they are not lost.

1. **Explicit JSON `null`s render as the text "null"** — against a server that sends explicit
   `null` for optional fields the UI shows e.g. `mock-term · null` and
   `1/1 running · loaded · null`. The real host omits optional fields (per the wire contract),
   so this only bites against a non-conforming server; `WorkspaceMeta.parse` /
   `TerminalCard.parse` use `has()` without `isNull()`.
2. **Floor `statusProgress` is parsed but never displayed** — floor chips show state + text only.
3. **`terminalInputBytes` is never used** — every quick key goes out as a UTF-8 `input` text
   frame, never the protocol's raw-byte path (works on this host, but bypasses the contract).
4. **A feed re-subscribe on failure is silent** — `QAFeed onFailure: null` was observed
   reconnecting cleanly with a fresh snapshot in the same epoch (no resync storm); nothing is
   surfaced to the user while it happens.
5. **True mid-stream WS kill is untested** — the live pane stayed usable after the host
   terminal exited, but the socket itself stayed open, so the degrade-to-preview path is not
   directly observed.
6. **A live `pendingPrompt` cannot be produced for testing** — the host raises S/n only for a
   real agent prompt, so the Approve/Reject happy path is only verifiable against a mock.

## PHASE 0b — quick wins (branch `feature/presence-camera`, worktree `~/Projects/mra-parity-p0b`, base `ddce776`)

- **Persisted camera per workspace** — `camera.<serverKey>.<wsId>` = `"x,y,zoom"` in the pairing DataStore (iOS `remote.camera.<serverId>.<wsId>` shape); restored on canvas open (restore beats the one-shot fit), Fit recenters **and persists**, gestures write debounced to ≤2/s, never to the host. Verified on the tablet across an app restart.
- **Follow-Mac toggle** — canvas chip (`⚲ Follow Mac` / `⚲ Following`), persisted as `followmac.<serverKey>.<wsId>` = `true|false`; while on the viewport mirrors the host `origin`/`zoom` from the snapshot and pan/pinch are hidden; one tap off keeps the current pose and persists it. Verified: badge `zoom 100% · following Mac` vs our fit 25%.
- **Presence cursors** — `wire/Presence.kt` (`PresencePeer`, 20 s staleness); self report recorded by the feed socket, peers parsed tolerantly; rendered inside the camera layer (pointer + device-name pill, 5 s fade). Canvas now reports its viewport centre as the documented `point` presence form on camera settle (debounced 1.5 s) and clears it on exit. Verified: visible → gone after 26 s idle → back after a fresh report. Peers cannot render today (the contract sends no peer roster — presence is client → server only).
- **MaestroRequest banner** — feed banner (title + message + `Open terminal`) with **Acknowledge disabled and "(awaiting schema)"**: the contract defines no acknowledge route (`POST …/seen` is the closest documented attention-clearing route). Not visually verified — a real `maestroRequest` needs a Maestro to ask the person (production write, out of bounds) and the live host had none standing.
- **Diff:** 5 files modified + `wire/Presence.kt` new, +354/−10. **Not committed** (staged in the worktree; `main` untouched).
- **Known issue found (not fixed):** the app draws edge-to-edge without inset padding, so its top chrome (Feed's **Canvas** button, canvas header chips) sits under the system status bar; taps there are swallowed by the status bar on both the AVD and the tablet.
- **STEER (post-gate) — insets fix:** app now applies `WindowInsets.statusBars` as top padding on the top chrome of Workspaces / Feed / Canvas / Terminal (`statusBarsPadding()` in `WorkspacesScreen.kt:105`, `FeedScreen.kt:109`, `CanvasScreen.kt:253`, `TerminalScreen.kt:180`); the immersive dark background is untouched (padding only). Tablet status-bar inset measured at 70 px and every header moved exactly +70 px (workspace card y 545→615, Feed Canvas button 59→129, canvas Follow chip 45→115, terminal Back 59→129) — direct `input tap` now drives the Canvas button and the canvas/terminal chips (previously dead zone).
- **STEER (post-gate) — followMac per SERVER (iOS parity):** key changed to `followmac.<serverKey>` (no workspace segment) in `PairingStore.kt:170-190`; camera stays per workspace (`camera.<serverKey>.<wsId>`). Verified: turning Follow on in one workspace's canvas shows "Following" already in another workspace's canvas (Knowledge), and it survives an app restart (`followmac…=true` in DataStore); only one followmac key exists next to the per-ws camera keys. The old per-workspace followmac entry from the previous build remains as an unread leftover.

## P1a — Canvas writes (branch `feature/canvas-writes`)

- **Write layer** `wire/CanvasWrites.kt`: route paths + request bodies for every write recovered from
  the host schemas (node create/frame/rename/lock/duplicate/text/delete, batch delete, connections
  create+delete, groups create/rename/color/ungroup, ties create/move/delete, drawings create/delete),
  plus `CanvasOptimism` — the optimistic overlay keyed by `(epoch, sequence)` that drops every
  override on a new epoch and never re-sends an ambiguous write (`mutationId` is correlation only).
- **Snapshot model**: `drawings` and `cableTies` are now parsed (they were deliberately ignored in
  v1); `MutationEvent` carries `nodeId`, and the feed exposes the last mutation event so the canvas
  can retire a pending write when the host echoes its `mutationId`.
- **Canvas UI** (`CanvasWritesUi.kt` + `CanvasScreen.kt`): one gesture machine for the whole canvas —
  hold (platform long-press wait) then drag moves a node, hold then drag on empty marquee-selects,
  hold without moving opens the node options, a quick drag pans (two fingers pan+pinch), tap selects
  (terminal opens, text opens the inline editor), pen mode draws with one finger and yields to two.
  Node moves are optimistic, snapped to the 20-pt grid on commit; a locked node refuses the drag with
  the iOS sentence. Create menu with 2-step placement (note/text/folder), rename/lock/duplicate/
  connect/disconnect/delete menus, batch delete for a multi-selection, group name/colour/ungroup,
  tie create (two rope taps) + knot drag + cut, pen draw + erase-last.
- **Strings**: `ui/CanvasStrings.kt` holds 97 literals from the parity table verbatim (canvas_menu,
  node_actions, drawings, color_vocab, accessibility_hints).
- **PairingScreen** got the same `statusBarsPadding()` as the other four screens.
- `assembleDebug` green. No tablet install from this lane (the gate does that), no writes to any
  production workspace, and no commits to `main`.
- 2026-10-20 — Terminal face promoted to the xterm.js WebView (`assets/terminal/` + `TerminalWebView.kt`), `LiveTerminalView`/`MiniTerm`/termlib removed, socket byte tap (`frameSink`) with owner-scoped lifecycle, page boot gated on the first real layout size, geometry resets the page buffer.
- 2026-10-21 — P1b notes lane wired (the merger stage of P1B-INTEGRATION.md): `note/{wsId}/{nodeId}` route in the NavHost, note tap + "Edit note" menu open the editor (`onOpenNote`), `noteStack` cards render `FicharioTabRailInline` in both Stack and Board modes, and the node menu gains Add/Remove from Fichário (per-stack, filing capability), New Fichário (createFromSelection) and New Page — typed `CanvasNode.note`/`noteStack` payloads (no raw JSON), `key(node.id)` on the card loops, board gesture machine bails when the rail consumed the down. Review round (source-proven blocker: blanket Main-pass consumption canceled the rail's own taps/drag) fixed to a down-only consumer. Floor "Notes" (Scribe, Prism, Vellum), reports `~/knowledge/sources/2026-10-21-notes-lane-*.md`.
- 2026-10-22 — Release pipeline CI-only: `version.properties` + `release.yml` (push main → APK assinado + `latest.json` em GitHub Release); `build.gradle.kts` lê versão com fallback local e expõe BuildConfig; sem tocar em update/, manifest, res ou telas.
