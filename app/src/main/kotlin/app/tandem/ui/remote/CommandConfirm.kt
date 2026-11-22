// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.ui.remote

import app.tandem.state.RunCommand

/** Confirm flow for run commands. Destructive commands wait for a second tap in the dialog. */
data class CommandConfirmState(
    val pending: RunCommand? = null,
)

/** Result of a transition: the new state and the command to run now, if any. */
data class CommandStep(
    val state: CommandConfirmState,
    val run: RunCommand? = null,
)

object CommandConfirm {
    fun request(
        state: CommandConfirmState,
        command: RunCommand,
    ): CommandStep =
        if (command.destructive) {
            CommandStep(state.copy(pending = command))
        } else {
            CommandStep(state.copy(pending = null), run = command)
        }

    fun confirm(state: CommandConfirmState): CommandStep {
        val pending = state.pending ?: return CommandStep(state)
        return CommandStep(CommandConfirmState(null), run = pending)
    }

    fun cancel(): CommandStep = CommandStep(CommandConfirmState(null))
}
