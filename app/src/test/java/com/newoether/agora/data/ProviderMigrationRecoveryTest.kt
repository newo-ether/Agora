package com.newoether.agora.data

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.*
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProviderMigrationRecoveryTest {
    @Test fun cancelledPagesResumeFromBothStoresAndReusedDisplayNameCannotCaptureOldMessages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase(ChatDatabase.DB_NAME)
        val preferenceFile = context.preferencesDataStoreFile("provider-recovery")
        preferenceFile.delete()
        var storeJob = SupervisorJob()
        fun dataStore() = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(storeJob + Dispatchers.IO), produceFile = { preferenceFile },
        )
        var preferences = dataStore()
        val json = Json { ignoreUnknownKeys = true }
        var settings = SettingsModelPreferenceStore(preferences, json)
        val legacy = "旧供应商😀"
        preferences.edit { it[CUSTOM_PROVIDERS_JSON] = json.encodeToString(listOf(CustomProviderConfig(legacy))) }
        val migration = settings.normalizeCustomProviderIdentities().single()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean()
        val once = AtomicBoolean()
        var db = ChatDatabase.build(context, queryCallback = { sql, _ ->
            if (armed.get() && sql.trimStart().startsWith("SELECT id, conversationId FROM messages") &&
                once.compareAndSet(false, true)) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        })
        try {
            var dao = db.chatDao()
            dao.upsertConversation(ChatEntity("owner", "Owner", modelId = "$legacy:model"))
            dao.upsertRun(RunEntity("run", "owner", parentRunId = null, status = RunStatus.ACTIVE,
                activeSlot = 1, startedAt = 1, lastCheckpointAt = 1))
            fun message(id: String, model: String) = MessageEntity(id, "owner", text = "retained",
                participant = Participant.USER, timestamp = 1, runId = "run", modelName = model)
            repeat(130) { dao.insertMessage(message("m-$it", "$legacy:model:$it")) }
            dao.insertMessage(message("neighbor", "${legacy}x:model"))
            dao.upsertNewChatPersist(NewChatPersistEntity(modelId = "$legacy:draft"))
            armed.set(true)
            val migrating = async(Dispatchers.IO) {
                dao.renameConfiguredProviderModelReferences(legacy, migration.providerId)
            }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                assertEquals("${migration.providerId}:model", dao.getConversation("owner")?.modelId)
                val pendingConfig = settings.customProviders.first()
                assertEquals("${migration.providerId}:model:0", canonicalCustomModelId("$legacy:model:0", pendingConfig))
                migrating.cancel()
            } finally { release.countDown() }
            migrating.cancelAndJoin()
            assertTrue(settings.customProviders.first().single().legacyNames.contains(legacy))
            db.close()
            storeJob.cancelAndJoin()
            storeJob = SupervisorJob()
            preferences = dataStore()
            settings = SettingsModelPreferenceStore(preferences, json)
            db = ChatDatabase.build(context)
            dao = db.chatDao()
            assertEquals(listOf(migration), settings.normalizeCustomProviderIdentities())
            // A source written with the legacy reference before resumed migration must also migrate.
            dao.insertMessage(message("late", "$legacy:late"))
            dao.renameConfiguredProviderModelReferences(legacy, migration.providerId)
            settings.clearLegacyCustomProviderNames(listOf(migration))
            assertTrue(dao.getProviderMessageReferences(legacy, 1).isEmpty())
            assertEquals(131, dao.getProviderMessageReferences(migration.providerId, 200).size)
            assertEquals("${legacy}x:model", dao.getMessage("neighbor")?.modelName)
            assertEquals("${migration.providerId}:draft", dao.getNewChatPersist()?.modelId)
            assertTrue(settings.customProviders.first().single().legacyNames.isEmpty())

            val first = settings.customProviders.first().single().copy(name = "Renamed")
            val second = CustomProviderConfig(legacy, id = CustomProviderIdentityPolicy.newId())
            settings.saveCustomProviders(listOf(first, second))
            assertTrue(settings.normalizeCustomProviderIdentities().isEmpty())
            val configs = settings.customProviders.first()
            assertEquals("${second.id}:new", canonicalCustomModelId("$legacy:new", configs))
            assertEquals("${first.id}:model:0", dao.getMessage("m-0")?.modelName)
            assertEquals("${first.id}:model:0", canonicalCustomModelId(dao.getMessage("m-0")!!.modelName!!, configs))
        } finally {
            release.countDown()
            db.close()
            storeJob.cancelAndJoin()
            context.deleteDatabase(ChatDatabase.DB_NAME)
            preferenceFile.delete()
        }
    }
}
