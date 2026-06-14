package io.github.tritium_launcher.launcher.ui.dashboard

import io.github.tritium_launcher.launcher.*
import io.github.tritium_launcher.launcher.accounts.AccountDescriptor
import io.github.tritium_launcher.launcher.accounts.AccountProvider
import io.github.tritium_launcher.launcher.accounts.AuthMethod
import io.github.tritium_launcher.launcher.accounts.ProfileMngr
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.platform.Platform
import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.setStyle
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.*
import io.qt.Nullable
import io.qt.core.QTimer
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.widgets.*
import kotlinx.coroutines.*
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * [Dashboard] panel showing connected accounts and methods to connect them.
 *
 * Account services can be provided by extensions by registering an [AccountProvider].
 * @see [io.github.tritium_launcher.launcher.accounts.MicrosoftAccountProvider]
 * @see [io.github.tritium_launcher.launcher.extension.core.CoreExtension]
 */
@OptIn(ExperimentalAtomicApi::class)
class AccountsPanel internal constructor(): QWidget() {

    @Volatile
    private var isLoading: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val isRefreshing = AtomicBoolean(false)

    private val contentWidget = QWidget()
    private val mainLayout = vBoxLayout(contentWidget) {
        contentsMargins = 12.m
        widgetSpacing = 16
    }

    private val logger = logger()

    init {
        val outerLayout = vBoxLayout(this) {
            contentsMargins = 0.m
            widgetSpacing = 0
        }

        val scrollArea = QScrollArea()
        scrollArea.setWidget(contentWidget)
        scrollArea.widgetResizable = true
        scrollArea.frameShape = QFrame.Shape.NoFrame
        scrollArea.horizontalScrollBarPolicy = Qt.ScrollBarPolicy.ScrollBarAlwaysOff
        outerLayout.addWidget(scrollArea)

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

            selector("QLabel#keyEntryLabel") {
                fontSize(11)
                color(TColors.Subtext)
            }
            selector("QLineEdit#keyEntryField") {
                borderRadius(6)
                padding(6, 10)
            }
            selector("QPushButton#keyEntryOpen") {
                border()
                background("transparent")
                borderRadius(6)
                padding(6)
            }
            selector("QPushButton#keyEntryOpen:hover") {
                backgroundColor(TColors.Surface2)
            }
            selector("QLabel#keyEntryStatus") {
                fontSize(11)
                padding(4, 8)
                borderRadius(4)
            }
            selector("QLabel#keyEntryStatus[status=\"valid\"]") {
                color(TColors.Green)
            }
            selector("QLabel#keyEntryStatus[status=\"invalid\"]") {
                color(TColors.Error)
            }
            selector("QLabel#keyEntryStatus[status=\"unknown\"]") {
                color(TColors.Subtext)
            }
            selector("QLabel#sectionTitle") {
                fontSize(15)
                fontWeight(700)
            }
            selector("QFrame#providerSection") {
                borderRadius(10)
            }
        }

        this.destroyed.connect {
            scope.cancel()
        }

        scope.launch {
            ProfileMngr.profile.collect { _ ->
                isLoading = false
                QTimer.singleShot(0) { refreshUI() }
            }
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
        userAvatar: QPixmap?,
        displayName: String,
        subtitle: String?,
        actionText: String,
        actionHandler: suspend () -> Unit
    ): QWidget {

        val card = frame {
            objectName = "accountCard"
            frameShape = QFrame.Shape.NoFrame
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

        val avatarPix = userAvatar?.scaled(64, 64, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation) ?: QPixmap()
        val avatarLabel = label {
            objectName = "AccountPanelAvatarLabel"
            pixmap = avatarPix
            minimumSize = qs(64, 64)
            maximumSize = qs(64, 64)
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

    private val addEntryVisible = mutableMapOf<String, Boolean>()

    private fun createKeyEntryRow(provider: AccountProvider): QWidget {
        val row = qWidget { objectName = "keyEntryRow" }
        val layout = hBoxLayout(row) {
            contentsMargins = 0.m
            widgetSpacing = 8
            setAlignment(Qt.AlignmentFlag.AlignLeft)
        }

        val lbl = label(provider.tokenLabel ?: "Token:") { objectName = "keyEntryLabel" }
        layout.addWidget(lbl)

        val field = QLineEdit().apply {
            setFixedWidth(280)
        }
        layout.addWidget(field)

        val tokenPageUrl = provider.tokenPageUrl
        if (!tokenPageUrl.isNullOrBlank()) {
            val openBtn = pushButton {
                objectName = "keyEntryOpen"
                icon = QIcon(TIcons.ExternalArrow)
                setFixedSize(32, 32)
                onClicked {
                    val setupWidget = provider.createTokenSetupWidget(this@AccountsPanel)
                    if (setupWidget != null) {
                        val dialog = QDialog(this@AccountsPanel).apply {
                            windowTitle = "${provider.displayName} Token Setup"
                            setMinimumSize(400, 420)
                            setMaximumSize(500, 600)
                            setAttribute(Qt.WidgetAttribute.WA_DeleteOnClose, true)
                            objectName = "tokenSetupDialog"
                        }
                        val dialogLayout = vBoxLayout(dialog) {
                            contentsMargins = 0.m
                            setSpacing(0)
                        }
                        dialogLayout.addWidget(setupWidget, 1)

                        val btnBar = QFrame().apply {
                            frameShape = QFrame.Shape.NoFrame
                            val btnLayout = hBoxLayout(this) {
                                setContentsMargins(16, 12, 16, 12)
                                setSpacing(8)
                            }
                            btnLayout.addStretch()
                            val cancelBtn = pushButton("Cancel") {
                                onClicked { dialog.reject() }
                            }
                            btnLayout.addWidget(cancelBtn)
                            val openBtn2 = pushButton("Open Token Page") {
                                onClicked {
                                    Platform.openBrowser(tokenPageUrl)
                                    dialog.accept()
                                }
                            }
                            btnLayout.addWidget(openBtn2)
                        }
                        dialogLayout.addWidget(btnBar)

                        dialog.setThemedStyle {
                            selector("#tokenSetupDialog") {
                                backgroundColor(TColors.Surface0)
                            }
                        }

                        dialog.exec()
                    } else {
                        Platform.openBrowser(tokenPageUrl)
                    }
                }
            }
            layout.addWidget(openBtn)
        }

        val debounceTimer = QTimer()
        debounceTimer.setSingleShot(true)
        debounceTimer.timeout.connect {
            val token = field.text().trim()
            if (token.isEmpty()) return@connect
            scope.launch {
                try {
                    provider.signInWithToken(token, this@AccountsPanel)
                } catch (t: Throwable) {
                    logger.warn("Token validation failed for ${provider.id}", t)
                } finally {
                    runOnGuiThread { refreshUI() }
                }
            }
        }
        field.textChanged.connect { debounceTimer.start(400) }

        return row
    }

    private fun createAddEntryWidget(provider: AccountProvider): QWidget? = when (provider.authMethod) {
        AuthMethod.KEY -> createKeyEntryRow(provider)
        AuthMethod.OAUTH_AND_KEY -> {
            val container = qWidget()
            val layout = vBoxLayout(container) {
                contentsMargins = 0.m
                widgetSpacing = 8
            }
            val oauthBtn = pushButton {
                text = "Sign In Online"
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
                                isEnabled = true
                                QTimer.singleShot(0) { refreshUI() }
                            }
                        }
                    }
                }
            }
            layout.addWidget(oauthBtn)
            layout.addWidget(createKeyEntryRow(provider))
            container
        }
        AuthMethod.OAUTH -> null
    }

    private fun composeProviderAvatar(serviceIcon: QPixmap?, userAvatar: QPixmap?, size: Int): QPixmap {
        if (serviceIcon == null || serviceIcon.isNull()) {
            return userAvatar?.scaled(size, size, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation) ?: QPixmap()
        }
        val result = QPixmap(size, size)
        result.fill(Qt.GlobalColor.transparent)
        val painter = QPainter(result)
        try {
            val iconSize = size - 16
            val scaled = serviceIcon.scaled(iconSize, iconSize, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
            painter.drawPixmap((size - scaled.width()) / 2, (size - scaled.height()) / 2, scaled)

            if (userAvatar != null && !userAvatar.isNull()) {
                val emblemSize = size / 3
                val margin = 2
                val emX = size - emblemSize - margin
                val emY = size - emblemSize - margin

                val clipPath = QPainterPath()
                clipPath.addEllipse(emX.toDouble(), emY.toDouble(), emblemSize.toDouble(), emblemSize.toDouble())

                painter.setBrush(QBrush(QColor(255, 255, 255)))
                painter.setPen(Qt.PenStyle.NoPen)
                painter.drawEllipse(emX - 1, emY - 1, emblemSize + 2, emblemSize + 2)

                painter.save()
                painter.setClipPath(clipPath)
                val scaledEm = userAvatar.scaled(emblemSize, emblemSize, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
                painter.drawPixmap(emX, emY, scaledEm)
                painter.restore()
            }
        } finally {
            painter.end()
        }
        return result
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
                            val hasAccounts = accountList.isNotEmpty()

                            val section = frame {
                                objectName = "providerSection"
                                frameShape = QFrame.Shape.NoFrame
                                provider.sectionColor?.let { color ->
                                    setAttribute(Qt.WidgetAttribute.WA_StyledBackground, true)
                                    styleSheet = "QFrame#providerSection { background-color: #$color; }"
                                }
                            }
                            val sectionLayout = vBoxLayout(section) {
                                widgetSpacing = 10
                                contentsMargins = 16.m
                            }

                            val headerWidget = qWidget()
                            val headerLayout = hBoxLayout(headerWidget) {
                                contentsMargins = 0.m
                                widgetSpacing = 8
                            }
                            provider.serviceIcon?.let { icon ->
                                val iconLabel = label {
                                    pixmap = icon.scaled(32, 32, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
                                    setFixedSize(32, 32)
                                }
                                headerLayout.addWidget(iconLabel)
                            }
                            val nameLabel = label(provider.displayName) {
                                objectName = "sectionTitle"
                            }
                            headerLayout.addWidget(nameLabel)

                            headerLayout.addStretch(1)

                            val infoDesc = provider.infoDescription
                            if (infoDesc != null) {
                                val infoIcon = label {
                                    pixmap = TIcons.QuestionMark
                                    setFixedSize(16, 16)
                                    toolTip = infoDesc
                                }
                                headerLayout.addWidget(infoIcon)
                            }
                            sectionLayout.addWidget(headerWidget)

                            when {
                                accountList.isEmpty() && provider.authMethod == AuthMethod.KEY -> {
                                    sectionLayout.addWidget(createKeyEntryRow(provider))
                                }
                                accountList.isEmpty() && provider.authMethod == AuthMethod.OAUTH_AND_KEY -> {
                                    val oauthBtn = pushButton {
                                        text = "Sign In Online"
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
                                                        isEnabled = true
                                                        QTimer.singleShot(0) { refreshUI() }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    sectionLayout.addWidget(oauthBtn)

                                    sectionLayout.addWidget(label("Or use a personal access token:") { objectName = "keyEntryLabel" })

                                    sectionLayout.addWidget(createKeyEntryRow(provider))
                                }
                                accountList.isEmpty() -> {
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
                                }
                                else -> {
                                    for(triple in accountList) {
                                        val (acc, avatar, prov) = triple

                                        val displayName = acc.label ?: acc.username ?: provider.displayName
                                        val subtitle = acc.subtitle ?: acc.username
                                        val accountId = acc.id

                                        val card = createProfileCard(
                                            userAvatar = avatar,
                                            displayName = displayName,
                                            subtitle = subtitle,
                                            actionText = "Sign out",
                                            actionHandler = suspend {
                                                try {
                                                    prov.signOutAccount(accountId)
                                                } catch (t: Throwable) {
                                                    logger.warn("signOutAccount failed for ${prov.id}/$accountId", t)
                                                }
                                            }
                                        )
                                        sectionLayout.addWidget(card)
                                    }

                                    if(provider.supportsMultipleAccounts) {
                                        if(addEntryVisible[provider.id] == true) {
                                            createAddEntryWidget(provider)?.let { sectionLayout.addWidget(it) }
                                        }

                                        val addBtn = pushButton {
                                            text = "Add ${provider.displayName} account"
                                            objectName = "primary"
                                            setFixedHeight(38)
                                        }
                                        addBtn.onClicked {
                                            when(provider.authMethod) {
                                                AuthMethod.OAUTH -> {
                                                    addBtn.isEnabled = false
                                                    scope.launch {
                                                        try {
                                                            provider.signIn(this@AccountsPanel)
                                                        } catch (t: Throwable) {
                                                            logger.warn("Provider signIn failed: ${provider.id}", t)
                                                        } finally {
                                                            runOnGuiThread {
                                                                addBtn.isEnabled = true
                                                                QTimer.singleShot(0) { refreshUI() }
                                                            }
                                                        }
                                                    }
                                                }
                                                else -> {
                                                    addEntryVisible[provider.id] = true
                                                    QTimer.singleShot(0) { refreshUI() }
                                                }
                                            }
                                        }
                                        sectionLayout.addWidget(addBtn)
                                    }
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
