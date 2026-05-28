package io.github.tritium_launcher.launcher.importing

import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.platform.Platform
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.qt.gui.QIcon
import kotlinx.serialization.json.*
import java.nio.file.Files

data class InstanceMeta(
    val name: String?,
    val gameVersion: String?,
    val loader: String?,
    val loaderVersion: String?
)

data class KnownLauncher(
    val id: String,
    val displayName: String,
    val icon: QIcon,
    val instanceDirs: List<VPath>,
    val minecraftSubdirName: String,
    val parser: (VPath) -> InstanceMeta?
) {
    companion object {
        private fun windowsAppData(vararg subdirs: String) =
            VPath.get(System.getenv("APPDATA") ?: "${Platform.userHome}/AppData/Roaming")
                .resolve(subdirs.joinToString("/"))

        private fun windowsLocalAppData(vararg subdirs: String) =
            VPath.get(System.getenv("LOCALAPPDATA") ?: "${Platform.userHome}/AppData/Local")
                .resolve(subdirs.joinToString("/"))

        private fun xdgData(vararg subdirs: String) =
            VPath.get(System.getenv("XDG_DATA_HOME") ?: "${Platform.userHome}/.local/share")
                .resolve(subdirs.joinToString("/"))

        private fun macSupport(vararg subdirs: String) =
            VPath.get("${Platform.userHome}/Library/Application Support")
                .resolve(subdirs.joinToString("/"))

        private fun flatpakDir(appId: String, vararg subdirs: String): VPath? {
            if (Platform.current != Platform.Linux) return null
            val p = VPath.get("${Platform.userHome}/.var/app/$appId/data")
                .resolve(subdirs.joinToString("/"))
            return p.takeIf { it.exists() }
        }

        val all: List<KnownLauncher> = listOf(
            KnownLauncher(
                id = "prismlauncher",
                displayName = "PrismLauncher",
                icon = TIcons.Prism.icon,
                instanceDirs = buildList {
                    addAll(when (Platform.current) {
                        Platform.Windows -> listOf(windowsAppData("PrismLauncher", "instances"))
                        Platform.MacOSX  -> listOf(macSupport("PrismLauncher", "instances"))
                        else -> listOf(
                            xdgData("PrismLauncher", "instances"),
                            VPath.get("${Platform.userHome}/.local/share/PrismLauncher/instances")
                        )
                    })
                    addAll(when (Platform.current) {
                        Platform.Windows -> listOf(windowsAppData("MultiMC", "instances"))
                        Platform.MacOSX  -> listOf(macSupport("MultiMC", "instances"))
                        else -> listOf(
                            xdgData("multimc", "instances"),
                            VPath.get("${Platform.userHome}/.local/share/multimc/instances")
                        )
                    })
                    addAll(when (Platform.current) {
                        Platform.Windows -> listOf(windowsAppData("PolyMC", "instances"))
                        Platform.MacOSX  -> listOf(macSupport("PolyMC", "instances"))
                        else -> listOf(
                            xdgData("PolyMC", "instances"),
                            VPath.get("${Platform.userHome}/.local/share/PolyMC/instances")
                        )
                    })
                    flatpakDir("org.prismlauncher.PrismLauncher", "PrismLauncher", "instances")?.let { add(it) }
                    flatpakDir("io.github.PolyMC.PolyMC", "PolyMC", "instances")?.let { add(it) }
                    VPath.get("${Platform.userHome}/Applications/PrismLauncher/instances").takeIf { it.exists() }?.let { add(it) }
                },
                minecraftSubdirName = "minecraft",
                parser = ::parsePrismInstance
            ),
            KnownLauncher(
                id = "atlauncher",
                displayName = "ATLauncher",
                icon = TIcons.ATL.icon,
                instanceDirs = buildList {
                    addAll(when (Platform.current) {
                        Platform.Windows -> listOf(windowsAppData("ATLauncher", "instances"))
                        Platform.MacOSX  -> listOf(macSupport("ATLauncher", "instances"))
                        else -> listOf(
                            xdgData("ATLauncher", "instances"),
                            VPath.get("${Platform.userHome}/.local/share/atlauncher/instances")
                        )
                    })
                    flatpakDir("com.atlauncher.ATLauncher", "ATLauncher", "instances")?.let { add(it) }
                },
                minecraftSubdirName = "",
                parser = ::parseATLauncherInstance
            ),
            KnownLauncher(
                id = "curseforge",
                displayName = "CurseForge",
                icon = TIcons.CurseForge.icon,
                instanceDirs = when (Platform.current) {
                    Platform.Windows -> listOf(
                        windowsLocalAppData("CurseForge", "Minecraft", "Instances"),
                        VPath.get("${Platform.userHome}/curseforge/minecraft/Instances")
                    )
                    Platform.MacOSX -> listOf(macSupport("CurseForge", "Minecraft", "Instances"))
                    else -> listOf(
                        xdgData("CurseForge", "Minecraft", "Instances"),
                        VPath.get("${Platform.userHome}/Documents/curseforge/minecraft/Instances")
                    )
                },
                minecraftSubdirName = "",
                parser = ::parseCurseForgeInstance
            ),
            KnownLauncher(
                id = "gdlauncher",
                displayName = "GDLauncher",
                icon = TIcons.GDL.icon,
                instanceDirs = buildList {
                    addAll(when (Platform.current) {
                        Platform.Windows -> listOf(
                            windowsLocalAppData("gdlauncher", "instances"),
                            VPath.get("${Platform.userHome}/AppData/Roaming/gdlauncher_next/instances")
                        )
                        Platform.MacOSX -> listOf(macSupport("gdlauncher", "instances"))
                        else -> listOf(
                            xdgData("gdlauncher", "instances"),
                            xdgData("gdlauncher_carbon", "instances"),
                            VPath.get("${Platform.userHome}/.local/share/gdlauncher_carbon/data/instances")
                        )
                    })
                },
                minecraftSubdirName = "instance",
                parser = ::parseGDLauncherInstance
            ),
        )

        val BROWSE_FOLDER = KnownLauncher(
            id = "_browse",
            displayName = "Existing Project",
            icon = TIcons.Tritium.icon,
            instanceDirs = emptyList(),
            minecraftSubdirName = "",
            parser = { null }
        )

        // --- Parser implementations ---
        // TODO: Needs to be extendable in the future.

        private fun parsePrismInstance(dir: VPath): InstanceMeta? {
            val pack = dir.resolve("mmc-pack.json")
            val root = parseJsonFile(pack) ?: return null
            val components = root["components"]?.jsonArray ?: return null
            var gv: String? = null; var ln: String? = null; var lv: String? = null
            for (c in components) {
                val o = c.jsonObject; val uid = o["uid"]?.jsonPrimitive?.contentOrNull ?: continue
                when (uid) {
                    "net.minecraft" -> gv = o["cachedVersion"]?.jsonPrimitive?.contentOrNull
                    "net.minecraftforge" -> { ln = "Forge"; lv = o["cachedVersion"]?.jsonPrimitive?.contentOrNull }
                    "net.neoforged" -> { ln = "NeoForge"; lv = o["cachedVersion"]?.jsonPrimitive?.contentOrNull }
                    "net.fabricmc.fabric-loader" -> { ln = "Fabric"; lv = o["cachedVersion"]?.jsonPrimitive?.contentOrNull }
                    "org.quiltmc.quilt-loader" -> { ln = "Quilt"; lv = o["cachedVersion"]?.jsonPrimitive?.contentOrNull }
                }
            }
            return InstanceMeta(null, gv, ln, lv)
        }

        private fun parseATLauncherInstance(dir: VPath): InstanceMeta? {
            val file = dir.resolve("instance.json")
            if (!file.exists()) return null
            val root = parseJsonFile(file) ?: return null
            val launcher = root["launcher"]?.jsonObject ?: return null
            val name = launcher["name"]?.jsonPrimitive?.contentOrNull
            val gv = launcher["version"]?.jsonPrimitive?.contentOrNull
            val lvObj = launcher["loaderVersion"]?.jsonObject
            val ln = lvObj?.get("type")?.jsonPrimitive?.contentOrNull
            val lv = lvObj?.get("rawVersion")?.jsonPrimitive?.contentOrNull
            return InstanceMeta(name, gv, ln, lv)
        }

        private fun parseCurseForgeInstance(dir: VPath): InstanceMeta? {
            val file = dir.resolve("minecraftinstance.json")
            if (!file.exists()) return null
            val root = parseJsonFile(file) ?: return null
            val name = root["name"]?.jsonPrimitive?.contentOrNull ?: dir.fileName()
            val gv = root["gameVersion"]?.jsonPrimitive?.contentOrNull
            val bml = root["baseModLoader"]?.jsonObject
            val ln = bml?.get("name")?.jsonPrimitive?.contentOrNull
                ?.substringBefore('-')
                ?.replaceFirstChar { it.uppercase() }
                ?.let { if (it == "Neoforge") "NeoForge" else if (it == "Fabric") "Fabric" else it }
            val lv = bml?.get("forgeVersion")?.jsonPrimitive?.contentOrNull
            return InstanceMeta(name, gv, ln, lv)
        }

        private fun parseGDLauncherInstance(dir: VPath): InstanceMeta? {
            val file = dir.resolve("instance.json")
            if (!file.exists()) return null
            val root = parseJsonFile(file) ?: return null
            val name = root["name"]?.jsonPrimitive?.contentOrNull
            val gc = root["game_configuration"]?.jsonObject
            val ver = gc?.get("version")?.jsonObject
            val gv = ver?.get("release")?.jsonPrimitive?.contentOrNull
            val loaders = ver?.get("modloaders")?.jsonArray
            var ln: String? = null; var lv: String? = null
            if (!loaders.isNullOrEmpty()) {
                val first = loaders[0].jsonObject
                ln = first["type"]?.jsonPrimitive?.contentOrNull
                lv = first["version"]?.jsonPrimitive?.contentOrNull
            }
            return InstanceMeta(name, gv, ln, lv)
        }
    }
}

data class DetectedInstance(
    val launcher: KnownLauncher,
    val name: String,
    val instanceDir: VPath,
    val minecraftDir: VPath,
    val gameVersion: String?,
    val loader: String?,
    val loaderVersion: String?
)

object LauncherDetector {
    private val log = logger()

    fun detectInstalled(): List<KnownLauncher> =
        KnownLauncher.all.filter { launcher ->
            launcher.instanceDirs.any { dir ->
                try { dir.exists() && dir.toJPath().let { Files.isDirectory(it) } }
                catch (_: Exception) { false }
            }
        }

    fun scanInstances(launcher: KnownLauncher): List<DetectedInstance> {
        val results = mutableListOf<DetectedInstance>()
        val seenDirs = mutableSetOf<String>()
        for (dir in launcher.instanceDirs) {
            if (!dir.exists()) continue
            try {
                val jPath = dir.toJPath()
                if (!Files.isDirectory(jPath)) continue
                val canonical = jPath.toRealPath().toString()
                if (!seenDirs.add(canonical)) continue
                val entries = Files.list(jPath).toList()
                for (instancePath in entries) {
                    val instanceDir = VPath.get(instancePath.toString())
                    val meta = launcher.parser(instanceDir)
                    if (meta != null) {
                        val name = meta.name ?: instanceDir.fileName()
                        val minecraftDir = instanceDir.resolve(launcher.minecraftSubdirName)
                        results.add(
                            DetectedInstance(
                                launcher = launcher,
                                name = name,
                                instanceDir = instanceDir,
                                minecraftDir = minecraftDir,
                                gameVersion = meta.gameVersion,
                                loader = meta.loader,
                                loaderVersion = meta.loaderVersion
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                log.warn("Failed to scan instances in {}", dir, e)
            }
        }
        return results
    }

    fun inspectDirectory(dir: VPath): DetectedInstance? {
        if (!dir.exists()) return null
        try {
            val jPath = dir.toJPath()
            if (!Files.isDirectory(jPath)) return null

            val dotMc = dir.resolve(".minecraft")
            if (dotMc.exists()) {
                val name = dir.fileName()
                return DetectedInstance(
                    launcher = KnownLauncher.BROWSE_FOLDER,
                    name = name,
                    instanceDir = dir,
                    minecraftDir = dotMc,
                    gameVersion = null,
                    loader = null,
                    loaderVersion = null
                )
            }

            val name = dir.fileName()
            return DetectedInstance(
                launcher = KnownLauncher.BROWSE_FOLDER,
                name = name,
                instanceDir = dir,
                minecraftDir = dir,
                gameVersion = null,
                loader = null,
                loaderVersion = null
            )
        } catch (_: Exception) {
            return null
        }
    }

    fun resolveInstanceIcon(instance: DetectedInstance): VPath? {
        if (instance.launcher.id == "curseforge") {
            val cfFile = instance.instanceDir.resolve("minecraftinstance.json")
            if (cfFile.exists()) {
                val root = parseJsonFile(cfFile)
                val path = root?.get("profileImagePath")?.jsonPrimitive?.contentOrNull
                if (path != null) {
                    val iconFile = VPath.get(path)
                    if (iconFile.exists()) return iconFile
                }
            }
        }
        val directIcon = instance.instanceDir.resolve("icon.png")
        if (directIcon.exists()) return directIcon
        val mcIcon = instance.minecraftDir.resolve("icon.png")
        if (mcIcon.exists()) return mcIcon
        try {
            val jDir = instance.instanceDir.toJPath()
            if (Files.isDirectory(jDir)) {
                val pngFiles = Files.list(jDir).toList()
                    .filter { it.fileName.toString().lowercase().endsWith(".png") }
                if (pngFiles.isNotEmpty()) {
                    return VPath.get(pngFiles.first().toString())
                }
            }
        } catch (_: Exception) {}
        return null
    }
}

// --- Shared parser helpers ---

private val json = Json { ignoreUnknownKeys = true }

private fun parseJsonFile(file: VPath): JsonObject? {
    val text = file.readTextOrNull() ?: return null
    return try { json.parseToJsonElement(text).jsonObject } catch (_: Exception) { null }
}