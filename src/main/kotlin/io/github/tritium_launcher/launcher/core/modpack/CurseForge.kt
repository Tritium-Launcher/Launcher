package io.github.tritium_launcher.launcher.core.modpack

import io.github.tritium_launcher.launcher.registry.Registrable
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.qt.gui.QPixmap

data class CurseForge(
    override val id: String = "curseforge",
    override val displayName: String = "CurseForge",
    override val icon: QPixmap = TIcons.CurseForge,
    override val webpage: String = "https://www.curseforge.com/",
    override val order: Int = 1
) : ModSource(), Registrable {

    override fun toString(): String = id
}