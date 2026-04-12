package io.github.tritium_launcher.launcher.ui.settings

import io.github.tritium_launcher.launcher.settings.NamespacedId
import io.github.tritium_launcher.launcher.settings.SettingNode

/**
 * Deep link reference to a specific setting.
 *
 * @property key Fully-qualified setting key.
 */
data class SettingsLink(
    val key: NamespacedId
) {
    companion object {
        /**
         * Builds a link from an existing [node].
         *
         * @param node Target setting node.
         * @return Link to [node].
         */
        @JvmStatic
        fun from(node: SettingNode<*>): SettingsLink = SettingsLink(node.key)

        /**
         * Builds a link from namespace + local setting id.
         *
         * @param namespace Setting namespace.
         * @param id Local setting id.
         * @return Link to the target setting.
         */
        @JvmStatic
        fun of(namespace: String, id: String): SettingsLink = SettingsLink(NamespacedId(namespace, id))

        /**
         * Builds a link from a canonical `namespace:id` setting key.
         *
         * @param raw Canonical key string.
         * @return Parsed settings link.
         * @see NamespacedId.parse
         */
        @JvmStatic
        fun parse(raw: String): SettingsLink = SettingsLink(NamespacedId.parse(raw))
    }
}
