package io.github.tritium_launcher.launcher.font

import io.qt.core.QByteArray
import io.qt.gui.QFontDatabase

fun loadFont(path: String): String? {
    val stream = object {}.javaClass.getResourceAsStream(path)
        ?: return null

    val bytes = stream.readBytes()
    stream.close()

    val qba = QByteArray(bytes)
    val fontId = QFontDatabase.addApplicationFontFromData(qba)
    if(fontId == -1) return null

    val families = QFontDatabase.applicationFontFamilies(fontId)
    if(families.isEmpty) return null
    return families[0]
}