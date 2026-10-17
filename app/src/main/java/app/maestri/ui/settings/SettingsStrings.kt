package app.maestri.ui.settings

/**
 * P6 app-lock vocabulary. House style: EN literals as consts (FloorsStrings.kt pattern);
 * the pt-BR pairs from /tmp/ptbr.json live in the comments/report:
 *   Privacy → Privacidade
 *   Immediately → Imediatamente · After 1 Minute → Após 1 minuto · After 5 Minutes → Após 5 minutos
 *   Maestri Remote is locked → O Maestri Remote está bloqueado
 *   Unlock → Desbloquear · Unlock Maestri Remote → Desbloquear o Maestri Remote
 *   Confirm it’s you to require this when opening Maestri Remote →
 *       Confirme que é você para exigir isso ao abrir o Maestri Remote
 *
 * ADAPTED (ruling A, user mandate): the toggle label is neutral biometric wording, chosen
 * over the corpus's Face/Touch-ID literals because a fingerprint-class Android device is
 * neither; PT for the adapted label composes the corpus patterns: "Exigir biometria para
 * abrir o Maestri Remote". The explainer below stays corpus-verbatim (’ = U+2019).
 * "Notifications" is a plain label: the P4-A lane owns a real constant — flagged W-4.
 */
object SettingsStrings {
    const val settings = "Settings"
    const val privacy = "Privacy" // Privacidade
    const val requireBiometrics = "Require biometrics to open Maestri Remote" // ADAPTED label
    const val explainer =
        "Confirm it’s you to require this when opening Maestri Remote" // ’ U+2019 verbatim
    const val graceImmediately = "Immediately" // Imediatamente
    const val graceAfter1Minute = "After 1 Minute" // Após 1 minuto
    const val graceAfter5Minutes = "After 5 Minutes" // Após 5 minutos
    const val lockHeadline = "Maestri Remote is locked" // O Maestri Remote está bloqueado
    const val unlockPromptTitle = "Unlock Maestri Remote" // Desbloquear o Maestri Remote — prompt reason
    const val unlock = "Unlock" // Desbloquear — cover button + prompt negative
    const val notifications = "Notifications" // plain label; P4-A constant supersedes (W-4)
}