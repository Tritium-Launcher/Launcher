package io.github.tritium_launcher.launcher

var debugLogging: Boolean = false
var genThemeSchema: Boolean = false

internal fun manageArguments(args: List<String>) {

    debugLogging   = args.any { it == "-debug" || it == "--debug" || it == "-d"  }
    genThemeSchema = args.any { it == "-gts" }
}