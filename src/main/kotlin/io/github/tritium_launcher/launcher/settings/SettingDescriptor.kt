package io.github.tritium_launcher.launcher.settings

import io.qt.widgets.QWidget
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer

private val LOCAL_ID = Regex("^[a-z0-9_.-]+$")

/**
 * Validation result returned by [SettingDescriptor.validate].
 *
 * @see SettingsMngr.updateValue
 */
sealed interface SettingValidation {
    /**
     * Value passed validation.
     */
    data object Valid : SettingValidation

    /**
     * Value failed validation.
     *
     * @property reason Human-readable failure reason.
     */
    data class Invalid(val reason: String) : SettingValidation
}

/**
 * Base descriptor for a setting definition.
 *
 * @property id Local setting id within the owning namespace.
 * @property title Display title shown in the settings UI.
 * @property description Optional help/description text.
 * @property defaultValue Default value used when no persisted value exists.
 * @property serializer Serializer used for persistence.
 * @property comments Comments to render above this setting on disk.
 * @property order Relative display order within a category.
 * @property persistValue Whether the setting value should be persisted.
 * @see SettingNode
 * @see SettingsMngr.registerSetting
 */
sealed class SettingDescriptor<T>(
    val id: String,
    val title: String,
    val description: String? = null,
    val defaultValue: T,
    val serializer: KSerializer<T>,
    val comments: List<String> = emptyList(),
    val order: Int = -1,
    val persistValue: Boolean = true
) {
    init {
        require(LOCAL_ID.matches(id)) { "Setting id must match ${LOCAL_ID.pattern} (got '$id')" }
        require(order >= -1) { "Setting order must be >= -1 (got $order)" }
    }

    /**
     * Validates a candidate value before persistence.
     *
     * @param value Candidate value to validate.
     * @return [SettingValidation.Valid] when acceptable, otherwise [SettingValidation.Invalid].
     */
    open fun validate(value: T): SettingValidation = SettingValidation.Valid
}

/**
 * Boolean on/off setting descriptor.
 */
class ToggleSettingDescriptor(
    id: String,
    title: String,
    description: String? = null,
    defaultValue: Boolean = false,
    comments: List<String> = emptyList(),
    order: Int = -1
) : SettingDescriptor<Boolean>(id, title, description, defaultValue, Boolean.serializer(), comments, order)

/**
 * Free-form text setting descriptor with optional disallowed regex patterns.
 *
 * @property disallowed Regex patterns that are not permitted in values.
 * @property placeholder Optional UI placeholder text for text editors.
 */
class TextSettingDescriptor(
    id: String,
    title: String,
    description: String? = null,
    defaultValue: String = "",
    val disallowed: List<Regex> = emptyList(),
    val placeholder: String? = null,
    comments: List<String> = emptyList(),
    order: Int = -1
) : SettingDescriptor<String>(id, title, description, defaultValue, String.serializer(), comments, order) {
    /**
     * Validates that [value] does not match any [disallowed] pattern.
     *
     * @param value Text value to validate.
     * @return [SettingValidation.Invalid] when a disallowed regex matches.
     */
    override fun validate(value: String): SettingValidation {
        val failing = disallowed.firstOrNull { it.containsMatchIn(value) } ?: return SettingValidation.Valid
        return SettingValidation.Invalid("Value for '$id' violates pattern '${failing.pattern}'")
    }
}

/**
 * Comment-only entry (useful for UI hints and on-disk comments).
 * Not persisted as a value.
 */
class CommentSettingDescriptor(
    id: String,
    text: String,
    order: Int = -1
) : SettingDescriptor<Unit>(
    id = id,
    title = text,
    description = null,
    defaultValue = Unit,
    serializer = Unit.serializer(),
    comments = text.trim().lines(),
    order = order,
    persistValue = false
)

/**
 * Factory used to build a custom setting editor widget.
 *
 * @see SettingWidgetContext
 * @see WidgetSettingDescriptor
 */
typealias SettingWidgetFactory<T> = (SettingWidgetContext<T>) -> QWidget

/**
 * Runtime context passed to widget factories created by [WidgetSettingDescriptor].
 *
 * @property id Full setting id.
 * @property descriptor Setting descriptor for this widget.
 * @property currentValue Callback returning the latest setting value.
 * @property updateValue Callback that validates and persists a new value.
 * @see WidgetSettingDescriptor
 * @see SettingsMngr.updateValue
 */
data class SettingWidgetContext<T>(
    val id: NamespacedId,
    val descriptor: SettingDescriptor<T>,
    val currentValue: () -> T,
    val updateValue: (T) -> Unit
)

/**
 * Optional contract for custom setting widgets that can refresh themselves from model state.
 *
 * [SettingsView] calls this during staged/apply/cancel refresh so custom editors stay in sync
 * with persisted and pending values.
 *
 * @see SettingWidgetContext.currentValue
 * @see io.github.tritium_launcher.launcher.ui.settings.SettingsView
 */
interface RefreshableSettingWidget {
    /**
     * Re-read the setting value from [SettingWidgetContext.currentValue] and update widget UI.
     */
    fun refreshFromSettingValue()
}

/**
 * Descriptor for settings rendered through a custom widget factory.
 *
 * If [serializer] is `null`, the value is treated as runtime-only and not persisted.
 *
 * @property widgetFactory Factory that creates the editor widget.
 */
class WidgetSettingDescriptor<T>(
    id: String,
    title: String,
    description: String? = null,
    defaultValue: T,
    serializer: KSerializer<T>?,
    val widgetFactory: SettingWidgetFactory<T>,
    comments: List<String> = emptyList(),
    order: Int = -1
) : SettingDescriptor<T>(
    id = id,
    title = title,
    description = description,
    defaultValue = defaultValue,
    serializer = serializer ?: @Suppress("UNCHECKED_CAST") (Unit.serializer() as KSerializer<T>),
    comments = comments,
    order = order,
    persistValue = serializer != null
)
