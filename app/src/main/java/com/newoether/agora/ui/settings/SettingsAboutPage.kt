package com.newoether.agora.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.util.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAboutPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val packageInfo = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (_: Exception) { null }
    }
    val versionName = packageInfo?.versionName ?: "?"
    @Suppress("DEPRECATION")
    val versionCode = packageInfo?.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode
        else it.versionCode.toLong()
    } ?: 0L
    val upToDateStatus = stringResource(R.string.about_up_to_date, versionName)
    val developerOptionsEnabled by viewModel.settings.developerOptionsEnabled.collectAsState()
    var developerTapCount by rememberSaveable { mutableIntStateOf(0) }
    val haptics = LocalAgoraHaptics.current
    val developerTapsRemainingFormat = stringResource(R.string.developer_options_taps_remaining)
    val developerEnabledMessage = stringResource(R.string.developer_options_enabled_message)
    val developerAlreadyEnabledMessage = stringResource(R.string.developer_options_already_enabled_message)

    val autoUpdateCheck by viewModel.settings.autoUpdateCheck.collectAsState()
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var isChecking by remember { mutableStateOf(false) }
    var showLicenseDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val focusManager = LocalFocusManager.current

    fun openUrl(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    fun onVersionTapped() {
        val result = DeveloperModeUnlockPolicy.advance(
            currentTapCount = developerTapCount,
            alreadyEnabled = developerOptionsEnabled,
        )
        developerTapCount = result.tapCount
        when (result.feedback) {
            DeveloperUnlockFeedback.NONE -> haptics.selection()
            DeveloperUnlockFeedback.REMAINING_TAPS -> {
                haptics.selection()
                viewModel.emitSnackbar(
                    String.format(developerTapsRemainingFormat, result.remainingTaps),
                )
            }
            DeveloperUnlockFeedback.ENABLED -> {
                haptics.confirm()
                viewModel.settings.setDeveloperOptionsEnabled(true)
                viewModel.emitSnackbar(developerEnabledMessage)
            }
            DeveloperUnlockFeedback.ALREADY_ENABLED -> {
                haptics.selection()
                viewModel.emitSnackbar(developerAlreadyEnabledMessage)
            }
        }
    }

    if (showLicenseDialog) {
        LicenseDialog(onDismiss = { showLicenseDialog = false })
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.about_title),
        onBack = onBack
    ) {
            SettingsGroupColumn {
                // -- App Info --
                SettingsGroup(title = stringResource(R.string.about_info), items = listOf({
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_developer)) },
                    supportingContent = { Text(stringResource(R.string.about_developer_name)) },
                    leadingContent = { Icon(Icons.Default.Person, contentDescription = null) }
                )
            }, {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_version)) },
                    supportingContent = { Text("v$versionName ($versionCode)") },
                    leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = ::onVersionTapped),
                )
            }))

            // -- Updates --
            SettingsGroup(title = stringResource(R.string.about_updates), items = buildList {
                add {
                    val updateLabel = if (isChecking) {
                        stringResource(R.string.about_checking)
                    } else {
                        updateStatus ?: stringResource(R.string.about_check_updates)
                    }
                    SettingsItem(
                        headlineContent = {
                            Crossfade(
                                targetState = updateLabel,
                                animationSpec = tween(durationMillis = 250),
                                label = "aboutUpdateLabel",
                            ) { label ->
                                Text(label)
                            }
                        },
                        supportingContent = { Text(stringResource(R.string.about_check_updates_desc)) },
                        leadingContent = {
                            Crossfade(
                                targetState = isChecking,
                                animationSpec = tween(durationMillis = 250),
                                label = "aboutUpdateIcon",
                            ) { checking ->
                                if (checking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.5.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        },
                        modifier = Modifier.clickable(enabled = !isChecking) {
                            isChecking = true
                            scope.launch {
                                val info = withContext(Dispatchers.IO) { viewModel.checkForUpdates() }
                                if (info != null) {
                                    viewModel.showUpdateDialog(info)
                                } else {
                                    updateStatus = upToDateStatus
                                }
                                isChecking = false
                            }
                        }
                    )
                }
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.about_auto_update)) },
                        supportingContent = { Text(stringResource(R.string.about_auto_update_desc)) },
                        leadingContent = { Icon(Icons.Default.Sync, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(checked = autoUpdateCheck, onCheckedChange = { viewModel.settings.setAutoUpdateCheck(it) })
                        },
                        modifier = Modifier.clickable { viewModel.settings.setAutoUpdateCheck(!autoUpdateCheck) }
                    )
                }
            })

            // -- Documentation --
            val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
            SettingsGroup(title = stringResource(R.string.documentation), items = listOf({
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.show_documentation_links)) },
                    supportingContent = { Text(stringResource(R.string.show_documentation_links_desc)) },
                    leadingContent = { Icon(Icons.Default.MenuBook, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Switch(checked = showDocFab, onCheckedChange = { viewModel.settings.setShowDocumentationFab(it) })
                    },
                    modifier = Modifier.clickable { viewModel.settings.setShowDocumentationFab(!showDocFab) }
                )
            }))

            // -- Links --
            SettingsGroup(title = stringResource(R.string.about_links), items = listOf({
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_website)) },
                    supportingContent = { Text("agora.newoether.com") },
                    leadingContent = { Icon(Icons.Default.Language, contentDescription = null) },
                    trailingContent = {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    modifier = Modifier.clickable { openUrl("https://agora.newoether.com") }
                )
            }, {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_github), modifier = Modifier.padding(vertical = 6.dp)) },
                    leadingContent = { Icon(Icons.Default.Code, contentDescription = null) },
                    trailingContent = {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    modifier = Modifier.clickable { openUrl("https://github.com/newo-ether/Agora") }
                )
            }, {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_issue_tracker), modifier = Modifier.padding(vertical = 6.dp)) },
                    leadingContent = { Icon(Icons.Default.BugReport, contentDescription = null) },
                    trailingContent = {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    modifier = Modifier.clickable { openUrl("https://github.com/newo-ether/Agora/issues") }
                )
            }, {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_contribute), modifier = Modifier.padding(vertical = 6.dp)) },
                    leadingContent = { Icon(Icons.Default.VolunteerActivism, contentDescription = null) },
                    trailingContent = {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    modifier = Modifier.clickable { openUrl("https://github.com/newo-ether/Agora/pulls") }
                )
            }, {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_privacy_policy), modifier = Modifier.padding(vertical = 6.dp)) },
                    leadingContent = { Icon(Icons.Default.VerifiedUser, contentDescription = null) },
                    trailingContent = {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    modifier = Modifier.clickable { openUrl("https://github.com/newo-ether/Agora/blob/master/PRIVACY.md") }
                )
            }, {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_license)) },
                    supportingContent = { Text(stringResource(R.string.about_license_desc)) },
                    leadingContent = { Icon(Icons.Default.Policy, contentDescription = null) },
                    modifier = Modifier.clickable { showLicenseDialog = true }
                )
            }))

            // -- Rating Section (title + card as one unit so the title stays tight to the card) --
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.rating_category),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
                    ) {
                        RatingForm()
                    }
                }
            }
            }
    }
}
