package io.github.tritium_launcher.launcher.ui.project.editor.file.builtin

import io.github.tritium_launcher.launcher.TConstants
import io.github.tritium_launcher.launcher.matches
import io.github.tritium_launcher.launcher.ui.project.editor.file.FileTypeDescriptor
import io.github.tritium_launcher.launcher.ui.project.editor.file.builtin.BuiltinFileTypes.File
import io.github.tritium_launcher.launcher.ui.project.editor.file.builtin.BuiltinFileTypes.Folder
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon

/**
 * Built-in file type descriptors and matchers used by the editor.
 *
 * The core extension registers these into the `ui.file_type` registry; [File] and [Folder]
 * act as late-match fallbacks because they are ordered at [Int.MAX_VALUE].
 */
object BuiltinFileTypes {
    val File = FileTypeDescriptor.create(
        id = "file",
        displayName = "File",
        icon = TIcons.File.icon,
        matches = { file, _ -> file.isFile() },
        order = Int.MAX_VALUE
    )

    val Folder = FileTypeDescriptor.create(
        id = "folder",
        displayName = "Folder",
        icon = TIcons.Folder.icon,
        matches = { file, _ -> file.isDir() },
        order = Int.MAX_VALUE
    )

    val CSV = FileTypeDescriptor.create(
        id = "csv",
        displayName = "CSV",
        icon = TIcons.CSV.icon,
        matches = { file, _ ->
            file.extension().matches("csv", "tsv", "psv", "ssv", "dsv", "xls", "xlsx", "ods", "numbers")
        }
    )

    val HTML = FileTypeDescriptor.create(
        id = "html",
        displayName = "HTML",
        icon = TIcons.HTML.icon,
        matches = { file, _ -> file.extension().matches("html", "htm", "xhtml", "shtml") }
    )

    val JS = FileTypeDescriptor.create(
        id = "js",
        displayName = "JavaScript",
        icon = TIcons.JavaScript.icon,
        matches = { file, _ -> file.extension().matches("js", "jsx", "mdx", "mjs") }
    )

    val KubeScript = FileTypeDescriptor.create(
        id = "kubescript",
        displayName = "KubeJS Script",
        icon = TIcons.KubeScript.icon,
        matches = { file, _ ->
            file.parent().toString().matches("startup_scripts", "server_scripts", "client_scripts") &&
                    file.extension().matches("js")
        }
    )

    val TS = FileTypeDescriptor.create(
        id = "ts",
        displayName = "TypeScript",
        icon = TIcons.TypeScript.icon,
        matches = { file, _ -> file.extension().matches("ts", "tsx") },
    )

    val Image = FileTypeDescriptor.create(
        id = "image",
        displayName = "Image",
        icon = TIcons.Image.icon,
        matches = { file, _ -> file.extension().matches(TConstants.Lists.ImageExtensions) }
    )

    val Json = FileTypeDescriptor.create(
        id = "json",
        displayName = "JSON",
        icon = TIcons.JSON.icon,
        matches = { file, _ -> file.extension().matches("json", "json5", "hjson", "jsonc", "jsonl", "ndjson") }
    )

    val TOML = FileTypeDescriptor.create(
        id = "toml",
        displayName = "TOML",
        icon = TIcons.TOML.icon,
        matches = { file, _ -> file.extension().matches("toml") }
    )

    val Archive = FileTypeDescriptor.create(
        id = "archive",
        displayName = "Archive",
        icon = TIcons.Archive.icon,
        matches = { file, _ ->
            file.extension().matches(
                "zip",
                "gz",
                "z",
                "bz2",
                "xz",
                "lz",
                "lz4",
                "lzma",
                "zst",
                "sz",
                "br",
                "tar.gz",
                "tgz",
                "tar",
                "tar.bz2",
                "tbz2",
                "tar.xz",
                "txz",
                "tar.lz",
                "tar.lz4",
                "tar.zst",
                "zipx",
                "ear",
                "apk",
                "ipa",
                "xpi",
                "whl",
                "vsix",
                "nupkg",
                "crx",
                "cpio",
                "pax",
                "ar",
                "rar",
                "7z",
                "cab",
                "ace",
                "alz",
                "lzh",
                "lha",
                "pak",
                "iso",
                "udf",
                "img",
                "dmg",
                "vhd",
                "vhdx",
                "qcow2",
                "vdi",
                "deb",
                "rpm",
                "pkg",
                "msi",
                "snap",
                "flatpak",
                "appimage",
                "pkg.tar.zst"
            )
        }
    )

    val Jar = FileTypeDescriptor.create(
        id = "jar",
        displayName = "Java Archive",
        icon = TIcons.Jar.icon,
        matches = { file, _ -> file.extension().matches("jar", "war") }
    )

    val Markdown = FileTypeDescriptor.create(
        id = "markdown",
        displayName = "Markdown",
        icon = TIcons.Markdown.icon,
        matches = { file, _ ->
            file.extension().matches(
                "md", "mdx", "markdown", "mdown", "mkd", "mkdn", "mdwn", "mdtxt", "mdtext", "mdc", "mdoc", "qmd",
                "rmd", "pmd", "adoc", "rst", "wiki", "textile", "readme"
            )
        }
    )

    val CSS = FileTypeDescriptor.create(
        id = "css",
        displayName = "CSS",
        icon = TIcons.CSS.icon,
        matches = { file, _ ->
            file.extension().matches(
                "css", "scss", "less", "styl", "pcss", "postcss", "cssnano", "qss"
            )
        }
    )

    val Python = FileTypeDescriptor.create(
        id = "py",
        displayName = "Python",
        icon = TIcons.Python.icon,
        matches = { file, _ ->
            file.extension().matches(
                "py", "pyw", "pyc", "pyo", "pyd", "pyi", "whl", "egg", "egg-info", "dist-info"
            )
        }
    )

    val Shell = FileTypeDescriptor.create(
        id = "shell",
        displayName = "Shell",
        icon = TIcons.Shell.icon,
        matches = { file, _ ->
            file.extension().matches(
                "sh", "bash", "zsh", "ksh", "ash", "dash", "csh", "tcsh", "env", "install", "command", "run"
            )
        }
    )

    val Powershell = FileTypeDescriptor.create(
        id = "powershell",
        displayName = "Powershell",
        icon = TIcons.Powershell.icon,
        matches = { file, _ ->
            file.extension().matches(
                "ps1", "psm1", "psd1", "ps1xml", "clixml", "pssc", "csxml", "psc1", "psrc"
            )
        }
    )

    val Yaml = FileTypeDescriptor.create(
        id = "yaml",
        displayName = "YAML",
        icon = TIcons.YAML.icon,
        matches = { file, _ ->
            file.extension().matches(
                "yaml", "yml"
            )
        }
    )

    /* Tritium Icons */

    val ModConfig = FileTypeDescriptor.create(
        id = "modcfg",
        displayName = "Mod Config",
        icon = TIcons.ModConfig.icon,
        matches = { file, _ ->
            file.extension().matches(
                "json", "json5", "toml", "properties", "cfg", "conf", "hocon", "yaml", "yml"
            ) &&
                    file.parent().toString().matches("config", "defaultconfigs") &&
                    file.parent().parent().exists()
        }
    )

    val ZenScript = FileTypeDescriptor.create(
        id = "zs",
        displayName = "ZenScript",
        icon = TIcons.ZenScript.icon,
        matches = { file, _ ->
            file.extension().matches("zs")
        }
    )

    val AnvilRegion = FileTypeDescriptor.create(
        id = "region",
        displayName = "Anvil Region",
        icon = TIcons.AnvilRegion.icon,
        matches = { file, _ -> file.extension().matches("mca") }
    )

    val SessionLock = FileTypeDescriptor.create(
        id = "sessionlock",
        displayName = "Session Lock",
        icon = TIcons.SessionLock.icon,
        matches = { file, _ -> file.fileName().matches("session.lock") }
    )

    val NBT = FileTypeDescriptor.create(
        id = "nbt",
        displayName = "NBT",
        icon = TIcons.NBT.icon,
        matches = { file, _ -> file.extension().matches("nbt") }
    )

    val Schematic = FileTypeDescriptor.create(
        id = "schematic",
        displayName = "Schematic",
        icon = TIcons.Schematic.icon,
        matches = { file, _ -> file.extension().matches("schematic", "schem", "litematic", "mcstructure") }
    )

    val McFunction = FileTypeDescriptor.create(
        id = "mcfunction",
        displayName = "MC Function",
        icon = TIcons.McFunction.icon,
        matches = { file, _ -> file.extension().matches("mcfunction") }
    )

    fun all() = listOf(
        File, Folder, CSV, HTML, JS, KubeScript, TS, Image, Json, TOML, Archive, Jar, Markdown, CSS, Python, Shell,
        Powershell, Yaml, ModConfig, ZenScript, AnvilRegion, SessionLock, NBT, Schematic, McFunction
    )
}
