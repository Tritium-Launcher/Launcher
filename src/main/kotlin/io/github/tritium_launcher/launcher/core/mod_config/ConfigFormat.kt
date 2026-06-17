package io.github.tritium_launcher.launcher.core.mod_config

import io.github.tritium_launcher.launcher.core.mod_config.formats.*
import io.github.tritium_launcher.launcher.core.mod_config.formats.JsonConfigFormat.Variant.*
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.registry.Registrable

interface ConfigFormat: Registrable {
    override val id: String
    val extensions: List<String>
    fun parse(text: String): ConfigNode
    fun serialize(node: ConfigNode): String

    companion object {
        internal val builtin = listOf(
            PropertiesConfigFormat(),
            ForgeCfgConfigFormat(),
            TomlConfigFormat(),
            YamlConfigFormat(),
            JsonConfigFormat(J),
            JsonConfigFormat(JC),
            JsonConfigFormat(J5)
        )

        fun of(ext: String): ConfigFormat? =
            BuiltinRegistries.ConfigFormat.all()
                .firstOrNull { ext in it.extensions }
    }
}
