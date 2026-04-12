package io.github.tritium_launcher.launcher.core.project.templates.generation.license

import io.github.tritium_launcher.launcher.registry.Registrable
import io.github.tritium_launcher.launcher.resources.ResourceLoader

/**
 * Represents a License for a project
 */
interface License: Registrable {
    override val id: String
    val name: String
    val resourcePath: String
    val requiresAuthor: Boolean
    val requiresYear: Boolean
    val order: Int

    fun content(): String = ResourceLoader.loadText(resourcePath, caller = this::class.java)
}

class NoLicense: License {
    override val id: String = "none"
    override val name: String = "No License"
    override val resourcePath: String = ""
    override val requiresAuthor: Boolean = false
    override val requiresYear: Boolean = false
    override val order: Int = 1
}

class MITLicense: License {
    override val id: String = "mit"
    override val name: String = "MIT License"
    override val resourcePath: String = "licensetemplates/mit.txt"
    override val requiresAuthor: Boolean = true
    override val requiresYear: Boolean = true
    override val order: Int = 2
}

class Apache2License: License {
    override val id: String = "apache2"
    override val name: String = "Apache 2.0 License"
    override val resourcePath: String = "licensetemplates/apache2.txt"
    override val requiresAuthor: Boolean = false
    override val requiresYear: Boolean = false
    override val order: Int = 3
}

class Gpl3License: License {
    override val id = "gpl3"
    override val name = "GNU General Public License v3.0"
    override val resourcePath: String = "licensetemplates/gpl3.txt"
    override val requiresAuthor = false
    override val requiresYear = false
    override val order: Int = 4
}

class Gpl2License: License {
    override val id = "gpl2"
    override val name = "GNU General Public License v2.0"
    override val resourcePath: String = "licensetemplates/gpl2.txt"
    override val requiresAuthor = false
    override val requiresYear = false
    override val order: Int = 5
}

class Gpl21LesserLicense: License {
    override val id = "gpl21-lesser"
    override val name = "GNU Lesser General Public License v2.1"
    override val resourcePath: String = "licensetemplates/gpl2.1-lesser.txt"
    override val requiresAuthor = false
    override val requiresYear = false
    override val order: Int = 6
}

class Bsd2License: License {
    override val id = "bsd2"
    override val name = "BSD 2-Clause License"
    override val resourcePath: String = "licensetemplates/bsd2.txt"
    override val requiresAuthor = true
    override val requiresYear = true
    override val order: Int = 7
}

class Bsd3License: License {
    override val id = "bsd3"
    override val name = "BSD 3-Clause License"
    override val resourcePath: String = "licensetemplates/bsd3.txt"
    override val requiresAuthor = true
    override val requiresYear = true
    override val order: Int = 8
}

class ISCLicense: License {
    override val id = "isc"
    override val name = "ISC License"
    override val resourcePath: String = "licensetemplates/isc.txt"
    override val requiresAuthor = false
    override val requiresYear = false
    override val order: Int = 9
}

class MPL2License: License {
    override val id = "mpl2"
    override val name = "Mozilla Public License v2.0"
    override val resourcePath: String = "licensetemplates/mpl2.txt"
    override val requiresAuthor = false
    override val requiresYear = false
    override val order: Int = 10
}

class Unlicense : License {
    override val id = "unlicense"
    override val name = "The Unlicense"
    override val resourcePath: String = "licensetemplates/unlicense.txt"
    override val requiresAuthor = false
    override val requiresYear = false
    override val order: Int = 11
}

class AllRightsReservedLicense: License {
    override val id = "arr"
    override val name = "All Rights Reserved"
    override val resourcePath: String = "licensetemplates/arr.txt"
    override val requiresAuthor = true
    override val requiresYear = true
    override val order: Int = 999
}