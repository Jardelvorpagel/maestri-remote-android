# Build: Maestri Remote Android client (MVP)

You are implementing a native Android client for **Maestri Wire** — the vendor-documented, open
protocol the Maestri Remote iPhone app speaks. This is a sanctioned second client
("Maestri Remote é só o primeiro cliente... aberto a qualquer coisa que você construir").

## Contract — read BEFORE writing code

1. `~/Projects/maestri-remote-re/work/maestri-remote-protocol/evidence/wire-protocol-brief.md` —
   English distillation of the whole protocol v1 (transport, pairing, capabilities, feed,
   terminal stream, client obligations checklist).
2. Same dir, `wire-docs.txt` — the vendor's full documentation (pt-BR, authoritative:
   Endereços e transporte · Pareamento · GET /api/info · Capabilities · Convenções ·
   Workspaces e o feed · Socket do feed · Terminais · O stream do terminal).
3. Same dir, `wire-probe.py` + `wire-probe-run1.log` — a working Python reference client
   (pairing, SPKI pinning, info, workspaces, feed, live WS), validated against the live host today.

## Deliverable

Kotlin + Jetpack Compose app in THIS directory (`~/Projects/maestri-remote-android`),
Gradle wrapper, minSdk 29 / target 36. Android SDK: `~/Library/Android/sdk`
(build-tools 36.0.0, platforms present), JDK 21. Test on AVD **Pixel_8**.

### Screens (MVP)

1. **Pairing**: host[:port] + 6-digit code + device name → `POST /pair {deviceName, code}`.
   On first connection show the computed SPKI SHA-256 (hex, colon-separated) next to the
   stored pin and require explicit confirmation (the docs' manual pairing: confirm the
   computer's security key). Persist `{token, deviceId, serverKeyHash, hosts[]}` in
   EncryptedSharedPreferences/DataStore.
2. **Workspaces**: `GET /api/workspaces` — name, terminalCount, runningTerminalCount,
   attentionCount badge (red dot when > 0).
3. **Feed**: `WS /api/feed/stream?ws={ws}&token=…` — live FeedSnapshot; list terminal items
   (name, needsAttention badge, preview lines, pendingPrompt). Dedupe mutations by
   (epoch, sequence); on epoch change resync from snapshot; ignore unknown types.
4. **Terminal detail**: preview lines + prompt composer (`POST …/prompt {text}`); Approve /
   Reject buttons when `pendingPrompt` (`POST …/approve | /reject`); `POST …/seen` on open.
5. **(Stretch)** Live terminal: `WS /api/terminals/{id}/stream` — on `{type:geometry}` reset
   the emulator, binary frames = raw PTY bytes; send `{"type":"input","data":"…"}`; resize
   20..250 cols × 5..120 rows. Degrade gracefully to preview text if no emulator lib fits.

### Hard protocol rules

- TLS: custom `X509TrustManager` that accepts the cert ONLY if SHA-256(SPKI DER) == stored pin.
  No CA validation, no hostname check — pinning is the trust model.
- Every `/api/` request: `Authorization: Bearer <token>`; GET may use `?token=`; WS always `?token=`.
- Check `protocolVersion == 1` on `/api/info`; gate optional features on `capabilities`
  (this host has 50); never probe routes to discover them.
- UUIDs are UPPERCASE strings — repeat literally. Optional fields are omitted, never null.
  Enums are open strings — unknown value = "some other kind", not an error.
- Persistent 401 → token revoked/forgotten → return to pairing UX.

### Live host for testing (this machine, keep on-device)

- Host: `https://192.168.1.10:7434` (from the Android emulator: `https://10.0.2.2:7434` — same server)
- SPKI pin (base64): `zunL9hf4n/Zefo/l2sTOZFsCZsPUVM+7+JMO9f0+vRo=`
- A paired owner token exists in `wire-probe-run1.log` (device "Pi bridge") — use it for
  API testing from the emulator so you don't burn pairing codes; in-app pairing needs a
  fresh code from the host's Settings → Wire → Pareamento tab.
- 3 workspaces exist (Knowledge, Protector New APP, Protector Web) with live attention counts.

### Definition of done

- `./gradlew assembleDebug` green.
- Installs on Pixel_8 AVD, pairs (or reuses the owner token), shows live workspaces + feed
  of the real host, can send a prompt to a terminal — send prompts ONLY to your own test
  terminals; the production terminals (Daedalus, cheff, AIVideo, Protector fleet) belong to
  real work: never type into them.
- `README.md`: architecture, protocol decisions (pinning, epoch/sequence dedupe, snapshot
  resync), how to run, what's stretch.
- `PROGRESS.md`: append one short line per milestone; the dispatching terminal reads it
  through the feed.

### Reporting

Post your floor status as you go (`maestri floor status post "…" --state working|blocked|review|done`).
You are not connect-wired to the dispatcher, so when done or blocked >15 min: write the
summary to `PROGRESS.md` — it will be read from the feed snapshots.

Authorized use only. The owner token grants full control of this machine's canvas — treat
it as a secret, never commit it, never paste it into logs.