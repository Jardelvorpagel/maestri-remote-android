# P1b notes lane — integration map (for the merge stage)

Branch `feature/notes-editor` (worktree `~/Projects/mra-parity-p1b`, base main @ ea9f714).
Every file below is NEW and lane-owned: the canvas lane's files are untouched, so this merges clean.

## Wire to add

One line inside the `NavHost { }` block in `MainActivity.Root`:

    noteEditorRoute(session, onBack = { nav.popBackStack() })

(from `app/src/main/java/app/maestri/ui/NotesNav.kt`; helper `nav.openNote(wsId, nodeId)` exists for call sites.)

## Where the canvas lane calls in (CanvasScreen — its lane, its call)

1. **Note card tap / "Edit note"** → `nav.navigate("note/$wsId/$nodeId")` (ids only; the editor fetches).
2. **Fichário card** (`kind == "noteStack"`): build `FicharioState` from the raw node payload —
   `FicharioState().parse(nodeJson)` in `wire/NotesApi.kt` — then render
   `FicharioTabRailInline(session, wsId, stack, onOpenNote = { nav.openNote(wsId, it) })`
   at the card's bottom edge. It handles tap=front (+editor), silent drag-order, and the
   overflow sheet (search/count/front badge/rename/remove+landing/New Page) itself.
3. **Node menu items** on notes/stacks: construct one `FicharioActions(api, wsId) { toast(it) }`
   per screen: `fileNote(...)` = "Add to Fichário", `removeFromFichario(...)` = page out with
   optional `{x,y}` landing, `newPage(...)` = "New Page", `createFromSelection(...)` = file
   selected notes into a new fichário. Client-side guards live there (same-floor note, 2-note
   minimum, exact iOS toasts). Call `capabilityGate(session)` before showing the items (old host
   → "Your computer can't take fichário changes yet." and a hidden menu).
4. **Note cards**: the payload for a `note` node carries `note:{fileName, displayName, color,
   preview, isContentLocked, isExternal, hasCustomName}`; `note.preview` = first lines ONLY.

## Truth proven on the wire this lane (sandbox probed, teardown 404-verified)

- Stack routes are STACK-SCOPED (`…/nodes/{stackId}/stack/{order,pages,…}`) — the schema doc's
  workspace-scoped paths 404. `stackOrder` route: `PUT …/nodes/{stackId}/stack/order`.
- 412 PUT note body = the CURRENT NoteContent (conflict dialog's "computer's version").
- Note color: vocabulary NAMES only (raw hex/unknown → 400 "Invalid color"); CLEAR = {"color":""};
  customColor rides WITH a name (alone → 400); color writes work while content-locked.
- Every save (without a custom name) re-derives fileName/displayName from the first line
  ("alpha docs" → alpha-docs.md; "zeta now" → zeta-now.md).
- DELETE stack/pages/{member} accepts body {x,y} (landing) or NONE; non-member → 409
  "Page is not in this fichário" (= the "That page isn't in this fichário any more." guard).
- `noteStack` snapshot payload: {name, frontNoteId, front: <card>, tabs: [{nodeId, title, color}]}.
- Host accepts a 1-note note-stacks create; iOS's "needs two notes" text is a client-side guard.
- 409 while locked: {"error":{"code":"conflict","message":"Note is locked"}}.
- Capabilities present: `nativeNotes`, `noteStackWrites`, `noteStackPages`, `noteStackFiling`.

## Deliberate deltas (flagged to the merge stage)

1. **Lock-state watch**: iOS learns `isContentLocked` flips from the feed CARD; our shared
   `Models.kt` drops note payloads from CanvasNode (kept intact for other lanes). The editor
   polls GET note every 8 s ONLY while locked+dirty, and honors every manual action. To close
   the gap properly, Models.kt can grow an optional `note` payload parse and the editor's poll
   can switch to card-watching (NotesApi.FicharioState patterns show how).
2. **Custom Color** = {color: <current name or yellow>, customColor: "#RRGGBB"} — round-tripped
   (200) but the card-render precedence of customColor vs name was not visually verified.
3. **Empty "Rename Note" confirm** = no-op (host 400s empty rename bodies); iOS's
   "Leave it empty for no name." desktop wording suggests a clear-custom-name path we don't have.
4. First-line naming is HOST-side (probed); the editor does nothing but re-render the title.

No lane files touched by this branch: `PROGRESS.md`, `WireSession.kt`, `CanvasScreen.kt`, `FeedScreen.kt` — untouched; production was never written to (all probes on a disposable sandbox, deleted + 404-verified).