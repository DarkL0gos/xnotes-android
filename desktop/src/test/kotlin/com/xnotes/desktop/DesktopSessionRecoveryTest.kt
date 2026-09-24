package com.xnotes.desktop

import com.xnotes.core.model.Document
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Recovery of an unsaved session: edit, get killed, start again, find the edits. The kill is a
 * real SIGKILL of a separate JVM, so no shutdown hook or finally block gets to help.
 */
class DesktopSessionRecoveryTest {
    private val root = Files.createTempDirectory("xnotes-session-recovery-").toFile()
    private val note = root.resolve("note.xnote")

    @After fun cleanup() {
        root.deleteRecursively()
    }

    private fun writeNote(strokes: Int) {
        DesktopStorage(DesktopPaths(root)).use { storage ->
            val doc = Document.blank(count = 2).apply { repeat(strokes) { pages[0].items.add(stroke()) } }
            storage.save(OpenDocument.Note(note, doc), note)
        }
    }

    @Test fun editsSurviveAKilledProcess() {
        writeNote(strokes = 1)
        val original = note.readBytes()

        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
            EditThenHang::class.java.name, root.path, note.path)
            .redirectErrorStream(true)
            .start()
        try {
            val line = process.inputStream.bufferedReader().readLine()
            assertEquals("checkpointed", line)
            process.destroyForcibly() // SIGKILL on Linux
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        } finally {
            process.destroyForcibly()
        }

        DesktopStorage(DesktopPaths(root)).use { storage ->
            val restored = storage.restoreSession()
            assertNotNull("the session should survive the kill", restored)
            val doc = restored!!.document as OpenDocument.Note
            assertEquals(note.absoluteFile, doc.file!!.absoluteFile)
            assertTrue(doc.dirty)
            assertEquals(3, doc.value.pages[0].items.size)
            assertEquals(1.5, restored.view["zoom"]!!, 0.0)
        }
        assertArrayEquals("the note's own file is untouched until Save", original, note.readBytes())
    }

    @Test fun savingTheDocumentLeavesNothingToRestore() {
        writeNote(strokes = 1)
        DesktopStorage(DesktopPaths(root)).use { storage ->
            val doc = storage.open(note) as OpenDocument.Note
            doc.value.pages[0].items.add(stroke())
            doc.value.dirty = true
            storage.saveSession(doc, emptyMap())
            storage.save(doc, note)
            storage.clearSession()
        }
        DesktopStorage(DesktopPaths(root)).use { assertNull(it.restoreSession()) }
    }

    @Test fun aSessionWithoutEditsIsNotRestored() {
        writeNote(strokes = 1)
        DesktopStorage(DesktopPaths(root)).use { storage ->
            storage.saveSession(storage.open(note), emptyMap())
        }
        DesktopStorage(DesktopPaths(root)).use { assertNull(it.restoreSession()) }
    }

    @Test fun anUntitledDocumentIsRestoredWithoutAFile() {
        DesktopStorage(DesktopPaths(root)).use { storage ->
            val doc = Document.blank(count = 1).apply { dirty = true }
            storage.saveSession(OpenDocument.Note(null, doc), emptyMap())
        }
        DesktopStorage(DesktopPaths(root)).use { storage ->
            val restored = storage.restoreSession()!!.document
            assertNull(restored.file)
            assertEquals("Без имени", restored.title)
        }
    }
}

private fun stroke() =
    Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(10.0, 10.0, 1.0), Sample(40.0, 30.0, 1.0)))

/** Child process for [DesktopSessionRecoveryTest]: opens the note, edits it, checkpoints, then waits to be killed. */
object EditThenHang {
    @JvmStatic fun main(args: Array<String>) {
        val storage = DesktopStorage(DesktopPaths(File(args[0])))
        val doc = storage.open(File(args[1])) as OpenDocument.Note
        repeat(2) { doc.value.pages[0].items.add(stroke()) }
        doc.value.dirty = true
        storage.saveSession(doc, mapOf("zoom" to 1.5))
        println("checkpointed")
        System.out.flush()
        Thread.sleep(60_000)
    }
}
