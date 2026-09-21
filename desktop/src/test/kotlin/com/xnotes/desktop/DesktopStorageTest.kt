package com.xnotes.desktop

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopStorageTest {
    @Test fun noteCanBeSavedOpenedAndCopied() {
        val dir = Files.createTempDirectory("xnotes-desktop-test-").toFile()
        try {
            DesktopStorage(DesktopPaths(dir)).use { storage ->
                val first = dir.resolve("first.xnote")
                storage.save(OpenDocument.Note(first, Document.blank(count = 2)), first)
                val loaded = storage.open(first) as OpenDocument.Note
                assertEquals(2, loaded.value.pages.size)
                val copy = dir.resolve("copy.xnote")
                storage.save(loaded, copy)
                assertTrue(copy.isFile)
                assertEquals(2, (storage.open(copy) as OpenDocument.Note).value.pages.size)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun canvasCanBeSavedOpenedAndCopied() {
        val dir = Files.createTempDirectory("xnotes-desktop-test-").toFile()
        try {
            DesktopStorage(DesktopPaths(dir)).use { storage ->
                val first = dir.resolve("first.xcanvas")
                storage.save(OpenDocument.Canvas(first, InfiniteDocument()), first)
                val loaded = storage.open(first) as OpenDocument.Canvas
                assertEquals(0, loaded.value.itemCount)
                val copy = dir.resolve("copy.xcanvas")
                storage.save(loaded, copy)
                assertTrue(copy.isFile)
                assertEquals(0, (storage.open(copy) as OpenDocument.Canvas).value.itemCount)
            }
        } finally { dir.deleteRecursively() }
    }
}
