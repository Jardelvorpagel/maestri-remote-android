package app.maestri.ui

/**
 * Canvas wording, iOS-identical and verbatim.
 *
 * Key names follow the parity strings table (maestri-android-parity-strings.md, derived from the iOS
 * bundle's pt-BR Localizable.strings, whose keys ARE the English literals the iOS UI shows); the
 * values are those English literals unchanged, so the Android UI reads exactly like iOS.
 * Sections carried: canvas_menu, node_actions, drawings, color_vocab, accessibility_hints.
 */
object CanvasStrings {

    // ---------------------------------------------------------------- canvas_menu
    const val holdThenDragToMove = "Hold, then drag to move"
    const val panTheCanvasView = "Pan the canvas view"
    const val tapWhereTheNoteShouldGo = "Tap where the note should go"
    const val tapWhereTheTextShouldGo = "Tap where the text should go"
    const val tapWhereTheFolderShouldGo = "Tap where the folder should go"
    const val tapWhereTheTerminalShouldGo = "Tap where the terminal should go"
    const val tapTheNodeToConnectTo = "Tap the node to connect to"
    const val connectToAnotherNode = "Connect to another node"
    const val connectsItToTheNodeYouStartedAt = "Connects it to the node you started at"
    const val connectingFromHere = "Connecting from here"
    const val canConnectItToTheNodeYouStartedAt = "Can connect it to the node you started at"
    const val cancelsConnecting = "Cancels connecting"
    const val canTConnectToThis = "Can't connect to this"
    const val alreadyConnected = "Already connected"
    const val thisNodeCanTTakeThatConnection = "This node can't take that connection"
    const val theseTwoNodesCanTBeConnected = "These two nodes can't be connected."
    const val thisNodeCanTBeConnected = "This node can't be connected."
    const val disconnect = "Disconnect %@"
    const val removeConnection = "Remove connection"
    const val removeAllConnections = "Remove All Connections"
    const val manageConnections = "Manage Connections"
    const val selectsThisNode = "Selects this node"
    const val selectsEveryNodeInTheGroup = "Selects every node in the group"
    const val nameGroup = "Name Group"
    const val groupName = "Group Name"
    const val ungroup = "Ungroup"
    const val group = "Group"
    const val aGroupNeedsAtLeastTwoNodesOnThisFloor = "A group needs at least two nodes on this floor."
    const val searchThisWorkspace = "Search this workspace"
    const val focusThisElementOnTheCanvas = "Focus this element on the canvas"
    const val placeInTheCenterOfTheScreen = "Place in the center of the screen"
    const val lockedOnYourComputer = "Locked on your computer"
    const val thisNodeIsLockedOnYourComputer = "This node is locked on your computer."
    const val thisNodeIsLockedInMaestriAndCanTBeMoved = "This node is locked in Maestri and can't be moved."
    const val addNote = "Add Note"
    const val newNote = "New Note"
    const val addToFichário = "Add to Fichário"
    const val opensThisFichárioSPages = "Opens this fichário's pages"
    const val aFichárioConnectsToTerminals = "A fichário connects to terminals."
    const val aPortalConnectsToTerminalsAndOtherPortals = "A portal connects to terminals and other portals."
    const val aTerminalConnectsToTerminalsNotesFicháriosAndPortals = "A terminal connects to terminals, notes, fichários and portals."
    const val aNoteConnectsToTerminalsAndOtherNotes = "A note connects to terminals and other notes."

    // ---------------------------------------------------------------- node_actions
    const val rename = "Rename"
    const val deleteThisNode = "Delete this node?"
    const val deleteSelectedNodes = "Delete Selected Nodes?"
    const val lldNodesWillBeRemovedFromTheCanvasOnYourComputer = "%lld nodes will be removed from the canvas on your computer."
    const val lldNodesWillBeRemovedFromTheCanvasOnYourComputerIncludingEve = "%lld nodes will be removed from the canvas on your computer, including every page inside a selected fichário."
    const val itSRemovedFromTheCanvasOnYourComputer = "It's removed from the canvas on your computer."
    const val maestriCouldnTDeleteThoseNodes = "Maestri couldn't delete those nodes."
    const val maestriCouldnTMoveThatNode = "Maestri couldn't move that node."
    const val maestriCouldnTRenameThat = "Maestri couldn't rename that."
    const val maestriCouldnTChangeThatText = "Maestri couldn't change that text."
    const val maestriCouldnTConnectThoseNodes = "Maestri couldn't connect those nodes."
    const val maestriCouldnTDisconnectThoseNodes = "Maestri couldn't disconnect those nodes."
    const val maestriCouldnTGroupThoseNodes = "Maestri couldn't group those nodes."
    const val maestriCouldnTUngroupThoseNodes = "Maestri couldn't ungroup those nodes."
    const val maestriCouldnTRecolorThatGroup = "Maestri couldn't recolor that group."
    const val maestriCouldnTRenameThatGroup = "Maestri couldn't rename that group."
    const val maestriCouldnTTieThoseRopes = "Maestri couldn't tie those ropes."
    const val maestriCouldnTCutThatTie = "Maestri couldn't cut that tie."
    const val maestriCouldnTMoveThatTie = "Maestri couldn't move that tie."
    const val thatCanTBeRenamedFromHere = "That can't be renamed from here."
    const val externalNotesCanTBeRenamed = "External notes can't be renamed."
    const val thisNodeIsNoLongerOnTheCanvas = "This node is no longer on the canvas."
    const val thisGroupIsNoLongerOnTheCanvas = "This group is no longer on the canvas."
    const val thisConnectionIsNoLongerOnTheCanvas = "This connection is no longer on the canvas."

    // ------------------------------------------------------------------- drawings
    const val draw = "Draw"
    const val drawingTool = "Drawing tool"
    const val drawingTools = "Drawing tools"
    const val drawingSurface = "Drawing surface"
    const val drawWithOneFingerTwoFingersPanAndZoom = "Draw with one finger. Two fingers pan and zoom."
    const val drawingSomething = "Drawing something…"
    const val erasesTheLastDrawingYouMade = "Erases the last drawing you made"
    const val maestriCouldnTAddThatDrawing = "Maestri couldn't add that drawing."
    const val maestriCouldnTEraseThatDrawing = "Maestri couldn't erase that drawing."
    const val maestriCouldnTMoveThatDrawing = "Maestri couldn't move that drawing."
    const val thisDrawingIsLockedOnYourComputer = "This drawing is locked on your computer."
    const val thisDrawingIsLockedInMaestriAndCanTBeErased = "This drawing is locked in Maestri and can't be erased."
    const val thisDrawingIsLockedInMaestriAndCanTBeMoved = "This drawing is locked in Maestri and can't be moved."
    const val thisDrawingIsNoLongerOnTheCanvas = "This drawing is no longer on the canvas."

    // ---------------------------------------------------------------- color_vocab
    /** Fixed colour vocabulary (note/group colours); custom colours are separate affordances. */
    val colors = listOf(
        "yellow",
        "pink",
        "blue",
        "green",
        "orange",
        "purple",
        "white",
        "charcoal",
        "slate",
        "midnight",
    )

    // -------------------------------------------------------- accessibility_hints
    const val panTheCanvasView2 = "Pan the canvas view"
    const val holdThenDragToMove2 = "Hold, then drag to move"
    const val selectsThisNode2 = "Selects this node"
    const val selectsEveryNodeInTheGroup2 = "Selects every node in the group"
    const val searchThisWorkspace2 = "Search this workspace"
    const val showsThisPage = "Shows this page"
    const val showsTheRepositorySChanges = "Shows the repository's changes"
}
