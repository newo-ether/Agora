package com.newoether.agora.data.local

import android.app.Application
import android.os.Looper
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ChatDatabaseDispatchTest {
    @Test
    fun suspendDaoDispatchesBeforeAcquiringHelperOnMainThread() = runBlocking {
        assertSame(Looper.getMainLooper(), Looper.myLooper())
        val rejectMainThreadOpen = AtomicBoolean(false)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .openHelperFactory { configuration ->
                val delegate = FrameworkSQLiteOpenHelperFactory().create(configuration)
                object : SupportSQLiteOpenHelper by delegate {
                    override val writableDatabase: SupportSQLiteDatabase
                        get() {
                            check(!rejectMainThreadOpen.get() || Looper.myLooper() != Looper.getMainLooper()) {
                                "Suspend DAO attempted to acquire the database helper on the main thread"
                            }
                            return delegate.writableDatabase
                        }
                }
            }.build()
        try {
            withContext(Dispatchers.IO) { database.openHelper.writableDatabase }
            rejectMainThreadOpen.set(true)
            val dao = database.chatDao()
            assertThrows(IllegalStateException::class.java) {
                database.query("SELECT 1", emptyArray())
            }
            dao.upsertConversation(ChatEntity("target", "before", lastUpdated = 1))
            assertEquals("before", dao.getConversation("target")?.title)
            try {
                database.withTransaction {
                    assertTrue(database.inTransaction())
                    dao.upsertConversation(ChatEntity("target", "rolled back", lastUpdated = 2))
                    error("rollback")
                }
            } catch (error: IllegalStateException) {
                assertEquals("rollback", error.message)
            }
            assertEquals("before", dao.getConversation("target")?.title)
            withContext(Dispatchers.IO) { assertFalse(database.inTransaction()) }
        } finally {
            withContext(Dispatchers.IO) { database.close() }
        }
    }

    @Test
    fun explicitlyAllowedMainThreadTransactionRetainsItsState() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            database.beginTransaction()
            try {
                assertTrue(database.inTransaction())
            } finally {
                database.endTransaction()
            }
            assertFalse(database.inTransaction())
        } finally {
            database.close()
        }
    }
}
