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