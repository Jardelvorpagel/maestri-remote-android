package app.maestri.ui

/**
 * P1b notes lane strings — EN literals adopted VERBATIM from the iOS bundle's
 * localization tables (see knowledge/sources/2026-10-16-maestri-android-parity-strings.md
 * and 2026-10-16-maestri-ios-behavior-spec.md). Per-lane file: only the notes editor +
 * fichário lane writes here. `%lld` = integer format arg.
 */
object NotesStrings {
    // notes_editor
    const val EDIT_NOTE = "Edit note"
    const val RENAME_NOTE = "Rename Note"
    const val LOADING_NOTE = "Loading note"
    const val CANT_OPEN_THIS_NOTE = "Can't Open This Note"
    const val NOTE_UNAVAILABLE = "Note Unavailable"
    const val THIS_NOTE_IS_NO_LONGER_ON_THE_CANVAS = "This note is no longer on the canvas."
    const val CHANGE_NOTE_COLOR = "Change note color"
    const val CLEAR_COLOR = "Clear Color"
    const val CUSTOM_COLOR = "Custom Color"
    const val DEFAULT_COLOR = "Default color"
    const val ALL_CHANGES_SAVED = "All changes saved"
    const val THIS_NOTE_HAS_UNSAVED_CHANGES = "This note has unsaved changes"
    const val YOUR_UNSAVED_EDITS_STAY_ON_THIS_DEVICE = "Your unsaved edits stay on this device"
    const val SAVING_CONTINUES_IN_THE_BACKGROUND = "Saving continues in the background"
    const val SAVED_YOU_HAVE_NEWER_EDITS = "Saved. You have newer edits that aren't saved yet."
    const val CHANGED_ON_YOUR_COMPUTER = "Changed on Your Computer"
    const val CONFLICT_BODY_WHILE_DIRTY =
        "This note changed on your computer while you had unsaved edits. " +
            "Keeping yours replaces the version on your computer."
    const val CONFLICT_BODY_BEFORE_EDITS =
        "This note changed on your computer before your edits could be saved."
    const val USE_THE_COMPUTERS_VERSION = "Use the Computer's Version"
    const val UNSAVED_EDITS_RESTORED = "Unsaved Edits Restored"
    const val UNSAVED_EDITS_FROM_A_PREVIOUS_SESSION =
        "Unsaved edits from a previous session were restored."
    const val UNSAVED_EDITS_DISCARDED = "Unsaved edits discarded"
    const val RETRY_SAVING_NOTE = "Retry saving note"
    const val LOCKED_IN_MAESTRI = "Locked in Maestri"
    const val LOCKED_BANNER_BODY =
        "This note is locked on your computer, so your edits can't be saved there. " +
            "They're kept on this device until you unlock it in Maestri."
    const val THIS_NOTE_IS_LOCKED_IN_MAESTRI =
        "This note is locked in Maestri, so it can't be changed from here."
    const val LOCK_CONTENTS = "Lock Contents"
    const val UNLOCK_CONTENTS = "Unlock Contents"
    const val NOTE_LOCKED = "Note locked"
    const val NOTE_UNLOCKED = "Note unlocked"
    const val READ_ONLY_ACCESS = "Read-only Access"

    const val YOUR_COMPUTER_COULDNT_SAVE_THIS_NOTE =
        "Your computer couldn't save this note. Your edits are kept on this device."
    const val YOUR_COMPUTER_DIDNT_RESPOND_IN_TIME =
        "Your computer didn't respond in time. Your edits are kept on this device."
    const val YOUR_COMPUTER_IS_OUT_OF_REACH = "Your computer is out of reach."
    const val YOUR_COMPUTER_IS_OUT_OF_REACH_KEPT =
        "Your computer is out of reach. Your edits are kept on this device."
    const val THIS_NOTE_IS_TOO_LARGE_TO_SEND = "This note is too large to send to your computer."
    const val THIS_DEVICE_IS_NO_LONGER_PAIRED = "This device is no longer paired with your computer."

    // fichário
    const val FICHARIO = "Fichário"
    const val FICHARIO_OPTIONS = "Fichário options"
    const val ADD_TO_FICHARIO = "Add to Fichário"
    const val REMOVE_FROM_FICHARIO = "Remove from Fichário"
    const val NEW_PAGE = "New Page"
    const val RENAME_PAGE = "Rename Page"
    const val PAGES = "Pages"
    const val FRONT_PAGE = "Front page"
    const val SEARCH_PAGES = "Search pages"
    const val NO_PAGES_FOUND = "No pages found"
    const val SHOWS_THIS_PAGE = "Shows this page"
    const val A_NOTE_CAN_ONLY_BE_FILED_ON_ITS_OWN_FLOOR =
        "A note can only be filed into a fichário on its own floor."
    const val A_FICHARIO_NEEDS_TWO_NOTES_ON_THIS_FLOOR =
        "A fichário needs two notes, or a fichário and a note, on this floor."
    const val YOUR_COMPUTER_CANT_TAKE_FICHARIO_CHANGES_YET =
        "Your computer can't take fichário changes yet."

    fun pages(lld: Int) = "$lld pages"

    // fichário failure toasts (map 1:1 to routes)
    const val MAESTRI_COULDNT_ADD_A_PAGE = "Maestri couldn't add a page to that fichário."
    const val MAESTRI_COULDNT_TAKE_THAT_PAGE_OUT = "Maestri couldn't take that page out of the fichário."
    const val MAESTRI_COULDNT_FILE_THAT_NOTE = "Maestri couldn't file that note."
    const val MAESTRI_COULDNT_PLACE_THOSE_NOTES = "Maestri couldn't place those notes in a fichário."
    const val MAESTRI_COULDNT_REORDER_THOSE_PAGES = "Maestri couldn't reorder those pages."
    const val MAESTRI_COULDNT_TURN_THAT_FICHARIO = "Maestri couldn't turn that fichário."
    const val MAESTRI_COULDNT_RENAME = "Maestri couldn't rename that."
    const val THIS_FICHARIO_CHANGED_ON_YOUR_COMPUTER =
        "This fichário changed on your computer. Try that again."
    const val THAT_PAGE_ISNT_IN_THIS_FICHARIO_ANY_MORE =
        "That page isn't in this fichário any more."

    // color vocabulary (the 10 names the host accepts; order from the iOS binary)
    const val COLOR_YELLOW = "yellow"
    const val COLOR_PINK = "pink"
    const val COLOR_BLUE = "blue"
    const val COLOR_GREEN = "green"
    const val COLOR_ORANGE = "orange"
    const val COLOR_PURPLE = "purple"
    const val COLOR_WHITE = "white"
    const val COLOR_CHARCOAL = "charcoal"
    const val COLOR_SLATE = "slate"
    const val COLOR_MIDNIGHT = "midnight"

    val COLOR_VOCABULARY = listOf(
        COLOR_YELLOW, COLOR_PINK, COLOR_BLUE, COLOR_GREEN, COLOR_ORANGE,
        COLOR_PURPLE, COLOR_WHITE, COLOR_CHARCOAL, COLOR_SLATE, COLOR_MIDNIGHT,
    )

    // editor-adjacent literals used by the notes lane (node_actions / color edge cases)
    const val EXTERNAL_NOTES_CANT_BE_RENAMED = "External notes can't be renamed."
    const val SOMETHING_WENT_WRONG_ON_YOUR_COMPUTER = "Something went wrong on your computer."
    const val COULDNT_CHANGE_COLOR = "Couldn't Change Color"
    const val COLOR_MAY_BE_OUT_OF_DATE = "Color May Be Out of Date"
    const val DISMISS_KEYBOARD = "Dismiss Keyboard"
}
