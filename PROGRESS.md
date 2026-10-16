# PROGRESS — Maestri Remote Android client

- 2026-10-16 02:20 — Contract studied: wire-protocol-brief.md + vendor docs + wire-probe.py verified; JSON shapes locked in (FeedItem kinds, TerminalCard, mutations, terminal stream frames).
- 2026-10-16 02:21 — Toolchain verified: JDK 21, build-tools 36.0.0, platforms android-36/37, Gradle 9.5.1, Pixel_8 AVD exists.
- 2026-10-16 02:22 — Scaffolded Kotlin/Compose project: AGP 9.1.1 (built-in Kotlin) + compose BOM, wrapper bootstrapped, minSdk 29 / targetSdk 36.
- 2026-10-16 02:31 — `./gradlew assembleDebug` GREEN (app-debug.apk). Wire layer: SPKI pin TrustManager, OrgJSON defensive models, WireClient (HTTP/1.1, Bearer, 401 surfacing), FeedSocket (epoch/sequence dedupe + epoch resync + JSON ping + backoff), TerminalStreamSocket, MiniTerm ANSI-lite emulator, Keystore-encrypted token in DataStore.
- 2026-10-16 02:44 — Pixel_8 AVD e2e vs live host (10.0.2.2:7434): token reuse → SPKI SHA-256 shown & matched pin → trust → Workspaces with real attention badges (Knowledge/Protector fleet + activity dot) → Feed "Android MVP test" live (epoch FB39B082, floor chips incl. Térreo status line, real preview lines) → Terminal mvp-cat: prompt round-trip echoed in live feed snapshot; Live view: geometry 75×23 rendered, typed "live-echo" + ⏎ chip → echo on screen r7c10. No crashes.
- 2026-10-16 02:51 — Post-test polish: SF-Symbol icon names → neutral glyph helper; authLost now closes sockets + forces re-pair UX ("Forget this server" path); rebuilt + reinstalled clean.
- 2026-10-16 02:56 — Sandbox cleanup: test workspace deleted via DELETE /api/workspaces/{ws} (owner), list updated live in app; production workspaces/terminals untouched. Stretch (live terminal) done; README.md written.
- STATUS: Definition of done MET (assembleDebug green; installs on Pixel_8; pairs/reuses token; live workspaces + feed; prompt to own test terminal; 401→re-pair path). SGR styling parsed but not rendered (renderer is plain text) — documented in README.
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
