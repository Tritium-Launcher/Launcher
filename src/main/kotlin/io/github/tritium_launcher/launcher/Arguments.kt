/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher

var debugLogging: Boolean = false
var genThemeSchema: Boolean = false

/**
 * Manages Tritium arguments
 *
 * TODO: Make this better
 */
internal fun manageArguments(args: List<String>) {

    debugLogging   = args.any { it == "-debug" || it == "--debug" || it == "-d"  }
    genThemeSchema = args.any { it == "-gts" }
}
