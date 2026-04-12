package io.github.tritium_launcher.launcher.ui.dashboard

import io.github.tritium_launcher.launcher.*
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.core.project.ProjectMngr
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.qtStyle
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.gridLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.qWidget
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.Nullable
import io.qt.core.*
import io.qt.gui.*
import io.qt.widgets.*
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/** Grid view style. */
object GridStyleProvider : ProjectListStyleProvider {
    override val id: String = "grid"
    override val title: String = "Grid"
    override val icon: QIcon = QIcon(TIcons.GridView)
    override fun create(ctx: ProjectStyleContext): ProjectListStyle = GridStyle(ctx)
}

/** List view style. */
object ListStyleProvider : ProjectListStyleProvider {
    override val id: String = "list"
    override val title: String = "List"
    override val icon: QIcon = QIcon(TIcons.ListView)
    override fun create(ctx: ProjectStyleContext): ProjectListStyle = ListStyle(ctx)
}

/** Special-super-secret-style which is totally a joke. */
object DvdStyleProvider : ProjectListStyleProvider {
    override val id: String = "dvd"
    override val title: String = "DVD"
    override val icon: QIcon? = null
    override val hidden: Boolean = true
    override fun create(ctx: ProjectStyleContext): ProjectListStyle = DvdStyle(ctx)
}

private const val GRID_TILE_WIDTH = 90
private const val GRID_ICON_SIZE = 36
private const val GRID_H_SPACING = 6
private const val GRID_TILE_TITLE_WIDTH = GRID_TILE_WIDTH - 16

/** Header label for groups with a context menu to reorder groups. */
private class GroupHeaderLabel(
    private val groupName: String,
    private val ctx: ProjectStyleContext,
) : QLabel() {

    init {
        objectName = "groupHeader_${groupName.hashCode()}"
        contextMenuPolicy = Qt.ContextMenuPolicy.CustomContextMenu
        customContextMenuRequested.connect { pos ->
            if(groupName == "Ungrouped") return@connect
            val global = mapToGlobal(pos)
            showContextMenu(global)
        }

        setThemedStyle {
            selector("#$objectName") { color(TColors.Subtext) }
        }
    }

    private fun showContextMenu(globalPos: QPoint) {
        val menu = QMenu(this).apply {
            addAction("Move Up")?.triggered?.connect {
                ctx.groupStore.moveGroup(groupName, -1)
                ctx.requestRefresh()
            }
            addAction("Move Down")?.triggered?.connect {
                ctx.groupStore.moveGroup(groupName, 1)
                ctx.requestRefresh()
            }
        }
        menu.exec(globalPos)
    }
}

/** PrismLauncher-inspired grid style supporting groups and freeform placement. */
private class GridStyle(private val ctx: ProjectStyleContext) : ProjectListStyle {
    override val id: String = "grid"
    override val title: String = "Grid"
    override val icon: QIcon = QIcon(TIcons.GridView)

    override val sortOptions: List<ProjectSortOption> = listOf(
        ProjectSortOption(
            "alpha-asc",
            "Alphabetical (A→Z)",
            comparatorProvider = { compareBy { it.name.lowercase() } }
        ),
        ProjectSortOption(
            "alpha-desc",
            "Alphabetical (Z→A)",
            comparatorProvider = { compareByDescending { it.name.lowercase() } }
        ),
        ProjectSortOption(
            "last-desc",
            "Last Opened (newest)",
            comparatorProvider = {
                compareByDescending<ProjectBase> { it.lastAccessedMillis() }.thenBy { it.name.lowercase() }
            }
        ),
        ProjectSortOption(
            "last-asc",
            "Last Opened (oldest)",
            comparatorProvider = { compareBy<ProjectBase> { it.lastAccessedMillis() }.thenBy { it.name.lowercase() } }
        ),
        ProjectSortOption(
            "type-asc",
            "Type (A→Z)",
            comparatorProvider = { compareBy<ProjectBase> { it.typeId.lowercase() }.thenBy { it.name.lowercase() } }
        ),
        ProjectSortOption(
            "type-desc",
            "Type (Z→A)",
            comparatorProvider = { compareByDescending<ProjectBase> { it.typeId.lowercase() }.thenByDescending { it.name.lowercase() } }
        ),
        ProjectSortOption("freeform", "Freeform", kind = SortKind.Manual)
    )

    private val rootHost = qWidget { setContentsMargins(0, 0, 0, 0) }
    private val root = QStackedLayout().also { rootHost.setLayout(it) }
    private val gridPage = qWidget()
    private val gridLayout = vBoxLayout(gridPage)
    private val scroll = QScrollArea().apply {
        widgetResizable = true
        frameShape = QFrame.Shape.NoFrame
        verticalScrollBarPolicy = Qt.ScrollBarPolicy.ScrollBarAlwaysOff
        horizontalScrollBarPolicy = Qt.ScrollBarPolicy.ScrollBarAlwaysOff
    }
    private val scrollContent = object : QWidget() {
        init { sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Preferred) }
        override fun resizeEvent(event: @Nullable QResizeEvent?) {
            super.resizeEvent(event)
            val w = width().coerceAtLeast(0)
            if (w > 0) minimumWidth = w
            handleViewportResize(event?.size()?.width() ?: width())
        }
        override fun mousePressEvent(event: QMouseEvent?) {
            super.mousePressEvent(event)
            if (event?.button() == Qt.MouseButton.LeftButton) {
                selectedTile?.setSelected(false)
                selectedTile = null
            }
        }
    }

    private val scrollLayout = vBoxLayout(scrollContent)
    private var lastColumns = -1
    private var lastViewportWidth = -1
    private var selectedTile: ProjectTile? = null

    private val freeformCanvas = FreeformCanvas(ctx)

    private val freeformPage = object : QScrollArea() {
        override fun resizeEvent(event: @Nullable QResizeEvent?) {
            super.resizeEvent(event)
            val vw = viewport()?.width() ?: 0
            if (vw > 0) {
                freeformCanvas.setFixedWidth(vw)
            }
        }
    }.apply {
        widgetResizable = true
        verticalScrollBarPolicy = Qt.ScrollBarPolicy.ScrollBarAlwaysOff
        horizontalScrollBarPolicy = Qt.ScrollBarPolicy.ScrollBarAlwaysOff
        frameShape = QFrame.Shape.NoFrame
        lineWidth = 0
        viewport()?.setContentsMargins(0, 0, 0, 0)
    }

    init {
        root.setContentsMargins(0, 0, 0, 0)

        scrollLayout.contentsMargins = QMargins(0, 10, 0, 10)
        scrollLayout.widgetSpacing = 10
        scroll.setWidget(scrollContent)

        gridLayout.contentsMargins = 0.m
        gridLayout.widgetSpacing = 8
        gridLayout.addWidget(scroll, 1)

        root.addWidget(gridPage)
        freeformPage.setWidget(freeformCanvas)
        root.addWidget(freeformPage)
    }

    /** Returns the grid root widget. */
    override fun widget(): QWidget = rootHost

    /** Applies projects with grouping or freeform layout. */
    override fun applyProjects(projects: List<ProjectBase>, sort: ProjectSortOption) {
        if (sort.kind == SortKind.Manual) {
            root.setCurrentWidget(freeformPage)
            val viewportWidth = freeformPage.viewport()?.width() ?: freeformCanvas.width()
            if (viewportWidth > 0) {
                freeformCanvas.setFixedWidth(viewportWidth)
                freeformCanvas.minimumWidth = viewportWidth
            }
            val contentWidth = availableContentWidth()
            lastViewportWidth = contentWidth
            lastColumns = columnsForWidth(contentWidth)
            freeformCanvas.applyProjects(projects)
            return
        }

        root.setCurrentWidget(gridPage)

        rootHost.updatesEnabled = false
        try {
            clearGrid()
            selectedTile = null

            val grouped = projects.groupBy { ctx.groupStore.groupOf(it.path.toString()) ?: "Ungrouped" }
            val orderedGroups = ctx.groupStore.groupNames()
            val sortedGroupNames = orderedGroups.filter { grouped.containsKey(it) } +
                    grouped.keys.filterNot { orderedGroups.contains(it) }.sorted()
            val comparator = sort.comparator(ProjectSortContext(ctx)) ?: compareBy { it.name.lowercase() }

            val contentWidth = availableContentWidth()
            val cols = columnsForWidth(contentWidth)
            lastColumns = cols
            lastViewportWidth = contentWidth

            scroll.verticalScrollBar()?.singleStep = GRID_TILE_WIDTH + 12
            scroll.verticalScrollBar()?.pageStep = (GRID_TILE_WIDTH + 12) * 3

            sortedGroupNames.forEach { groupName ->
                val section = qWidget()
                val sectionLayout = vBoxLayout(section) { contentsMargins = QMargins(8, 0, 0, 6); widgetSpacing = 6 }

                val header = GroupHeaderLabel(groupName, ctx).apply {
                    text = if (groupName == "Ungrouped") "Ungrouped" else groupName
                    font = QFont(font).also { it.setBold(true) }
                    setContentsMargins(4, 0, 0, 0)
                }
                sectionLayout.addWidget(header)

                val grid = gridLayout {
                    setHorizontalSpacing(GRID_H_SPACING)
                    setVerticalSpacing(12)
                }

                val items = grouped[groupName].orEmpty().sortedWith(comparator)
                items.forEachIndexed { idx, project ->
                    val tile = ProjectTile(project, ctx) { chosen ->
                        selectedTile?.setSelected(false)
                        selectedTile = chosen
                        selectedTile?.setSelected(true)
                    }
                    val row = idx / cols
                    val col = idx % cols
                    grid.addWidget(tile, row, col, Qt.AlignmentFlag.AlignTop)
                }

                for (c in 0 until cols) grid.setColumnStretch(c, 0)

                grid.setColumnStretch(cols, 1)

                sectionLayout.addLayout(grid)
                sectionLayout.setAlignment(grid, Qt.AlignmentFlag.AlignLeft)
                scrollLayout.addWidget(section)
            }
            scrollLayout.addStretch(1)
        } finally {
            rootHost.updatesEnabled = true
            rootHost.update()
        }
    }

    private fun availableContentWidth(): Int {
        val viewportWidth = scroll.viewport()?.width() ?: 0
        if (viewportWidth > 0) return viewportWidth
        val contentWidth = scrollContent.width
        val hintWidth = scrollContent.sizeHint.width()
        return max(contentWidth, hintWidth)
    }

    private fun columnsForWidth(contentWidth: Int): Int {
        if (contentWidth <= 0) return 1
        val margins = scrollLayout.contentsMargins()
        val usable = (contentWidth - (margins.left() + margins.right())).coerceAtLeast(GRID_TILE_WIDTH)
        return max(1, (usable + GRID_H_SPACING) / (GRID_TILE_WIDTH + GRID_H_SPACING))
    }

    private fun handleViewportResize(newWidth: Int) {
        if (newWidth <= 0) return
        if (newWidth == lastViewportWidth) return
        lastViewportWidth = newWidth
        val newCols = columnsForWidth(newWidth)
        if (newCols != lastColumns) ctx.requestRefresh()
    }

    /** Clears the scroll layout. */
    private fun clearGrid() {
        while (scrollLayout.count() > 0) {
            val item = scrollLayout.takeAt(0) ?: continue
            item.widget()?.let { w ->
                scrollLayout.removeWidget(w)
                w.hide()
                w.setParent(null)
                w.disposeLater()
            } ?: run {
                item.layout()?.let { l ->
                    clearLayout(l)
                    scrollLayout.removeItem(item)
                }
            }
        }
    }

    private fun clearLayout(l: QLayout) {
        while(l.count() > 0) {
            val it = l.takeAt(0) ?: continue
            it.widget()?.let { w ->
                l.removeWidget(w)
                w.hide()
                w.setParent(null)
                w.disposeLater()
            } ?: it.layout()?.let { sub ->
                clearLayout(sub)
            }
        }
    }

    /** Releases resources. */
    override fun dispose() {}
}

/** Joke style that bounces a project around like the classic DVD screensaver. */
private class DvdStyle(ctx: ProjectStyleContext) : ProjectListStyle {
    override val id: String = "dvd"
    override val title: String = "DVD"
    override val icon: QIcon? = null
    override val sortOptions: List<ProjectSortOption> = listOf(
        ProjectSortOption("bounce", "Bouncing Shuffle", kind = SortKind.Manual)
    )

    private val container = object : QWidget() {
        init {
            setAttribute(Qt.WidgetAttribute.WA_StyledBackground, true)
            setContentsMargins(0, 0, 0, 0)
        }

        override fun resizeEvent(event: QResizeEvent?) {
            super.resizeEvent(event)
            clampBouncer()
        }
    }

    private val bouncer = DvdBouncerTile(ctx.openProject)
    private val timer = QTimer().apply { interval = 16 }
    private var projects: List<ProjectBase> = emptyList()
    private var currentIndex = -1
    private var posX = 0.0
    private var posY = 0.0
    private var velX: Double
    private var velY: Double

    init {
        val (vx, vy) = randomVelocity()
        velX = vx
        velY = vy
        bouncer.setParent(container)
        bouncer.hide()
        timer.timeout.connect { tick() }
    }

    override fun widget(): QWidget = container

    override fun applyProjects(projects: List<ProjectBase>, sort: ProjectSortOption) {
        this.projects = projects
        if (projects.isEmpty()) {
            timer.stop()
            bouncer.hide()
            return
        }
        if (currentIndex !in projects.indices) currentIndex = Random.nextInt(projects.size)
        showCurrentProject()
        if (!bouncer.isVisible) {
            dropIntoView()
            bouncer.show()
        }
        if (!timer.isActive) timer.start()
    }

    override fun dispose() {
        timer.stop()
        bouncer.disposeLater()
    }

    private fun tick() {
        if (!container.isVisible || projects.isEmpty()) return
        val maxX = (container.width() - bouncer.width()).coerceAtLeast(0)
        val maxY = (container.height() - bouncer.height()).coerceAtLeast(0)
        if (maxX == 0 && maxY == 0) return
        var newX = posX + velX
        var newY = posY + velY
        var bounced = false

        if (newX <= 0 || newX >= maxX) {
            velX = -velX
            newX = newX.coerceIn(0.0, maxX.toDouble())
            bounced = true
        }
        if (newY <= 0 || newY >= maxY) {
            velY = -velY
            newY = newY.coerceIn(0.0, maxY.toDouble())
            bounced = true
        }

        posX = newX
        posY = newY
        bouncer.move(newX.roundToInt(), newY.roundToInt())

        if (bounced) {
            advanceProject()
            bouncer.randomizeAccent()
        }
    }

    private fun randomVelocity(): Pair<Double, Double> {
        val speeds = listOf(1.0, 1.25, 1.5, 1.75, 2.0)
        val sx = speeds.random() * if (Random.nextBoolean()) 1 else -1
        val sy = speeds.random() * if (Random.nextBoolean()) 1 else -1
        return Pair(sx, sy)
    }

    private fun dropIntoView() {
        val maxX = (container.width() - bouncer.width()).coerceAtLeast(0)
        val maxY = (container.height() - bouncer.height()).coerceAtLeast(0)
        posX = if (maxX > 0) Random.nextInt(maxX).toDouble() else 0.0
        posY = if (maxY > 0) Random.nextInt(maxY).toDouble() else 0.0
        val (vx, vy) = randomVelocity()
        velX = vx
        velY = vy
        bouncer.move(posX.roundToInt(), posY.roundToInt())
    }

    private fun clampBouncer() {
        if (!bouncer.isVisible) return
        val maxX = (container.width() - bouncer.width()).coerceAtLeast(0)
        val maxY = (container.height() - bouncer.height()).coerceAtLeast(0)
        posX = posX.coerceIn(0.0, maxX.toDouble())
        posY = posY.coerceIn(0.0, maxY.toDouble())
        bouncer.move(posX.roundToInt(), posY.roundToInt())
    }

    private fun showCurrentProject() {
        if (projects.isEmpty() || currentIndex !in projects.indices) return
        bouncer.bind(projects[currentIndex])
    }

    private fun advanceProject() {
        if (projects.isEmpty()) return
        if (projects.size == 1) {
            showCurrentProject()
            return
        }
        currentIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % projects.size
        showCurrentProject()
    }
}

/** Widget that acts as the bouncing DVD logo and shows project info. */
private class DvdBouncerTile(
    private val openProject: (ProjectBase) -> Unit
) : QFrame() {
    private var project: ProjectBase? = null
    private val iconSize = qs(56, 56)
    private val iconLabel: QLabel
    private val nameLabel: QLabel
    private val hintLabel: QLabel

    init {
        objectName = "dvdBouncerTile"
        setAttribute(Qt.WidgetAttribute.WA_StyledBackground, true)
        minimumSize = qs(220, 130)
        cursor = QCursor(Qt.CursorShape.PointingHandCursor)

        val layout = vBoxLayout(this) {
            contentsMargins = QMargins(14, 12, 14, 12)
            widgetSpacing = 6
            setAlignment(Qt.AlignmentFlag.AlignCenter)
        }

        iconLabel = label {
            minimumSize = iconSize
            maximumSize = iconSize
            alignment = Qt.AlignmentFlag.AlignCenter.asAlignment()
        }
        layout.addWidget(iconLabel, 0, Qt.AlignmentFlag.AlignHCenter)

        nameLabel = label {
            alignment = Qt.AlignmentFlag.AlignCenter.asAlignment()
            wordWrap = true
            textFormat = Qt.TextFormat.RichText
            font = QFont(font).also { it.setBold(true) }
        }
        layout.addWidget(nameLabel)

        hintLabel = label {
            alignment = Qt.AlignmentFlag.AlignCenter.asAlignment()
            wordWrap = true
            textFormat = Qt.TextFormat.PlainText
            font = QFont(font).also { it.setPointSize(max(8, it.pointSize() - 2)) }
        }
        layout.addWidget(hintLabel)

        randomizeAccent()
    }

    fun bind(project: ProjectBase) {
        this.project = project
        nameLabel.text = wrapProjectName(project)
        hintLabel.text = project.path.toString()
        iconLabel.pixmap = try {
            loadScaledPixmap(project.getIconPath(), iconSize, this)
        } catch (_: Throwable) { QPixmap() }
    }

    fun randomizeAccent() {
        val palette = listOf("#FF7F50", "#7B68EE", "#45D6D6", "#FFD166", "#FF8CC6", "#80ED99", "#8EE3F5")
        val color = palette.random()
        styleSheet = qtStyle {
            selector("#$objectName") {
                backgroundColor("transparent")
                border(0)
                borderRadius(0)
            }
            selector("#$objectName QLabel") {
                color(color)
            }
        }.toStyleSheet()
    }

    override fun mouseDoubleClickEvent(event: QMouseEvent?) {
        super.mouseDoubleClickEvent(event)
        if (event?.button() == Qt.MouseButton.LeftButton) {
            project?.takeUnless { it.isInvalidCatalogProject() }?.let { openProject(it) }
        }
    }
}

/** List style with sorting. */
private class ListStyle(private val ctx: ProjectStyleContext) : ProjectListStyle {
    override val id: String = "list"
    override val title: String = "List"
    override val icon: QIcon = QIcon(TIcons.ListView)

    override val sortOptions: List<ProjectSortOption> = listOf(
        ProjectSortOption(
            "alpha-asc",
            "Alphabetical (A→Z)",
            comparatorProvider = { compareBy { it.name.lowercase() } }
        ),
        ProjectSortOption(
            "alpha-desc",
            "Alphabetical (Z→A)",
            comparatorProvider = { compareByDescending { it.name.lowercase() } }
        ),
        ProjectSortOption(
            "last-desc",
            "Last Opened (newest)",
            comparatorProvider = {
                compareByDescending<ProjectBase> { it.lastAccessedMillis() }.thenBy { it.name.lowercase() }
            }
        ),
        ProjectSortOption(
            "last-asc",
            "Last Opened (oldest)",
            comparatorProvider = { compareBy<ProjectBase> { it.lastAccessedMillis() }.thenBy { it.name.lowercase() } }
        ),
        ProjectSortOption(
            "type-asc",
            "Type (A→Z)",
            comparatorProvider = { compareBy<ProjectBase> { it.typeId.lowercase() }.thenBy { it.name.lowercase() } }
        ),
        ProjectSortOption(
            "type-desc",
            "Type (Z→A)",
            comparatorProvider = { compareByDescending<ProjectBase> { it.typeId.lowercase() }.thenBy { it.name.lowercase() } }
        )
    )

    private val list = QListWidget().apply {
        objectName = "projectsListView"
        uniformItemSizes = false
        alternatingRowColors = true
        frameShape = QFrame.Shape.NoFrame
        selectionMode = QAbstractItemView.SelectionMode.SingleSelection
        verticalScrollMode = QAbstractItemView.ScrollMode.ScrollPerItem
        verticalScrollBarPolicy = Qt.ScrollBarPolicy.ScrollBarAlwaysOff
        mouseTracking = true
        setThemedStyle {
            selector("QListWidget#projectsListView") {
                backgroundColor(TColors.Surface0)
                border()
                any("alternate-background-color", TColors.Surface0)
            }
            selector("QListWidget#projectsListView::item") {
                background("transparent")
            }
        }
    }

    init {
        list.itemDoubleClicked.connect { item ->
            val project = item?.data(Qt.ItemDataRole.UserRole) as? ProjectBase ?: return@connect
            if (project.isInvalidCatalogProject()) return@connect
            ctx.openProject(project)
        }
        list.contextMenuPolicy = Qt.ContextMenuPolicy.CustomContextMenu
        list.customContextMenuRequested.connect { pos ->
            val viewport = list.viewport() ?: return@connect
            val viewportPos = viewport.mapFrom(list, pos)
            val item = list.itemAt(viewportPos) ?: return@connect
            val project = item.data(Qt.ItemDataRole.UserRole) as? ProjectBase ?: return@connect
            if (!project.isInvalidCatalogProject()) return@connect
            list.setCurrentItem(item)

            val menu = QMenu(list).apply {
                separatorsCollapsible = true
                applyMenuStyle(this)
            }
            menu.addAction("Hide Invalid Project")?.triggered?.connect {
                ctx.hideInvalidProject(project)
            }
            menu.exec(viewport.mapToGlobal(viewportPos))
        }
        list.setItemDelegate(ListRowDelegate())
    }

    /** Returns the list widget. */
    override fun widget(): QWidget = list

    /** Applies projects to the list. */
    override fun applyProjects(projects: List<ProjectBase>, sort: ProjectSortOption) {
        list.clear()
        val comparator = sort.comparator(ProjectSortContext(ctx))
        val sorted = if (sort.kind == SortKind.Comparator && comparator != null) projects.sortedWith(comparator) else projects
        sorted.forEach { project ->
            val item = QListWidgetItem()
            item.setText(project.name)
            item.setData(Qt.ItemDataRole.UserRole, project)
            item.setSizeHint(qs(420, 56))
            list.addItem(item)
        }
        list.verticalScrollBar()?.singleStep = 56
    }

    /** Releases resources. */
    override fun dispose() {}

    /** Delegate that paints list rows. */
    private class ListRowDelegate : QStyledItemDelegate() {

        /** Paints a row with icon and secondary text. */
        override fun paint(painter: QPainter?, option: QStyleOptionViewItem, index: QModelIndex) {
            val p = painter ?: return
            val opt = QStyleOptionViewItem(option)
            val project = index.data(Qt.ItemDataRole.UserRole) as? ProjectBase
            val rect = opt.rect

            p.save()
            if (opt.state.testFlag(QStyle.StateFlag.State_MouseOver)) {
                p.fillRect(rect, opt.palette.color(QPalette.ColorRole.Highlight))
            }
            if (opt.state.testFlag(QStyle.StateFlag.State_Selected)) {
                p.fillRect(rect, opt.palette.color(QPalette.ColorRole.Highlight))
            }

            val iconRect = QRect(rect.x() + 12, rect.y() + 8, 40, 40)
            val textRect = QRect(rect.x() + 60, rect.y() + 6, rect.width() - 72, rect.height() - 12)

            val pix = try {
                val path = project?.getIconPath().orEmpty()
                loadScaledPixmap(path, qs(iconRect.width(), iconRect.height()))
                } catch (_: Throwable) { QPixmap() }

            if (!pix.isNull) {
                val drawRect = QRect(iconRect)
                p.drawPixmap(drawRect, pix)
            }

            val name = project?.name ?: index.data(Qt.ItemDataRole.DisplayRole).toString()
            val isInvalid = project?.isInvalidCatalogProject() == true
            val secondary = if (isInvalid) {
                "Invalid project: missing trproj.json"
            } else {
                project?.path?.toString()?.redactUserPath().orEmpty()
            }

            val nameFont = QFont(opt.font)
            nameFont.setBold(true)
            p.setFont(nameFont)
            p.setPen(if (isInvalid) QColor(TColors.Warning) else opt.palette.color(QPalette.ColorRole.Text))
            p.drawText(textRect, Qt.AlignmentFlag.AlignTop.value(), name)

            val secondaryFont = QFont(opt.font)
            secondaryFont.setPointSize(max(8, opt.font.pointSize() - 2))
            p.setFont(secondaryFont)
            p.setPen(if (isInvalid) QColor(TColors.Warning) else opt.palette.color(QPalette.ColorRole.PlaceholderText))
            p.drawText(textRect, Qt.AlignmentFlag.AlignBottom.value(), secondary)
            p.restore()
        }
    }
}

/** Grid tile widget with context menu for grouping. */
private class ProjectTile(
    private val project: ProjectBase,
    private val ctx: ProjectStyleContext,
    private val onSelected: ((ProjectTile) -> Unit)? = null
) : QWidget() {
    private var selected = false
    init {
        objectName = "ProjectTile_${System.identityHashCode(this)}"
        setAttribute(Qt.WidgetAttribute.WA_StyledBackground, true)
        sizePolicy = QSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Preferred)
        setFixedWidth(GRID_TILE_WIDTH)
        minimumSize = qs(GRID_TILE_WIDTH, 0)

        val layout = vBoxLayout(this) { contentsMargins = 8.m; widgetSpacing = 6; setAlignment(Qt.AlignmentFlag.AlignTop) }

        val iconLabel = label {
            val iconSize = qs(GRID_ICON_SIZE, GRID_ICON_SIZE)
            minimumSize = iconSize
            maximumSize = iconSize
            setAlignment(Qt.AlignmentFlag.AlignCenter)
            pixmap = loadScaledPixmap(project.getIconPath(), iconSize, this@ProjectTile)
        }
        layout.addWidget(iconLabel, 0, Qt.AlignmentFlag.AlignHCenter)

        val title = label(wrapProjectName(project)) {
            setAlignment(Qt.AlignmentFlag.AlignCenter)
            wordWrap = true
            textFormat = Qt.TextFormat.RichText
            sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Minimum)
        }
        configureTileTitleLabel(title, title.text)
        layout.addWidget(title)

        cursor = if(ctx.controlsVisible()) QCursor(Qt.CursorShape.OpenHandCursor) else QCursor(Qt.CursorShape.PointingHandCursor)
        applyTileStyle()
        applySelectionStyle()
    }

    override fun enterEvent(event: @Nullable QEnterEvent?) {
        super.enterEvent(event)
        cursor = if(ctx.controlsVisible()) QCursor(Qt.CursorShape.OpenHandCursor) else QCursor(Qt.CursorShape.PointingHandCursor)
    }

    override fun leaveEvent(event: @Nullable QEvent?) {
        super.leaveEvent(event)
        unsetCursor()
    }

    /** Opens the project on double click and shows group menu on right click. */
    override fun mousePressEvent(event: QMouseEvent?) {
        super.mousePressEvent(event)
        if (event?.button() == Qt.MouseButton.LeftButton) {
            onSelected?.invoke(this)
            event.accept()
        }
        if (event?.button() == Qt.MouseButton.RightButton) {
            showContextMenu(event.globalPosition().toPoint())
            event.accept()
        }
    }

    override fun mouseDoubleClickEvent(event: QMouseEvent?) {
        super.mouseDoubleClickEvent(event)
        if (event?.button() == Qt.MouseButton.LeftButton && !project.isInvalidCatalogProject()) {
            ctx.openProject(project)
        }
    }

    fun setSelected(value: Boolean) {
        selected = value
        applySelectionStyle()
    }

    /** Applies themed tile styles. */
    private fun applyTileStyle() {
        setThemedStyle {
            selector("#${objectName}") {
                background("transparent")
                border(0)
            }
        }
    }

    private fun applySelectionStyle() {
        styleSheet = qtStyle {
            widget {
                if (selected) {
                    backgroundColor(TColors.Highlight)
                    borderRadius(6)
                } else {
                    background("transparent")
                    border(0)
                }
            }
        }.toStyleSheet()
    }

    /** Shows group assignment menu. */
    private fun showContextMenu(pos: QPoint) {
        val menu = QMenu(this).apply {
            separatorsCollapsible = true
            applyMenuStyle(this)
        }
        if (project.isInvalidCatalogProject()) {
            menu.addAction("Hide Invalid Project")?.triggered?.connect {
                ctx.hideInvalidProject(project)
            }
            menu.exec(pos)
            return
        }

        val groups = ctx.groupStore.groupNames()
        val assign = menu.addMenu("Move to group")
        assign?.addAction("Ungroup")?.triggered?.connect {
            ctx.groupStore.assign(project.path.toString(), null)
            ctx.requestRefresh()
        }
        groups.forEach { g ->
            assign?.addAction(g)?.triggered?.connect {
                ctx.groupStore.assign(project.path.toString(), g)
                ctx.requestRefresh()
            }
        }
        assign?.addSeparator()
        assign?.addAction("New group...")?.triggered?.connect { promptNewGroup() }
        menu.exec(pos)
    }

    /** Prompts for a new group and assigns the project. */
    private fun promptNewGroup() {
        val name = QInputDialog.getText(this, "Add group", "Group name:")
        if (!name.isNullOrBlank()) {
            ctx.groupStore.addGroup(name.trim())
            ctx.groupStore.assign(project.path.toString(), name.trim())
            ctx.requestRefresh()
        }
    }
}

/** Freeform canvas allowing Shift+Drag positioning persisted by LayoutStore. */
private class FreeformCanvas(private val ctx: ProjectStyleContext) : QWidget() {
    private val tiles = mutableMapOf<String, DraggableTile>()
    private var dragTile: DraggableTile? = null
    private var dragOffset = QPoint()
    private var selectedTile: DraggableTile? = null

    init {
        minimumSize = qs(0, 0)
        sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)
        setContentsMargins(0, 0, 0, 0)
    }

    override fun resizeEvent(event: QResizeEvent?) {
        super.resizeEvent(event)
        minimumWidth = width()
    }

    override fun mousePressEvent(event: QMouseEvent?) {
        super.mousePressEvent(event)
        if (event?.button() == Qt.MouseButton.LeftButton) {
            selectedTile?.setSelected(false)
            selectedTile = null
        }
    }

    /** Applies projects, maintaining stored positions when available. */
    fun applyProjects(projects: List<ProjectBase>) {
        val paths = projects.map { it.path.toString() }.toSet()
        val toRemove = tiles.keys - paths
        toRemove.forEach { key -> tiles.remove(key)?.let { it.hide(); it.setParent(null); it.disposeLater() } }
        if (selectedTile != null && !tiles.values.contains(selectedTile)) selectedTile = null

        projects.forEachIndexed { idx, project ->
            val key = project.path.toString()
            val tile = tiles.getOrPut(key) { DraggableTile(project, ctx, this::onDragStart, this::onDragMove, this::onDragEnd, this::onSelected) }
            tile.setParent(this)
            if (!tile.isVisible) tile.show()
            if (tile == selectedTile) tile.setSelected(true)

            val saved = ctx.layoutStore.get(key)
            if (saved != null) tile.move(saved) else {
                val slot = autoSlot(idx)
                tile.move(slot)
                ctx.layoutStore.put(key, tile.pos)
            }

            tile.move(clampPosition(tile, tile.pos))
        }

        var maxBottom = 0
        tiles.values.forEach {
            val bottom = it.y + it.height
            if(bottom > maxBottom) maxBottom = bottom
        }
        minimumHeight = (maxBottom + 32).coerceAtLeast(300)
        updateGeometry()
        update()
    }

    /** Computes an automatic tile slot position. */
    private fun autoSlot(idx: Int): QPoint {
        val col = idx % 6
        val row = idx / 6
        val spacing = 20
        val size = GRID_TILE_WIDTH
        val x = 16 + col * (size + spacing)
        val y = 16 + row * (size + spacing)
        return QPoint(x, y)
    }

    /** Begins project dragging. */
    private fun onDragStart(tile: DraggableTile, event: QMouseEvent) {
        if (event.modifiers().value() != Qt.KeyboardModifier.ShiftModifier.value()) return
        dragTile = tile
        dragOffset = event.pos()
        tile.cursor = QCursor(Qt.CursorShape.ClosedHandCursor)
    }

    /** Updates project dragging. */
    private fun onDragMove(tile: DraggableTile, event: QMouseEvent) {
        if (dragTile != tile) return
        val newPos = tile.pos() + event.pos() - dragOffset
        val clamped = clampPosition(tile, newPos)
        tile.move(clamped)

        val bottom = clamped.y() + tile.height
        if(bottom + 32 > minimumHeight) {
            minimumHeight = bottom + 32
            updateGeometry()
        }
    }

    /** Completes project drag, saves position. */
    private fun onDragEnd(tile: DraggableTile, event: QMouseEvent) {
        if (dragTile != tile) return
        dragTile = null
        tile.cursor = QCursor(Qt.CursorShape.OpenHandCursor)
        val key = tile.project.path.toString()
        ctx.layoutStore.put(key, tile.pos)
    }

    private fun onSelected(tile: DraggableTile) {
        if (selectedTile == tile) return
        selectedTile?.setSelected(false)
        selectedTile = tile
        selectedTile?.setSelected(true)
    }

    /** Clamps a tile position to keep it within the canvas bounds. */
    private fun clampPosition(tile: QWidget, pos: QPoint): QPoint {
        val maxX = (width() - tile.width()).coerceAtLeast(0)
        val maxY = (height() - tile.height()).coerceAtLeast(0)
        val x = pos.x().coerceIn(0, maxX)
        val y = pos.y().coerceIn(0, maxY)
        return QPoint(x, y)
    }

    /** Returns a size hint for the canvas. */
    override fun sizeHint(): QSize = qs(0, 600)
}

/** Draggable tile used inside the freeform canvas. */
private class DraggableTile(
    val project: ProjectBase,
    private val ctx: ProjectStyleContext,
    private val onStart: (DraggableTile, QMouseEvent) -> Unit,
    private val onMove: (DraggableTile, QMouseEvent) -> Unit,
    private val onEnd: (DraggableTile, QMouseEvent) -> Unit,
    private val onSelected: (DraggableTile) -> Unit,
) : QWidget() {
    private var dragging = false
    private var selected = false

    init {
        setAttribute(Qt.WidgetAttribute.WA_StyledBackground, true)
        setFixedWidth(GRID_TILE_WIDTH)
        cursor = defaultCursor()

        val layout = vBoxLayout(this) { contentsMargins = 8.m; widgetSpacing = 6; setAlignment(Qt.AlignmentFlag.AlignTop) }
        val iconLabel = label {
            val iconSize = qs(GRID_ICON_SIZE, GRID_ICON_SIZE)
            minimumSize = iconSize
            maximumSize = iconSize
            setAlignment(Qt.AlignmentFlag.AlignCenter)
            pixmap = loadScaledPixmap(project.getIconPath(), iconSize, this@DraggableTile)
        }
        layout.addWidget(iconLabel, 0, Qt.AlignmentFlag.AlignHCenter)
        val title = label(wrapProjectName(project)) {
            setAlignment(Qt.AlignmentFlag.AlignCenter)
            wordWrap = true
            textFormat = Qt.TextFormat.RichText
            sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Minimum)
        }
        configureTileTitleLabel(title, title.text)
        layout.addWidget(title)

        setThemedStyle {
            selector("#${objectName.takeIf { it.isNotBlank() } ?: "tile"}") {
                background("transparent")
                border(0)
                borderRadius(8)
            }
        }
        applySelectionStyle()
    }

    override fun enterEvent(event: @Nullable QEnterEvent?) {
        super.enterEvent(event)
        cursor = defaultCursor()
    }
    override fun leaveEvent(event: QEvent?) {
        super.leaveEvent(event)
        if (!dragging) unsetCursor()
    }

    /** Starts dragging on mouse press. */
    override fun mousePressEvent(event: QMouseEvent?) {
        super.mousePressEvent(event)
        if (event == null) return
        if (event.button() == Qt.MouseButton.RightButton && project.isInvalidCatalogProject()) {
            showContextMenu(event.globalPosition().toPoint())
            event.accept()
            return
        }
        if (event.button() == Qt.MouseButton.LeftButton &&
            event.modifiers().value() == Qt.KeyboardModifier.ShiftModifier.value()) {
            dragging = true
            cursor = QCursor(Qt.CursorShape.ClosedHandCursor)
            onStart(this, event)
            event.accept()
        } else if (event.button() == Qt.MouseButton.LeftButton) {
            onSelected(this)
            applySelectionStyle()
            event.accept()
        }
    }

    private fun showContextMenu(pos: QPoint) {
        val menu = QMenu(this).apply {
            separatorsCollapsible = true
            applyMenuStyle(this)
        }
        menu.addAction("Hide Invalid Project")?.triggered?.connect {
            ctx.hideInvalidProject(project)
        }
        menu.exec(pos)
    }

    override fun mouseDoubleClickEvent(event: QMouseEvent?) {
        super.mouseDoubleClickEvent(event)
        if (event?.button() == Qt.MouseButton.LeftButton &&
            event.modifiers().value() != Qt.KeyboardModifier.ShiftModifier.value() &&
            !project.isInvalidCatalogProject()) {
            ctx.openProject(project)
        }
    }

    /** Updates dragging on mouse move. */
    override fun mouseMoveEvent(event: QMouseEvent?) {
        super.mouseMoveEvent(event)
        if (event != null) {
            if (!dragging) cursor = defaultCursor()
            onMove(this, event)
        }
    }

    /** Stops dragging on mouse release. */
    override fun mouseReleaseEvent(event: QMouseEvent?) {
        super.mouseReleaseEvent(event)
        if (event != null) {
            dragging = false
            onEnd(this, event)
            cursor = defaultCursor()
        }
    }

    private fun defaultCursor(): QCursor {
        val shiftHeld = QGuiApplication.keyboardModifiers().testFlag(Qt.KeyboardModifier.ShiftModifier)
        return if (shiftHeld || ctx.controlsVisible()) QCursor(Qt.CursorShape.OpenHandCursor) else QCursor(Qt.CursorShape.PointingHandCursor)
    }

    fun setSelected(value: Boolean) {
        selected = value
        applySelectionStyle()
    }

    private fun applySelectionStyle() {
        val bg = if (selected) TColors.Highlight else "transparent"
        styleSheet = qtStyle {
            widget {
                backgroundColor(bg)
                border(0)
                borderRadius(8)
            }
        }.toStyleSheet()
    }
}

private fun ProjectBase.isInvalidCatalogProject(): Boolean =
    this.typeId == ProjectMngr.INVALID_CATALOG_PROJECT_TYPE

private fun escapeHtml(input: String): String = input
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

private fun wrapProjectName(project: ProjectBase): String {
    val escaped = escapeHtml(project.name)
    val invalidSuffix = if (project.isInvalidCatalogProject()) {
        """<br/><span style="color:${TColors.Warning}; font-size:10px;">Invalid project (missing trproj.json)</span>"""
    } else ""
    return "<div style=\"text-align:center; word-break: break-word; overflow-wrap: anywhere;\">$escaped$invalidSuffix</div>"
}

private fun wrapProjectName(name: String): String {
    val escaped = escapeHtml(name)
    return "<div style=\"text-align:center; word-break: break-word; overflow-wrap: anywhere;\">$escaped</div>"
}

private fun configureTileTitleLabel(label: QLabel, richText: String) {
    label.minimumWidth = GRID_TILE_TITLE_WIDTH
    label.text = richText

    val doc = QTextDocument()
    doc.defaultFont = label.font
    doc.documentMargin = 0.0
    doc.setHtml(richText)
    doc.textWidth = GRID_TILE_TITLE_WIDTH.toDouble()

    val renderedHeight = ceil(doc.size().height()).toInt()
    val twoLines = label.fontMetrics().lineSpacing() * 2
    label.minimumHeight = max(renderedHeight, twoLines)
}

private fun applyMenuStyle(menu: QMenu) {
    menu.setThemedStyle {
        selector("QMenu") {
            backgroundColor(TColors.Surface1)
            color(TColors.Text)
            border(1, TColors.Button0)
            padding(2,4,2,4)
        }
        selector("QMenu::item:selected") {
            backgroundColor(TColors.Highlight)
            color(TColors.Text)
        }
        selector("QMenu::separator") {
            backgroundColor(TColors.Button0)
        }
    }
}

/** Returns last accessed in millis or zero when missing. */
private fun ProjectBase.lastAccessedMillis(): Long = lastAccessed?.toEpochMilli() ?: 0L
