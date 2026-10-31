@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.newoether.agora.data.local

import androidx.room.withTransaction
import com.newoether.agora.data.local.migration.installAttachmentReferenceTriggers
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.SelectedAttachment
import java.io.File
import java.io.InputStream
import kotlinx.serialization.json.decodeFromStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Maintenance-owned derived index. Triggers invalidate only changed owners in the source transaction.
 * Legacy identity seeding and parsed-reference commits are short; all payload reads/JSON are outside.
 */
internal class AttachmentReferenceIndex(private val database: ChatDatabase) {
    private val json = Json { ignoreUnknownKeys = true }
    private val db get() = database.openHelper.writableDatabase
    private var installed = false

    private data class Owner(val kind: Int, val id: String, val token: String)
    private data class Parsed(val paths: Set<String>, val invalid: Boolean)

    /** Resumes durable work; cancellation never publishes a partial owner or completed bootstrap. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (!installed) {
            database.withTransaction { installAttachmentReferenceTriggers(db) }
            installed = true
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            val seeded = database.withTransaction { seedPage() }
            val owners = db.query(
                "SELECT kind, ownerId, token FROM attachment_reference_owners WHERE dirty = 1 ORDER BY kind, ownerId LIMIT ?",
                arrayOf<Any>(PAGE_SIZE),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(Owner(cursor.getInt(0), cursor.getString(1), cursor.getString(2)))
                }
            }
            for (owner in owners) {
                currentCoroutineContext().ensureActive()
                val parsed = parse(owner)
                claim(owner)?.let { publish(it, parsed) }
                yield()
            }
            if (!seeded && firstDirty() == null) return@withContext
            yield()
        }
    }

    /** A fresh token fences competing rebuilds without a lock spanning payload reads or batches. */
    private suspend fun claim(owner: Owner): Owner? = database.withTransaction {
        db.execSQL(
            "UPDATE attachment_reference_owners SET token = lower(hex(randomblob(16))) WHERE kind = ? AND ownerId = ? AND token = ? AND dirty = 1",
            arrayOf<Any>(owner.kind, owner.id, owner.token),
        )
        val accepted = db.query("SELECT changes()").use { check(it.moveToFirst()); it.getInt(0) == 1 }
        if (accepted) owner.copy(token = checkNotNull(token(owner))) else null
    }

    /** Dirty stays set across bounded commits, so cancellation cannot expose a partial index. */
    private suspend fun publish(owner: Owner, parsed: Parsed) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val removed = database.withTransaction {
                if (token(owner) != owner.token) return@withTransaction -1
                db.execSQL(
                    "DELETE FROM attachment_references WHERE rowid IN (SELECT rowid FROM attachment_references WHERE kind = ? AND ownerId = ? LIMIT ?)",
                    arrayOf<Any>(owner.kind, owner.id, REFERENCE_PAGE_SIZE),
                )
                db.query("SELECT changes()").use { check(it.moveToFirst()); it.getInt(0) }
            }
            if (removed < 0) return
            if (removed < REFERENCE_PAGE_SIZE) break
            yield()
        }
        for (paths in parsed.paths.chunked(REFERENCE_PAGE_SIZE)) {
            currentCoroutineContext().ensureActive()
            val accepted = database.withTransaction {
                if (token(owner) != owner.token) return@withTransaction false
                paths.forEach { path ->
                    db.execSQL("INSERT OR IGNORE INTO attachment_references VALUES(?, ?, ?)", arrayOf<Any>(owner.kind, owner.id, path))
                }
                true
            }
            if (!accepted) return
            yield()
        }
        database.withTransaction {
            if (token(owner) == owner.token) {
                db.execSQL("UPDATE attachment_reference_owners SET dirty = 0, invalid = ? WHERE kind = ? AND ownerId = ?",
                    arrayOf<Any>(if (parsed.invalid) 1 else 0, owner.kind, owner.id))
            }
        }
    }

    /** Source revision fences malformed legacy checks against every concurrent reference mutation. */
    suspend fun prepareCheck(path: String): Long = withContext(Dispatchers.IO) {
        refresh()
        val revision = revision()
        val token = Regex("[A-Za-z0-9._-]+").findAll(File(path).name)
            .maxByOrNull { it.value.length }?.value.orEmpty()
        var kind = -1
        var id = ""
        while (true) {
            currentCoroutineContext().ensureActive()
            val owners = db.query(
                "SELECT kind, ownerId, token FROM attachment_reference_owners WHERE invalid = 1 AND (kind, ownerId) > (?, ?) ORDER BY kind, ownerId LIMIT ?",
                arrayOf<Any>(kind, id, PAGE_SIZE),
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(Owner(cursor.getInt(0), cursor.getString(1), cursor.getString(2))) }
            }
            if (owners.isEmpty()) break
            owners.forEach { parse(it, token) }
            kind = owners.last().kind
            id = owners.last().id
            yield()
        }
        revision
    }

    /** Must run in the same short write transaction as the final live-owner check and unlink. */
    fun isCurrent(expectedRevision: Long): Boolean =
        revision() == expectedRevision &&
            db.query("SELECT 1 FROM attachment_reference_state WHERE id = 0 AND phase = 3").use { it.moveToFirst() } &&
            firstDirty() == null

    fun contains(path: String): Boolean =
        db.query("SELECT 1 FROM attachment_references WHERE path = ? LIMIT 1", arrayOf<Any>(path)).use { it.moveToFirst() }

    private fun revision(): Long =
        db.query("SELECT revision FROM attachment_reference_state WHERE id = 0").use { check(it.moveToFirst()); it.getLong(0) }

    private fun token(owner: Owner): String? =
        db.query("SELECT token FROM attachment_reference_owners WHERE kind = ? AND ownerId = ?", arrayOf<Any>(owner.kind, owner.id))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    private fun firstDirty(): Owner? =
        db.query("SELECT kind, ownerId, token FROM attachment_reference_owners WHERE dirty = 1 ORDER BY kind, ownerId LIMIT 1")
            .use { if (it.moveToFirst()) Owner(it.getInt(0), it.getString(1), it.getString(2)) else null }

    private fun seedPage(): Boolean {
        val (phase, after) = db.query("SELECT phase, afterId FROM attachment_reference_state WHERE id = 0").use {
            check(it.moveToFirst())
            it.getInt(0) to if (it.isNull(1)) null else it.getString(1)
        }
        if (phase == TABLES.size) return false
        val table = TABLES[phase]
        val predicate = if (after == null) "" else "WHERE id > ?"
        val args: Array<Any> = if (after == null) arrayOf<Any>(PAGE_SIZE) else arrayOf<Any>(after, PAGE_SIZE)
        val ids = db.query("SELECT id FROM $table $predicate ORDER BY id LIMIT ?", args).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        ids.forEach { id ->
            db.execSQL("INSERT OR IGNORE INTO attachment_reference_owners VALUES(?, ?, lower(hex(randomblob(16))), 1, 0)", arrayOf<Any>(phase, id))
        }
        if (ids.size < PAGE_SIZE) {
            db.execSQL("UPDATE attachment_reference_state SET phase = ?, afterId = NULL WHERE id = 0", arrayOf<Any>(phase + 1))
        } else {
            db.execSQL("UPDATE attachment_reference_state SET afterId = ? WHERE id = 0", arrayOf<Any>(ids.last()))
        }
        return true
    }

    /** Retains the original exact-token fail-closed rule for malformed legacy JSON. */
    private suspend fun parse(owner: Owner, candidateToken: String? = null): Parsed {
        val context = currentCoroutineContext()
        val table = TABLES[owner.kind]
        val paths = linkedSetOf<String>()
        var invalid = false
        fun path(raw: String) {
            if (raw.isNotBlank()) paths += normalizePath(raw)
        }
        fun artifacts(values: List<ImageArtifact>) {
            values.filterNot { it.unavailable }.forEach { path(it.path) }
        }
        fun hasCandidate(column: String): Boolean = db.query(
            "SELECT 1 FROM $table WHERE id = ? AND instr($column, ?) > 0",
            arrayOf<Any>(owner.id, requireNotNull(candidateToken)),
        ).use { it.moveToFirst() }
        fun payload(column: String) = AttachmentReferencePayload(db, table, column, owner.id, context)
        fun decode(column: String, block: (InputStream) -> Unit) {
            // Invalid owners need inspection only when this exact field contains the original token.
            if (candidateToken != null && !hasCandidate(column)) return
            payload(column).buffered().use { input ->
                input.mark(1)
                if (input.read() == -1) return
                input.reset()
                try {
                    block(input)
                } catch (error: SerializationException) {
                    invalid = true
                    if (candidateToken != null) throw error
                } catch (error: IllegalArgumentException) {
                    invalid = true
                    if (candidateToken != null) throw error
                }
            }
        }
        if (owner.kind == 0) {
            if (candidateToken == null) {
                payload("images").reader(Charsets.UTF_8).use {
                    MessageConverters().toStringList(it.readText()).forEach(::path)
                }
            }
            decode("attachmentMeta") { input ->
                json.decodeFromStream<AttachmentMeta>(input).items.forEach { item ->
                    item.originalUri?.takeIf { it.startsWith("file://") }?.let(::path)
                    artifacts(item.imageArtifacts)
                }
            }
            decode("toolCallJson") { input ->
                json.decodeFromStream<List<MessageSegment>>(input).forEach { artifacts(it.toolImages) }
            }
        } else {
            decode("draftAttachments") { input ->
                json.decodeFromStream<List<SelectedAttachment>>(input).forEach { attachment ->
                    attachment.localPath?.let(::path)
                    attachment.processedFrames.orEmpty().forEach(::path)
                    attachment.preRenderedPaths.orEmpty().forEach(::path)
                    artifacts(attachment.imageArtifacts)
                }
            }
        }
        return Parsed(paths, invalid)
    }

    companion object {
        private const val PAGE_SIZE = 16
        private const val REFERENCE_PAGE_SIZE = 64
        private val TABLES = listOf("messages", "conversations", "new_chat_persist")
        fun normalizePath(path: String): String {
            val raw = path.removePrefix("file://")
            if (raw.isBlank()) return ""
            return runCatching { File(raw).canonicalPath }.getOrElse { File(raw).absolutePath }
        }
    }
}
