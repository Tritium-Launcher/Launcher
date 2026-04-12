package io.github.tritium_launcher.launcher.ui.project.editor.pane

import io.github.tritium_launcher.launcher.TConstants
import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.matches
import io.github.tritium_launcher.launcher.ui.project.editor.EditorPane
import io.github.tritium_launcher.launcher.ui.project.editor.EditorPaneProvider
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.UniversalHighlighter
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.qWidget
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.Nullable
import io.qt.core.Qt
import io.qt.gui.QFont
import io.qt.gui.QMovie
import io.qt.gui.QPixmap
import io.qt.gui.QResizeEvent
import io.qt.widgets.*

/**
 * An Editor Pane specifically tailored to viewing Image files.
 * @see ImageViewerProvider
 * @see EditorPane
 */
class ImageViewerPane(project: ProjectBase, file: VPath): EditorPane(project, file) {
    private var originalPixmap: QPixmap? = null
    private var scaledPixmap: QPixmap? = null
    private var movie: QMovie? = null

    private val imageLabel = object : QLabel() {
        init {
            setAlignment(Qt.AlignmentFlag.AlignCenter)
            setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Ignored)
        }

        override fun resizeEvent(event: @Nullable QResizeEvent?) {
            super.resizeEvent(event)
            updatePixmap()
        }

        fun updatePixmap() {
            val m = movie
            val usingMovie = m != null && m.isValid
            val pix = if(usingMovie) m.currentPixmap() else originalPixmap
            if(pix == null) return

            try {
                if(pix.isNull) return

                val w = width
                val h = height
                if(w <= 0 || h <= 0) return

                val sourceW = pix.width()
                val sourceH = pix.height()

                val dpr = devicePixelRatio()
                val targetW = (w * dpr).toInt()
                val targetH = (h * dpr).toInt()

                val isUpscaling = targetW > sourceW || targetH > sourceH
                val isSmall = sourceW <= 128 || sourceH <= 128

                val transformMode = if (isUpscaling && isSmall) {
                    Qt.TransformationMode.FastTransformation
                } else {
                    Qt.TransformationMode.SmoothTransformation
                }

                val scaled = pix.scaled(
                    targetW, targetH,
                    Qt.AspectRatioMode.KeepAspectRatio,
                    transformMode
                )

                try {
                    scaled.setDevicePixelRatio(dpr)
                } catch (_: Throwable) {
                }

                scaledPixmap?.dispose()
                scaledPixmap = scaled
                super.setPixmap(scaled)
            } finally {
                if(usingMovie) {
                    pix.dispose()
                }
            }
        }
    }

    private val scroll = QScrollArea().apply {
        setWidget(imageLabel)
        widgetResizable = true
        setAlignment(Qt.AlignmentFlag.AlignCenter)
    }

    private val svgTextEdit = QTextEdit().apply {
        font = QFont("JetBrains Mono", 10)
        lineWrapMode = QTextEdit.LineWrapMode.NoWrap
        isVisible = false
    }

    private val content = qWidget {
        hBoxLayout(this) {
            addWidget(scroll, 1)
            addWidget(svgTextEdit, 1)
        }
    }

    private val infoLabel = label {
        isVisible = false
    }

    private val playPauseButton = QToolButton().apply {
        icon = TIcons.SmallPause.icon
        toolTip = "Pause"
        isVisible = false
        clicked.connect { toggleMoviePlayback() }
    }

    private val infoRow = qWidget {
        hBoxLayout(this) {
            contentsMargins = 4.m
            widgetSpacing = 6
            addWidget(infoLabel)
            addStretch()
            addWidget(playPauseButton)
        }
    }

    private val root = qWidget {
        vBoxLayout(this) {
            contentsMargins = 0.m
            widgetSpacing = 4
            addWidget(infoRow)
            addWidget(content)
        }
    }

    private fun toggleMoviePlayback() {
        val m = movie ?: return
        when (m.state()) {
            QMovie.MovieState.Running -> m.setPaused(true)
            QMovie.MovieState.Paused -> m.setPaused(false)
            QMovie.MovieState.NotRunning -> m.start()
        }
        updateMovieControls()
    }

    private fun updateMovieControls() {
        val m = movie
        if (m != null && m.isValid) {
            playPauseButton.isVisible = true
            val running = m.state() == QMovie.MovieState.Running
            playPauseButton.icon = if (running) TIcons.SmallPause.icon else TIcons.SmallPlay.icon
            playPauseButton.toolTip = if (running) "Pause" else "Play"
        } else {
            playPauseButton.isVisible = false
        }
    }

    override fun onOpen() {
        val path = file.toAbsoluteString()
        val m = QMovie(path)

        if(m.isValid) {
            m.setCacheMode(QMovie.CacheMode.CacheNone)
            this.movie = m
            m.frameChanged.connect { _ -> imageLabel.updatePixmap() }
            m.stateChanged.connect { _ -> updateMovieControls() }
            m.start()
        } else {
            m.dispose()
            originalPixmap = QPixmap(path)
        }

        updateMovieControls()
        imageLabel.updatePixmap()

        val isSvg = file.extension().matches("svg", "svgz")
        if(isSvg) {
            svgTextEdit.plainText = file.readTextOr("")
            svgTextEdit.isVisible = true

            val syntaxRegistry = BuiltinRegistries.SyntaxLanguage
            val lang = syntaxRegistry.all().find { it.matches(file) }
            if(lang != null) {
                UniversalHighlighter(svgTextEdit.document!!, lang)
            }
        } else {
            svgTextEdit.isVisible = false
        }

        val infoFromMovie = movie != null
        val pix = if(infoFromMovie) movie!!.currentPixmap() else originalPixmap
        if(pix != null && !pix.isNull) {
            val bytes = file.sizeOrNull() ?: 0L
            val sizeStr = formatFileSize(bytes)
            val frames = movie?.frameCount() ?: 0
            val typeInfo = if(movie != null && frames > 1) ", Animation ($frames frames)" else ""
            infoLabel.text = "${pix.width()}x${pix.height()}, $sizeStr$typeInfo"
            infoLabel.isVisible = true
        }
        if(infoFromMovie && pix != null) {
            pix.dispose()
        }
    }

    override fun onClose() {
        movie?.stop()
        movie?.dispose()
        movie = null
        playPauseButton.isVisible = false
        scaledPixmap?.dispose()
        scaledPixmap = null
        originalPixmap?.dispose()
        originalPixmap = null
        imageLabel.clear()
    }

    override suspend fun save(): Boolean {
        if(svgTextEdit.isVisible) {
            return try {
                file.writeBytes(svgTextEdit.toPlainText().toByteArray())
                true
            } catch (_: Throwable) { false }
        }
        return true
    }

    private fun formatFileSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while(size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return "%.1f %s".format(size, units[unitIndex])
    }

    override fun widget(): QWidget = root
}

class ImageViewerProvider : EditorPaneProvider {
    override val id: String = "image_viewer"
    override val displayName: String = "ImageViewer"
    override val order: Int = 10

    override fun canOpen(
        file: VPath,
        project: ProjectBase
    ): Boolean = file.extension().matches(TConstants.Lists.ImageExtensions)

    override fun create(
        project: ProjectBase,
        file: VPath
    ): EditorPane = ImageViewerPane(project, file)
}
