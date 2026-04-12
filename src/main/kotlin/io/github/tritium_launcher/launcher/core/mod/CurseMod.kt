package io.github.tritium_launcher.launcher.core.mod

data class CurseMod(
    override val id: String,
    override val version: String,
    override val source: String
) : Mod