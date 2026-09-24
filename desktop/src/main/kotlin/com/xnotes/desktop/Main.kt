package com.xnotes.desktop

import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.filechooser.FileNameExtensionFilter

/** Minimal desktop host for reading and safely writing the shared file formats. */
private class DesktopWindow : JFrame("xnotes") {
    private val storage = DesktopStorage()
    private val preferences = DesktopPreferences()
    private var document: OpenDocument? = null
    private val details = JTextArea()
    private val pageView = PagedDocumentView()
    private val pageScroll = JScrollPane(pageView)
    private val cards = JPanel(CardLayout())
    private var currentPage = 0
    private val pageLabel = JLabel("Страница 0/0")
    private val zoomSlider = JSlider(15, 250, 65)
    private val status = JLabel("Откройте файл .xnote или .xcanvas")

    /** Edits made since the last session checkpoint; the timer writes them out. */
    private var sessionPending = false
    private val sessionTimer = Timer(SESSION_CHECKPOINT_MS) { checkpointSession() }

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        minimumSize = Dimension(600, 380)
        layout = BorderLayout(12, 12)
        details.isEditable = false
        details.lineWrap = true
        details.wrapStyleWord = true
        zoomSlider.preferredSize = Dimension(150, 24)
        zoomSlider.addChangeListener { pageView.setZoom(zoomSlider.value / 100.0) }
        val actions = JPanel(FlowLayout(FlowLayout.LEADING)).apply {
            add(JButton("Открыть…").apply { addActionListener { chooseOpen() } })
            add(JButton("Сохранить").apply { addActionListener { save(false) } })
            add(JButton("Сохранить как…").apply { addActionListener { save(true) } })
            add(JButton("←").apply { addActionListener { navigate(-1) } })
            add(pageLabel)
            add(JButton("→").apply { addActionListener { navigate(1) } })
            add(JLabel("Масштаб"))
            add(zoomSlider)
            add(JCheckBox("Тёмная бумага", true).apply {
                addActionListener { pageView.setDarkPaper(isSelected) }
            })
        }
        add(actions, BorderLayout.NORTH)
        cards.add(JScrollPane(details), "details")
        cards.add(pageScroll, "pages")
        add(cards, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        addWindowListener(object : WindowAdapter() {
            // Unsaved edits are kept as the session rather than asked about: the next launch
            // restores them, just as it would after a crash.
            override fun windowClosing(event: WindowEvent) = flushSession()
            override fun windowClosed(event: WindowEvent) {
                sessionTimer.stop()
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
        restoreView(restored.view)
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
        if (opened is OpenDocument.Note) {
            pageView.show(opened.value)
            currentPage = 0
            updatePageLabel()
            (cards.layout as CardLayout).show(cards, "pages")
            pageScroll.viewport.viewPosition = java.awt.Point(0, 0)
        } else {
            pageView.show(null)
            updatePageLabel()
            (cards.layout as CardLayout).show(cards, "details")
        }
        details.text = when (opened) {
            is OpenDocument.Note -> buildString {
                appendLine("Заметка: ${opened.title}")
                appendLine("Страниц: ${opened.value.pages.size}")
                appendLine("Элементов: ${opened.value.pages.sumOf { it.items.size }}")
                appendLine("Исходный PDF: ${if (opened.value.hasPdf) "встроен" else "нет"}")
            }
            is OpenDocument.Canvas -> buildString {
                appendLine("Холст: ${opened.title}")
                appendLine("Элементов: ${opened.value.itemCount}")
                appendLine("Закладок: ${opened.value.waypoints.size}")
            }
        }
        updateTitle()
    }

    /**
     * The hook every document edit goes through: marks the document unsaved and schedules a
     * session checkpoint. (The viewer has no editing tools yet; the editor will call this.)
     */
    fun documentEdited() {
        when (val d = document ?: return) {
            is OpenDocument.Note -> d.value.dirty = true
            is OpenDocument.Canvas -> d.value.dirty = true
        }
        sessionPending = true
        updateTitle()
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

    private fun currentView(): Map<String, Double> = mapOf(
        VIEW_ZOOM to pageView.zoom,
        VIEW_SCROLL_X to pageScroll.viewport.viewPosition.x.toDouble(),
        VIEW_SCROLL_Y to pageScroll.viewport.viewPosition.y.toDouble(),
    )

    private fun restoreView(view: Map<String, Double>) {
        view[VIEW_ZOOM]?.let { zoomSlider.value = (it * 100).toInt() }
        val x = view[VIEW_SCROLL_X]?.toInt() ?: 0
        val y = view[VIEW_SCROLL_Y]?.toInt() ?: 0
        // After the zoom's relayout, so the position lands in the resized view.
        SwingUtilities.invokeLater { pageScroll.viewport.viewPosition = java.awt.Point(x, y) }
    }

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
        if (document !is OpenDocument.Note || pageView.pageCount() == 0) return
        currentPage = (currentPage + delta).coerceIn(0, pageView.pageCount() - 1)
        updatePageLabel()
        pageScroll.viewport.viewPosition = java.awt.Point(0, pageView.pageTop(currentPage))
    }

    private fun updatePageLabel() {
        pageLabel.text = if (pageView.pageCount() == 0) "Страница 0/0"
            else "Страница ${currentPage + 1}/${pageView.pageCount()}"
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
        const val VIEW_ZOOM = "zoom"
        const val VIEW_SCROLL_X = "scrollX"
        const val VIEW_SCROLL_Y = "scrollY"
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
