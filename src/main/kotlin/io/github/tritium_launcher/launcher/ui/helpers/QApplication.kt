package io.github.tritium_launcher.launcher.ui.helpers

import io.qt.core.QObject
import io.qt.widgets.QApplication

fun QApplication.installEventFilter(filterObj: QObject, condition: Boolean) {
    if(condition) this.installEventFilter(filterObj)
}