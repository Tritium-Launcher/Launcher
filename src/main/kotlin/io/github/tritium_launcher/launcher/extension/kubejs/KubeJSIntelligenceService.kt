package io.github.tritium_launcher.launcher.extension.kubejs

import io.github.treesitter.ktreesitter.Node
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.extension.kubejs.KubeJSIntelligenceService.getCompletions
import io.github.tritium_launcher.launcher.extension.kubejs.typings.KubeTypings
import io.github.tritium_launcher.launcher.extension.kubejs.typings.TypeKind
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.ui.project.editor.intelligence.CompletionItem
import io.github.tritium_launcher.launcher.ui.project.editor.intelligence.CompletionItemKind
import io.github.tritium_launcher.launcher.ui.project.editor.intelligence.HoverContent
import io.github.tritium_launcher.launcher.ui.project.editor.treesitter.ItemSlotInfo
import io.github.tritium_launcher.launcher.ui.project.editor.treesitter.TreeSitterService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.sqlite.SQLiteConfig
import java.nio.ByteBuffer
import java.sql.Connection

/**
 * Provides KubeJS-aware editor intelligence from the per-project registry export.
 *
 * The service prefers the FlatBuffer snapshot at `registryObjs/<latest>/typings.fb`
 * because it can be loaded into compact immutable lookup structures, then falls
 * back to the fallback SQLite typings database for completion and hover paths that
 * still support that storage format. It also owns the Tree-sitter-based heuristics
 * used to infer callback parameter types, call signatures, recipe schema argument
 * types, and item string drop targets inside KubeJS scripts.
 */
object KubeJSIntelligenceService {
    private val logger = logger()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Deserializes `registryObjs/latest.json`, which points at the newest registry
     * snapshot directory and identifies the snapshot that produced the typings data.
     */
    @Serializable
    private data class LatestPointer(
        val path: String,
        @SerialName("snapshotId")
        val snapshotId: String
    )

    /**
     * Represents a global JavaScript binding exposed by KubeJS, such as `Item`,
     * `Block`, or an event group root object.
     */
    private data class BindingInfo(
        val name: String,
        val type: String,
        val documentation: String,
        val side: String
    )

    /**
     * Captures one callable or property member from a Java/KubeJS type, including
     * enough parameter metadata to build completion details and signature help.
     */
    private data class MemberInfo(
        val name: String,
        val type: String,
        val isMethod: Boolean,
        val isStatic: Boolean = false,
        val parameters: List<Pair<String, String>> = emptyList()
    ) {
        /**
         * Formats a compact display label that includes parameters only when this
         * member is a method and the registry export supplied parameter metadata.
         */
        fun displayName(): String = if (isMethod && parameters.isNotEmpty()) {
            "$name(${parameters.joinToString { "${it.first}: ${it.second}" }})"
        } else name

        /**
         * Formats the member as a TypeScript-like method or field signature for
         * completion documentation and hover-style detail text.
         */
        fun signature(): String = if (isMethod) {
            val params = if (parameters.isEmpty()) "" else parameters.joinToString { "${it.first}: ${it.second}" }
            "$name($params): $type"
        } else "$name: $type"
    }

    /**
     * Describes a type exported by the KubeJS typings snapshot, including its
     * inheritance edges so member lookup can walk superclasses and interfaces.
     */
    private data class ClassInfo(
        val fullName: String,
        val simpleName: String,
        val kind: Byte,
        val typeParams: List<String>,
        val methods: List<MemberInfo>,
        val fields: List<MemberInfo>,
        val constructors: List<String>,
        val documentation: String,
        val superClass: String,
        val interfaces: List<String>
    )

    /**
     * Describes an event registration function such as `ServerEvents.recipes`,
     * including the callback event class used to infer callback parameter members.
     */
    private data class EventInfo(
        val groupName: String,
        val eventName: String,
        val eventClass: String,
        val side: String,
        val extraType: String,
        val targetRequired: Boolean,
        val documentation: String
    )

    /**
     * Stores a KubeJS recipe schema binding so recipe helper fields can expose
     * useful argument signatures and item-drop validation.
     */
    private data class RecipeSchemaInfo(
        val namespace: String,
        val schemaId: String,
        val recipeClass: String,
        val keys: List<RecipeKeyInfo>,
        val documentation: String
    )

    /**
     * Describes a single positional or named key from a recipe schema.
     */
    private data class RecipeKeyInfo(
        val name: String,
        val type: String,
        val optional: Boolean
    )

    /**
     * Groups the top-level collections read from a FlatBuffer typings snapshot
     * before they are indexed into the runtime cache.
     */
    private data class ParseResult(
        val bindings: List<BindingInfo>,
        val classes: List<ClassInfo>,
        val events: List<EventInfo>,
        val recipes: List<RecipeSchemaInfo>
    )

    /**
     * Holds the fully parsed FlatBuffer typings for one project plus the lookup
     * maps needed by hot editor paths.
     */
    private data class FlatCache(
        val projectDir: String,
        val bindings: List<BindingInfo>,
        val classes: List<ClassInfo>,
        val events: List<EventInfo>,
        val recipes: List<RecipeSchemaInfo>,
        val bindingByName: Map<String, BindingInfo>,
        val classBySimpleName: Map<String, ClassInfo>,
        val classByFullName: Map<String, ClassInfo>,
        val eventsByGroup: Map<String, List<EventInfo>>
    )

    /**
     * Holds the read-only SQLite connection for one project's fallback typings
     * database so repeated editor queries do not reopen the file.
     */
    private data class SqliteCache(
        val projectDir: String,
        val connection: Connection
    )

    @Volatile
    private var flatCache: FlatCache? = null

    @Volatile
    private var sqliteCache: SqliteCache? = null

    /**
     * Drops all cached KubeJS typing state and closes the SQLite connection.
     */
    fun invalidateConnection() {
        sqliteCache?.connection?.close()
        flatCache = null
        sqliteCache = null
    }

    /**
     * Loads and indexes the current project's FlatBuffer typings snapshot.
     *
     * Returns the in-memory cache when `latest.json` and `typings.fb` are present
     * and parse successfully; otherwise returns `null` so callers can fall back to
     * SQLite or suppress KubeJS-specific intelligence.
     */
    private fun getData(project: ProjectBase): FlatCache? {
        val projectDir = project.projectDir.toString()
        val current = flatCache
        if (current != null && current.projectDir == projectDir) {
            return current
        }

        val registryRoot = project.projectDir.resolve("registryObjs")
        val latestPath = registryRoot.resolve("latest.json")
        if (!latestPath.exists()) {
            logger.info("getData: latest.json not found at {}", latestPath)
            return null
        }

        val pointer: LatestPointer = try {
            json.decodeFromString(latestPath.readTextOrNull() ?: return null)
        } catch (t: Throwable) {
            logger.warn("getData: failed to read latest.json for project {}", project.name, t)
            return null
        }

        val typingsPath = registryRoot.resolve(pointer.path).resolve("typings.fb")
        if (!typingsPath.exists()) {
            logger.info("getData: typings.fb not found at {}", typingsPath)
            return null
        }

        val bytes = typingsPath.bytesOrNull() ?: return null
        logger.info("getData: reading typings.fb ({} bytes) from {}", bytes.size, typingsPath)
        val buf = ByteBuffer.wrap(bytes)

        val (bindings, classes, events, recipes) = try {
            parseTypings(buf)
        } catch (t: Throwable) {
            logger.error("getData: FAILED to parse typings.fb", t)
            return null
        }

        logger.info("getData: loaded {} bindings, {} classes, {} events, {} recipes",
            bindings.size, classes.size, events.size, recipes.size)

        val cache = FlatCache(
            projectDir = projectDir,
            bindings = bindings,
            classes = classes,
            events = events,
            recipes = recipes,
            bindingByName = bindings.associateBy { it.name },
            classBySimpleName = classes.associateBy { it.simpleName },
            classByFullName = classes.associateBy { it.fullName },
            eventsByGroup = events.groupBy { it.groupName }
        )
        flatCache = cache
        return cache
    }

    /**
     * Converts the generated FlatBuffer typings payload into Kotlin model objects.
     *
     * The generated FlatBuffer accessors expose nullable strings and indexed child
     * collections, so this method normalizes missing values to empty strings and
     * materializes lists that can be searched repeatedly by editor features.
     */
    private fun parseTypings(buf: ByteBuffer): ParseResult {
        val root = KubeTypings.getRootAsKubeTypings(buf)

        val bindings = mutableListOf<BindingInfo>()
        val classes = mutableListOf<ClassInfo>()
        val events = mutableListOf<EventInfo>()
        val recipes = mutableListOf<RecipeSchemaInfo>()

        for (i in 0 until root.bindingsLength()) {
            val b = root.bindings(i) ?: continue
            bindings.add(BindingInfo(
                name = b.name() ?: "",
                type = b.type() ?: "",
                documentation = b.documentation() ?: "",
                side = b.side() ?: ""
            ))
        }

        for (i in 0 until root.classesLength()) {
            val c = root.classes(i) ?: continue
            val methods = mutableListOf<MemberInfo>()
            for (j in 0 until c.methodsLength()) {
                val m = c.methods(j) ?: continue
                val params = mutableListOf<Pair<String, String>>()
                for (k in 0 until m.parametersLength()) {
                    val p = m.parameters(k) ?: continue
                    params.add((p.name() ?: "") to (p.type() ?: ""))
                }
                methods.add(MemberInfo(
                    name = m.name() ?: "",
                    type = m.returnType() ?: "",
                    isMethod = true,
                    isStatic = m.isStatic(),
                    parameters = params
                ))
            }
            val fields = mutableListOf<MemberInfo>()
            for (j in 0 until c.fieldsLength()) {
                val f = c.fields(j) ?: continue
                fields.add(MemberInfo(
                    name = f.name() ?: "",
                    type = f.type() ?: "",
                    isMethod = false,
                    isStatic = f.isStatic()
                ))
            }
            val typeParams = mutableListOf<String>()
            for (j in 0 until c.typeParamsLength()) {
                typeParams.add(c.typeParams(j) ?: "")
            }
            val constructors = mutableListOf<String>()
            for (j in 0 until c.constructorsLength()) {
                val ct = c.constructors(j) ?: continue
                val paramNames = mutableListOf<String>()
                for (k in 0 until ct.parametersLength()) {
                    val p = ct.parameters(k) ?: continue
                    paramNames.add("${p.name() ?: ""}: ${p.type() ?: ""}")
                }
                constructors.add("constructor(${paramNames.joinToString(", ")})")
            }
            val interfaces = mutableListOf<String>()
            for (j in 0 until c.interfacesLength()) {
                interfaces.add(c.interfaces(j) ?: "")
            }
            classes.add(ClassInfo(
                fullName = c.fullName() ?: "",
                simpleName = c.simpleName() ?: "",
                kind = c.kind(),
                typeParams = typeParams,
                methods = methods,
                fields = fields,
                constructors = constructors,
                documentation = c.documentation() ?: "",
                superClass = c.superClass() ?: "",
                interfaces = interfaces
            ))
        }

        for (i in 0 until root.eventsLength()) {
            val e = root.events(i) ?: continue
            events.add(EventInfo(
                groupName = e.groupName() ?: "",
                eventName = e.eventName() ?: "",
                eventClass = e.eventClass() ?: "",
                side = e.side() ?: "",
                extraType = e.extraType() ?: "",
                targetRequired = e.targetRequired(),
                documentation = e.documentation() ?: ""
            ))
        }

        for (i in 0 until root.recipesLength()) {
            val r = root.recipes(i) ?: continue
            val keys = mutableListOf<RecipeKeyInfo>()
            for (j in 0 until r.keysLength()) {
                val k = r.keys(j) ?: continue
                keys.add(RecipeKeyInfo(
                    name = k.name() ?: "",
                    type = k.type() ?: "",
                    optional = k.optional()
                ))
            }
            recipes.add(RecipeSchemaInfo(
                namespace = r.namespace() ?: "",
                schemaId = r.schemaId() ?: "",
                recipeClass = r.recipeClass() ?: "",
                keys = keys,
                documentation = r.documentation() ?: ""
            ))
        }

        return ParseResult(bindings, classes, events, recipes)
    }

    /**
     * Opens the fallback per-project KubeJS typings database in read-only mode.
     *
     * The returned cache is reused while it still belongs to the same project
     * directory, and any previous connection is closed before switching projects.
     */
    private fun getSqlite(project: ProjectBase): SqliteCache? {
        val projectDir = project.projectDir.toString()
        val current = sqliteCache
        if (current != null && current.projectDir == projectDir) {
            return current
        }

        val dbPath = project.projectDir.resolve("registryObjs/kubejs_typings.db")
        if (!dbPath.exists()) return null

        return try {
            sqliteCache?.connection?.close()
            Class.forName("org.sqlite.JDBC")
            val config = SQLiteConfig().apply { setReadOnly(true) }
            val conn = config.createConnection("jdbc:sqlite:${dbPath.toAbsolute()}")
            val cache = SqliteCache(projectDir, conn)
            sqliteCache = cache
            cache
        } catch (t: Throwable) {
            logger.warn("Failed to open KubeJS typings database for project {}", project.name, t)
            null
        }
    }

    /**
     * Builds completion items for all visible members of [className], walking
     * superclasses and interfaces while de-duplicating inherited overloads.
     */
    private fun membersOfClass(cache: FlatCache, className: String): List<CompletionItem> {
        val seen = mutableSetOf<String>()
        val seenMembers = mutableSetOf<String>()
        val items = mutableListOf<CompletionItem>()
        val queue = ArrayDeque<String>()
        queue.add(className)
        while (queue.isNotEmpty()) {
            val currentName = queue.removeFirst()
            if (currentName in seen) continue
            seen.add(currentName)
            val cls = cache.classByFullName[currentName] ?: cache.classBySimpleName[currentName] ?: continue
            for (m in cls.methods) {
                val key = "${m.name}|${m.parameters.size}|${m.parameters.joinToString { it.second }}"
                if (seenMembers.add(key)) {
                    val paramsStr = if (m.parameters.isNotEmpty()) "(${m.parameters.joinToString { "${it.first}: ${formatDisplayType(it.second)}" }})" else "()"
                    items.add(CompletionItem(
                        label = m.name,
                        kind = CompletionItemKind.Method,
                        detail = "$paramsStr: ${formatDisplayType(m.type)}",
                        documentation = m.signature()
                    ))
                }
            }
            for (f in cls.fields) {
                val key = "field:${f.name}"
                if (seenMembers.add(key)) {
                    items.add(CompletionItem(
                        label = f.name,
                        kind = CompletionItemKind.Field,
                        detail = f.type,
                        documentation = f.signature()
                    ))
                }
            }
            val superClass = cls.superClass.takeIf { it.isNotEmpty() }
            if (superClass != null) queue.add(superClass)
            for (iface in cls.interfaces) {
                queue.add(iface)
            }
        }
        return items
    }

    /**
     * Returns event names registered under a KubeJS event group as callable
     * completion items, for example members shown after `ServerEvents.`.
     */
    private fun eventHandlersOfGroup(cache: FlatCache, groupName: String): List<CompletionItem> {
        return cache.eventsByGroup[groupName]?.map { e ->
            CompletionItem(
                label = e.eventName,
                kind = CompletionItemKind.Method,
                detail = null,
                documentation = e.eventClass
            )
        } ?: emptyList()
    }

    /**
     * Returns line-oriented completions for the current cursor position.
     *
     * This path handles global names and simple dotted receivers from the current
     * line only. It tries FlatBuffer-backed data first, then SQLite if the newer
     * snapshot is unavailable.
     */
    fun getCompletions(project: ProjectBase, line: String, column: Int): List<CompletionItem> {
        val fb = getData(project)
        if (fb != null) {
            return getCompletionsFlat(fb, line, column)
        }

        val sqlite = getSqlite(project) ?: return emptyList()
        return getCompletionsSqlite(sqlite, line, column)
    }

    /**
     * Returns completions that may require the full document and AST context.
     *
     * This extends [getCompletions] by resolving callback parameter receivers such
     * as `event.` inside `ServerEvents.recipes(event => ...)`.
     */
    fun getContextualCompletions(project: ProjectBase, fullText: String, cursorPos: Int): List<CompletionItem> {
        val fb = getData(project)
        if (fb != null) {
            return getContextualCompletionsFlat(fb, fullText, cursorPos)
        }
        val sqlite = getSqlite(project) ?: return emptyList()
        return getContextualCompletionsSqlite(sqlite, fullText, cursorPos)
    }

    /**
     * Implements full-document contextual completion against the FlatBuffer cache.
     *
     * The method first attempts normal line completions, then uses Tree-sitter to
     * infer the type of a dotted callback parameter receiver when no simple result
     * is available.
     */
    private fun getContextualCompletionsFlat(cache: FlatCache, fullText: String, cursorPos: Int): List<CompletionItem> {
        val lineStart = fullText.lastIndexOf('\n', cursorPos - 1).let { if (it == -1) 0 else it + 1 }
        val lineEnd = fullText.indexOf('\n', cursorPos).let { if (it == -1) fullText.length else it }
        val line = fullText.substring(lineStart, lineEnd)
        val column = cursorPos - lineStart

        val existing = getCompletionsFlat(cache, line, column)
        logger.info("getContextualCompletionsFlat: line='{}' col={} existing={}", line, column, existing.size)
        if (existing.isNotEmpty()) return existing

        val prefix = line.substring(0, column)
        val dotPos = prefix.lastIndexOf('.')
        if (dotPos == -1) {
            logger.info("getContextualCompletionsFlat: no dot in prefix '{}'", prefix)
            return existing
        }

        val varName = prefix.substring(0, dotPos).trim().split(Regex("[^a-zA-Z0-9_]")).lastOrNull() ?: return existing.also {
            logger.info("getContextualCompletionsFlat: varName is null")
        }
        logger.info("getContextualCompletionsFlat: dotPos={} varName='{}'", dotPos, varName)
        val typeName = resolveCallbackParameterTypeFlat(cache, fullText, cursorPos, varName)
        logger.info("getContextualCompletionsFlat: resolved typeName='{}'", typeName)
        if (typeName == null) return existing
        val members = membersOfClass(cache, typeName)
        logger.info("getContextualCompletionsFlat: membersOfClass({}) = {} items", typeName, members.size)
        return members
    }

    /**
     * Resolves the class of a callback parameter by walking from the cursor to the
     * enclosing function and matching that callback to its event registration call.
     */
    private fun resolveCallbackParameterTypeFlat(cache: FlatCache, fullText: String, cursorPos: Int, varName: String): String? {
        if (!TreeSitterService.isAvailable()) {
            logger.info("resolveCallbackParameterTypeFlat: TreeSitter not available")
            return null
        }
        val result = TreeSitterService.parse(fullText) ?: return null.also {
            logger.info("resolveCallbackParameterTypeFlat: parse returned null")
        }
        val node = result.findNodeAt(cursorPos) ?: return null.also {
            logger.info("resolveCallbackParameterTypeFlat: findNodeAt({}) returned null", cursorPos)
        }
        logger.info("resolveCallbackParameterTypeFlat: deepest node type='{}' text='{}' pos={}-{}",
            node.type, node.text().toString().replace("\n", "\\n"), node.startByte, node.endByte)

        var current: Node? = node
        while (current?.parent != null) {
            current = current.parent!!
            logger.info("resolveCallbackParameterTypeFlat: visiting parent type='{}'", current.type)
            if (current.type == "arrow_function" || current.type == "function") {
                val paramOk = isParamOf(current, varName)
                logger.info("resolveCallbackParameterTypeFlat: found {} isParamOf('{}')={}", current.type, varName, paramOk)
                if (!paramOk) break

                val args = current.parent
                logger.info("resolveCallbackParameterTypeFlat: args parent type='{}'", args?.type)
                if (args == null || args.type != "arguments") break
                val call = args.parent
                logger.info("resolveCallbackParameterTypeFlat: call parent type='{}'", call?.type)
                if (call == null || call.type != "call_expression") break

                val callTarget = extractCallTarget(call)
                logger.info("resolveCallbackParameterTypeFlat: callTarget='{}'", callTarget)
                if (callTarget == null) break
                val dotIdx = callTarget.indexOf('.')
                if (dotIdx == -1) break
                val group = callTarget.substring(0, dotIdx)
                val eventName = callTarget.substring(dotIdx + 1)
                logger.info("resolveCallbackParameterTypeFlat: group='{}' eventName='{}'", group, eventName)
                val event = cache.eventsByGroup[group]?.find { it.eventName == eventName }
                logger.info("resolveCallbackParameterTypeFlat: event found={} class={}", event != null, event?.eventClass)
                return event?.eventClass
            }
        }
        logger.info("resolveCallbackParameterTypeFlat: reached root without finding arrow_function")
        return null
    }

    /**
     * Checks whether [varName] is declared as a parameter of a Tree-sitter
     * JavaScript function or arrow-function node.
     */
    private fun isParamOf(fnNode: Node, varName: String): Boolean {
        val namedChildren = fnNode.children.toList()
        if (namedChildren.isEmpty()) return false
        val first = namedChildren[0]
        return when (first.type) {
            "identifier" -> first.text().toString() == varName
            "formal_parameters" -> {
                first.children.any { it.type == "identifier" && it.text().toString() == varName }
            }
            else -> false
        }
    }

    /**
     * Recovers a call target from a Tree-sitter `ERROR` node created while the
     * user is typing an incomplete call expression.
     */
    private fun resolveIncompleteCallFromError(errorNode: Node): String? {
        for (child in errorNode.children.toList()) {
            val found = findCallTargetRecursive(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * Searches a Tree-sitter subtree for the first plausible function or member
     * expression target that can be used for signature recovery.
     */
    private fun findCallTargetRecursive(node: Node): String? {
        when (node.type) {
            "member_expression" -> {
                val parts = mutableListOf<String>()
                collectMemberParts(node, parts)
                if (parts.size >= 2) return parts.joinToString(".")
            }
            "identifier" -> return node.text().toString()
            "call_expression" -> {
                val target = extractCallTarget(node)
                if (target != null) return target
            }
        }
        for (child in node.children.toList()) {
            val found = findCallTargetRecursive(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * Extracts a dotted call target such as `ServerEvents.recipes` or
     * `event.shaped` from a Tree-sitter `call_expression` node.
     */
    private fun extractCallTarget(call: Node): String? {
        val namedChildren = call.children.toList()
        logger.info("extractCallTarget: {} named children, first type='{}' text='{}'",
            namedChildren.size, namedChildren.firstOrNull()?.type, namedChildren.firstOrNull()?.text().toString().replace("\n", "\\n"))
        namedChildren.forEachIndexed { i, n ->
            logger.info("extractCallTarget: children[{}] type='{}' text='{}'", i, n.type, n.text().toString().replace("\n", "\\n"))
        }
        if (namedChildren.isEmpty()) return null
        val func = namedChildren[0]
        logger.info("extractCallTarget: func.type='{}'", func.type)
        return if (func.type == "member_expression") {
            val parts = mutableListOf<String>()
            collectMemberParts(func, parts)
            logger.info("extractCallTarget: member_expression parts={}", parts)
            if (parts.size >= 2) parts.joinToString(".") else null
        } else if (func.type == "identifier") {
            func.text().toString()
        } else {
            logger.info("extractCallTarget: unexpected func type '{}', returning null", func.type)
            null
        }
    }

    /**
     * Flattens a nested Tree-sitter member expression into identifier parts in
     * source order.
     */
    private fun collectMemberParts(node: Node, parts: MutableList<String>) {
        when (node.type) {
            "identifier" -> parts.add(node.text().toString())
            "property_identifier" -> parts.add(node.text().toString())
            "member_expression" -> {
                for (child in node.children.toList()) {
                    collectMemberParts(child, parts)
                }
            }
        }
    }

    /**
     * Implements current-line completion against the FlatBuffer cache.
     *
     * Dotted prefixes resolve binding members, class members, or event handlers;
     * non-dotted prefixes resolve global bindings, classes, and event groups.
     */
    private fun getCompletionsFlat(cache: FlatCache, line: String, column: Int): List<CompletionItem> {
        val prefix = line.substring(0, column)
        val lastDotIndex = prefix.lastIndexOf('.')

        return if (lastDotIndex != -1) {
            val objectName = prefix.substring(0, lastDotIndex).trim().split(Regex("[^a-zA-Z0-9_]")).lastOrNull()
            if (objectName != null) {
                val members = mutableListOf<CompletionItem>()
                val binding = cache.bindingByName[objectName]
                if (binding != null) {
                    members.addAll(membersOfClass(cache, binding.type))
                }
                val cls = cache.classBySimpleName[objectName] ?: cache.classByFullName[objectName]
                if (cls != null) {
                    members.addAll(membersOfClass(cache, cls.fullName))
                }
                members.addAll(eventHandlersOfGroup(cache, objectName))
                members
            } else {
                emptyList()
            }
        } else {
            val word = prefix.split(Regex("[^a-zA-Z0-9_$]")).lastOrNull()?.lowercase() ?: ""
            getGlobalCompletionsFlat(cache, word)
        }
    }

    /**
     * Builds global completion items from FlatBuffer bindings, classes, and event
     * groups, optionally filtering by the lowercase [word] prefix.
     */
    private fun getGlobalCompletionsFlat(cache: FlatCache, word: String = ""): List<CompletionItem> {
        val items = mutableListOf<CompletionItem>()
        val lowerWord = word.lowercase()

        for (b in cache.bindings) {
            if (lowerWord.isNotEmpty() && !b.name.lowercase().startsWith(lowerWord)) continue
            items.add(CompletionItem(
                label = b.name,
                kind = CompletionItemKind.Variable,
                detail = null,
                documentation = b.documentation.ifEmpty { b.type }
            ))
        }

        for (c in cache.classes) {
            if (lowerWord.isNotEmpty() && !c.simpleName.lowercase().startsWith(lowerWord)) continue
            items.add(CompletionItem(
                label = c.simpleName,
                kind = CompletionItemKind.Class,
                detail = null,
                documentation = c.fullName
            ))
        }

        for (groupName in cache.eventsByGroup.keys) {
            if (lowerWord.isNotEmpty() && !groupName.lowercase().startsWith(lowerWord)) continue
            items.add(CompletionItem(
                label = groupName,
                kind = CompletionItemKind.Module,
                detail = null,
                documentation = null
            ))
        }

        return items
    }

    /**
     * Implements current-line completion against the SQLite typings database.
     */
    private fun getCompletionsSqlite(cache: SqliteCache, line: String, column: Int): List<CompletionItem> {
        val conn = cache.connection
        val prefix = line.substring(0, column)
        val lastDotIndex = prefix.lastIndexOf('.')

        return if (lastDotIndex != -1) {
            val objectName = prefix.substring(0, lastDotIndex).trim().split(Regex("[^a-zA-Z0-9_]")).lastOrNull()
            if (objectName != null) {
                val bindingType = getBindingTypeSqlite(conn, objectName)
                if (bindingType != null) {
                    return getMembersSqlite(conn, bindingType)
                }
                val fullClassName = getFullClassNameSqlite(conn, objectName)
                if (fullClassName != null) {
                    return getMembersSqlite(conn, fullClassName)
                }
                return getEventHandlersSqlite(conn, objectName)
            }
            emptyList()
        } else {
            val word = prefix.split(Regex("[^a-zA-Z0-9_$]")).lastOrNull()?.lowercase() ?: ""
            getGlobalCompletionsSqlite(conn, word)
        }
    }

    /**
     * Implements full-document contextual completion against the SQLite typings
     * database for projects that do not have a FlatBuffer snapshot.
     */
    private fun getContextualCompletionsSqlite(cache: SqliteCache, fullText: String, cursorPos: Int): List<CompletionItem> {
        val conn = cache.connection
        val lineStart = fullText.lastIndexOf('\n', cursorPos - 1).let { if (it == -1) 0 else it + 1 }
        val lineEnd = fullText.indexOf('\n', cursorPos).let { if (it == -1) fullText.length else it }
        val line = fullText.substring(lineStart, lineEnd)
        val column = cursorPos - lineStart

        val existing = getCompletionsSqlite(cache, line, column)
        if (existing.isNotEmpty()) return existing

        val prefix = line.substring(0, column)
        val dotPos = prefix.lastIndexOf('.')
        if (dotPos == -1) return existing

        val varName = prefix.substring(0, dotPos).trim().split(Regex("[^a-zA-Z0-9_]")).lastOrNull() ?: return existing
        val typeName = resolveCallbackParameterTypeSqlite(conn, fullText, cursorPos, varName) ?: return existing
        return getMembersSqlite(conn, typeName)
    }

    /**
     * Resolves the best signature-help string for the call surrounding [cursorPos].
     *
     * Signature help currently requires the FlatBuffer cache because it depends on
     * recipe schemas and class metadata that are most complete in that path.
     */
    fun getSignatureHelp(project: ProjectBase, fullText: String, cursorPos: Int): String? {
        logger.info("getSignatureHelp: called cursorPos={} fullTextLen={}", cursorPos, fullText.length)
        val fb = getData(project)
        logger.info("getSignatureHelp: fb={}", fb != null)
        if (fb != null) {
            val result = getSignatureHelpFlat(fb, fullText, cursorPos)
            logger.info("getSignatureHelp: result='{}'", result)
            return result
        }
        return null
    }

    /**
     * Implements signature help by combining Tree-sitter parent-chain lookup with
     * text fallbacks for incomplete or temporarily invalid JavaScript.
     */
    private fun getSignatureHelpFlat(cache: FlatCache, fullText: String, cursorPos: Int): String? {
        if (!TreeSitterService.isAvailable()) return null
        val result = TreeSitterService.parse(fullText) ?: return null.also {
            logger.info("getSignatureHelpFlat: parse returned null")
        }
        val start = result.findNodeAt(cursorPos) ?: return null.also {
            logger.info("getSignatureHelpFlat: findNodeAt({}) returned null", cursorPos)
        }
        logger.info("getSignatureHelpFlat: deepest node type='{}' text='{}'", start.type, start.text().toString().replace("\n", "\\n"))
        var node: Node? = start
        var passedCallback = false

        while (node != null) {
            logger.info("getSignatureHelpFlat: visiting type='{}'", node.type)
            if (node.type == "arrow_function" || node.type == "function") {
                passedCallback = true
            }

            if (node.type == "arguments") {
                if (passedCallback) {
                    logger.info("getSignatureHelpFlat: skipping arguments (inside callback)")
                } else {
                    val call = node.parent ?: break
                    logger.info("getSignatureHelpFlat: found arguments, call type='{}'", call.type)
                    if (call.type != "call_expression") break
                    val paramIndex = currentParameterIndex(fullText, node, cursorPos)
                    val sig = resolveSignatureForCall(cache, call, paramIndex)
                    if (sig != null) return sig
                }
            }
            if (node.type == "call_expression") {
                if (passedCallback) {
                    logger.info("getSignatureHelpFlat: skipping call_expression (inside callback)")
                } else {
                    val argsChild = node.children.find { it.type == "arguments" }
                    val funcChild = node.children.find { it.type == "member_expression" || it.type == "identifier" }
                    val cursorInArgs = if (argsChild != null) {
                        cursorPos >= argsChild.startByte.toInt()
                    } else if (funcChild != null) {
                        cursorPos >= funcChild.endByte.toInt()
                    } else {
                        false
                    }
                    if (!cursorInArgs) {
                        logger.info("getSignatureHelpFlat: skipping call_expression (cursor not in argument area)")
                    } else {
                        logger.info("getSignatureHelpFlat: found call_expression directly")
                        val paramIndex = if (argsChild != null) {
                            currentParameterIndex(fullText, argsChild, cursorPos)
                        } else {
                            0
                        }
                        val sig = resolveSignatureForCall(cache, node, paramIndex)
                        if (sig != null) return sig
                    }
                }
            }
            if (node.type == "ERROR") {
                if (passedCallback) {
                    logger.info("getSignatureHelpFlat: skipping ERROR (inside callback, already passed relevant scope)")
                } else {
                    logger.info("getSignatureHelpFlat: visiting ERROR node")
                    val target = resolveIncompleteCallFromError(node)
                    if (target != null) {
                        logger.info("getSignatureHelpFlat: resolved incomplete call target='{}'", target)
                        val parts = target.split('.')
                        if (parts.size >= 2) {
                            val varName = parts[0]
                            val methodName = parts.drop(1).joinToString(".")
                            val typeName = resolveVarTypeFromEnclosingCallback(cache, node, varName)
                            if (typeName != null) {
                                val paramIndex = estimateParamIndexInError(fullText, node, cursorPos)
                                val sig = formatMethodSignature(cache, typeName, methodName, target, paramIndex)
                                if (sig != null) return sig
                                val fieldSig = formatFieldSignature(cache, typeName, methodName, target, paramIndex)
                                if (fieldSig != null) return fieldSig
                            }
                        }
                    }
                }
            }
            node = node.parent
        }
        logger.info("getSignatureHelpFlat: no arguments/call_expression node found in parent chain")

        val parenPos = fullText.lastIndexOf('(', cursorPos - 1)
        if (parenPos >= 0) {
            val beforeParen = fullText.substring(0, parenPos).trimEnd()
            val dotIdx = beforeParen.lastIndexOf('.')
            if (dotIdx > 0) {
                val rawVar = beforeParen.substring(0, dotIdx).trim()
                val varName = rawVar.split(Regex("[^a-zA-Z0-9_]")).lastOrNull()
                val methodName = beforeParen.substring(dotIdx + 1).trim()
                if (varName != null && methodName.isNotEmpty() && rawVar.contains(varName)) {
                    val target = "$varName.$methodName"
                    logger.info("getSignatureHelpFlat: text fallback target='{}'", target)

                    val allParts = target.split('.')
                    if (allParts.size >= 2) {
                        val group = allParts[0]
                        val eventName = allParts.drop(1).joinToString(".")
                        val event = cache.eventsByGroup[group]?.find { it.eventName == eventName }
                        if (event != null) {
                            return "$target(callback: ${event.eventClass.substringAfterLast('.')})"
                        }
                    }

                    var typeName = resolveVarTypeFromEnclosingCallback(cache, start, varName)
                    if (typeName == null) {
                        typeName = resolveVarTypeTextBased(cache, fullText, parenPos, varName)
                        logger.info("getSignatureHelpFlat: text fallback text-based typeName='{}'", typeName)
                    }
                    if (typeName != null) {
                        val argsText = fullText.substring(parenPos + 1, cursorPos.coerceAtMost(fullText.length))
                        val paramIndex = countCommasInText(argsText)
                        val sig = formatMethodSignature(cache, typeName, methodName, target, paramIndex)
                        if (sig != null) return sig
                        val fieldSig = formatFieldSignature(cache, typeName, methodName, target, paramIndex)
                        if (fieldSig != null) return fieldSig
                    }
                }
            }
        }
        return null
    }

    /**
     * Resolves a formatted signature for a complete Tree-sitter call expression.
     *
     * Event registrations are handled before instance methods so calls such as
     * `ServerEvents.recipes(...)` display the event callback type directly.
     */
    private fun resolveSignatureForCall(cache: FlatCache, call: Node, paramIndex: Int = 0): String? {
        val target = extractCallTarget(call) ?: return null.also {
            logger.info("resolveSignatureForCall: extractCallTarget returned null")
        }
        logger.info("resolveSignatureForCall: target='{}'", target)
        val parts = target.split('.')

        if (parts.size >= 2) {
            val groupName = parts[0]
            val eventLookup = parts.drop(1).joinToString(".")
            val event = cache.eventsByGroup[groupName]?.find { it.eventName == eventLookup }
            logger.info("resolveSignatureForCall: event registration check: group='{}' event='{}' found={}", groupName, eventLookup, event != null)
            if (event != null) {
                val callbackType = event.eventClass.substringAfterLast('.')
                return "$target(callback: $callbackType)"
            }
        }

        if (parts.size >= 2) {
            val varName = parts[0]
            val methodName = parts.drop(1).joinToString(".")
            logger.info("resolveSignatureForCall: method lookup: varName='{}' methodName='{}'", varName, methodName)
            val typeName = resolveVarTypeFromEnclosingCallback(cache, call, varName)
            logger.info("resolveSignatureForCall: resolved typeName='{}'", typeName)
            if (typeName != null) {
                val sig = formatMethodSignature(cache, typeName, methodName, target, paramIndex)
                logger.info("resolveSignatureForCall: formatted sig='{}'", sig)
                if (sig != null) return sig

                val fieldSig = formatFieldSignature(cache, typeName, methodName, target, paramIndex)
                logger.info("resolveSignatureForCall: field sig='{}'", fieldSig)
                if (fieldSig != null) return fieldSig
            }
        }

        val cls = cache.classByFullName[target] ?: cache.classBySimpleName[target]
        if (cls != null && cls.constructors.isNotEmpty()) {
            return "${cls.simpleName}${cls.constructors.first()}"
        }
        logger.info("resolveSignatureForCall: fallback returning null (no signature found)")
        return null
    }

    /**
     * Infers the type of [varName] by locating an enclosing event callback whose
     * registration metadata names the callback event class.
     */
    private fun resolveVarTypeFromEnclosingCallback(cache: FlatCache, fromNode: Node, varName: String): String? {
        var current: Node? = fromNode
        while (current?.parent != null) {
            current = current.parent!!
            if (current.type == "arrow_function" || current.type == "function") {
                if (!isParamOf(current, varName)) continue
                val args = current.parent ?: break
                if (args.type != "arguments") continue
                val call = args.parent ?: break
                if (call.type != "call_expression") continue
                val callTarget = extractCallTarget(call) ?: continue
                val dotIdx = callTarget.indexOf('.')
                if (dotIdx == -1) continue
                val group = callTarget.substring(0, dotIdx)
                val eventName = callTarget.substring(dotIdx + 1)
                return cache.eventsByGroup[group]?.find { it.eventName == eventName }?.eventClass
            }
        }
        return null
    }

    /**
     * Pure text-based fallback to find the callback parameter type.
     * Searches backwards from [searchEndPos] for the pattern
     *  `<group>.<eventName>(...<varName> =>` and returns the event class.
     * Does NOT rely on tree-sitter AST parent chain, so it works even when
     * tree-sitter has absorbed outer tokens and broken the parent chain.
     */
    private fun resolveVarTypeTextBased(cache: FlatCache, fullText: String, searchEndPos: Int, varName: String): String? {
        val textBefore = fullText.substring(0, searchEndPos)
        for ((group, events) in cache.eventsByGroup) {
            val groupStr = "$group."
            var searchFrom = textBefore.length
            while (true) {
                val gPos = textBefore.lastIndexOf(groupStr, searchFrom - 1)
                if (gPos == -1) break
                val afterGroup = textBefore.substring(gPos + groupStr.length)
                val parenPos = afterGroup.indexOf('(')
                if (parenPos == -1) { searchFrom = gPos; continue }
                val eventName = afterGroup.substring(0, parenPos).trim()
                if (eventName.isEmpty()) { searchFrom = gPos; continue }
                if (events.any { it.eventName == eventName }) {
                    val callbackSection = afterGroup.substring(parenPos + 1)
                    val arrowPos = callbackSection.indexOf("=>")
                    if (arrowPos > 0) {
                        val beforeArrow = callbackSection.substring(0, arrowPos).trim()
                            .removePrefix("(").removeSuffix(")")
                        if (beforeArrow == varName || beforeArrow.contains(varName)) {
                            return events.find { it.eventName == eventName }!!.eventClass
                        }
                    } else {
                        val funcPos = callbackSection.indexOf("function")
                        if (funcPos >= 0) {
                            val afterFunc = callbackSection.substring(funcPos + "function".length).trim()
                            if (afterFunc.startsWith('(')) {
                                val closeParen = afterFunc.indexOf(')')
                                if (closeParen > 0) {
                                    val params = afterFunc.substring(1, closeParen).trim()
                                    if (params == varName || params.contains(varName)) {
                                        return events.find { it.eventName == eventName }!!.eventClass
                                    }
                                }
                            }
                        }
                    }
                }
                searchFrom = gPos
            }
        }
        return null
    }

    /**
     * Formats the matching method on [typeName] as a signature-help string and
     * highlights the parameter at [paramIndex].
     */
    private fun formatMethodSignature(cache: FlatCache, typeName: String, methodName: String, displayTarget: String, paramIndex: Int = 0): String? {
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(typeName)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current in seen) continue
            seen.add(current)
            val cls = cache.classByFullName[current] ?: cache.classBySimpleName[current] ?: continue
            val method = cls.methods.find { it.name == methodName }
            if (method != null) {
                val params = method.parameters.mapIndexed { idx, p ->
                    val text = "${escapeHtml(p.first)}: ${escapeHtml(formatDisplayType(p.second))}"
                    if (idx == paramIndex) "<b>$text</b>" else text
                }.joinToString(", ")
                val retType = escapeHtml(method.type.substringAfterLast('.'))
                return "$displayTarget($params): $retType"
            }
            val superClass = cls.superClass.takeIf { it.isNotEmpty() }
            if (superClass != null) queue.add(superClass)
            for (iface in cls.interfaces) {
                queue.add(iface)
            }
        }
        return null
    }

    /**
     * Formats recipe-schema backed field calls, such as recipe helper fields that
     * behave like functions, as signature-help strings.
     */
    private fun formatFieldSignature(cache: FlatCache, typeName: String, fieldName: String, displayTarget: String, paramIndex: Int = 0): String? {
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(typeName)
        var field: MemberInfo? = null
        while (queue.isNotEmpty() && field == null) {
            val current = queue.removeFirst()
            if (current in seen) continue
            seen.add(current)
            val cls = cache.classByFullName[current] ?: cache.classBySimpleName[current] ?: continue
            field = cls.fields.find { it.name == fieldName }
            if (field == null) {
                val superClass = cls.superClass.takeIf { it.isNotEmpty() }
                if (superClass != null) queue.add(superClass)
                for (iface in cls.interfaces) {
                    queue.add(iface)
                }
            }
        }
        if (field == null) return null
        val candidates = recipeSchemaCandidates(fieldName)
        for (schemaId in candidates) {
            val schemas = cache.recipes.filter { it.schemaId == schemaId }
            if (schemas.isNotEmpty()) {
                val schema = schemas.find { it.namespace == "minecraft" } ?: schemas.first()
                val params = schema.keys.mapIndexed { idx, k ->
                    val text = "${escapeHtml(k.name)}: ${escapeHtml(formatDisplayType(k.type))}"
                    if (idx == paramIndex) "<b>$text</b>" else text
                }.joinToString(", ")
                return "$displayTarget($params)"
            }
        }
        return null
    }

    /**
     * Converts a camelCase KubeJS helper field name into the snake_case recipe
     * schema identifier used by generated recipe metadata.
     */
    private fun fieldNameToSchemaId(name: String): String {
        val result = StringBuilder()
        for (ch in name) {
            if (ch.isUpperCase() && result.isNotEmpty()) {
                result.append('_')
                result.append(ch.lowercaseChar())
            } else {
                result.append(ch.lowercaseChar())
            }
        }
        return result.toString()
    }

    /**
     * Produces likely recipe schema IDs for a helper field, including the
     * `vanilla` prefix convention used by some generated KubeJS helpers.
     */
    private fun recipeSchemaCandidates(name: String): Set<String> {
        val candidates = mutableSetOf(name, fieldNameToSchemaId(name))
        if (name.startsWith("vanilla") && name.length > 7) {
            val rest = name[7].lowercaseChar() + name.substring(8)
            candidates.add(rest)
            candidates.add(fieldNameToSchemaId(rest))
        }
        return candidates
    }

    /**
     * Shortens fully qualified and generic type names for editor-facing display.
     */
    private fun formatDisplayType(type: String): String {
        val simple = stripPackages(type)
        val angleStart = simple.indexOf('<')
        if (angleStart == -1) return simple
        val baseName = simple.substring(0, angleStart)
        val inner = simple.substring(angleStart + 1, simple.length - 1)
        val params = splitGenericParams(inner)
        val simplified = params.joinToString(", ") { formatDisplayType(it.trim()) }
        return "$baseName<$simplified>"
    }

    /**
     * Removes the top-level package prefix from a type name while preserving
     * package-qualified generic arguments for later recursive formatting.
     */
    private fun stripPackages(fqName: String): String {
        var depth = 0
        var lastDotAtDepth0 = -1
        for (i in fqName.indices) {
            when (fqName[i]) {
                '<' -> depth++
                '>' -> depth--
                '.' -> if (depth == 0) lastDotAtDepth0 = i
            }
        }
        return if (lastDotAtDepth0 >= 0) fqName.substring(lastDotAtDepth0 + 1) else fqName
    }

    /**
     * Splits a generic type argument list on commas that are not nested inside
     * deeper generic parameter lists.
     */
    private fun splitGenericParams(inner: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in inner.indices) {
            when (inner[i]) {
                '<' -> depth++
                '>' -> depth--
                ',' -> if (depth == 0) {
                    parts.add(inner.substring(start, i))
                    start = i + 1
                }
            }
        }
        parts.add(inner.substring(start))
        return parts
    }

    /**
     * Determines the active argument index inside an `arguments` node by counting
     * top-level commas before [cursorPos].
     */
    private fun currentParameterIndex(fullText: String, argsNode: Node, cursorPos: Int): Int {
        val argsStart = argsNode.startByte.toInt()
        val argsEnd = argsNode.endByte.toInt().coerceAtMost(fullText.length)
        if (argsStart >= argsEnd) return 0
        val argsText = fullText.substring(argsStart, argsEnd)
        val cursorOffset = cursorPos - argsStart
        if (cursorOffset < 0) return 0
        var depth = 0
        var count = 0
        val end = cursorOffset.coerceAtMost(argsText.length)
        val startOffset = if (argsText.startsWith('(')) 1 else 0
        var i = startOffset
        while (i < end) {
            val c = argsText[i]
            if (c == '\'' || c == '"') {
                i++
                while (i < end && argsText[i] != c) {
                    if (argsText[i] == '\\') i++
                    i++
                }
            } else {
                when (c) {
                    '(' -> depth++
                    ')' -> depth--
                    '[' -> depth++
                    ']' -> depth--
                    '{' -> depth++
                    '}' -> depth--
                    ',' -> if (depth == 0) count++
                }
            }
            i++
        }
        return count
    }

    /**
     * Estimates the active argument index inside a Tree-sitter error node that
     * represents a partially typed or syntactically incomplete call.
     */
    private fun estimateParamIndexInError(fullText: String, errorNode: Node, cursorPos: Int): Int {
        val errorStart = errorNode.startByte.toInt()
        val errorEnd = errorNode.endByte.toInt().coerceAtMost(fullText.length)
        if (errorStart >= errorEnd) return 0
        val errorText = fullText.substring(errorStart, errorEnd)
        val parenPos = errorText.indexOf('(')
        if (parenPos == -1) return 0
        val cursorOffset = cursorPos - errorStart
        if (cursorOffset <= parenPos) return 0
        var depth = 0
        var count = 0
        val end = cursorOffset.coerceAtMost(errorText.length)
        var i = parenPos + 1
        while (i < end) {
            val c = errorText[i]
            if (c == '\'' || c == '"') {
                i++
                while (i < end && errorText[i] != c) {
                    if (errorText[i] == '\\') i++
                    i++
                }
            } else {
                when (c) {
                    '(' -> depth++
                    ')' -> depth--
                    '[' -> depth++
                    ']' -> depth--
                    '{' -> depth++
                    '}' -> depth--
                    ',' -> if (depth == 0) count++
                }
            }
            i++
        }
        return count
    }

    /**
     * Counts top-level commas in arbitrary argument text, ignoring commas nested
     * in strings, arrays, objects, or parenthesized expressions.
     */
    private fun countCommasInText(text: String): Int {
        var depth = 0
        var count = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\'' || c == '"') {
                i++
                while (i < text.length && text[i] != c) {
                    if (text[i] == '\\') i++
                    i++
                }
            } else {
                when (c) {
                    '(' -> depth++
                    ')' -> depth--
                    '[' -> depth++
                    ']' -> depth--
                    '{' -> depth++
                    '}' -> depth--
                    ',' -> if (depth == 0) count++
                }
            }
            i++
        }
        return count
    }

    /**
     * Escapes a small signature fragment before it is embedded in tooltip HTML.
     */
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    /**
     * SQLite-backed version of callback parameter type inference used by
     * contextual completions when only the fallback database is available.
     */
    private fun resolveCallbackParameterTypeSqlite(conn: Connection, fullText: String, cursorPos: Int, varName: String): String? {
        if (!TreeSitterService.isAvailable()) return null
        val result = TreeSitterService.parse(fullText) ?: return null
        val node = result.findNodeAt(cursorPos) ?: return null

        var current = node
        while (current.parent != null) {
            current = current.parent!!
            if (current.type == "arrow_function" || current.type == "function") {
                if (!isParamOf(current, varName)) break

                val args = current.parent ?: break
                if (args.type != "arguments") break
                val call = args.parent ?: break
                if (call.type != "call_expression") break

                val callTarget = extractCallTarget(call) ?: break
                val dotIdx = callTarget.indexOf('.')
                if (dotIdx == -1) break
                val group = callTarget.substring(0, dotIdx)
                val eventName = callTarget.substring(dotIdx + 1)

                val sql = "SELECT event_class FROM js_events WHERE group_name = ? AND event_name = ?"
                return conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, group)
                    stmt.setString(2, eventName)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getString("event_class") else null
                    }
                }
            }
        }
        return null
    }

    /**
     * Reads event handler completions for a group from the SQLite typings tables.
     */
    private fun getEventHandlersSqlite(conn: Connection, groupName: String): List<CompletionItem> {
        val sql = "SELECT event_name, event_class, extra_type, documentation FROM js_events WHERE group_name = ?"
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, groupName)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val eventClass = rs.getString("event_class")
                        val extraType = rs.getString("extra_type")
                        val detail = if (extraType.isNotEmpty()) "$eventClass (target: $extraType)" else eventClass
                        add(CompletionItem(
                            label = rs.getString("event_name"),
                            kind = CompletionItemKind.Method,
                            detail = detail,
                            documentation = rs.getString("documentation")
                        ))
                    }
                }
            }
        }
    }

    /**
     * Builds hover content for a global binding or class symbol.
     *
     * FlatBuffer metadata is preferred for richer type kind and side information;
     * SQLite is used as a fallback for older project registry exports.
     */
    fun getHover(project: ProjectBase, symbol: String): HoverContent? {
        val fb = getData(project)
        if (fb != null) {
            val binding = fb.bindingByName[symbol]
            if (binding != null) {
                val doc = binding.documentation.ifEmpty { "No documentation available." }
                val sideInfo = if (binding.side.isNotEmpty()) " (${binding.side})" else ""
                return HoverContent("**Binding: ${binding.name}**${sideInfo} (${binding.type})\n\n$doc")
            }
            val cls = fb.classBySimpleName[symbol]
            if (cls != null) {
                val doc = cls.documentation.ifEmpty { "No documentation available." }
                val kindName = when (cls.kind) {
                    TypeKind.Class -> "Class"
                    TypeKind.Interface -> "Interface"
                    TypeKind.Primitive -> "Primitive"
                    TypeKind.Array -> "Array"
                    TypeKind.Event -> "Event"
                    else -> "Class"
                }
                val typeParams = if (cls.typeParams.isNotEmpty()) "<${cls.typeParams.joinToString(", ")}>" else ""
                return HoverContent("**$kindName: ${cls.fullName}$typeParams**\n\n$doc")
            }
            return null
        }

        val sqlite = getSqlite(project) ?: return null
        val conn = sqlite.connection
        return getHoverSqlite(conn, symbol)
    }

    /**
     * Finds the item slot at [charPos] in [fullText], if the character is inside
     * a string argument of a function call whose parameter type is an item type
     * (ItemStack, Ingredient, etc.) or if the enclosing call cannot be resolved.
     * @return the slot info, or null if the position is not a valid drop target.
     */
    fun findItemSlotAt(project: ProjectBase, fullText: String, charPos: Int): ItemSlotInfo? {
        val cache = getData(project) ?: return null
        if (charPos < 0 || charPos > fullText.length) return null
        val result = TreeSitterService.parse(fullText) ?: return null
        var node = result.findNodeAt(charPos) ?: return null
        while (node.type != "string" && node.parent != null) {
            node = node.parent!!
        }
        if (node.type != "string") return null
        return checkStringIsItemSlot(cache, fullText, node)
    }

    /**
     * Returns all string arguments in [fullText] that correspond to item-type
     * parameters (ItemStack, Ingredient, etc.) or are in unresolvable calls.
     * Used by drag-drop to highlight valid drop targets.
     */
    fun findAllItemSlots(project: ProjectBase, fullText: String): List<ItemSlotInfo> {
        val cache = getData(project) ?: return emptyList()
        val result = TreeSitterService.parse(fullText) ?: return emptyList()
        val slots = mutableListOf<ItemSlotInfo>()
        findItemSlotsRecursive(cache, fullText, result.rootNode, slots)
        return slots
    }

    /**
     * Returns all method names (static and non-static) for the class
     * bound to the given binding name (e.g. "Item"). Used by drag-drop
     * detection to recognise Item.xxx(...) calls in editor code.
     */
    fun getMethodNamesForBinding(project: ProjectBase, bindingName: String): Set<String> {
        val cache = getData(project) ?: return emptySet()
        val binding = cache.bindingByName[bindingName] ?: return emptySet()
        val seen = mutableSetOf<String>()
        val seenMembers = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(binding.type)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current in seen) continue
            seen.add(current)
            val cls = cache.classByFullName[current] ?: cache.classBySimpleName[current] ?: continue
            for (m in cls.methods) {
                if (seenMembers.add(m.name)) {

                }
            }
            val superClass = cls.superClass.takeIf { it.isNotEmpty() }
            if (superClass != null) queue.add(superClass)
            for (iface in cls.interfaces) {
                queue.add(iface)
            }
        }
        return seenMembers
    }

    // --- SQLite helpers ---

    /**
     * Looks up the declared type of a global binding in the SQLite typings table.
     */
    private fun getBindingTypeSqlite(conn: Connection, name: String): String? {
        val sql = "SELECT type FROM js_bindings WHERE name = ?"
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, name)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getString("type") else null
            }
        }
    }

    /**
     * Resolves a simple class name to its fully qualified class name in SQLite.
     */
    private fun getFullClassNameSqlite(conn: Connection, simpleName: String): String? {
        val sql = "SELECT full_name FROM js_classes WHERE simple_name = ?"
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, simpleName)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getString("full_name") else null
            }
        }
    }

    /**
     * Walks a SQLite-backed class hierarchy and returns the queried class followed
     * by reachable superclasses and interfaces.
     */
    private fun getClassHierarchySqlite(conn: Connection, className: String): List<String> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(className)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current in seen) continue
            seen.add(current)
            result.add(current)
            val sql = "SELECT super_class, interfaces_json FROM js_classes WHERE full_name = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, current)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val superClass = rs.getString("super_class")
                        if (superClass != null && superClass.isNotEmpty()) {
                            queue.add(superClass)
                        }
                        val interfacesJson = rs.getString("interfaces_json")
                        if (interfacesJson != null && interfacesJson.isNotEmpty() && interfacesJson != "[]") {
                            try {
                                val arr = json.parseToJsonElement(interfacesJson).jsonArray
                                for (elem in arr) {
                                    queue.add(elem.jsonPrimitive.content)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
        return result
    }

    /**
     * Reads methods and fields for a SQLite-backed class hierarchy and converts
     * them into completion items.
     */
    private fun getMembersSqlite(conn: Connection, className: String): List<CompletionItem> {
        val hierarchy = getClassHierarchySqlite(conn, className)
        val seenMembers = mutableSetOf<String>()
        val items = mutableListOf<CompletionItem>()
        for (clsName in hierarchy) {
            val sql = "SELECT name, kind, type, parameters_json, documentation FROM js_members WHERE class_full_name = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, clsName)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val name = rs.getString("name")
                        val kind = rs.getString("kind")
                        if (kind == "method") {
                            val paramsJson = rs.getString("parameters_json")
                            val params = if (paramsJson != null && paramsJson.isNotEmpty() && paramsJson != "[]") {
                                try {
                                    val arr = json.parseToJsonElement(paramsJson).jsonArray
                                    arr.map { obj ->
                                        val o = obj.jsonObject
                                        (o["name"]?.jsonPrimitive?.content ?: "") to (o["type"]?.jsonPrimitive?.content ?: "")
                                    }
                                } catch (_: Exception) {
                                    emptyList()
                                }
                            } else emptyList()
                            val key = "$name|${params.size}|${params.joinToString("") { it.second }}"
                            if (seenMembers.add(key)) {
                                val paramsStr = if (params.isNotEmpty()) "(${params.joinToString { "${it.first}: ${formatDisplayType(it.second)}" }})" else "()"
                                items.add(CompletionItem(
                                    label = name,
                                    kind = CompletionItemKind.Method,
                                    detail = "$paramsStr: ${formatDisplayType(rs.getString("type"))}",
                                    documentation = rs.getString("documentation")
                                ))
                            }
                        } else {
                            val key = "field:$name"
                            if (seenMembers.add(key)) {
                                items.add(CompletionItem(
                                    label = name,
                                    kind = CompletionItemKind.Field,
                                    detail = rs.getString("type"),
                                    documentation = rs.getString("documentation")
                                ))
                            }
                        }
                    }
                }
            }
        }
        return items
    }

    /**
     * Builds SQLite-backed global completions for bindings and classes, optionally
     * filtering by a lowercase prefix.
     */
    private fun getGlobalCompletionsSqlite(conn: Connection, word: String = ""): List<CompletionItem> {
        val items = mutableListOf<CompletionItem>()
        val lowerWord = word.lowercase()

        conn.prepareStatement("SELECT name, type, documentation FROM js_bindings").use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val name = rs.getString("name")
                    if (lowerWord.isNotEmpty() && !name.lowercase().startsWith(lowerWord)) continue
                    items.add(CompletionItem(
                        label = name,
                        kind = CompletionItemKind.Variable,
                        detail = rs.getString("type"),
                        documentation = rs.getString("documentation")
                    ))
                }
            }
        }

        conn.prepareStatement("SELECT simple_name, full_name, documentation FROM js_classes").use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val simpleName = rs.getString("simple_name")
                    if (lowerWord.isNotEmpty() && !simpleName.lowercase().startsWith(lowerWord)) continue
                    items.add(CompletionItem(
                        label = simpleName,
                        kind = CompletionItemKind.Class,
                        detail = rs.getString("full_name"),
                        documentation = rs.getString("documentation")
                    ))
                }
            }
        }

        return items
    }

    /**
     * Builds SQLite-backed hover content for a binding or class symbol.
     */
    private fun getHoverSqlite(conn: Connection, symbol: String): HoverContent? {
        conn.prepareStatement("SELECT type, documentation FROM js_bindings WHERE name = ?").use { stmt ->
            stmt.setString(1, symbol)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    val type = rs.getString("type")
                    val doc = rs.getString("documentation") ?: "No documentation available."
                    return HoverContent("**Binding: $symbol** ($type)\n\n$doc")
                }
            }
        }

        conn.prepareStatement("SELECT full_name, documentation FROM js_classes WHERE simple_name = ?").use { stmt ->
            stmt.setString(1, symbol)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    val fullName = rs.getString("full_name")
                    val doc = rs.getString("documentation") ?: "No documentation available."
                    return HoverContent("**Class: $fullName**\n\n$doc")
                }
            }
        }

        return null
    }

    // ---- Item slot detection for drag-drop (used by TextEditorPane.DragDropTextEdit) ----

    /**
     * Traverses a Tree-sitter subtree and accumulates every string literal that
     * can be treated as an item drop slot.
     */
    private fun findItemSlotsRecursive(cache: FlatCache, fullText: String, node: Node, slots: MutableList<ItemSlotInfo>) {
        if (node.type == "string") {
            val slot = checkStringIsItemSlot(cache, fullText, node)
            if (slot != null) slots.add(slot)
        }
        for (child in node.children) {
            findItemSlotsRecursive(cache, fullText, child, slots)
        }
    }

    /**
     * Determines whether a string literal is an acceptable item argument for its
     * enclosing call and returns the editable string range when it is.
     */
    private fun checkStringIsItemSlot(cache: FlatCache, fullText: String, stringNode: Node): ItemSlotInfo? {
        if (stringNode.type != "string") return null
        val callExpression = findEnclosingCallExpr(stringNode) ?: return null
        val target = extractCallTarget(callExpression) ?: return null
        val parts = target.split(".")
        if (parts.size < 2) return null
        val varName = parts[0]
        val methodName = parts.drop(1).joinToString(".")

        val typeName = resolveVarTypeFromEnclosingCallback(cache, callExpression, varName)
            ?: cache.bindingByName[varName]?.type

        if (typeName != null) {
            val argsNode = findArgumentsAncestor(stringNode) ?: return null
            val argIndex = currentParameterIndex(fullText, argsNode, stringNode.startByte.toInt() + 1)
            val paramType = lookupParamType(cache, typeName, methodName, argIndex)
            if (paramType != null && !isAcceptableItemType(paramType)) return null
        }

        return ItemSlotInfo(
            startByte = stringNode.startByte.toInt() + 1,
            endByte = stringNode.endByte.toInt() - 1,
            exprStartByte = callExpression.startByte.toInt(),
            exprEndByte = callExpression.endByte.toInt()
        )
    }

    /**
     * Finds the call expression for a string argument while allowing the string to
     * be nested in object, array, or pair syntax inside the argument list.
     */
    private fun findEnclosingCallExpr(node: Node): Node? {
        var current = node.parent
        while (current != null) {
            when (current.type) {
                "arguments" -> {
                    val parent = current.parent
                    if (parent?.type == "call_expression") return parent
                    return null
                }
                "pair", "object", "array" -> {
                    current = current.parent
                }
                else -> return null
            }
        }
        return null
    }

    /**
     * Finds the nearest Tree-sitter `arguments` ancestor for an expression node.
     */
    private fun findArgumentsAncestor(node: Node): Node? {
        var current = node.parent
        while (current != null) {
            if (current.type == "arguments") return current
            current = current.parent
        }
        return null
    }

    /**
     * Looks up the parameter type at [argIndex] for [methodName] on [typeName].
     * Checks both regular methods and recipe-schema field patterns.
     * Returns the type string (e.g. "ItemStack", "Ingredient") or null.
     */
    private fun lookupParamType(cache: FlatCache, typeName: String, methodName: String, argIndex: Int): String? {
        lookupRecipeSchemaParamType(cache, methodName, argIndex)?.let { return it }

        val seen = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(typeName)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current in seen) continue
            seen.add(current)
            val cls = cache.classByFullName[current] ?: cache.classBySimpleName[current] ?: continue
            val method = cls.methods.find { it.name == methodName }
            if (method != null) {
                return method.parameters.getOrNull(argIndex)?.second
            }
            val superClass = cls.superClass.takeIf { it.isNotEmpty() }
            if (superClass != null) queue.add(superClass)
            for (iface in cls.interfaces) {
                queue.add(iface)
            }
        }

        val seen2 = mutableSetOf<String>()
        val queue2 = ArrayDeque<String>()
        queue2.add(typeName)
        while (queue2.isNotEmpty()) {
            val current = queue2.removeFirst()
            if (current in seen2) continue
            seen2.add(current)
            val cls = cache.classByFullName[current] ?: cache.classBySimpleName[current] ?: continue
            val field = cls.fields.find { it.name == methodName }
            if (field != null) {
                lookupRecipeSchemaParamType(cache, methodName, argIndex)?.let { return it }
            }
            val superClass = cls.superClass.takeIf { it.isNotEmpty() }
            if (superClass != null) queue2.add(superClass)
            for (iface in cls.interfaces) {
                queue2.add(iface)
            }
        }
        return null
    }

    /**
     * Resolves a recipe-schema argument type for the helper method or field name.
     */
    private fun lookupRecipeSchemaParamType(cache: FlatCache, methodName: String, argIndex: Int): String? {
        for (schemaId in recipeSchemaCandidates(methodName)) {
            val schemas = cache.recipes.filter { it.schemaId == schemaId }
            if (schemas.isNotEmpty()) {
                val schema = schemas.find { it.namespace == "minecraft" } ?: schemas.first()
                return schema.keys.getOrNull(argIndex)?.type
            }
        }
        return null
    }

    /**
     * Returns whether a resolved parameter type can accept item identifiers
     * dropped from the UI.
     */
    private fun isAcceptableItemType(type: String): Boolean {
        val trimmed = type.trim()
        if (trimmed.endsWith("[]")) {
            return isAcceptableItemType(trimmed.removeSuffix("[]"))
        }
        val genericStart = trimmed.indexOf('<')
        if (genericStart >= 0 && trimmed.endsWith(">")) {
            val outer = trimmed.substring(0, genericStart).substringAfterLast('.')
            if (outer in setOf("List", "Collection", "Iterable", "ArrayList", "NonNullList")) {
                val inner = trimmed.substring(genericStart + 1, trimmed.length - 1)
                return splitGenericParams(inner).any { isAcceptableItemType(it) }
            }
        }
        val simple = trimmed.substringAfterLast('.')
        return simple == "ItemStack" || simple == "Ingredient" || simple == "IIngredient"
    }
}
