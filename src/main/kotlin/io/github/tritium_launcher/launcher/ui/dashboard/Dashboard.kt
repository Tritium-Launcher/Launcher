package io.github.tritium_launcher.launcher.ui.dashboard

import io.github.tritium_launcher.launcher.*
import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.ui.settings.SettingsLink
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.qWidget
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.core.Qt
import io.qt.gui.QIcon
import io.qt.widgets.*

/**
 * The main window for viewing Projects, Accounts, Themes and Settings.
 */
class Dashboard internal constructor() : QMainWindow() {
    private object DashboardBackground

    companion object {
        var I: Dashboard? = null

        @JvmStatic
        fun createAndShow() {
            val win = Dashboard()
            win.show()
            I = win
        }

        internal val logger = logger()
        internal val bgDashboardLogger = logger(DashboardBackground::class)
    }

    private val stackedWidget = QStackedWidget().apply {
        objectName = "dashboardStack"
        frameShape = QFrame.Shape.NoFrame
        lineWidth = 0
    }
    private var settingsBtn: QPushButton
    private val settingsDialog by lazy { SettingsDialog(this) }
    private var selectedButton: QPushButton? = null
    private val dashboardWindowSize: Pair<Int, Int> = CoreSettingValues.dashboardWindowSize()

    init {
        windowTitle = "Tritium - Dashboard"
        minimumSize = qs(dashboardWindowSize.first, dashboardWindowSize.second)
        maximumSize = qs(dashboardWindowSize.first, dashboardWindowSize.second)
        isWindowModified = false
        windowIcon = QIcon(TIcons.Tritium)

        val central = qWidget {
            objectName = "dashboard"
        }

        val mainLayout = hBoxLayout(central) {
            setSpacing(0)
            setContentsMargins(0, 0, 0, 0)
        }

        val leftWidget = qWidget {
            objectName = "dashboardNav"
            setContentsMargins(0, 0, 0, 0)
        }

        stackedWidget.setContentsMargins(0, 0, 0, 0)

        val leftLayout = vBoxLayout(leftWidget)
        leftLayout.contentsMargins = 10.m
        leftLayout.widgetSpacing = 0

        val tritiumWidget = qWidget {
            objectName = "dashboardTritium"
            setContentsMargins(0, 0, 0, 0)
        }
        val tritiumLayout = hBoxLayout(tritiumWidget) {
            contentsMargins = 6.m
            widgetSpacing = 8
        }
        val tritiumIconLabel = label {
            objectName = "dashboardTritiumIcon"
            val icon = TIcons.Tritium
            if (!icon.isNull) {
                pixmap = icon.scaled(qs(22, 22), Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.FastTransformation)
            }
            minimumSize = qs(22, 22)
            maximumSize = qs(22, 22)
        }
        val tritiumTextWidget = qWidget {
            objectName = "dashboardTritiumText"
            setContentsMargins(0, 0, 0, 0)
        }
        val tritiumTextLayout = vBoxLayout(tritiumTextWidget) {
            contentsMargins = 0.m
            widgetSpacing = 0
        }
        val tritiumTitleLabel = label("Tritium") {
            objectName = "dashboardTritiumTitle"
        }
        val tritiumVersionLabel = label("v${TConstants.VERSION}") {
            objectName = "dashboardTritiumVersion"
        }
        tritiumTextLayout.add(tritiumTitleLabel, tritiumVersionLabel)
        tritiumLayout.addWidget(tritiumIconLabel, 0)
        tritiumLayout.addWidget(tritiumTextWidget, 1)

        val projectsBtn = createNavBtn("Projects")
        val accountBtn  = createNavBtn("Accounts")
        val themesBtn   = createNavBtn("Themes")
        settingsBtn = createNavBtn("Settings").apply {
            isCheckable = false
        }

        selectedButton = projectsBtn
        projectsBtn.isChecked = true

        projectsBtn.onClicked {
            updateSelectedBtn(projectsBtn)
            stackedWidget.currentIndex = 0
        }

        accountBtn.onClicked {
            updateSelectedBtn(accountBtn)
            stackedWidget.currentIndex = 1
        }

        themesBtn.onClicked {
            updateSelectedBtn(themesBtn)
            stackedWidget.currentIndex = 2
        }

        settingsBtn.onClicked {
            openSettings()
        }

        leftLayout.addWidget(tritiumWidget)
        leftLayout.addSpacing(8)
        leftLayout.add(projectsBtn, accountBtn, themesBtn, settingsBtn)
        leftLayout.addStretch(1)

        val bottomWidget = qWidget()
        val bottomLayout = vBoxLayout(bottomWidget)
        bottomLayout.widgetSpacing = 10
        bottomLayout.contentsMargins = 0.m

        leftLayout.add(bottomWidget)

        setupNavPanels()

        val divider = QFrame().apply {
            objectName = "dashboardDivider"
            frameShape = QFrame.Shape.NoFrame
            frameShadow = QFrame.Shadow.Plain
            lineWidth = 0
            setAttribute(Qt.WidgetAttribute.WA_StyledBackground, true)
            setFixedWidth(1)
            sizePolicy = QSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Expanding)
        }

        mainLayout.addWidget(leftWidget, 0)
        mainLayout.addWidget(divider, 0)
        mainLayout.addWidget(stackedWidget, 1)


        setCentralWidget(central)

        central.setThemedStyle {
            selector("#dashboard") {
                backgroundColor(TColors.Surface0)
            }

            selector("#dashboardNav") {
                backgroundColor(TColors.Surface1)
            }

            selector("#dashboardDivider") {
                backgroundColor(TColors.Surface2)
            }

            selector("#dashboardTritium") {
                backgroundColor(TColors.Surface0)
                border(1, TColors.Surface2)
                borderRadius(6)
            }
            selector("#dashboardTritiumTitle") {
                fontSize(13)
                fontWeight(700)
                color(TColors.Text)
            }
            selector("#dashboardTritiumVersion") {
                fontSize(11)
                color(TColors.Subtext)
            }

            selector("#navBtn") {
                borderRadius(4)
                border()
                textAlign("bottom")
                padding(top = 6, bottom = 6)
                background("transparent")
            }
            selector("#navBtn:focus") {
                outlineColor("none")
            }
            selector("QPushButton#navBtn:checked") {
                backgroundColor(TColors.Highlight)
            }
        }
    }

    private fun createNavBtn(label: String): QPushButton {
        val btn = QPushButton(label).apply {
            isCheckable = true
            objectName = "navBtn"
            isEnabled = true
            focusPolicy = Qt.FocusPolicy.StrongFocus
            sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
            minimumHeight = 20
        }
        return btn
    }

    private fun updateSelectedBtn(newBtn: QPushButton) {
        selectedButton?.isChecked = false
        newBtn.isChecked = true
        selectedButton = newBtn
    }

    private fun setupNavPanels() {
        // Projects
        val projectsPanel = ProjectsPanel()
        stackedWidget.addWidget(projectsPanel)

        // Accounts
        val accountsPanel = AccountsPanel()
        stackedWidget.addWidget(accountsPanel)

        // Themes
        val themesPanel = ThemesPanel()
        stackedWidget.addWidget(themesPanel)
    }

    /**
     * Opens settings in a dedicated dialog and optionally focuses [link].
     *
     * @param link Optional target setting deep link.
     */
    fun openSettings(link: SettingsLink? = null) {
        show()
        raise()
        activateWindow()
        settingsDialog.open(link)
    }
}
