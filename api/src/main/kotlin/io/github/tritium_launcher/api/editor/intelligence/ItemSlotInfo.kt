/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.editor.intelligence

data class ItemSlotInfo(
    val startByte: Int,
    val endByte: Int,
    val exprStartByte: Int,
    val exprEndByte: Int
)
