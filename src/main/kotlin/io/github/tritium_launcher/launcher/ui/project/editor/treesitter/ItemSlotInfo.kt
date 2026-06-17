package io.github.tritium_launcher.launcher.ui.project.editor.treesitter

data class ItemSlotInfo(
    val startByte: Int,
    val endByte: Int,
    val exprStartByte: Int,
    val exprEndByte: Int
)