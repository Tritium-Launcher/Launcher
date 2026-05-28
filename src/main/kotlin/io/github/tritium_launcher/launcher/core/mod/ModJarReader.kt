package io.github.tritium_launcher.launcher.core.mod

import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.jar.JarFile

data class ModJarInfo(
    val modId: String,
    val displayName: String,
    val side: String,
)

object ModJarReader

fun readModJarInfo(jarPath: VPath): ModJarInfo? {
    return try {
        JarFile(jarPath.toJFile()).use { jar ->
            readFabricLike(jar)
                ?: readForgeLike(jar)
                ?: readQuiltLike(jar)
        }
    } catch (e: Exception) {
        logger(ModJarReader::class).warn("Failed to read mod metadata from '${jarPath.fileName()}': ${e.message}")
        null
    }
}

private fun readFabricLike(jar: JarFile): ModJarInfo? {
    val entry = jar.getEntry("fabric.mod.json") ?: return null
    val json = Json { ignoreUnknownKeys = true }
    val obj = json.decodeFromString<JsonObject>(jar.getInputStream(entry).readBytes().decodeToString())
    val id = obj["id"]?.jsonPrimitive?.content ?: return null
    val name = obj["name"]?.jsonPrimitive?.content ?: id
    val env = obj["environment"]?.jsonPrimitive?.content
    val side = when (env) {
        "client" -> "CLIENT"
        "server" -> "SERVER"
        else -> "BOTH"
    }
    return ModJarInfo(modId = id, displayName = name, side = side)
}

private fun readQuiltLike(jar: JarFile): ModJarInfo? {
    val entry = jar.getEntry("quilt.mod.json") ?: return null
    val json = Json { ignoreUnknownKeys = true }
    val obj = json.decodeFromString<JsonObject>(jar.getInputStream(entry).readBytes().decodeToString())
    val loader = obj["quilt_loader"]?.jsonObject ?: return null
    val id = loader["id"]?.jsonPrimitive?.content ?: return null
    val metadata = loader["metadata"]?.jsonObject
    val name = metadata?.get("name")?.jsonPrimitive?.content ?: id
    val env = loader["environment"]?.jsonPrimitive?.content
    val side = when (env) {
        "client" -> "CLIENT"
        "server" -> "SERVER"
        else -> "BOTH"
    }
    return ModJarInfo(modId = id, displayName = name, side = side)
}

private fun readForgeLike(jar: JarFile): ModJarInfo? {
    val entry = jar.getEntry("META-INF/neoforge.mods.toml")
        ?: jar.getEntry("META-INF/mods.toml")
        ?: return null
    val text = jar.getInputStream(entry).readBytes().decodeToString()
    val modIdRegex = Regex("""modId\s*=\s*"([^"]+)""")
    val nameRegex = Regex("""displayName\s*=\s*"([^"]+)""")
    val sideRegex = Regex("""side\s*=\s*"([^"]+)""")
    val modId = modIdRegex.find(text)?.groupValues?.getOrNull(1) ?: return null
    val name = nameRegex.find(text)?.groupValues?.getOrNull(1) ?: modId
    val side = sideRegex.find(text)?.groupValues?.getOrNull(1)?.uppercase() ?: "BOTH"
    return ModJarInfo(modId = modId, displayName = name, side = side)
}

fun readModJarIcon(jarPath: VPath): ByteArray? {
    return try {
        JarFile(jarPath.toJFile()).use { jar ->
            jar.entries().asSequence()
                .filter { !it.isDirectory }
                .filter { e -> e.name.count { c -> c == '/' } == 0 }
                .filter { e -> e.name.endsWith(".png", ignoreCase = true) }
                .filter { e ->
                    val name = e.name.lowercase()
                    name.contains("icon") || name.contains("logo")
                }
                .maxByOrNull { it.size }
                ?.let {

                    jar.getInputStream(it).readBytes()
                }
        }
    } catch (e: Exception) {
        logger(ModJarReader::class).warn("Failed to read mod icon from '${jarPath.fileName()}': ${e.message}")
        null
    }
}
