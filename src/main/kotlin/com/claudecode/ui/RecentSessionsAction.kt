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
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
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
import javax.swing.event.DocumentEvent

/**
 * Toolbar action surfacing recent Claude chats for the current project.
 * Clicking the toolbar icon opens a popup of past sessions. Per-row UX:
 *
 *   - **Hover** highlights the row.
 *   - **Click the name** → opens a new tab with `claude --resume <id>`.
 *   - **Click the star icon** → pin/unpin (pinned chats always sort first
 *     and don't get rotated out by the per-project cap).
 *   - **Click the pencil icon** → inline rename via input dialog.
 *   - **Click the trash icon** → confirm and delete the dropdown entry
 *     (Claude's own JSONL transcript is untouched).
 *   - **Search box at top** filters by name as you type.
 *
 * Storage lives in [RecentSessionsStore]; this file is purely a UI consumer.
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
        // All known entries — the JList is a filtered view of this.
        val allItems = recents.toMutableList()

        val listModel = DefaultListModel<RecentSession>().apply {
            allItems.forEach { addElement(it) }
        }
        val renderer = RecentItemRenderer()
        val list = JBList(listModel).apply {
            cellRenderer = renderer
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = minOf(recents.size, 10)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty()
        }

        val searchField = SearchTextField().apply {
            textEditor.toolTipText = "Filter by name"
        }
        // Keep the popup focused on the list when arrow-keying, but route
        // typing into the search box. We wire focus to the list and let
        // SearchTextField pick up keyboard events via its own subtree.

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                val q = searchField.text.trim().lowercase()
                listModel.clear()
                val filtered = if (q.isEmpty()) allItems
                else allItems.filter { it.name.lowercase().contains(q) }
                filtered.forEach { listModel.addElement(it) }
            }
        })

        val container = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(searchField, BorderLayout.NORTH)
            add(
                JBScrollPane(list).apply {
                    border = JBUI.Borders.empty()
                    preferredSize = Dimension(420, list.preferredSize.height.coerceAtMost(320))
                },
                BorderLayout.CENTER,
            )
            preferredSize = Dimension(420, 350)
        }

        val popup: JBPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(container, searchField.textEditor)
            .setTitle("Recent Chats")
            .setResizable(true)
            .setMovable(false)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(false)  // keep popup alive when Rename / Delete dialogs open
            .createPopup()

        // In "Undock" view mode the tool window auto-hides as soon as
        // focus leaves it (the popup counts as "elsewhere"). Re-activate
        // the tool window when the popup closes so the chat tab the user
        // just interacted with stays visible, instead of disappearing
        // back to the toolbar icon.
        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                ensureToolWindowVisible()
            }
        })

        installMouseHandlers(list, renderer, listModel, allItems, projectPath, popup)

        val component = e.inputEvent?.component
        if (component != null) popup.showUnderneathOf(component)
        else popup.showCenteredInCurrentWindow(project)
    }

    private fun installMouseHandlers(
        list: JBList<RecentSession>,
        renderer: RecentItemRenderer,
        model: DefaultListModel<RecentSession>,
        allItems: MutableList<RecentSession>,
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
                        handleDelete(item, model, allItems, idx, projectPath, popup)
                    isInRenameZone(e.point, bounds) ->
                        handleRename(item, model, allItems, idx, projectPath, popup)
                    isInPinZone(e.point, bounds) ->
                        handlePinToggle(item, model, allItems, idx, projectPath)
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
        // The popup's onClose listener will also activate, but we call
        // here too so the timing is deterministic — the new tab gets
        // focus before the auto-hide kicks in.
        ensureToolWindowVisible()
    }

    /**
     * Force-shows and activates the Claude Code tool window. Needed because
     * the "Undock" view mode auto-hides the tool window when focus leaves
     * it (and the Recent dropdown counts as "elsewhere"). Without this,
     * picking a session would open the tab but the tool window itself
     * would disappear back to the toolbar icon.
     *
     * No-op when the tool window manager can't resolve the window — e.g.
     * during shutdown.
     */
    private fun ensureToolWindowVisible() {
        val tw = ToolWindowManager.getInstance(project)
            .getToolWindow(com.claudecode.ClaudeConstants.TOOL_WINDOW_ID) ?: return
        tw.activate(null, true, false)
    }

    private fun handleDelete(
        item: RecentSession,
        model: DefaultListModel<RecentSession>,
        allItems: MutableList<RecentSession>,
        index: Int,
        projectPath: String,
        popup: JBPopup,
    ) {
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
        allItems.removeAll { it.id == item.id }
        if (index < model.size) model.remove(index)
        if (model.isEmpty) popup.cancel()
    }

    private fun handleRename(
        item: RecentSession,
        model: DefaultListModel<RecentSession>,
        allItems: MutableList<RecentSession>,
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
        val updated = item.copy(name = newName)
        RecentSessionsStore.touch(projectPath, updated)
        val sourceIdx = allItems.indexOfFirst { it.id == item.id }
        if (sourceIdx >= 0) allItems[sourceIdx] = updated
        if (index < model.size) model.set(index, updated)
    }

    private fun handlePinToggle(
        item: RecentSession,
        model: DefaultListModel<RecentSession>,
        allItems: MutableList<RecentSession>,
        index: Int,
        projectPath: String,
    ) {
        val newPinned = !item.pinned
        RecentSessionsStore.setPinned(projectPath, item.id, newPinned)
        val updated = item.copy(pinned = newPinned)
        val sourceIdx = allItems.indexOfFirst { it.id == item.id }
        if (sourceIdx >= 0) allItems[sourceIdx] = updated
        if (index < model.size) model.set(index, updated)
    }

    // ──────────────────── hit zones ────────────────────

    /** Three trailing icons, right-aligned: [pin][rename][delete]. */
    private fun isInDeleteZone(point: Point, bounds: Rectangle): Boolean {
        val rightEdge = bounds.x + bounds.width
        return point.x >= rightEdge - DELETE_ICON_ZONE_WIDTH
    }

    private fun isInRenameZone(point: Point, bounds: Rectangle): Boolean {
        val rightEdge = bounds.x + bounds.width
        val end = rightEdge - DELETE_ICON_ZONE_WIDTH
        val start = end - RENAME_ICON_ZONE_WIDTH
        return point.x in start until end
    }

    private fun isInPinZone(point: Point, bounds: Rectangle): Boolean {
        val rightEdge = bounds.x + bounds.width
        val end = rightEdge - DELETE_ICON_ZONE_WIDTH - RENAME_ICON_ZONE_WIDTH
        val start = end - PIN_ICON_ZONE_WIDTH
        return point.x in start until end
    }

    // ──────────────────── renderer ────────────────────

    /** Renderer: history icon + name + pin/rename/delete trailing icons. */
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

            val pinIcon = if (value?.pinned == true) AllIcons.Nodes.Favorite
                else AllIcons.Nodes.NotFavoriteOnHover
            val trailing = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(JLabel(pinIcon).apply {
                    toolTipText = if (value?.pinned == true) "Unpin" else "Pin (keeps this chat at the top)"
                })
                add(JLabel(AllIcons.Actions.Edit).apply { toolTipText = "Rename" })
                add(JLabel(AllIcons.Actions.Close).apply { toolTipText = "Delete" })
                preferredSize = Dimension(
                    PIN_ICON_ZONE_WIDTH + RENAME_ICON_ZONE_WIDTH + DELETE_ICON_ZONE_WIDTH, 0
                )
            }
            panel.add(trailing, BorderLayout.EAST)

            return panel
        }
    }

    companion object {
        // Widths (px) of the trailing icon hit zones, right-to-left. AllIcons
        // buttons are 16px; we reserve extra room so the click targets stay
        // forgiving.
        private const val DELETE_ICON_ZONE_WIDTH = 28
        private const val RENAME_ICON_ZONE_WIDTH = 28
        private const val PIN_ICON_ZONE_WIDTH = 28
    }
}
