// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.remote

import app.tandem.state.CommandIcon

/**
 * The desktop sends only a name and a shell line, so the icon and the "ask first" flag are guessed from
 * those words. A wrong guess only costs a different icon or one extra confirmation.
 */
object CommandClassifier {
    private val power = Regex("""poweroff|power off|shut ?down|reboot|restart|halt""", RegexOption.IGNORE_CASE)
    private val destructive =
        Regex(
            """poweroff|power off|shut ?down|reboot|restart|halt|rm -rf|mkfs|log ?out|logout""",
            RegexOption.IGNORE_CASE,
        )
    private val lock = Regex("""\block\b|lock-session|loginctl lock|screensaver""", RegexOption.IGNORE_CASE)
    private val sleep = Regex("""suspend|sleep|hibernate""", RegexOption.IGNORE_CASE)
    private val backup = Regex("""backup|rsync|borg|restic|timeshift""", RegexOption.IGNORE_CASE)
    private val screenshot = Regex("""screenshot|spectacle|scrot|grim|flameshot""", RegexOption.IGNORE_CASE)

    fun icon(
        name: String,
        command: String,
    ): CommandIcon {
        val text = "$name $command"
        return when {
            power.containsMatchIn(text) -> CommandIcon.Power
            lock.containsMatchIn(text) -> CommandIcon.Lock
            sleep.containsMatchIn(text) -> CommandIcon.Sleep
            backup.containsMatchIn(text) -> CommandIcon.Backup
            screenshot.containsMatchIn(text) -> CommandIcon.Screenshot
            else -> CommandIcon.Generic
        }
    }

    fun isDestructive(
        name: String,
        command: String,
    ): Boolean = destructive.containsMatchIn("$name $command")
}
