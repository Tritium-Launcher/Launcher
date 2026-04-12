package io.github.tritium_launcher.launcher.ui.dashboard

import io.github.tritium_launcher.launcher.*
import io.github.tritium_launcher.launcher.accounts.AccountDescriptor
import io.github.tritium_launcher.launcher.accounts.AccountProvider
import io.github.tritium_launcher.launcher.accounts.ProfileMngr
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import io.github.tritium_launcher.launcher.ui.theme.qt.setStyle
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.LineLabelWidget
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.*
import io.qt.Nullable
import io.qt.core.QTimer
import io.qt.core.Qt
import io.qt.gui.QCloseEvent
import io.qt.gui.QPixmap
import io.qt.widgets.QFrame
import io.qt.widgets.QLayout
import io.qt.widgets.QPushButton
import io.qt.widgets.QWidget
import kotlinx.coroutines.*
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * [Dashboard] panel showing connected accounts and methods to connect them.
 *
 * Account services can be provided by extensions by registering an [AccountProvider].
 * @see [io.github.tritium_launcher.launcher.accounts.ui.MicrosoftAccountProvider]
 * @see [io.github.tritium_launcher.launcher.extension.core.CoreExtension]
 */
@OptIn(ExperimentalAtomicApi::class)
class AccountsPanel internal constructor(): QWidget() {

    @Volatile
    private var isLoading: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val isRefreshing = AtomicBoolean(false)

    private val mainLayout = vBoxLayout {
        contentsMargins = 0.m
        widgetSpacing = 10
    }

    private val logger = logger()

    init {
        setLayout(mainLayout)

        setThemedStyle {
            selector("QWidget#accountCard") {
                borderRadius(10)
            }
            selector("QLabel#accountName") {
                fontSize(18)
                fontWeight(600)
            }
            selector("QLabel#accountSub") {
                fontSize(12)
            }
            selector("QPushButton#primary") {
                padding(top = 6, right = 12)
                borderRadius(8)
            }
            selector("QPushButton#secondary") {
                padding(top = 6, right = 12)
                borderRadius(8)
                background("transparent")
            }
        }

        this.destroyed.connect {
            scope.cancel()
        }

        ProfileMngr.addListener { _ ->
            isLoading = false
            QTimer.singleShot(0) { refreshUI() }
        }

        QTimer.singleShot(0) { refreshUI() }

        scope.launch {
            Dashboard.bgDashboardLogger.info("Initial profile check started")
            val profile = ProfileMngr.Cache.get()
            if(profile != null) isLoading = false
            QTimer.singleShot(0) { refreshUI() }
        }
    }

    override fun closeEvent(event: @Nullable QCloseEvent?) {
        try {
            scope.cancel()
        } finally {
            super.closeEvent(event)
        }
    }

    private fun clearLayout(layout: QLayout?) {
        val l = layout ?: return
        while(l.count() > 0) {
            val item = l.takeAt(0) ?: continue
            val widget = item.widget()
            if(widget != null) {
                widget.hide()
                widget.setParent(null)
                try { widget.dispose() } catch (_: Throwable) {}
            } else {
                clearLayout(item.layout())
            }
        }
    }

    internal fun createProfileCard(
        avatar: QPixmap?,
        displayName: String,
        subtitle: String?,
        actionText: String,
        actionHandler: suspend () -> Unit,
        secondaryText: String? = null,
        secondaryHandler: (suspend () -> Unit)? = null
    ): QWidget {

        val card = frame {
            objectName = "accountCard"
            setFrameStyle(QFrame.Shape.NoFrame.value())
        }

        val cardLayout = hBoxLayout(card) {
            widgetSpacing = 12
            contentsMargins = 12.m
            setAlignment(Qt.AlignmentFlag.AlignVCenter)
        }

        val avatarCont = widget()
        val avatarLayout = vBoxLayout(avatarCont) {
            widgetSpacing = 0
            contentsMargins = 0.m
        }

        val avatarLabel = label {
            objectName = "AccountPanelAvatarLabel"
            val scaled = avatar?.scaled(72,72) ?: QPixmap()

            pixmap = scaled
            minimumSize = qs(72, 72)
            maximumSize = qs(72, 72)
            setStyle {
                borderRadius(36)
            }
        }
        avatarLayout.addWidget(avatarLabel)
        cardLayout.addWidget(avatarCont, 0)

        val textCont = QWidget()
        val textLayout = vBoxLayout(textCont)
        textLayout.contentsMargins = 0.m
        textLayout.widgetSpacing = 4
        val nameLabel = label {
            text = displayName
            objectName = "accountName"
        }
        textLayout.addWidget(nameLabel)
        if (!subtitle.isNullOrBlank()) {
            val sub = label {
                text = subtitle
                objectName = "accountSub"
            }
            textLayout.addWidget(sub)
        }
        cardLayout.addWidget(textCont, 1)

        val actions = QWidget()
        val actionsLayout = hBoxLayout(actions) {
            contentsMargins = 0.m
            widgetSpacing = 8
            setAlignment(Qt.AlignmentFlag.AlignRight)
        }

        val primaryBtn = pushButton {
            text = actionText
            objectName = "primary"
            setFixedHeight(36)
        }
        actionsLayout.addWidget(primaryBtn)

        var secBtn: QPushButton? = null
        if (!secondaryText.isNullOrBlank() && secondaryHandler != null) {
            secBtn = pushButton {
                text = secondaryText
                objectName = "secondary"
                isEnabled = !isLoading
                setFixedHeight(32)
            }
            actionsLayout.addWidget(secBtn)
        }

        secBtn?.onClicked {
            runOnGuiThread {
                isLoading = true
                primaryBtn.isEnabled = false
            }

            scope.launch {
                try {
                    secondaryHandler?.invoke()
                } catch (t: Throwable) {
                    logger.warn("Secondary handler error", t)
                } finally {
                    runOnGuiThread {
                        isLoading = false
                        QTimer.singleShot(0) { refreshUI() }
                    }
                }
            }
        }

        primaryBtn.onClicked {
            runOnGuiThread {
                isLoading = true
                primaryBtn.isEnabled = false
            }

            scope.launch {
                try {
                    actionHandler()
                } catch (t: Throwable) {
                    logger.warn("Action handler error", t)
                } finally {
                    runOnGuiThread {
                        isLoading = false
                        QTimer.singleShot(0) { refreshUI() }
                    }
                }
            }
        }

        cardLayout.addWidget(actions)
        return card
    }

    private fun refreshUI() {
        if(!isRefreshing.compareAndSet(expectedValue = false, newValue = true)) return

        scope.launch {
            try {
                val providers = try {
                    val registry = BuiltinRegistries.AccountProvider
                    logger.info("Registered account providers: ${registry.toListString()}")
                    registry.all()
                } catch (t: Throwable) {
                    logger.warn("Failed to read providers from registry", t)
                    emptyList()
                }

                val providerResultsDeferred: List<Deferred<Pair<AccountProvider, List<Triple<AccountDescriptor, QPixmap?, AccountProvider>>>>> =
                    providers.map { provider ->
                        async(Dispatchers.IO) {
                            val accounts = try {
                                provider.listAccounts()
                            } catch (t: Throwable) {
                                logger.warn("Provider ${provider.id} failed to list accounts", t)
                                emptyList()
                            }

                            val accountTriples = coroutineScope {
                                accounts.map { acc ->
                                    async(Dispatchers.IO) {
                                        val avatar = try {
                                            provider.getAvatar(acc.id)
                                        } catch (t: Throwable) {
                                            logger.warn("Failed to get avatar for ${provider.id}", t)
                                            null
                                        }
                                        Triple(acc, avatar, provider)
                                    }
                                }.awaitAll()
                            }

                            Pair(provider, accountTriples)
                        }
                    }

                val providerResults = providerResultsDeferred.awaitAll()

                runOnGuiThread {
                    try {
                        clearLayout(mainLayout)

                        val header = QWidget()
                        val headerLayout = hBoxLayout(header) {
                            widgetSpacing = 8
                            contentsMargins = 0.m
                        }

                        val refreshBtn = pushButton {
                            text = "Refresh"
                            setFixedHeight(32)
                            onClicked {
                                isEnabled = false
                                QTimer.singleShot(0) { refreshUI() }
                            }
                        }

                        headerLayout.addStretch(1)
                        headerLayout.addWidget(refreshBtn)
                        mainLayout.addWidget(header)

                        for(result in providerResults) {
                            val provider = result.first
                            val accountList = result.second

                            val section = QWidget()
                            val sectionLayout = vBoxLayout(section) {
                                widgetSpacing = 8
                                contentsMargins = 0.m
                            }

                            val title = LineLabelWidget(provider.displayName, 0.10f)
                            sectionLayout.addWidget(title)

                            if(accountList.isEmpty()) {
                                val signInBtn = pushButton {
                                    text = "Sign in (${provider.displayName})"
                                    objectName = "primary"
                                    setFixedHeight(38)
                                    clicked.connect {
                                        isEnabled = false
                                        scope.launch {
                                            try {
                                                provider.signIn(this@AccountsPanel)
                                            } catch (t: Throwable) {
                                                logger.warn("Provider signIn failed: ${provider.id}", t)
                                            } finally {
                                                runOnGuiThread {
                                                    isEnabled = true; QTimer.singleShot(0) { refreshUI() }
                                                }
                                            }
                                        }
                                    }
                                }
                                sectionLayout.addWidget(signInBtn)
                            } else {
                                for(triple in accountList) {
                                    val (acc, avatar, prov) = triple

                                    val displayName = acc.label ?: acc.username ?: provider.displayName
                                    val subtitle = acc.subtitle ?: acc.username
                                    val accountId = acc.id

                                    val card = createProfileCard(
                                        avatar = avatar,
                                        displayName = displayName,
                                        subtitle = subtitle,
                                        actionText = "Sign out",
                                        actionHandler = suspend {
                                            try {
                                                prov.signOutAccount(accountId)
                                            } catch (t: Throwable) {
                                                logger.warn("signOutAccount failed for ${prov.id}/$accountId", t)
                                            }
                                        },
                                        secondaryText = "Switch",
                                        secondaryHandler = suspend {
                                            val ok = try { prov.switchToAccount(accountId) } catch (t: Throwable) {
                                                logger.warn("switchToAccount failed for ${prov.id}/$accountId", t); false
                                            }
                                            if (!ok) {
                                                try { prov.signIn(this@AccountsPanel) } catch (t: Throwable) { logger.warn("Interactive fallback failed", t) }
                                            }
                                        }
                                    )
                                    sectionLayout.addWidget(card)
                                }
                            }

                            mainLayout.addWidget(section)
                        }

                        mainLayout.addStretch(1)
                        update()
                        repaint()
                    } finally {
                        isRefreshing.store(false)
                    }
                }
            } catch (t: Throwable) {
                logger.warn("Failed to refresh Accounts UI", t)
                runOnGuiThread { isRefreshing.store(false) }
            }
        }
    }
}
