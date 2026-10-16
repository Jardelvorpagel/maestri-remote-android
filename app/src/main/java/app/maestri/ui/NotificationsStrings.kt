package app.maestri.ui

import java.util.Locale

/**
 * P4-A notification strings. Banked EN + pt-BR literals are VERBATIM from the parity
 * corpus (ptbr.json); composed notification bodies (rows 2/3/5/7/8) have no banked
 * literals and are marked CANDIDATE in-line — QA flagging hooks on those.
 */
object NotificationsStrings {
    private val pt: Boolean get() = Locale.getDefault().language.startsWith("pt")

    // ---- banked verbatim (iOS bundle, ptbr.json) -------------------------------------------
    val appName: String get() = "Maestri"
    val sectionNotifications: String get() = if (pt) "Notificações" else "Notifications"
    val pushSettingsTitle: String get() = if (pt) "Notificações push" else "Push Notifications"
    val allowInSettings: String get() =
        if (pt) "Permitir notificações nos Ajustes" else "Allow Notifications in Settings"
    val clearAll: String get() =
        if (pt) "Limpar todas as notificações" else "Clear All Notifications"
    val activityBlurb: String get() =
        if (pt) "Maestri detecta a atividade do terminal e notifica você quando os agentes terminam o trabalho."
        else "Maestri detects terminal activity and notifies you when agents finish work."

    // ---- CANDIDATE (Android-only channel labels; no iOS literal) ----------------------------
    val channelChatName: String get() = if (pt) "Mensagens de chat" else "Chat messages"
    val channelAgentsName: String get() = if (pt) "Atividade de agentes" else "Agent activity"
    val channelSystemName: String get() = if (pt) "Alertas do sistema" else "System alerts"

    // ---- CANDIDATE bodies (composed from banked stream fields; QA-hook each) ---------------
    val attentionBody: String get() =
        if (pt) "Um agente terminou ou precisa de você." else "An agent finished or needs your attention."

    fun landedBody(branch: String): String =
        if (pt) "Pousou em $branch." else "Landed on $branch."

    val copyFailedBody: String get() =
        if (pt) "A cópia do andar falhou." else "The floor copy failed."

    val cloneMissingBody: String get() =
        if (pt) "O clone do andar sumiu no computador." else "The floor's clone is missing on the computer."

    fun pullBody(number: Int?, attention: String, title: String): String {
        val n = number?.toString()
        val prefix = if (n != null) (if (pt) "Pull request #$n" else "Pull request #$n") else (if (pt) "Pull request" else "Pull request")
        val detail = if (title.isNotBlank()) "$prefix · $attention · $title" else "$prefix · $attention"
        return detail
    }

    val lockBody: String get() =
        if (pt) "Este workspace está bloqueado pelo plano do host." else "This workspace is locked on the host's plan."

    val authLostFallbackBody: String get() =
        if (pt) "Pareie novamente com o computador." else "Pair with the computer again."

    val maestroTitle: String get() = "Maestro"

    // ---- CANDIDATE (row 9 note; no iOS literal) -------------------------------------------
    val activityToggleNote: String get() =
        if (pt) "Desligado por padrão." else "Off by default."
}