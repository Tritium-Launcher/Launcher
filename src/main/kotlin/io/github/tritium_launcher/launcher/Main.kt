package io.github.tritium_launcher.launcher

import io.github.tritium_launcher.launcher.accounts.MicrosoftAuth.attemptAutoSignIn
import io.github.tritium_launcher.launcher.bootstrap.runLowPriorityTasks
import io.github.tritium_launcher.launcher.bootstrap.startHost
import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.font.loadFont
import io.github.tritium_launcher.launcher.git.Git
import io.github.tritium_launcher.launcher.logging.Logs
import io.github.tritium_launcher.launcher.platform.GameProcessMngr
import io.github.tritium_launcher.launcher.platform.Platform
import io.github.tritium_launcher.launcher.ui.dashboard.Dashboard
import io.github.tritium_launcher.launcher.ui.logging.Hotkeys
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr
import io.github.tritium_launcher.launcher.ui.theme.TritiumProxyStyle
import io.qt.core.QCoreApplication
import io.qt.core.Qt
import io.qt.gui.QFont
import io.qt.gui.QIcon
import io.qt.widgets.QApplication
import io.qt.widgets.QMessageBox
import io.qt.widgets.QStyleFactory
import io.qt.widgets.QWidget
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.prefs.Preferences

// TODO: Needs some cleanup

internal val mainLogger: Logger = LoggerFactory.getLogger(Main::class.java)

@Volatile
internal var appInstance: QApplication? = null

val TApp: QApplication
    get() = appInstance ?: throw IllegalStateException("QApplication not initialized.")

lateinit var referenceWidget: QWidget

class Main {
    companion object {

        @JvmStatic
        fun main(vararg args: String) {
            Logs.prepareForLaunch()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                mainLogger.error("Uncaught exception on thread {}", thread.name, throwable)
            }

            try {
                mainLogger.info("Starting Tritium (argCount={})", args.size)
                Platform.printSystemDetails(mainLogger)

                if (QApplication.instance() == null) QApplication.initialize(args)
                appInstance = QApplication.instance() as QApplication
                Hotkeys.install()

                QCoreApplication.setAttribute(Qt.ApplicationAttribute.AA_EnableHighDpiScaling, true)
                QCoreApplication.setAttribute(Qt.ApplicationAttribute.AA_UseHighDpiPixmaps, true)


                referenceWidget = QWidget()

                manageArguments(args.toList())

                ThemeMngr.init()

                val loaders = startHost(TConstants.EXT_DIR)
                Git.init()

                attemptAutoSignIn()

                val baseStyle = QStyleFactory.create("Fusion") ?: QApplication.style()
                QApplication.setStyle(TritiumProxyStyle(baseStyle))
                ThemeMngr.setTheme(ThemeMngr.currentThemeId)

                applyStartupFont()

                QApplication.setWindowIcon(QIcon(resourceIcon("icons/tritium.png", TConstants.classLoader)!!))
                QApplication.setDesktopFileName("tritium")
                QApplication.setApplicationName("tritium")
                TApp.aboutToQuit.connect { handleRunningGamesOnExit() }

                Dashboard.createAndShow()

                runBlocking {
                    runLowPriorityTasks()
                }

                QApplication.exec()
            } catch (t: Throwable) {
                mainLogger.error("Fatal startup failure", t)
                throw t
            }
        }

        private fun handleRunningGamesOnExit() {
            val running = GameProcessMngr.active().filter { it.isRunning }
            if (running.isEmpty()) return

            val policy = CoreSettingValues.closeGameOnExitPolicy()
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

        private fun applyStartupFont() {
            val prefs = Preferences.userRoot().node("/tritium")
            val defaultLoaded = loadFont("/fonts/Inter/InterVariable.ttf")?.let { QFont(it, 10) }

            val savedFamily = prefs.get("globalFontFamily", null)
            val savedSize = prefs.getInt("globalFontSize", -1)
            val useSaved = !savedFamily.isNullOrBlank() && savedSize > 0

            val fontToSet = when {
                useSaved -> QFont(savedFamily, savedSize)
                defaultLoaded != null -> defaultLoaded
                else -> null
            }

            fontToSet?.let { QApplication.setFont(it) }
        }
    }
}
