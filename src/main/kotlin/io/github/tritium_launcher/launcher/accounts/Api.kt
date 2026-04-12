package io.github.tritium_launcher.launcher.accounts

import io.github.tritium_launcher.launcher.registry.Registrable
import io.qt.gui.QPixmap
import io.qt.widgets.QWidget

/**
 * Account metadata for UI and account switching.
 */
data class AccountDescriptor(
    val id: String,
    val username: String? = null,
    val subtitle: String? = null,
    val avatarUrl: String? = null,
    val label: String? = null
)

/**
 * Provides account discovery and session management for a service.
 */
interface AccountProvider: Registrable {
    override val id: String
    /** Human-readable provider name for UI. */
    val displayName: String

    /** Lists accounts known to this provider. */
    suspend fun listAccounts(): List<AccountDescriptor>

    /** Starts an interactive sign-in flow. */
    suspend fun signIn(parentWindow: QWidget? = null)

    /** Signs out a specific account by id. */
    suspend fun signOutAccount(accountId: String)

    /** Switches the active account to the given id. */
    suspend fun switchToAccount(accountId: String): Boolean

    /** Returns an avatar for the account, if available. */
    suspend fun getAvatar(accountId: String): QPixmap? = null
}
