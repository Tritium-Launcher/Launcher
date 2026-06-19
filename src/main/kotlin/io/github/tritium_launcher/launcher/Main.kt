package io.github.tritium_launcher.launcher

import io.github.tritium_launcher.launcher.accounts.MicrosoftAuth.attemptAutoSignIn
import io.github.tritium_launcher.launcher.bootstrap.runLowPriorityTasks
import io.github.tritium_launcher.launcher.bootstrap.startHost
import io.github.tritium_launcher.launcher.bootstrap.startKeymap
import io.github.tritium_launcher.launcher.bootstrap.startSettings
import io.github.tritium_launcher.launcher.core.project.ProjectMngr
import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.font.FontMngr
import io.github.tritium_launcher.launcher.git.Git
import io.github.tritium_launcher.launcher.logging.Logs
import io.github.tritium_launcher.launcher.platform.GameProcessMngr
import io.github.tritium_launcher.launcher.platform.Platform
import io.github.tritium_launcher.launcher.ui.dashboard.Dashboard
import io.github.tritium_launcher.launcher.ui.global.TooltipInterceptor
import io.github.tritium_launcher.launcher.ui.helpers.installEventFilter
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr
import io.github.tritium_launcher.launcher.ui.theme.TritiumProxyStyle
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.util.SeasonalEvents.isPrideMonth
import io.qt.core.QCoreApplication
import io.qt.core.QLogging
import io.qt.core.Qt
import io.qt.core.QtMsgType
import io.qt.gui.QFont
import io.qt.gui.QIcon
import io.qt.widgets.QApplication
import io.qt.widgets.QMessageBox
import io.qt.widgets.QStyleFactory
import io.qt.widgets.QWidget
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler

// TODO: Needs some cleanup

internal val mainLogger: Logger = LoggerFactory.getLogger(Main::class.java)
internal val qtLogger:   Logger = LoggerFactory.getLogger(Qt::class.java)

/**
 * QApplication instance
 */
@Volatile
internal var appInstance: QApplication? = null

/**
 * Global QApplication Getter
 */
val TApp: QApplication
    get() = appInstance ?: throw IllegalStateException("QApplication not initialized.")

/**
 * Used for reference elsewhere
 */
lateinit var referenceWidget: QWidget

/** Main Entrypoint */
class Main {
    companion object {

        @JvmStatic
        fun main(vararg args: String) {
            SLF4JBridgeHandler.removeHandlersForRootLogger()
            SLF4JBridgeHandler.install()
            installQtMessageHandler()
            Logs.prepareForLaunch()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                mainLogger.error("Uncaught exception on thread {}", thread.name, throwable)
            }

            try {
                mainLogger.info("Starting Tritium (argCount={})", args.size)
                Platform.printSystemDetails(mainLogger)

                check(QApplication.instance() == null) { "QApplication already initialized" }
                QApplication.initialize(args)
                appInstance = QApplication.instance() as QApplication

                QCoreApplication.setAttribute(Qt.ApplicationAttribute.AA_EnableHighDpiScaling, true)
                QCoreApplication.setAttribute(Qt.ApplicationAttribute.AA_UseHighDpiPixmaps, true)

                referenceWidget = QWidget()

                manageArguments(args.toList())

                ThemeMngr.init()

                startSettings()

                startHost(TConstants.EXT_DIR)

                startKeymap()

                Git.init()

                attemptAutoSignIn()

                val baseStyle = QStyleFactory.create("Fusion") ?: QApplication.style()
                QApplication.setStyle(TritiumProxyStyle(baseStyle))
                ThemeMngr.setTheme(ThemeMngr.currentThemeIdValue)

                applyStartupFont()

                QApplication.setWindowIcon(
                    if (isPrideMonth()) {
                        TIcons.TritiumGrayscale.applyRainbowOverlay(opacity = 0.5f).icon
                    } else {
                        QIcon(TIcons.Tritium.scaled(qs(256, 256), Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation))
                    }
                )
                QApplication.setDesktopFileName("tritium")
                QApplication.setApplicationName("tritium")
                TApp.aboutToQuit.connect { handleRunningGamesOnExit() }
                TApp.installEventFilter(TooltipInterceptor(), CoreSettingValues.uiGameTooltipStyle)

                val reopened = CoreSettingValues.reopenLastProjectOnLaunch && ProjectMngr.loadActiveProject()
                if (!reopened) {
                    Dashboard.createAndShow()
                }

                runBlocking {
                    runLowPriorityTasks()
                }

                if (Platform.isWindows && CoreSettingValues.blockRecallOnStart) {
                    val helper = Platform.resolveOsHelper()
                    val cmd = listOfNotNull(helper ?: "os-helper", "block-recall", "--pid", ProcessHandle.current().pid().toString())
                    mainLogger.info("Starting Windows Recall blocker: {}", cmd.joinToString(" "))
                    Platform.runProcess(cmd)
                }

                QApplication.exec()
            } catch (t: Throwable) {
                mainLogger.error("Fatal startup failure", t)
                throw t
            }
        }

        private fun installQtMessageHandler() {
            QLogging.qInstallMessageHandler({ type, ctx, msg ->
                when(type) {
                    QtMsgType.QtDebugMsg    -> qtLogger.debug(msg)
                    QtMsgType.QtWarningMsg  -> qtLogger.warn(msg)
                    QtMsgType.QtCriticalMsg -> qtLogger.error(msg)
                    QtMsgType.QtFatalMsg    -> qtLogger.error("[FATAL] $msg")
                    QtMsgType.QtInfoMsg     -> qtLogger.info(msg)
                    QtMsgType.QtSystemMsg   -> qtLogger.trace(msg)
                }
            })
        }

        /**
         * If the game is running when trying to close Tritium,
         * ask to close the game depending on [CoreSettingValues.closeGameOnExitPolicy]
         */
        private fun handleRunningGamesOnExit() {
            val running = GameProcessMngr.active().filter { it.isRunning }
            if (running.isEmpty()) return

            val policy = CoreSettingValues.closeGameOnExitPolicy
            val shouldClose = when (policy) {
                CoreSettingValues.CloseGameOnExitPolicy.Never -> false
                CoreSettingValues.CloseGameOnExitPolicy.Always -> true
                CoreSettingValues.CloseGameOnExitPolicy.Ask -> {
                    val count = running.size
                    val question = if (count == 1) {
                        "Close the running game process before exiting?"
                    } else {
                        "Close $count running game processes before exiting?"
                    }
                    val parent = QApplication.activeWindow() ?: Dashboard.I
                    val choice = QMessageBox.question(
                        parent,
                        "Close Running Game",
                        question,
                        QMessageBox.StandardButtons(
                            QMessageBox.StandardButton.Yes,
                            QMessageBox.StandardButton.No
                        ),
                        QMessageBox.StandardButton.Yes
                    )
                    choice == QMessageBox.StandardButton.Yes
                }
            }
            if (!shouldClose) return

            running.forEach { ctx ->
                GameProcessMngr.killByScope(ctx.projectScope, force = true)
            }
        }

        /**
         * Applies the startup font using the bundled Inter as default,
         * or a user-configured family/size from settings.
         */
        private fun applyStartupFont() {
            FontMngr.init()

            val (family, size) = CoreSettingValues.globalFont()
            QApplication.setFont(QFont(family, size))
        }
    }
}
