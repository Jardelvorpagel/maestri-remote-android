# VENDOR-README — terminal-emulator

Source: https://github.com/termux/termux-app, directory `terminal-emulator`,
pinned revision `8629e632fcb95da272221be327db653fb24befe9`
(master, 2026-09-26, upstream publish version 0.118.0).

License: Apache License 2.0 (jackpal Android-Terminal-Emulator heritage).
The termux-app repo root is GPLv3-only BUT its LICENSE.md names an explicit
exception for the `terminal-emulator` + `terminal-view` library dirs
("Terminal Emulator for Android code … released under Apache 2.0").
No GPL app dirs (`app/`, `termux-shared/`, …) are vendored.

Contents: `src/main` ONLY, byte-identical to upstream at the pinned revision
(`src/test` excluded — the union gate carries no junit; `src/main/jni`
ships pristine but is deliberately NOT built: Maestri drives the emulator
with the REMOTE byte stream, never spawns a local PTY, so `JNI` is never
loaded and no NDK is required).

Maestri bridge: `app/.../ui/screens/TermuxBridge.kt` + the additive Termux
feed in `app/.../wire/TerminalStreamSocket.kt`. Vendored sources are NEVER
modified — re-pin by replacing `src/main` and updating the SHA above.
