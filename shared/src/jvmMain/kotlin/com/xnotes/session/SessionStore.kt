package com.xnotes.session

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.format.JsonPull
import com.xnotes.format.JsonWrite
import com.xnotes.format.utf8Reader
import com.xnotes.format.utf8Writer
import java.io.File
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/** The document a working session holds: the two models share no shape, so the kind travels with it. */
sealed interface SessionDocument {
    val path: String?
    val displayName: String?
    val dirty: Boolean

    class Note(val document: Document) : SessionDocument {
        override val path get() = document.path
        override val displayName get() = document.displayName
        override val dirty get() = document.dirty
    }

    class Canvas(val document: InfiniteDocument) : SessionDocument {
        override val path get() = document.path
        override val displayName get() = document.displayName
        override val dirty get() = document.dirty
    }
}

/**
 * Persists the working session — the open document, including edits never written to its file,
 * plus host view state — so a crash or kill loses nothing and the next launch reopens it.
 *
 * The document is written in its normal bundle format under a fresh name, then `session.json`
 * (which names that file and carries the identity the codecs don't: path, display name, dirty
 * flag) is atomically replaced. That rename is the commit point: a crash at any moment leaves
 * either the previous session or the new one, never a new document under an old identity — which
 * would make the next save overwrite the wrong file. Files from superseded sessions are removed
 * after the commit, and any left behind by a crash are swept on the next save.
 *
 * View state is a flat map of numbers the host owns (zoom, scroll, page…), since each host's
 * view differs. Loading is best effort: a missing or unreadable session restores nothing.
 */
class SessionStore(
    private val dir: File,
    private val noteCodec: DocumentCodec,
    private val canvasCodec: CanvasCodec,
) {
    private val metaFile = File(dir, META)

    /** A restored session. Assets (images, the PDF) were extracted into the load's work dir. */
    class Snapshot(val document: SessionDocument, val view: Map<String, Double>)

    /**
     * Commit [document] and [view] as the current session. Pass [writeDocument] = false to refresh
     * only the view state when the content is unchanged since the last save; the document is
     * still written when the session has none yet.
     */
    fun save(document: SessionDocument, view: Map<String, Double> = emptyMap(), writeDocument: Boolean = true) {
        dir.mkdirs()
        val committed = readMeta()
        val reuse = committed?.file?.takeIf {
            !writeDocument && it.kind == kindOf(document) && File(dir, it.name).isFile
        }
        val docName = reuse?.name ?: "document-${UUID.randomUUID()}.${kindOf(document).extension}"
        if (reuse == null) {
            writeSynced(File(dir, docName)) { out ->
                when (document) {
                    is SessionDocument.Note -> noteCodec.write(document.document, out)
                    is SessionDocument.Canvas -> canvasCodec.write(document.document, out)
                }
            }
        }
        val meta = Meta(DocFile(kindOf(document), docName), document.path, document.displayName,
            document.dirty, view)
        writeSynced(metaFile) { out -> writeMeta(meta, out) }
        sweep(keep = docName)
    }

    /** Load the committed session, extracting its assets into [workDir]; null when there is none. */
    fun load(workDir: File): Snapshot? = runCatching {
        val meta = readMeta() ?: return null
        val file = File(dir, meta.file.name)
        if (!file.isFile) return null
        val document = file.inputStream().buffered().use { input ->
            when (meta.file.kind) {
                Kind.NOTE -> SessionDocument.Note(noteCodec.read(input, workDir, workDir).also {
                    it.path = meta.path
                    it.displayName = meta.displayName
                    it.dirty = meta.dirty
                })
                Kind.CANVAS -> SessionDocument.Canvas(canvasCodec.read(input, workDir).also {
                    it.path = meta.path
                    it.displayName = meta.displayName
                    it.dirty = meta.dirty
                })
            }
        }
        Snapshot(document, meta.view)
    }.getOrNull()

    /** Whether a committed session exists (without reading its document). */
    fun exists(): Boolean = readMeta()?.let { File(dir, it.file.name).isFile } == true

    /** Discard the session, so the next launch restores nothing. The metadata goes first. */
    fun clear() {
        metaFile.delete()
        sweep(keep = null)
    }

    private enum class Kind(val id: String, val extension: String) {
        NOTE("note", "xnote"),
        CANVAS("canvas", "xcanvas"),
    }

    private class DocFile(val kind: Kind, val name: String)

    private class Meta(
        val file: DocFile,
        val path: String?,
        val displayName: String?,
        val dirty: Boolean,
        val view: Map<String, Double>,
    )

    private fun kindOf(document: SessionDocument) = when (document) {
        is SessionDocument.Note -> Kind.NOTE
        is SessionDocument.Canvas -> Kind.CANVAS
    }

    /** Remove every document and temp file except [keep]. */
    private fun sweep(keep: String?) {
        dir.listFiles()?.forEach { f ->
            val stale = (f.name.startsWith("document-") && f.name != keep) || f.name.endsWith(TMP_SUFFIX)
            if (stale) f.delete()
        }
    }

    /** Write [target] through a temp file that is flushed to disk, then renamed over it. */
    private fun writeSynced(target: File, body: (OutputStream) -> Unit) {
        val tmp = File(dir, target.name + TMP_SUFFIX)
        try {
            Files.newOutputStream(tmp.toPath()).buffered().use(body)
            FileChannel.open(tmp.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete()
        }
    }

    private fun writeMeta(meta: Meta, out: OutputStream) {
        val writer = utf8Writer(out)
        JsonWrite(writer).apply {
            beginObject()
            name("version").value(1)
            name("kind").value(meta.file.kind.id)
            name("file").value(meta.file.name)
            meta.path?.let { name("path").value(it) }
            meta.displayName?.let { name("displayName").value(it) }
            name("dirty").value(meta.dirty)
            name("view").beginObject()
            for ((key, value) in meta.view) name(key).value(value)
            endObject()
            endObject()
        }
        writer.flush()
    }

    private fun readMeta(): Meta? = runCatching {
        if (!metaFile.isFile) return null
        metaFile.inputStream().buffered().use { input ->
            val json = JsonPull(utf8Reader(input))
            var kind: Kind? = null
            var file: String? = null
            var path: String? = null
            var displayName: String? = null
            var dirty = false
            val view = LinkedHashMap<String, Double>()
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "kind" -> json.nextString().let { id -> kind = Kind.entries.firstOrNull { it.id == id } }
                    "file" -> file = json.nextString()
                    "path" -> path = json.nextString()
                    "displayName" -> displayName = json.nextString()
                    "dirty" -> dirty = json.nextBoolean()
                    "view" -> {
                        json.beginObject()
                        while (json.hasNext()) {
                            val key = json.nextName()
                            if (json.peek() == JsonPull.Token.NUMBER) view[key] = json.nextDouble() else json.skipValue()
                        }
                        json.endObject()
                    }
                    else -> json.skipValue()
                }
            }
            json.endObject()
            val k = kind ?: return null
            // Only names this store generates, so a tampered file can't point outside the dir.
            val name = file?.takeIf { it.startsWith("document-") && '/' !in it && '\\' !in it } ?: return null
            Meta(DocFile(k, name), path, displayName, dirty, view)
        }
    }.getOrNull()

    private companion object {
        const val META = "session.json"
        const val TMP_SUFFIX = ".tmp"
    }
}
