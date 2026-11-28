package com.newoether.agora.data

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AutoBackupTargetTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `filesystem target lists only backup artifacts newest first`() {
        val directory = temporary.newFolder("backup")
        File(directory, "Agora_backup_2026-01-02_03-04-05.agora").writeText("old")
        File(directory, "Agora_backup_2026-02-02_03-04-05.agora").writeText("new")
        File(directory, "Agora_backup_2026-03-02_03-04-05.agora.tmp").writeText("partial")
        File(directory, "notes.txt").writeText("unrelated")

        val target = FileBackupTarget(directory)

        assertEquals(
            listOf(
                "Agora_backup_2026-03-02_03-04-05.agora.tmp",
                "Agora_backup_2026-02-02_03-04-05.agora",
                "Agora_backup_2026-01-02_03-04-05.agora",
            ),
            target.entries().map { entry -> entry.name },
        )
        // The newest complete archive is the incremental baseline; a temporary never is.
        assertEquals("Agora_backup_2026-02-02_03-04-05.agora", target.baseline()?.name)
    }

    @Test
    fun `filesystem target publishes through a temporary name`() = runTest {
        val directory = temporary.newFolder("backup")
        val target = FileBackupTarget(directory)
        val writtenTo = mutableListOf<String>()

        val written = target.write("Agora_backup_2026-04-01_00-00-00.agora") { uri ->
            val path = checkNotNull(uri.path)
            writtenTo += File(path).name
            File(path).writeText("payload")
        }

        assertTrue(written)
        assertEquals(listOf("Agora_backup_2026-04-01_00-00-00.agora.tmp"), writtenTo)
        assertEquals(
            "payload",
            File(directory, "Agora_backup_2026-04-01_00-00-00.agora").readText(),
        )
        assertEquals(1, directory.listFiles().orEmpty().size)
    }

    @Test
    fun `filesystem target removes the temporary archive when the export fails`() = runTest {
        val directory = temporary.newFolder("backup")
        val target = FileBackupTarget(directory)

        val failure = runCatching {
            target.write("Agora_backup_2026-04-01_00-00-00.agora") { uri ->
                File(checkNotNull(uri.path)).writeText("partial")
                throw IOException("disk full")
            }
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(0, directory.listFiles().orEmpty().size)
    }

    @Test
    fun `saf target lists backup documents inside the picked tree`() {
        val provider = Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, AUTHORITY)
        provider.documents += FakeDocumentsProvider.Document(
            id = "doc_old",
            name = "Agora_backup_2026-01-01_00-00-00.agora",
            lastModified = 10L,
        )
        provider.documents += FakeDocumentsProvider.Document(
            id = "doc_new",
            name = "Agora_backup_2026-02-01_00-00-00.agora",
            lastModified = 20L,
        )
        provider.documents += FakeDocumentsProvider.Document("doc_note", "notes.txt", 30L)

        val target = SafBackupTarget(RuntimeEnvironment.getApplication(), treeUri())
        val entries = target.entries()

        assertEquals(
            listOf(
                "Agora_backup_2026-02-01_00-00-00.agora",
                "Agora_backup_2026-01-01_00-00-00.agora",
            ),
            entries.map { entry -> entry.name },
        )
        assertEquals(20L, entries.first().lastModified)
        assertEquals("content://$AUTHORITY/tree/root/document/doc_new", entries.first().uri.toString())
    }

    @Test
    fun `saf target creates the archive inside the picked tree`() = runTest {
        val provider = Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, AUTHORITY)
        val target = SafBackupTarget(RuntimeEnvironment.getApplication(), treeUri())
        var created: Uri? = null

        val written = target.write("Agora_backup_2026-03-01_00-00-00.agora") { uri ->
            created = uri
        }

        assertTrue(written)
        assertEquals("content://$AUTHORITY/tree/root/document/created", created.toString())
        assertEquals(
            listOf("Agora_backup_2026-03-01_00-00-00.agora"),
            provider.documents.map { document -> document.name },
        )
    }

    @Test
    fun `saf target deletes the partial document when the export fails`() = runTest {
        val provider = Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, AUTHORITY)
        val target = SafBackupTarget(RuntimeEnvironment.getApplication(), treeUri())

        val failure = runCatching {
            target.write("Agora_backup_2026-03-01_00-00-00.agora") { throw IOException("boom") }
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(
            listOf("content://$AUTHORITY/tree/root/document/created"),
            provider.deleted,
        )
    }

    private fun treeUri(): Uri = Uri.parse("content://$AUTHORITY/tree/root")

    private companion object {
        const val AUTHORITY = "com.newoether.agora.test.documents"
    }
}

/** Minimal SAF provider: enough of the [DocumentsContract] protocol to drive [SafBackupTarget]. */
class FakeDocumentsProvider : ContentProvider() {
    data class Document(val id: String, val name: String, val lastModified: Long?)

    val documents = mutableListOf<Document>()
    val deleted = mutableListOf<String>()

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
        )
        documents.forEach { document ->
            cursor.addRow(arrayOf<Any?>(document.id, document.name, document.lastModified))
        }
        return cursor
    }

    /**
     * [ContentProvider.call] receives no Uri of its own: the framework passes the target document
     * in the extras, exactly as a real provider reads it.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val target = extras?.getParcelable<Uri>(EXTRA_URI)
        if (method == METHOD_DELETE_DOCUMENT) {
            target?.let { uri -> deleted += uri.toString() }
            return Bundle().apply { putBoolean(EXTRA_RESULT, true) }
        }
        if (method != METHOD_CREATE_DOCUMENT) return Bundle()
        documents += Document(
            id = CREATED_ID,
            name = extras?.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME).orEmpty(),
            lastModified = 1L,
        )
        return Bundle().apply {
            putParcelable(EXTRA_URI, Uri.parse("content://$AUTHORITY/tree/root/document/$CREATED_ID"))
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        deleted += uri.toString()
        return 1
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun getType(uri: Uri): String? = null

    private companion object {
        const val AUTHORITY = "com.newoether.agora.test.documents"
        const val CREATED_ID = "created"
        // @hide framework constants; their values are part of the DocumentsProvider protocol.
        const val METHOD_CREATE_DOCUMENT = "android:createDocument"
        const val METHOD_DELETE_DOCUMENT = "android:deleteDocument"
        const val EXTRA_URI = "uri"
        const val EXTRA_RESULT = "result"
    }
}
