package com.xnotes.session

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.format.JsonPull
import com.xnotes.format.JsonWrite
import com.xnotes.core.platform.File
import com.xnotes.core.platform.OutputStream
import com.xnotes.core.platform.asInputStream
import com.xnotes.core.platform.asOutputStream
import com.xnotes.core.platform.asPath
import com.xnotes.format.Utf8CharSource
import com.xnotes.format.utf8Writer
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.buffer
import okio.use
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
    private val dirPath: Path = dir.asPath()
    private val fs = FileSystem.SYSTEM
    private val metaFile: Path = dirPath / META

    /** A restored session. Assets (images, the PDF) were extracted into the load's work dir. */
    class Snapshot(val document: SessionDocument, val view: Map<String, Double>)

    /**
     * Commit [document] and [view] as the current session. Pass [writeDocument] = false to refresh
     * only the view state when the content is unchanged since the last save; the document is
     * still written when the session has none yet.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun save(document: SessionDocument, view: Map<String, Double> = emptyMap(), writeDocument: Boolean = true) {
        fs.createDirectories(dirPath)
        val committed = readMeta()
        val reuse = committed?.file?.takeIf {
            !writeDocument && it.kind == kindOf(document) && isFile(dirPath / it.name)
        }
        val docName = reuse?.name ?: "document-${Uuid.random()}.${kindOf(document).extension}"
        if (reuse == null) {
            writeSynced(dirPath / docName) { out ->
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
        val file = dirPath / meta.file.name
        if (!isFile(file)) return null
        val document = fs.source(file).use { source ->
            val input = source.asInputStream()
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
    fun exists(): Boolean = readMeta()?.let { isFile(dirPath / it.file.name) } == true

    /** Discard the session, so the next launch restores nothing. The metadata goes first. */
    fun clear() {
        delete(metaFile)
        sweep(keep = null)
    }

    private fun isFile(path: Path): Boolean = fs.metadataOrNull(path)?.isRegularFile == true

    /** Delete [path] if present, never throwing. */
    private fun delete(path: Path) {
        runCatching { fs.delete(path) }
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
        fs.listOrNull(dirPath)?.forEach { f ->
            val stale = (f.name.startsWith("document-") && f.name != keep) || f.name.endsWith(TMP_SUFFIX)
            if (stale) delete(f)
        }
    }

    /** Write [target] through a temp file that is flushed to disk, then renamed over it. */
    private fun writeSynced(target: Path, body: (OutputStream) -> Unit) {
        val tmp = dirPath / (target.name + TMP_SUFFIX)
        try {
            fs.openReadWrite(tmp, mustCreate = false, mustExist = false).use { handle ->
                handle.resize(0L)
                val sink = handle.sink()
                try {
                    body(sink.asOutputStream())
                } finally {
                    sink.close()
                }
                handle.flush() // fsync: the bytes are on disk before the rename can publish them
            }
            try {
                fs.atomicMove(tmp, target)
            } catch (_: IOException) {
                // A file system without atomic rename: replace by copying, as a plain move would.
                fs.copy(tmp, target)
            }
        } finally {
            delete(tmp)
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
        if (!isFile(metaFile)) return null
        fs.source(metaFile).use { source ->
            val json = JsonPull(Utf8CharSource(source.buffer()))
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
