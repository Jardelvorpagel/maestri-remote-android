# VENDOR-README — terminal-view

Source: https://github.com/termux/termux-app, directory `terminal-view`,
pinned revision `8629e632fcb95da272221be327db653fb24befe9`
(master, 2026-09-26, upstream publish version 0.118.0).

License: Apache License 2.0 — same explicit LICENSE.md exception as
`terminal-emulator` (see its VENDOR-README). No GPL app dirs vendored.

Contents: `src/main` ONLY, byte-identical to upstream at the pinned revision
(`src/test` excluded, same rationale).

Maestri binds the view WITHOUT upstream `TerminalSession` (final class whose
`updateSize` path spawns a local PTY via JNI — incompatible with a remote
stream): `TermuxBridge.RemoteTerminalView` assigns the view's public
`mTermSession`/`mEmulator` fields directly with a never-spawned dummy session
(its `write()` is a no-op while `mShellPid <= 0`) and overrides `updateSize()`
so view-geometry changes never reach JNI. Vendored sources are NEVER modified.
