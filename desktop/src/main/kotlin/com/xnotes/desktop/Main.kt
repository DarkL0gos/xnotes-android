package com.xnotes.desktop

import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.Tool
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JColorChooser
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.filechooser.FileNameExtensionFilter

/** Desktop host: edits .xnote pages with the shared canvas, shows .xcanvas info, saves safely. */
private class DesktopWindow : JFrame("xnotes") {
    private val storage = DesktopStorage()
    private val preferences = DesktopPreferences()
    private var document: OpenDocument? = null
    private val details = JTextArea()
    private val canvas = DesktopCanvasView()
    private val cards = JPanel(CardLayout())
    private val pageLabel = JLabel("Страница 0/0")
    private val zoomLabel = JLabel("100%")
    private val undoButton = JButton("↶").apply { toolTipText = "Отменить (Ctrl+Z)" }
    private val redoButton = JButton("↷").apply { toolTipText = "Повторить (Ctrl+Shift+Z)" }
    private val colorButton = JButton("Цвет")
    private val toolButtons = LinkedHashMap<Tool, JToggleButton>()
    private val status = JLabel("Откройте файл .xnote или .xcanvas")

    /** Edits made since the last session checkpoint; the timer writes them out. */
    private var sessionPending = false
    private val sessionTimer = Timer(SESSION_CHECKPOINT_MS) { checkpointSession() }

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        minimumSize = Dimension(760, 480)
        preferredSize = Dimension(1100, 800)
        layout = BorderLayout(8, 8)
        details.isEditable = false
        details.lineWrap = true
        details.wrapStyleWord = true

        val files = JPanel(FlowLayout(FlowLayout.LEADING, 4, 2)).apply {
            add(JButton("Открыть…").apply { addActionListener { chooseOpen() } })
            add(JButton("Сохранить").apply { addActionListener { save(false) } })
            add(JButton("Сохранить как…").apply { addActionListener { save(true) } })
            add(undoButton.apply { addActionListener { canvas.undo() } })
            add(redoButton.apply { addActionListener { canvas.redo() } })
            add(JButton("←").apply { addActionListener { navigate(-1) } })
            add(pageLabel)
            add(JButton("→").apply { addActionListener { navigate(1) } })
            add(JButton("−").apply { addActionListener { canvas.zoomStep(false) } })
            add(zoomLabel)
            add(JButton("+").apply { addActionListener { canvas.zoomStep(true) } })
            add(JButton("По ширине").apply { addActionListener { canvas.fitWidth() } })
            add(JCheckBox("Тёмная бумага", true).apply {
                addActionListener { canvas.setDarkPaper(isSelected) }
            })
        }
        val tools = JPanel(FlowLayout(FlowLayout.LEADING, 4, 2)).apply {
            val group = ButtonGroup()
            for ((tool, label) in TOOLS) {
                val button = JToggleButton(label).apply { addActionListener { selectTool(tool) } }
                group.add(button)
                toolButtons[tool] = button
                add(button)
            }
            add(colorButton.apply { addActionListener { chooseColor() } })
        }
        add(JPanel(BorderLayout()).apply {
            add(files, BorderLayout.NORTH)
            add(tools, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        cards.add(JScrollPane(details), "details")
        cards.add(canvas, "pages")
        add(cards, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)

        canvas.onEdited = { documentEdited() }
        canvas.onViewChanged = { updateViewReadouts() }
        canvas.onHistoryChanged = { updateHistoryButtons() }
        selectTool(Tool.PEN)
        updateColorButton()
        updateHistoryButtons()
        installShortcuts()

        addWindowListener(object : WindowAdapter() {
            // Unsaved edits are kept as the session rather than asked about: the next launch
            // restores them, just as it would after a crash.
            override fun windowClosing(event: WindowEvent) = flushSession()
            override fun windowClosed(event: WindowEvent) {
                sessionTimer.stop()
                canvas.dispose()
                storage.close()
            }
        })
        sessionTimer.start()
        pack()
        setLocationRelativeTo(null)
    }

    /** Open [requested] (or the last file), unless a session with unsaved edits should come back first. */
    fun start(requested: File?) {
        val restored = if (storage.hasSession()) storage.restoreSession() else null
        if (restored == null) {
            storage.clearSession()
            (requested ?: preferences.lastFile())?.let(::open)
            return
        }
        val elsewhere = requested != null && requested.absoluteFile != restored.document.file?.absoluteFile
        if (elsewhere) {
            val choice = JOptionPane.showConfirmDialog(this,
                "Есть несохранённые изменения в «${restored.document.title}».\nВосстановить их вместо открытия ${requested!!.name}?",
                "Восстановление", JOptionPane.YES_NO_OPTION)
            if (choice != JOptionPane.YES_OPTION) {
                storage.clearSession()
                open(requested)
                return
            }
        }
        show(restored.document)
        if (restored.document is OpenDocument.Note) canvas.restoreView(restored.view)
        status.text = "Восстановлены несохранённые изменения: ${restored.document.file?.absolutePath ?: restored.document.title}"
    }

    fun open(file: File) {
        if (!confirmDiscard()) return
        try {
            val opened = storage.open(file)
            storage.clearSession()
            show(opened)
            status.text = file.absolutePath
            runCatching { preferences.remember(file) }
        } catch (e: Exception) {
            showError(e)
        }
    }

    private fun show(opened: OpenDocument) {
        document = opened
        sessionPending = false
        when (opened) {
            is OpenDocument.Note -> {
                canvas.show(opened.value)
                (cards.layout as CardLayout).show(cards, "pages")
                canvas.requestFocusInWindow()
            }
            is OpenDocument.Canvas -> {
                details.text = buildString {
                    appendLine("Холст: ${opened.title}")
                    appendLine("Элементов: ${opened.value.itemCount}")
                    appendLine("Закладок: ${opened.value.waypoints.size}")
                    appendLine()
                    appendLine("Бесконечный холст на Linux пока только просматривается как сводка.")
                }
                (cards.layout as CardLayout).show(cards, "details")
            }
        }
        updateViewReadouts()
        updateHistoryButtons()
        updateTitle()
    }

    /**
     * The hook every document edit goes through: marks the document unsaved and schedules a
     * session checkpoint.
     */
    fun documentEdited() {
        when (val d = document ?: return) {
            is OpenDocument.Note -> d.value.dirty = true
            is OpenDocument.Canvas -> d.value.dirty = true
        }
        sessionPending = true
        updateTitle()
    }

    private fun selectTool(tool: Tool) {
        canvas.setTool(tool)
        toolButtons[tool]?.isSelected = true
    }

    private fun chooseColor() {
        val current = canvas.inkColor
        val picked = JColorChooser.showDialog(this, "Цвет чернил", Color(current.r, current.g, current.b)) ?: return
        canvas.inkColor = Rgba(picked.red, picked.green, picked.blue, 255)
        updateColorButton()
    }

    private fun updateColorButton() {
        val c = canvas.inkColor
        colorButton.foreground = Color(c.r, c.g, c.b)
        colorButton.text = "● Цвет"
    }

    private fun updateHistoryButtons() {
        val editing = document is OpenDocument.Note
        undoButton.isEnabled = editing && canvas.canUndo
        redoButton.isEnabled = editing && canvas.canRedo
    }

    private fun updateViewReadouts() {
        val note = document is OpenDocument.Note
        pageLabel.text = if (!note || canvas.pageCount == 0) "Страница 0/0"
            else "Страница ${canvas.currentPage() + 1}/${canvas.pageCount}"
        zoomLabel.text = if (note) "${canvas.zoomPercent}%" else "—"
    }

    private fun installShortcuts() {
        val menu = java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        fun bind(name: String, stroke: KeyStroke, action: () -> Unit) {
            rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stroke, name)
            rootPane.actionMap.put(name, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    if (document is OpenDocument.Note) action()
                }
            })
        }
        bind("undo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu)) { canvas.undo() }
        bind("redo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu or InputEvent.SHIFT_DOWN_MASK)) { canvas.redo() }
        bind("redo-y", KeyStroke.getKeyStroke(KeyEvent.VK_Y, menu)) { canvas.redo() }
        bind("save", KeyStroke.getKeyStroke(KeyEvent.VK_S, menu)) { save(false) }
        bind("select-all", KeyStroke.getKeyStroke(KeyEvent.VK_A, menu)) { selectTool(Tool.LASSO); canvas.selectAll() }
        bind("delete", KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0)) { canvas.deleteSelection() }
        bind("escape", KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)) { canvas.escape() }
        bind("zoom-in", KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, menu)) { canvas.zoomStep(true) }
        bind("zoom-out", KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, menu)) { canvas.zoomStep(false) }
        for ((index, entry) in TOOLS.withIndex()) {
            bind("tool-${entry.first.id}", KeyStroke.getKeyStroke(KeyEvent.VK_1 + index, 0)) { selectTool(entry.first) }
        }
    }

    private fun checkpointSession() {
        if (!sessionPending) return
        val d = document ?: return
        try {
            storage.saveSession(d, currentView())
            sessionPending = false
        } catch (e: Exception) {
            status.text = "Не удалось сохранить сессию: ${e.message}"
        }
    }

    /** Write the session now if it holds unsaved edits, including view changes since the last checkpoint. */
    private fun flushSession() {
        val d = document ?: return
        if (!d.dirty) return
        runCatching { storage.saveSession(d, currentView()) }
    }

    private fun currentView(): Map<String, Double> =
        if (document is OpenDocument.Note) canvas.viewState() else emptyMap()

    private fun updateTitle() {
        val d = document ?: return
        title = "${if (d.dirty) "• " else ""}${d.title} — xnotes"
    }

    /** True when there are no unsaved edits, or the user agreed to drop them. */
    private fun confirmDiscard(): Boolean {
        val d = document ?: return true
        if (!d.dirty) return true
        return JOptionPane.showConfirmDialog(this, "Отбросить несохранённые изменения в «${d.title}»?",
            "xnotes", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION
    }

    private fun navigate(delta: Int) {
        if (document !is OpenDocument.Note || canvas.pageCount == 0) return
        canvas.goToPage((canvas.currentPage() + delta).coerceIn(0, canvas.pageCount - 1))
    }

    private fun chooseOpen() {
        val chooser = JFileChooser().apply {
            fileFilter = FileNameExtensionFilter("xnotes (*.xnote, *.xcanvas)", "xnote", "xcanvas")
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) open(chooser.selectedFile)
    }

    private fun save(asNew: Boolean) {
        val current = document ?: return
        val existing = current.file
        val target = if (asNew || existing == null) {
            val extension = if (current is OpenDocument.Note) "xnote" else "xcanvas"
            val chooser = JFileChooser().apply {
                selectedFile = existing ?: File("${current.title}.$extension")
                fileFilter = FileNameExtensionFilter("xnotes (*.$extension)", extension)
            }
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
            val picked = chooser.selectedFile
            if (picked.extension.isEmpty()) File(picked.parentFile, "${picked.name}.$extension") else picked
        } else existing
        if (target.exists() && (asNew || target != existing)) {
            val choice = JOptionPane.showConfirmDialog(this, "Заменить ${target.name}?", "Сохранение", JOptionPane.YES_NO_OPTION)
            if (choice != JOptionPane.YES_OPTION) return
        }
        try {
            storage.save(current, target)
            document = when (current) {
                is OpenDocument.Note -> current.copy(file = target)
                is OpenDocument.Canvas -> current.copy(file = target)
            }
            // The file now holds everything: nothing is left to restore.
            sessionPending = false
            storage.clearSession()
            updateTitle()
            status.text = "Сохранено: ${target.absolutePath}"
            runCatching { preferences.remember(target) }
        } catch (e: Exception) {
            showError(e)
        }
    }

    private fun showError(error: Exception) {
        status.text = error.message ?: "Ошибка"
        JOptionPane.showMessageDialog(this, error.message ?: error.toString(), "xnotes", JOptionPane.ERROR_MESSAGE)
    }

    private companion object {
        const val SESSION_CHECKPOINT_MS = 3000

        /** The toolbar's tools, in order; keys 1..N select them. */
        val TOOLS = listOf(
            Tool.PAN to "Рука",
            Tool.PEN to "Перо",
            Tool.HIGHLIGHTER to "Маркер",
            Tool.ERASER to "Ластик",
            Tool.LASSO to "Лассо",
        )
    }
}

fun main(args: Array<String>) {
    SwingUtilities.invokeLater {
        DesktopWindow().apply {
            isVisible = true
            start(args.firstOrNull()?.let(::File))
        }
    }
}
