package io.github.tritium_launcher.launcher.accounts.ui

import io.github.tritium_launcher.launcher.accounts.AccountDescriptor
import io.github.tritium_launcher.launcher.accounts.AccountProvider
import io.github.tritium_launcher.launcher.accounts.MicrosoftAuth
import io.github.tritium_launcher.launcher.accounts.ProfileMngr
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.toQImage
import io.github.tritium_launcher.launcher.toUrl
import io.qt.core.Qt
import io.qt.gui.QPainter
import io.qt.gui.QPixmap
import io.qt.widgets.QWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Account provider for Microsoft.
 */
class MicrosoftAccountProvider: AccountProvider {
    private val logger = logger()
    override val id: String = "microsoft_account_provider"
    override val displayName: String = "Microsoft"

    override suspend fun listAccounts(): List<AccountDescriptor> = withContext(Dispatchers.IO) {
        try {
            val accounts = MicrosoftAuth.listAccounts()
            accounts.map { a ->
                val mc = ProfileMngr.Cache.getForAccount(a.homeAccountId)
                AccountDescriptor(a.homeAccountId, mc?.name ?: a.username, mc?.id ?: a.username, null, mc?.name ?: a.username)
            }
        } catch (e: Exception) {
            logger.warn("Failed to map accounts", e)
            emptyList()
        }
    }

    override suspend fun signIn(parentWindow: QWidget?) {
        MicrosoftAuth.newSignIn()
    }

    override suspend fun signOutAccount(accountId: String) {
        MicrosoftAuth.signOutAccount(accountId)
    }

    override suspend fun switchToAccount(accountId: String): Boolean {
        return MicrosoftAuth.switchToAccount(accountId)
    }

    override suspend fun getAvatar(accountId: String): QPixmap? = withContext(Dispatchers.IO) {
        try {
            val p = ProfileMngr.Cache.getForAccount(accountId)
            if(p != null && p.skins.isNotEmpty()) {
                return@withContext createFacePixmap(p.skins.first().url)
            }

            val p2 = MicrosoftAuth.getMcProfileForAccount(accountId)
            if (p2 != null && p2.skins.isNotEmpty()) {
                return@withContext createFacePixmap(p2.skins.first().url)
            }
            null
        } catch (e: Exception) {
            logger.info("Failed to fetch MC avatar for $accountId", e)
            null
        }
    }

    private suspend fun createFacePixmap(url: String, size: Int = 64): QPixmap = withContext(Dispatchers.IO) {
        val img = ImageIO.read(url.toUrl()) ?: return@withContext QPixmap()

        val original = QPixmap.fromImage(img.toQImage())

        val baseFace = original.copy(8,8,8,8)
        val hatLayer = original.copy(40,8,8,8)

        val combined = QPixmap(8,8)
        combined.fill(Qt.GlobalColor.transparent)

        val painter = QPainter(combined)
        try {
            painter.setCompositionMode(QPainter.CompositionMode.CompositionMode_SourceOver)
            painter.drawPixmap(0, 0, baseFace)
            painter.drawPixmap(0, 0, hatLayer)
        } finally {
            painter.end()
        }

        val rawScale = size.toFloat() / 8f
        val intScale = max(1, rawScale.roundToInt())
        val finalSize = intScale * 8

        val upscaled = combined.scaled(
            finalSize,
            finalSize,
            Qt.AspectRatioMode.IgnoreAspectRatio,
            Qt.TransformationMode.FastTransformation
        )

        val finalPixmap = QPixmap(size, size)
        finalPixmap.fill(Qt.GlobalColor.transparent)
        val p2 = QPainter(finalPixmap)
        try {
            val offsetX = (size - finalSize) / 2
            val offsetY = (size - finalSize) / 2
            p2.drawPixmap(offsetX, offsetY, upscaled)
        } finally {
            p2.end()
        }

        finalPixmap
    }
}
