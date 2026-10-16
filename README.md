# Maestri Remote — Android client (MVP)

A native Kotlin + Jetpack Compose client for **Maestri Wire** protocol v1 — the
vendor-documented, open protocol the Maestri Remote iPhone app speaks. This is a
sanctioned second client.

## What works

- **Pairing** (Pairing screen):
  1. Enter host[:port] and a device name.
  2. *Advanced* ▸ paste an existing token from a device you already trust (the
     "Pi bridge"-style token-reuse path used for the MVP test) — or pair with a fresh
     6-digit code from the host's Settings → Wire → Pareamento (`POST /pair {code}`).
  3. First connection shows the **computed SPKI SHA-256** (hex, colon-separated, plus
     base64) next to the probe-reported host name / capabilities / protocol version —
     explicit confirmation is required (the docs' "confirm the computer's security key").
  4. `{token, deviceId, role, pin}` is persisted in **DataStore**, token encrypted with an
     **AndroidKeyStore AES/GCM** key.
- **Workspaces** (Workspaces screen): `GET /api/workspaces`, 15 s poll + pull-to-refresh;
  shows running/terminal counts, **red attention badge** when `attentionCount > 0`, green
  activity dot when `hasActivity`; tap opens the feed.
- **Feed** (Feed screen): `WS /api/feed/stream?ws=…` — live `FeedSnapshot`; terminal cards
  (icon, name, floor · state, needsAttention dot, `isLive` dot, `pendingPrompt` badge,
  preview lines in monospace), note items (title + description), floor chips with the
  current `floorStatus` line, connection state chip. Public **PendingPromptCard** with
  Approve / Reject (`POST …/approve | /reject`).
- **Terminal detail** (Terminal screen): marks `seen` on open, Preview (snapshot lines) vs
  **Live** segmented view, prompt composer (`POST …/prompt {text}`), resize dialog.
- **Live terminal** (stretch, done): `WS /api/terminals/{id}/stream` — first message is
  `{type:geometry}` → emulator reset; binary frames are raw PTY bytes; input via
  `{"type":"input"}` text frames + `{"type":"inputBytes"}` for control bytes; resize
  frames in 20…250 × 5…120. The mini-emulator (`MiniTerm.kt`) is a small ANSI-lite
  interpreter (cursor movement, erase, newline handling; SGR sequences are consumed, not
  rendered — styling is out of MVP scope). Plain-text renderer with 2× scrollback
  and quick keys (↵ ⌃C ⌃D ↑ ↓ ⇥ ⎋). Degrades to preview text if the stream can't connect.

## Architecture

```
app/
├── MainActivity.kt              ← Root: pairing-vs-workspaces switch + NavController
│                                   (workspaces → feed/{wsId}/{wsName} → terminal/…),
│                                   authLost collector forces re-pair UX
├── MaestriApp.kt                ← OkHttp singleton + WireSession construction
├── wire/
│   ├── Pinning.kt               ← PinnedTrustManager (SHA-256(SPKI DER) == pin),
│   │                              HTTP/1.1 forced, always-true hostname verifier
│   ├── Models.kt                ← defensive snapshot parsing (org.json)
│   ├── WireClient.kt            ← Bearer auth on every /api call; 401 surfacing;
│   │                              companion funs probeInfo() + pair()
│   ├── FeedSocket.kt            ← live feed: seed/apply mutations, (epoch, sequence)
│   │                              dedupe, epoch-change resync hook, ping, backoff
│   ├── TerminalStreamSocket.kt  ← geometry + ANSI binary frames → StateFlows,
│   │                              input/inputBytes/resize writers
│   ├── MiniTerm.kt              ← ANSI-lite screen emulator (plain text)
│   └── session/WireSession.kt   ← pairing state + sockets lifecycle (clientProvider
│                                  lambda so sockets pin the certificate lazily,
│                                  after pairing is known)
├── store/PairingStore.kt        ← DataStore + Keystore AES/GCM token encryption
└── ui/
    ├── IconGlyph.kt             ← SF-Symbol names → neutral glyph (emoji pass)
    └── screens/ PairingScreen · WorkspacesScreen · FeedScreen · TerminalScreen ·
                  LiveTerminalView
```

## Protocol decisions (per contract)

- **Trust model = pinning**, not CAs: a custom `X509TrustManager` accepts a chain only if
  `SHA-256(SPKI DER) == stored pin`. No CA validation, no hostname check. HTTPS/WSS on
  port 7434, HTTP/1.1.
- **Auth**: `Authorization: Bearer <token>` on every `/api/` request; WS subscribes with
  `?token=…`. A persistent **401** closes sockets and returns to the pairing screen
  (token revoked/forgotten on the host).
- **`/api/info` gates**: `protocolVersion == 1` checked at probe; optional features gated
  on `capabilities` (e.g. `terminalStreaming`, `terminalInputBytes`; never probed for).
- **IDs & enums**: UUIDs repeated verbatim (uppercase); unknown enum values fall back to
  "some other kind" instead of erroring; optional fields are absent, never null.
- **Mutation dedupe**: feed mutations are applied only when `(epoch, sequence)` is
  strictly newer than the last applied per key; a **new epoch** triggers a full resync
  from a fresh snapshot. Unknown mutation types are ignored.
- **Terminal stream**: `{type:geometry}` always resets the emulator; binary frames append
  raw PTY bytes; input is sent as `input` (UTF-8 JSON) or `inputBytes` (base64 control
  bytes); geometry honored via resize frames within 20…250 × 5…120.

## Build & run

Requires JDK 21 and the Android SDK (`~/Library/Android/sdk`, platforms android-36/37,
build-tools 36.0.0). AGP 9.1.1 with built-in Kotlin 2.4.10 + compose plugin;
compose-bom 2025.11.01; **minSdk 29, targetSdk 36, compileSdk 37** (androidx
core-ktx 1.19 / lifecycle 2.11 require compileSdk 37; runtime target stays 36).

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Pixel_8 AVD smoke route used for the MVP test: Pairing (host `10.0.2.2:7434` from the
emulator, Advanced → token) → confirm key → Workspaces → Feed → Terminal → Live; a
disposable sandbox workspace with a `cat` terminal served as the prompt/live-echo target
and was deleted from the host afterwards (production terminals untouched).

## Stretch / not done

- ANSI **styling** (SGR colors) is parsed but not rendered; renderer is plain text.
- No `terminalChat` surfaces, no note/file-tree writes, no presence — MVP scope.
- No landscape/tablet layouts, no dark/light switch (dark-only theme).

## Canvas v1 (read-only)

A spatial mirror of one floor's canvas, reachable from the Feed screen header
("Canvas" button → route `canvas/{wsId}/{wsName}`; the existing live FeedSocket keeps
streaming while you are there, so the canvas re-renders on every fresh snapshot and
survives epoch resyncs unchanged — mutation events are attribution-only and carry no
canvas state, so the full snapshot is the sole source of truth).

- **Parsing**: `canvas` rides defensively on `FeedSnapshot` in `wire/Models.kt`
  (`org.json`, `opt*` only): nodes `{id (UUID verbatim), frame, zIndex, isNodeLocked,
  kind, title, subtitle?, icon?, color? (#RRGGBB verbatim), groupId?}` with the
  `terminal` payload parsed into the same `TerminalCard` used by items; connections
  `{id, from/toNodeId, kind, isActive, points (verbatim edge-to-edge polyline)}`;
  host viewport `origin`/`zoom` kept but informative; `cableTies`/`groups`/`drawings`
  ignored gracefully (absent = survive). Unknown node kinds render as a simple titled
  box per the vendor docs.
- **Rendering**: fullscreen pan (one finger) + pinch zoom; fit-to-bounds at open
  (host `canvas.bounds`, else union of node frames; host-style zoom range 0.25–4×).
  All transform state lives in ONE deferred-reading `graphicsLayer` — gestures never
  recompose node content. Nodes: rounded cards in zIndex order reusing the feed card
  styling language (icon glyph via `iconGlyph`, color accent border, floor chip,
  isLive/needsAttention/pendingPrompt dots, manager tag, preview lines). Connections:
  polylines behind the nodes (active ropes emphasized with a halo; endpoints fall back
  to node center lines only when `points` is missing).
- **Note**: `detectTransformGestures` no longer exists in Compose foundation 1.10, so
  the pan/pinch detector is a small custom `awaitEachGesture` implementation (touch-slop
  gated, consumes position changes when active so card taps stay clean).
- **Integration**: tapping a terminal node navigates into the EXISTING terminal detail
  route (seen-marking, preview/live) — no stubs. No new dependencies (Compose + org.json).
