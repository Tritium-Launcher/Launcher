package io.github.tritium_launcher.launcher.import.ui

import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.import.mapLoaderId
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.frame
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.qt.core.Qt
import io.qt.widgets.QFrame
import io.qt.widgets.QWidget

fun buildMetaTagsWidget(gameVersion: String?, loaderName: String?): QWidget? {
    var hasAny = false
    val metaWidget = QWidget()
    val metaLayout = hBoxLayout(metaWidget) {
        setContentsMargins(0, 0, 0, 0)
        setSpacing(6)
    }

    if (gameVersion != null) {
        val verFrame = frame {
            frameShape = QFrame.Shape.NoFrame
            setFixedHeight(22)
            styleSheet = "border: 1px solid ${TColors.Green}; border-radius: 8px; background: transparent;"
        }
        val verLayout = hBoxLayout(verFrame) {
            setContentsMargins(8, 2, 8, 2)
            setSpacing(0)
        }
        verLayout.addWidget(label(gameVersion) {
            styleSheet = "color: ${TColors.Green}; font-size: 11px; font-weight: 600; background: transparent; border: none;"
        })
        metaLayout.addWidget(verFrame, 0, Qt.AlignmentFlag.AlignVCenter)
        hasAny = true
    }

    if (loaderName != null) {
        val loaderId = mapLoaderId(loaderName)
        val loader = if (loaderId != null) BuiltinRegistries.ModLoader.all().find { it.id == loaderId } else null
        val loaderFrame = frame {
            frameShape = QFrame.Shape.NoFrame
            setFixedHeight(22)
            styleSheet = "border: 1px solid ${TColors.Warning}; border-radius: 8px; background: transparent;"
        }
        val loaderLayout = hBoxLayout(loaderFrame) {
            setContentsMargins(8, 2, 8, 2)
            setSpacing(4)
        }
        if (loader != null) {
            loaderLayout.addWidget(label {
                pixmap = loader.icon
                setFixedSize(16, 16)
                styleSheet = "background: transparent; border: none;"
            }, 0, Qt.AlignmentFlag.AlignVCenter)
        }
        loaderLayout.addWidget(label(loader?.displayName ?: loaderName) {
            styleSheet = "color: ${TColors.Warning}; font-size: 11px; font-weight: 600; background: transparent; border: none;"
        }, 0, Qt.AlignmentFlag.AlignVCenter)
        metaLayout.addWidget(loaderFrame, 0, Qt.AlignmentFlag.AlignVCenter)
        hasAny = true
    }

    return if (hasAny) metaWidget else null
}
