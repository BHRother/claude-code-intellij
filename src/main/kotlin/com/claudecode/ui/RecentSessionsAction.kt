package com.claudecode.ui

import com.claudecode.history.RecentSession
import com.claudecode.history.RecentSessionsStore
import com.claudecode.session.SessionManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.UIManager

/**
 * Toolbar action surfacing recent Claude chats for the current project.
 * Clicking the toolbar icon opens a popup of past sessions. Per-row UX:
 *
 *   - **Hover** highlights the row (so click targets are obvious).
 *   - **Click the name** → opens a new tab with `claude --resume <id>`.
 *   - **Click the pencil icon** → inline rename via input dialog.
 *   - **Click the trash icon** → confirm and delete the dropdown entry
 *     (Claude's own JSONL transcript is untouched).
 *
 * Storage lives in [RecentSessionsStore]; this file is purely a UI consumer.
 * Replacing the popup with a side panel later only requires writing a new
 * consumer of the same store APIs.
 */
class RecentSessionsAction(private val project: Project) : AnAction(
    "Recent Sessions",
    "Resume a previous chat for this project",
    AllIcons.Vcs.History,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val projectPath = project.basePath
        e.presentation.isEnabled = !projectPath.isNullOrBlank() &&
            RecentSessionsStore.recentForProject(projectPath).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val projectPath = project.basePath ?: return
        val recents = RecentSessionsStore.recentForProject(projectPath)
        if (recents.isEmpty()) return
        showPopup(e, projectPath, recents)
    }

    // ──────────────────── popup construction ────────────────────

    private fun showPopup(e: AnActionEvent, projectPath: String, recents: List<RecentSession>) {
        val listModel = DefaultListModel<RecentSession>().apply {
            recents.forEach { addElement(it) }
        }
        val renderer = RecentItemRenderer()
        val list = JBList(listModel).apply {
            cellRenderer = renderer
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = minOf(recents.size, 10)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty()
        }

        val scrollPane = JBScrollPane(list).apply {
            border = JBUI.Borders.empty()
            // Bound the popup width — long session names get clipped with
            // ellipsis at render time. Height grows with row count up to 10.
            preferredSize = Dimension(380, list.preferredSize.height.coerceAtMost(320))
        }

        val popup: JBPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollPane, list)
            .setTitle("Recent Chats")
            .setResizable(true)
            .setMovable(false)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(false)  // keep popup alive when Rename / Delete dialogs open
            .createPopup()

        installMouseHandlers(list, renderer, listModel, projectPath, popup)

        val component = e.inputEvent?.component
        if (component != null) popup.showUnderneathOf(component)
        else popup.showCenteredInCurrentWindow(project)
    }

    private fun installMouseHandlers(
        list: JBList<RecentSession>,
        renderer: RecentItemRenderer,
        model: DefaultListModel<RecentSession>,
        projectPath: String,
        popup: JBPopup,
    ) {
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                val idx = list.locationToIndex(e.point)
                if (idx < 0) return
                val bounds = list.getCellBounds(idx, idx) ?: return
                if (!bounds.contains(e.point)) return

                val item = model.getElementAt(idx)
                when {
                    isInDeleteZone(e.point, bounds) ->
                        handleDelete(item, model, idx, projectPath, popup)
                    isInRenameZone(e.point, bounds) ->
                        handleRename(item, model, idx, projectPath, popup)
                    else -> {
                        popup.cancel()
                        resumeSession(item)
                    }
                }
            }

            override fun mouseExited(e: MouseEvent) {
                if (renderer.hoveredIndex != -1) {
                    renderer.hoveredIndex = -1
                    list.repaint()
                }
            }
        })

        list.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val idx = list.locationToIndex(e.point)
                val bounds = if (idx >= 0) list.getCellBounds(idx, idx) else null
                val onRow = bounds != null && bounds.contains(e.point)
                val newHover = if (onRow) idx else -1
                if (newHover != renderer.hoveredIndex) {
                    renderer.hoveredIndex = newHover
                    list.repaint()
                }
                // All three regions (name / rename / delete) are clickable,
                // so a single hand cursor across the whole row matches the
                // visual hover-highlight without flicker.
                list.cursor = Cursor.getPredefinedCursor(
                    if (onRow) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR
                )
            }
        })
    }

    // ──────────────────── actions ────────────────────

    private fun resumeSession(item: RecentSession) {
        SessionManager.getInstance(project).createSession(
            name = item.name,
            workDir = item.workingDirectory,
            initialSessionId = item.id,
        )
    }

    private fun handleDelete(
        item: RecentSession,
        model: DefaultListModel<RecentSession>,
        index: Int,
        projectPath: String,
        popup: JBPopup,
    ) {
        // Quick confirm — destructive on a per-project resource is worth a
        // single click, not silent. parentComponent inherits popup modality.
        val confirm = Messages.showYesNoDialog(
            popup.content,
            "Remove \"${item.name}\" from recent chats?\n\n" +
                "Claude's own conversation history under ~/.claude/projects is not deleted; " +
                "only the entry in this dropdown.",
            "Remove Recent Chat",
            Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return
        RecentSessionsStore.remove(projectPath, item.id)
        if (index < model.size) model.remove(index)
        if (model.isEmpty) popup.cancel()
    }

    private fun handleRename(
        item: RecentSession,
        model: DefaultListModel<RecentSession>,
        index: Int,
        projectPath: String,
        popup: JBPopup,
    ) {
        val newName = Messages.showInputDialog(
            popup.content,
            "Rename this chat:",
            "Rename Recent Chat",
            null,
            item.name,
            null
        )?.trim()
        if (newName.isNullOrBlank() || newName == item.name) return
        val updated = item.copy(name = newName)  // lastUsedAt left alone — rename isn't a "use"
        RecentSessionsStore.touch(projectPath, updated)
        if (index < model.size) model.set(index, updated)
    }

    // ──────────────────── hit zones ────────────────────

    /**
     * The trailing trash icon — last [DELETE_ICON_ZONE_WIDTH] pixels of
     * the cell.
     */
    private fun isInDeleteZone(point: Point, bounds: Rectangle): Boolean {
        val rightEdge = bounds.x + bounds.width
        return point.x >= rightEdge - DELETE_ICON_ZONE_WIDTH
    }

    /**
     * The pencil icon sits just left of the trash icon; this zone covers
     * the next [RENAME_ICON_ZONE_WIDTH] pixels inward from the delete zone.
     */
    private fun isInRenameZone(point: Point, bounds: Rectangle): Boolean {
        val rightEdge = bounds.x + bounds.width
        val renameStart = rightEdge - DELETE_ICON_ZONE_WIDTH - RENAME_ICON_ZONE_WIDTH
        val renameEnd = rightEdge - DELETE_ICON_ZONE_WIDTH
        return point.x in renameStart until renameEnd
    }

    // ──────────────────── renderer ────────────────────

    /**
     * Renderer: history icon + name (ellipsised) + trailing pencil & trash.
     * The [hoveredIndex] field is set by the parent action's mouse-move
     * handler; matching cells paint with the selection background so the
     * row "follows" the cursor like Cursor's history popup.
     */
    private class RecentItemRenderer : ListCellRenderer<RecentSession> {
        @Volatile var hoveredIndex: Int = -1

        override fun getListCellRendererComponent(
            list: javax.swing.JList<out RecentSession>?,
            value: RecentSession?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val isHovered = index == hoveredIndex
            val highlight = isSelected || isHovered

            val panel = JPanel(BorderLayout(6, 0))
            panel.isOpaque = true
            panel.background = if (highlight)
                UIManager.getColor("List.selectionBackground") ?: list?.background
            else list?.background
            panel.border = JBUI.Borders.empty(5, 8)

            val fg = if (highlight)
                UIManager.getColor("List.selectionForeground") ?: UIManager.getColor("List.foreground")
            else UIManager.getColor("List.foreground")

            val name = value?.name ?: "(untitled)"
            val nameLabel = JLabel(name, AllIcons.Vcs.History, JLabel.LEADING).apply {
                foreground = fg
                toolTipText = name
            }
            panel.add(nameLabel, BorderLayout.CENTER)

            // Trailing area carries the two action icons. Hit detection in
            // the mouse handler keys off pixel ranges from the right edge
            // (see isInDeleteZone / isInRenameZone), so the visual layout
            // here just needs to match those widths.
            val trailing = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(JLabel(AllIcons.Actions.Edit).apply { toolTipText = "Rename" })
                add(JLabel(AllIcons.Actions.Close).apply { toolTipText = "Delete" })
                preferredSize = Dimension(
                    RENAME_ICON_ZONE_WIDTH + DELETE_ICON_ZONE_WIDTH, 0
                )
            }
            panel.add(trailing, BorderLayout.EAST)

            return panel
        }
    }

    companion object {
        // Widths (px) of the two trailing icon hit zones. AllIcons buttons
        // are 16px; we reserve extra room so the click targets are forgiving.
        private const val DELETE_ICON_ZONE_WIDTH = 28
        private const val RENAME_ICON_ZONE_WIDTH = 28
    }
}
