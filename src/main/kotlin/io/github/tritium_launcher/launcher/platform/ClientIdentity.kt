package io.github.tritium_launcher.launcher.platform

import io.github.tritium_launcher.launcher.TConstants

/**
 * Client identity strings used in requests and diagnostic output.
 */
object ClientIdentity {

    const val CONTACT = "footermandev@protonmail.com"

    val osName: String by lazy {
        Platform.current.toString()
    }

    val osVer: String by lazy {
        Platform.version
    }

    val arch: String by lazy {
        Platform.arch
    }

    val jvmVer: String by lazy {
        System.getProperty("java.version") ?: "unknown"
    }

    val jvmVendor: String by lazy {
        System.getProperty("java.vendor") ?: "unknown"
    }

    val userAgent: String by lazy {
        buildString {
            append("Tritium Minecraft Launcher (TESTING)") //TODO: Not testing forever
            append("/")
            append(TConstants.VERSION)
            append(" (")

            append(osName)
            append("; ")
            append("arch=")
            append(arch)
            append("; ")
            append("os=")
            append(osVer)
            append("; ")
            append("jvm=")
            append(jvmVer)

            append(") ")
            append("+")
            append(CONTACT)
        }
    }

    /**
     * Compact identity header with version and platform details.
     */
    val clientInfoHeader: String by lazy {
        "Tritium Minecraft Launcher (TESTING)/${TConstants.VERSION}; os=$osName; arch=$arch; jvm=$jvmVer" //TODO: Not testing forever
    }
}
