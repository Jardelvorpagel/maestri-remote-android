package app.maestri.ui

/**
 * Chat parity strings — EN literals VERBATIM from the iOS bundle
 * (`pt-BR.lproj/Localizable.strings`, key == English literal) + binary-only literals
 * (behavior spec §3, marked "binary"). Source:
 * ~/knowledge/sources/2026-10-16-maestri-android-parity-strings.md (chat_composer +
 * working_verbs) and 2026-10-16-maestri-ios-behavior-spec.md §3.1–3.8.
 *
 * Never paraphrase these: wording parity with the iOS Remote app is the acceptance bar.
 * Format placeholders use %@ (string) / %lld (long) exactly as the corpus does.
 */
object ChatStrings {

    // ---- faces ------------------------------------------------------------

    const val enterChatMode = "Enter Chat Mode"
    const val exitChatMode = "Exit Chat Mode"
    const val returnToChat = "Return to Chat"
    const val startInChatMode = "Start in Chat Mode"

    // ---- thread switcher / slots -------------------------------------------

    const val clearChatHistory = "Clear Chat History"
    const val clearChatHistoryAsk = "Clear Chat History?"
    /** body of the confirm dialog */
    const val clearHistoryBodies = "Its messages will be deleted. This deletes Maestri's conversation and attachments. The agent's own memory is not cleared."

    // ---- scroll + history ---------------------------------------------------

    const val loadEarlierMessages = "Load earlier messages"
    const val earlierConversationHappenedInTheTerminal = "Earlier conversation happened in the terminal"
    const val scrollToBottom = "Scroll to bottom"

    // ---- empty state / working presentation ---------------------------------

    const val sendAMessageAndTheAgentWillAnswerHere = "Send a message and the agent will answer here."
    const val waitingForAReply = "Waiting for a reply"
    const val noReplyArrived = "No reply arrived."
    const val showTerminalPreview = "Show terminal preview"
    const val hideTerminalPreview = "Hide terminal preview"

    // working verbs (binary literals; local rotation while status == working)
    val workingVerbs = listOf(
        "Thinking it over…",
        "Tuning things…",
        "Finding the right note…",
        "Picking up the baton…",
        "Warming up the orchestra…",
        "Orchestrating…",
        "Doing something…",
    )

    // ---- composer ------------------------------------------------------------

    const val send = "Send"
    /** placeholder; %@ = the terminal/agent the user is talking to */
    const val messageToMentionFmt = "Message %1\$s. @ to mention"
    const val mentionSuggestions = "Mention suggestions"
    /** "%lld mention suggestions" */
    fun mentionSuggestionsCountFmt(count: Int): String = when (count) {
        0 -> "0 mention suggestions"
        1 -> "1 mention suggestion"
        else -> "${count} mention suggestions"
    }
    const val filesBackToMentions = "Files. Back to mentions"
    const val fileSuggestions = "File suggestions"
    const val loadingFiles = "Loading files…"
    const val couldnTLoadFiles = "Couldn't load files"
    const val mentionsAreTemporarilyUnavailable = "Mentions are temporarily unavailable."
    const val attach = "Attach"
    const val attachmentPreview = "Attachment preview"
    const val camera = "Camera"
    const val quote = "Quote"
    const val pasteImage = "Paste Image"
    /** "Pasted text #%lld" */
    fun pastedTextN(n: Int): String = "Pasted text #$n"
    /** "Attachment: %@" */
    fun attachmentNamed(name: String): String = "Attachment: $name"

    // ---- attachment limits (EXACT wording) ------------------------------------

    const val theAttachmentCantBeAddedChoose8Mb =
        "The attachment couldn't be added. Choose a file up to 8 MB."
    /** "%lld attachments couldn't be added. Each must be under 8 MB, and a draft holds up to 64 MB." */
    fun attachmentsRejected(n: Int): String =
        if (n == 1) theAttachmentCantBeAddedChoose8Mb
        else "$n attachments couldn't be added. Each must be under 8 MB, and a draft holds up to 64 MB."
    const val tooManyAttachmentsToSend =
        "This prompt has too many attachments to send. Remove a few and try again."
    const val promptTooLongToSend =
        "This prompt is too long to send. Shorten it and try again."
    const val promptCantBeSentDraftPreserved =
        "The prompt couldn't be sent. Your draft was preserved."
    const val attachmentCantBePreparedDraftPreserved =
        "An attachment couldn't be prepared. Your draft was preserved."
    const val attachmentCantBeCopiedDraftPreserved =
        "The attachment couldn't be copied to the remote computer. Your draft was preserved."
    const val attachmentExpiredOrTerminalUnavailable =
        "An attachment expired or this terminal is unavailable. Try sending again."
    const val thisComputerCantReceiveAttachments =
        "This computer can't receive attachments. Remove them to send the prompt."
    const val thisTerminalIsUnavailableCheckConnection =
        "This terminal is unavailable. Check the connection and try again."
    const val terminalBusyOrNotRunning =
        "The terminal is busy or not running. Check it and try again."

    // ---- send flight states -----------------------------------------------------

    const val sendingThePrompt = "Sending the prompt"
    const val uploadingAttachments = "Uploading attachments"
    const val deliveryStatusUnknown = "Delivery Status Unknown"
    const val deliveryStatusUnknownDetail =
        "Maestri lost the response after sending. The prompt may have started, so it won't retry automatically."
    const val keepDraft = "Keep Draft"
    const val openAsADraft = "Open as a draft"

    // ---- agent messages / code blocks ---------------------------------------------

    const val copyCode = "Copy Code"
    const val copyCodeBlock = "Copy Code Block"
    const val copyPath = "Copy Path"
    const val copyRelativePath = "Copy Relative Path"
    const val copyMarkdown = "Copy Markdown"
    const val copyDiagram = "Copy Diagram"
    const val copyAsTable = "Copy as Table"
    const val copyAsImage = "Copy as Image"
    const val copyMermaidSource = "Copy Mermaid Source"

    // ---- terminal-not-running / unavailable sends -----------------------------------

    const val thisTerminalIsntRunning = "This terminal isn't running."
    const val thisTerminalIsUnloaded = "This terminal is unloaded."
    const val thisAgentIsntRunning = "This agent isn't running. Open its floor to start it, then send."
    const val noAgentsOnThisFloor = "No agents on this floor."
}