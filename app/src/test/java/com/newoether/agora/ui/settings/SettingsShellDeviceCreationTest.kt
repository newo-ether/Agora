package com.newoether.agora.ui.settings

import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.core.app.ActivityOptionsCompat
import android.content.Intent
import android.net.Uri
import android.app.Activity
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import com.newoether.agora.data.SSH_AUTH_PRIVATE_KEY
import com.newoether.agora.viewmodel.HostKeyCaptureSession
import com.newoether.agora.viewmodel.SshHostKeyVerifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.sandbox.SandboxMirror
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.viewmodel.ChatViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h900dp-mdpi")
class SettingsShellDeviceCreationTest {
    @get:Rule val compose = createComposeRule()
    private val settings = mockk<SettingsRepository>()
    private val viewModel = mockk<ChatViewModel>()
    private val devices = MutableStateFlow<List<ShellDeviceConfig>>(emptyList())
    private val additions = mutableListOf<ShellDeviceConfig>()
    private val updates = mutableListOf<ShellDeviceConfig>()
    private val results = mutableListOf<(Boolean) -> Unit>()
    private val errors = mutableListOf<(Exception) -> Unit>()
    private var category by mutableStateOf("shell")
    private var shellCompositions = 0
    private var pickerRequestCode: Int? = null
    private val pickerRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            pickerRequestCode = requestCode
        }
    }
    private val retention = SettingsScrollRetention()
    private var pageVisible by mutableStateOf(true)
    private var backCalls = 0
    private lateinit var strings: Map<Int, String>

    private fun showPage(existing: List<ShellDeviceConfig> = emptyList(), reducedMotion: Boolean = true, categoryHost: Boolean = false) {
        devices.value = existing
        every { viewModel.settings } returns settings
        every { viewModel.isSandboxFlavor } returns false
        every { settings.shellEnabled } returns MutableStateFlow(true)
        every { settings.shellConfirmEnabled } returns MutableStateFlow(true)
        every { settings.shellDevices } returns devices
        every { settings.sandboxEnabled } returns MutableStateFlow(false)
        every { settings.sandboxSharedStorageEnabled } returns MutableStateFlow(false)
        every { settings.sandboxMirror } returns MutableStateFlow(SandboxMirror.DEFAULT)
        every { settings.showDocumentationFab } returns MutableStateFlow(false)
        every { settings.addShellDevice(any(), any(), any()) } answers {
            additions += firstArg<ShellDeviceConfig>()
            results += secondArg<(Boolean) -> Unit>()
            errors += thirdArg<(Exception) -> Unit>()
            Job().apply { complete() }
        }
        every { settings.updateShellDevice(any(), any(), any()) } answers {
            updates += firstArg<ShellDeviceConfig>()
            results += secondArg<(Boolean) -> Unit>()
            errors += thirdArg<(Exception) -> Unit>()
            Job().apply { complete() }
        }
        every { viewModel.emitSnackbar(any(), any(), any()) } returns Unit
        compose.setContent {
            val context = LocalContext.current
            strings = listOf(
                R.string.shell_remove_device,
                R.string.shell_device_host, R.string.shell_device_port, R.string.shell_device_user,
                R.string.shell_add_device, R.string.shell_device_name, R.string.shell_device_desc,
                R.string.shell_device_url, R.string.shell_device_key, R.string.save, R.string.back,
                R.string.custom_provider_name_error, R.string.unsaved_changes_discard,
                R.string.unsaved_changes_keep_editing, R.string.edit, R.string.img_save_failed,
                R.string.shell_ssh_verify_host_key, R.string.shell_ssh_verify_title,
                R.string.shell_ssh_import_private_key, R.string.shell_ssh_private_key_read_failed,
            ).associateWith { context.getString(it) }
            CompositionLocalProvider(
                LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = reducedMotion),
                LocalSettingsScrollRetention provides retention,
                LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                    override val activityResultRegistry = pickerRegistry
                },
            ) {
                MaterialTheme {
                    Box(Modifier.size(400.dp, 800.dp)) {
                        if (pageVisible) {
                            if (categoryHost) {
                                Crossfade(category, animationSpec = tween(250), label = "test-settings-category") { destination ->
                                    CompositionLocalProvider(
                                        LocalSettingsScrollRetentionActive provides (destination == category),
                                    ) {
                                        if (destination == "shell") {
                                            DisposableEffect(Unit) {
                                                shellCompositions++
                                                onDispose { shellCompositions-- }
                                            }
                                            SettingsShellPage(viewModel) { backCalls++ }
                                        } else Text("Other category")
                                    }
                                }
                            } else SettingsShellPage(viewModel) {
                                backCalls++
                                pageVisible = false
                            }
                        }
                    }
                }
            }
        }
    }

    private fun button(label: Int) = compose.onNode(hasText(strings.getValue(label)) and hasClickAction())
    private fun fieldTag(label: Int): String = when (label) {
        R.string.shell_device_name -> "shell-field-name"
        R.string.shell_device_desc -> "shell-field-description"
        R.string.shell_device_url -> "shell-field-url"
        R.string.shell_device_key -> "shell-field-key"
        R.string.shell_device_host -> "shell-field-host"
        R.string.shell_device_port -> "shell-field-port"
        R.string.shell_device_user -> "shell-field-user"
        else -> error("Unknown Shell field")
    }
    private fun field(label: Int) = compose.onNode(
        hasSetTextAction() and hasAnyAncestor(hasTestTag(fieldTag(label))),
    )
    private fun save() = compose.onNodeWithContentDescription(strings.getValue(R.string.save))
    private fun add() = button(R.string.shell_add_device)
    private fun nameError() = compose.onNodeWithText(strings.getValue(R.string.custom_provider_name_error))
    private fun back() = compose.onNodeWithContentDescription(strings.getValue(R.string.back))
    private fun open(name: String) {
        val id = devices.value.single { it.name == name }.id
        compose.onNodeWithTag("shell-device-menu:$id").performScrollTo().performClick()
        button(R.string.edit).performClick()
        compose.waitForIdle()
        field(R.string.shell_device_name).assertExists()
        compose.onNodeWithContentDescription(strings.getValue(R.string.shell_remove_device)).assertDoesNotExist()
    }
    private fun addDraft() {
        add().performScrollTo().performClick()
        compose.waitForIdle()
        field(R.string.shell_device_name).assertExists()
        compose.onNodeWithContentDescription(strings.getValue(R.string.shell_remove_device)).assertDoesNotExist()
        add().assertDoesNotExist()
    }
    private fun replace(label: Int, value: String) {
        field(label).performScrollTo().performTextReplacement(value)
    }
    private fun assertValue(label: Int, value: String) {
        field(label).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
    }
    private fun assertNoPersistence() {
        verify(exactly = 0) { settings.addShellDevice(any(), any(), any()) }
        verify(exactly = 0) { settings.updateShellDevice(any(), any(), any()) }
    }
    private fun discard() {
        back().performClick()
        button(R.string.unsaved_changes_discard).performClick()
        compose.waitForIdle()
    }

    @Test
    fun addIsOnlyALocalDraftAndBackRequiresDiscardWithoutSaving() {
        showPage()
        addDraft()
        listOf(R.string.shell_device_name, R.string.shell_device_desc, R.string.shell_device_url,
            R.string.shell_device_key).forEach { assertValue(it, "") }
        save().assertIsNotEnabled()
        assertNoPersistence()
        replace(R.string.shell_device_url, " https://draft.example.test:14216 ")
        replace(R.string.shell_device_key, " token ")
        replace(R.string.shell_device_name, " Draft host ")
        save().assertIsEnabled()
        back().performClick()
        button(R.string.unsaved_changes_keep_editing).performClick()
        assertValue(R.string.shell_device_name, " Draft host ")
        discard()
        field(R.string.shell_device_name).assertDoesNotExist()
        add().assertIsEnabled()
        assertNoPersistence()
        compose.runOnIdle {
            assertEquals(0, backCalls)
            assertTrue(devices.value.isEmpty())
        }
    }

    @Test
    fun untouchedNewDeviceReturnsWithoutAFalseUnsavedUsernameChange() {
        showPage()
        addDraft()
        back().performClick()
        compose.waitForIdle()
        button(R.string.unsaved_changes_discard).assertDoesNotExist()
        add().assertIsDisplayed()
        assertNoPersistence()
    }
    @Test
    fun newSshUsernameStaysEmptyWhileVerifyAndSaveUseTheRootDefault() {
        val probes = mutableListOf<ShellDeviceConfig>()
        every { viewModel.sshHostKeyVerifier } returns SshHostKeyVerifier(
            createSession = { request ->
                probes += request
                HostKeyCaptureSession(probe = {}, capturedHostKey = { null }, close = {})
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        showPage()
        addDraft()
        compose.onNodeWithText("SSH").performScrollTo().performClick()
        field(R.string.shell_device_user).performScrollTo()
        assertValue(R.string.shell_device_user, "")
        compose.onNode(hasText("root") and hasAnyAncestor(hasTestTag("shell-field-user")),
            useUnmergedTree = true).assertIsDisplayed()
        replace(R.string.shell_device_name, "New SSH")
        replace(R.string.shell_device_host, "new.example.test")
        button(R.string.shell_ssh_verify_host_key).performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("root", probes.single().sshUser) }
        field(R.string.shell_device_user).performScrollTo()
        assertValue(R.string.shell_device_user, "")
        assertNoPersistence()
        save().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("root", additions.single().sshUser) }
        assertValue(R.string.shell_device_user, "")
    }
    @Test
    fun savedRootAndExplicitUsernameRemainValuesAndClearingUsesTheDefaultOnlyAtSave() {
        val root = ShellDeviceConfig(id = "root", name = "Root Host", type = "ssh", sshHost = "root.example.test")
        val builder = root.copy(id = "builder", name = "Builder Host", sshUser = "builder")
        showPage(listOf(root, builder))
        open(root.name)
        field(R.string.shell_device_user).performScrollTo()
        assertValue(R.string.shell_device_user, "root")
        back().performClick()
        compose.waitForIdle()
        button(R.string.unsaved_changes_discard).assertDoesNotExist()
        open(builder.name)
        field(R.string.shell_device_user).performScrollTo()
        assertValue(R.string.shell_device_user, "builder")
        replace(R.string.shell_device_user, "   ")
        assertNoPersistence()
        save().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(builder.copy(sshUser = "root"), updates.single())
        }
        assertValue(R.string.shell_device_user, "   ")
    }
    @Test
    fun blankDuplicateAndReservedNamesDisableSaveWithoutChangingTheDraft() {
        val existing = ShellDeviceConfig(id = "existing", name = "  Build Host  ")
        showPage(listOf(existing))
        addDraft()
        replace(R.string.shell_device_name, "   ")
        save().assertIsNotEnabled()
        for (name in listOf("build host", "  BUILD HOST  ", "  lOcAl SaNdBoX  ")) {
            replace(R.string.shell_device_name, name)
            save().assertIsNotEnabled()
            nameError().assertExists()
            field(R.string.shell_device_name).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
            assertValue(R.string.shell_device_name, name)
        }
        replace(R.string.shell_device_name, " Unique host ")
        save().assertIsEnabled()
        nameError().assertDoesNotExist()
        assertNoPersistence()
    }

    @Test
    fun rejectionRetainsDraftAndFlowBeforeSuccessDoesNotResetItOrReopenTheEditor() {
        showPage()
        addDraft()
        replace(R.string.shell_device_url, " https://build.example.test:14216 ")
        replace(R.string.shell_device_key, " test-token ")
        replace(R.string.shell_device_desc, " Build machine ")
        replace(R.string.shell_device_name, " Build host ")
        val idleBounds = compose.onNodeWithTag("shell-save-slot").getUnclippedBoundsInRoot()
        save().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(strings.getValue(R.string.save)).assertDoesNotExist()
        assertEquals(idleBounds, compose.onNodeWithTag("shell-save-slot").getUnclippedBoundsInRoot())
        compose.runOnIdle {
            val submitted = additions.single()
            assertTrue(submitted.id.isNotBlank())
            assertEquals(ShellDeviceConfig(
                id = submitted.id, name = "Build host", description = "Build machine",
                serverUrl = "https://build.example.test:14216", apiKey = "test-token",
            ), submitted)
            results.single()(false)
        }
        compose.waitForIdle()
        assertValue(R.string.shell_device_name, " Build host ")
        assertValue(R.string.shell_device_desc, " Build machine ")
        nameError().assertExists()
        save().assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.runOnIdle { devices.value = listOf(additions.last()) }
        compose.waitForIdle()
        assertValue(R.string.shell_device_name, " Build host ")
        assertValue(R.string.shell_device_url, " https://build.example.test:14216 ")
        compose.runOnIdle { results.last()(true) }
        compose.waitForIdle()
        field(R.string.shell_device_name).assertDoesNotExist()
        add().assertIsEnabled()
        compose.onNodeWithText("Build host").assertExists()
        compose.runOnIdle { devices.value = listOf(additions.last().copy(description = "echo")) }
        field(R.string.shell_device_name).assertDoesNotExist()
        verify(exactly = 2) { settings.addShellDevice(any(), any(), any()) }
    }

    @Test
    fun successBeforeFlowReturnsToListAndLaterEmissionCannotReopenDraft() {
        showPage()
        addDraft()
        replace(R.string.shell_device_name, "Build host")
        save().performClick()
        compose.waitForIdle()
        compose.runOnIdle { results.single()(true) }
        compose.waitForIdle()
        add().assertIsEnabled()
        field(R.string.shell_device_name).assertDoesNotExist()
        compose.runOnIdle { devices.value = listOf(additions.single()) }
        compose.waitForIdle()
        compose.onNodeWithText("Build host").assertExists()
        field(R.string.shell_device_name).assertDoesNotExist()
    }

    @Test
    fun editingIgnoresPersistedEchoAndDiscardReentryStartsFromTheLatestSnapshot() {
        val initial = ShellDeviceConfig(id = "existing", name = "Build Host", description = "Original")
        showPage(listOf(initial))
        open(initial.name)
        replace(R.string.shell_device_name, "  bUiLd HoSt  ")
        replace(R.string.shell_device_desc, "Unsaved change")
        save().assertIsEnabled()
        val updated = initial.copy(description = "New persisted value")
        compose.runOnIdle { devices.value = listOf(updated) }
        compose.waitForIdle()
        assertValue(R.string.shell_device_desc, "Unsaved change")
        assertValue(R.string.shell_device_name, "  bUiLd HoSt  ")
        discard()
        open(initial.name)
        assertValue(R.string.shell_device_desc, updated.description)
        assertValue(R.string.shell_device_name, initial.name)
        assertNoPersistence()
    }

    @Test
    fun leavingDuringSaveAndReenteringTheSameDeviceRejectsTheOldCompletion() {
        val initial = ShellDeviceConfig(id = "existing", name = "Build Host", description = "Original")
        showPage(listOf(initial))
        open(initial.name)
        replace(R.string.shell_device_desc, "First draft")
        save().performClick()
        compose.waitForIdle()
        discard()
        open(initial.name)
        replace(R.string.shell_device_desc, "Second draft")
        compose.runOnIdle {
            devices.value = listOf(updates.single())
            results.single()(true)
        }
        compose.waitForIdle()
        assertValue(R.string.shell_device_desc, "Second draft")
        save().assertIsEnabled()
        add().assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, backCalls) }
    }

    @Test
    fun oldSaveAttemptCannotCloseAnEditorWhoseRetryIsStillPending() {
        val initial = ShellDeviceConfig(id = "existing", name = "Build Host")
        showPage(listOf(initial))
        open(initial.name)
        replace(R.string.shell_device_desc, "Changed")
        save().performClick()
        compose.waitForIdle()
        compose.runOnIdle { results.single()(false) }
        compose.waitForIdle()
        save().performClick()
        compose.waitForIdle()
        compose.runOnIdle { results.first()(true) }
        compose.waitForIdle()
        assertValue(R.string.shell_device_desc, "Changed")
        compose.onNodeWithTag("shell-save-slot").assertExists()
        compose.runOnIdle { results.last()(true) }
        compose.waitForIdle()
        add().assertIsEnabled()
    }

    @Test
    fun retainedOutgoingEditorKeepsItsDraftUntilTheSharedReturnAnimationFinishes() {
        val initial = ShellDeviceConfig(id = "existing", name = "Build Host")
        showPage(listOf(initial), reducedMotion = false)
        open(initial.name)
        replace(R.string.shell_device_desc, "Frozen during exit")
        save().performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            devices.value = listOf(updates.single().copy(description = "Persistence echo"))
            results.single()(true)
        }
        repeat(3) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        assertValue(R.string.shell_device_desc, "Frozen during exit")
        assertEquals(1, compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(
            hasTestTag("shell-field-description"),
        )).fetchSemanticsNodes().size)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        field(R.string.shell_device_desc).assertDoesNotExist()
        add().assertIsEnabled()
    }

    @Test
    fun successfulSaveRestoresTheRootScrollOwnerAndCollapsingTitlePosition() {
        val existing = (1..20).map { ShellDeviceConfig(id = "$it", name = "Host $it") }
        showPage(existing)
        val rootPath = listOf(false, "devices")
        compose.onNodeWithTag("shell-device-menu:8").performScrollTo()
        compose.waitForIdle()
        val expected = retention.scrollState(rootPath)!!.value
        compose.onNodeWithTag("shell-device-menu:8").performClick()
        button(R.string.edit).performClick()
        compose.waitForIdle()
        replace(R.string.shell_device_desc, "Saved detail")
        save().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            devices.value = existing.map { if (it.id == updates.single().id) updates.single() else it }
            results.single()(true)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(expected, retention.scrollState(rootPath)!!.value)
            assertEquals(0, backCalls)
        }
        field(R.string.shell_device_desc).assertDoesNotExist()
    }

    private fun quickCategoryRoundTrip() {
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { category = "other" }
        repeat(3) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.runOnIdle { assertEquals(1, shellCompositions) }
        compose.runOnIdle { category = "shell" }
        repeat(3) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.runOnIdle { assertEquals(1, shellCompositions) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        add().assertIsEnabled()
        field(R.string.shell_device_name).assertDoesNotExist()
    }

    @Test
    fun storageFailureReleasesSavingReportsOneSnackbarAndRetainsTheDraftForRetry() {
        showPage()
        addDraft()
        replace(R.string.shell_device_name, "Draft Host")
        replace(R.string.shell_device_desc, "Retain on failure")
        save().performClick()
        compose.waitForIdle()
        compose.runOnIdle { errors.single()(IOException("Storage full")) }
        compose.waitForIdle()
        assertValue(R.string.shell_device_name, "Draft Host")
        assertValue(R.string.shell_device_desc, "Retain on failure")
        nameError().assertDoesNotExist()
        save().assertIsEnabled()
        verify(exactly = 1) { viewModel.emitSnackbar(strings.getValue(R.string.img_save_failed), null, null) }
        save().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, additions.size) }
    }

    @Test
    fun quickCategoryReturnCannotReviveAnUnsavedDraftInTheRetainedComposition() {
        val initial = ShellDeviceConfig(id = "existing", name = "Build Host", description = "Persisted")
        showPage(listOf(initial), categoryHost = true)
        open(initial.name)
        replace(R.string.shell_device_desc, "Discarded by category navigation")
        quickCategoryRoundTrip()
        open(initial.name)
        assertValue(R.string.shell_device_desc, "Persisted")
        save().assertIsEnabled()
        assertNoPersistence()
    }

    @Test
    fun quickCategoryReturnRejectsLateSaveSuccessAndFailureAfterOpeningAFreshEditor() {
        val initial = ShellDeviceConfig(id = "existing", name = "Build Host", description = "Persisted")
        showPage(listOf(initial), categoryHost = true)
        open(initial.name)
        replace(R.string.shell_device_desc, "Old request")
        save().performClick()
        compose.waitForIdle()
        quickCategoryRoundTrip()
        open(initial.name)
        replace(R.string.shell_device_desc, "New request")
        compose.runOnIdle {
            results.single()(true)
            errors.single()(IOException("Late storage error"))
        }
        compose.waitForIdle()
        assertValue(R.string.shell_device_desc, "New request")
        save().assertIsEnabled()
        verify(exactly = 0) { viewModel.emitSnackbar(any(), any(), any()) }
        compose.runOnIdle { assertEquals(0, backCalls) }
    }

    @Test
    fun categoryNavigationCancelsVerificationAndLateHostKeyCannotReopenTheTrustDialog() {
        val initial = ShellDeviceConfig(id = "ssh", name = "SSH Host", type = "ssh", sshHost = "example.test")
        val completion = CompletableDeferred<Unit>()
        var closedSessions = 0
        every { viewModel.sshHostKeyVerifier } returns SshHostKeyVerifier(
            createSession = {
                HostKeyCaptureSession(
                    probe = { withContext(NonCancellable) { completion.await() } },
                    capturedHostKey = { "captured-host-key" },
                    close = { closedSessions++ },
                )
            },
            fingerprint = { "SHA256:test-fingerprint" },
            ioDispatcher = Dispatchers.Unconfined,
        )
        showPage(listOf(initial), categoryHost = true)
        open(initial.name)
        button(R.string.shell_ssh_verify_host_key).performScrollTo().performClick()
        compose.waitForIdle()
        quickCategoryRoundTrip()
        open(initial.name)
        compose.runOnIdle { completion.complete(Unit) }
        compose.waitForIdle()
        compose.onNodeWithText(strings.getValue(R.string.shell_ssh_verify_title)).assertDoesNotExist()
        save().assertIsEnabled()
        compose.runOnIdle { assertEquals(1, closedSessions) }
        assertNoPersistence()
    }

    @Test
    fun latePickerResultCannotReviveAKeyDraftAfterQuickCategoryReturn() {
        val initial = ShellDeviceConfig(
            id = "ssh", name = "SSH Host", type = "ssh", sshHost = "example.test",
            sshAuthMode = SSH_AUTH_PRIVATE_KEY,
        )
        showPage(listOf(initial), categoryHost = true)
        open(initial.name)
        button(R.string.shell_ssh_import_private_key).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(pickerRequestCode != null) }
        quickCategoryRoundTrip()
        open(initial.name)
        compose.runOnIdle {
            pickerRegistry.dispatchResult(
                requireNotNull(pickerRequestCode), Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://discarded-key/late-selection")),
            )
        }
        compose.waitForIdle()
        button(R.string.shell_ssh_import_private_key).assertIsEnabled()
        save().assertIsNotEnabled()
        assertNoPersistence()
    }

    @Test
    fun compactSshFieldsRemainIndependentDraftValuesAndOnlyExplicitSaveSubmitsThem() {
        val initial = ShellDeviceConfig(
            id = "ssh", name = "SSH Host", type = "ssh", sshHost = "old.example.test",
            sshUser = "old-user", sshPassword = "existing-secret",
        )
        showPage(listOf(initial))
        open(initial.name)
        replace(R.string.shell_device_name, " Renamed host ")
        replace(R.string.shell_device_desc, " Build machine ")
        replace(R.string.shell_device_host, " new.example.test ")
        replace(R.string.shell_device_port, "2x222")
        replace(R.string.shell_device_user, " builder ")
        assertValue(R.string.shell_device_port, "2222")
        assertNoPersistence()
        save().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(initial.copy(
                name = "Renamed host", description = "Build machine",
                sshHost = "new.example.test", sshPort = 2222, sshUser = "builder",
            ), updates.single())
            results.single()(true)
        }
        compose.waitForIdle()
        field(R.string.shell_device_name).assertDoesNotExist()
        add().assertIsEnabled()
        verify(exactly = 1) { settings.updateShellDevice(any(), any(), any()) }
        verify(exactly = 0) { settings.addShellDevice(any(), any(), any()) }
    }

    @Test
    fun retainedAuthenticationEntryPermanentlyRejectsPickerCompletionAfterIdentityLoss() {
        var current by mutableStateOf(true)
        val device = ShellDeviceConfig(name = "SSH", type = "ssh", sshAuthMode = SSH_AUTH_PRIVATE_KEY)
        val changes = mutableListOf<ShellDeviceConfig>()
        val busy = mutableListOf<Boolean>()
        var importLabel = ""
        var errorLabel = ""
        compose.setContent {
            val context = LocalContext.current
            importLabel = context.getString(R.string.shell_ssh_import_private_key)
            errorLabel = context.getString(R.string.shell_ssh_private_key_read_failed)
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                override val activityResultRegistry = pickerRegistry
            }) {
                MaterialTheme {
                    SettingsSshAuthenticationEditor(
                        device = device, onChange = { changes += it }, onImportingChange = { busy += it },
                        saveError = null, isCurrent = { current },
                    )
                }
            }
        }
        compose.onNode(hasText(importLabel) and hasClickAction()).performClick()
        compose.runOnIdle { current = false }
        compose.waitForIdle()
        compose.runOnIdle { current = true }
        compose.waitForIdle()
        compose.runOnIdle {
            pickerRegistry.dispatchResult(requireNotNull(pickerRequestCode), Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://discarded-key/retained-selection")))
        }
        compose.waitForIdle()
        compose.onNodeWithText(errorLabel).assertDoesNotExist()
        compose.onNode(hasText(importLabel) and hasClickAction()).assertIsNotEnabled()
        compose.runOnIdle {
            assertTrue(changes.isEmpty())
            assertEquals(listOf(true), busy)
        }
    }
}
