package com.xnotes.desktop

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/** Minimal desktop host for reading and safely writing the shared file formats. */
private class DesktopWindow : JFrame("xnotes") {
    private val storage = DesktopStorage()
    private val preferences = DesktopPreferences()
    private var document: OpenDocument? = null
    private val details = JTextArea()
    private val status = JLabel("Откройте файл .xnote или .xcanvas")

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        minimumSize = Dimension(600, 380)
        layout = BorderLayout(12, 12)
        details.isEditable = false
        details.lineWrap = true
        details.wrapStyleWord = true
        val actions = JPanel(FlowLayout(FlowLayout.LEADING)).apply {
            add(JButton("Открыть…").apply { addActionListener { chooseOpen() } })
            add(JButton("Сохранить").apply { addActionListener { save(false) } })
            add(JButton("Сохранить как…").apply { addActionListener { save(true) } })
        }
        add(actions, BorderLayout.NORTH)
        add(JScrollPane(details), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(event: WindowEvent) = storage.close()
        })
        pack()
        setLocationRelativeTo(null)
    }

    fun open(file: File) {
        try {
            val opened = storage.open(file)
            document = opened
            details.text = when (opened) {
                is OpenDocument.Note -> buildString {
                    appendLine("Заметка: ${opened.file.name}")
                    appendLine("Страниц: ${opened.value.pages.size}")
                    appendLine("Элементов: ${opened.value.pages.sumOf { it.items.size }}")
                    appendLine("Исходный PDF: ${if (opened.value.hasPdf) "встроен" else "нет"}")
                }
                is OpenDocument.Canvas -> buildString {
                    appendLine("Холст: ${opened.file.name}")
                    appendLine("Элементов: ${opened.value.itemCount}")
                    appendLine("Закладок: ${opened.value.waypoints.size}")
                }
            }
            title = "${opened.file.name} — xnotes"
            status.text = opened.file.absolutePath
            runCatching { preferences.remember(opened.file) }
        } catch (e: Exception) {
            showError(e)
        }
    }

    private fun chooseOpen() {
        val chooser = JFileChooser().apply {
            fileFilter = FileNameExtensionFilter("xnotes (*.xnote, *.xcanvas)", "xnote", "xcanvas")
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) open(chooser.selectedFile)
    }

    private fun save(asNew: Boolean) {
        val current = document ?: return
        val target = if (asNew) {
            val extension = if (current is OpenDocument.Note) "xnote" else "xcanvas"
            val chooser = JFileChooser().apply {
                selectedFile = current.file
                fileFilter = FileNameExtensionFilter("xnotes (*.$extension)", extension)
            }
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
            val picked = chooser.selectedFile
            if (picked.extension.isEmpty()) File(picked.parentFile, "${picked.name}.$extension") else picked
        } else current.file
        if (target.exists() && (asNew || target != current.file)) {
            val choice = JOptionPane.showConfirmDialog(this, "Заменить ${target.name}?", "Сохранение", JOptionPane.YES_NO_OPTION)
            if (choice != JOptionPane.YES_OPTION) return
        }
        try {
            storage.save(current, target)
            document = when (current) {
                is OpenDocument.Note -> current.copy(file = target)
                is OpenDocument.Canvas -> current.copy(file = target)
            }
            title = "${target.name} — xnotes"
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
}

fun main(args: Array<String>) {
    SwingUtilities.invokeLater {
        DesktopWindow().apply {
            isVisible = true
            (args.firstOrNull()?.let(::File) ?: DesktopPreferences().lastFile())?.let(::open)
        }
    }
}
