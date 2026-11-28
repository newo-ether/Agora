package com.newoether.agora.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.viewmodel.ChatViewModel

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
    val developerOptionsEnabled by viewModel.settings.developerOptionsEnabled.collectAsState()
    var developerTapCount by rememberSaveable { mutableIntStateOf(0) }
    val haptics = LocalAgoraHaptics.current
    val developerTapsRemainingFormat = stringResource(R.string.developer_options_taps_remaining)
    val developerEnabledMessage = stringResource(R.string.developer_options_enabled_message)
    val developerAlreadyEnabledMessage = stringResource(R.string.developer_options_already_enabled_message)

    var showLicenseDialog by remember { mutableStateOf(false) }

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
            SettingsGroup(
                title = stringResource(R.string.about_info),
                items = listOf(
                    {
                        SettingsItem(
                            headlineContent = { Text("Application") },
                            supportingContent = { Text("Agora (XNet Fork)") },
                            leadingContent = { Icon(Icons.Rounded.Apps, contentDescription = null) }
                        )
                    },
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.about_developer)) },
                            supportingContent = { Text("XNet Inc. · Foxnet Labs · XNet NGO") },
                            leadingContent = { Icon(Icons.Rounded.Business, contentDescription = null) }
                        )
                    },
                    {
                        SettingsItem(
                            headlineContent = { Text("Upstream") },
                            supportingContent = { Text("Forked from Newo Ether (newo-ether/Agora)") },
                            leadingContent = { Icon(Icons.Rounded.Person, contentDescription = null) },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            modifier = Modifier.clickable { openUrl("https://github.com/newo-ether/Agora") }
                        )
                    },
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.about_version)) },
                            supportingContent = { Text("v$versionName ($versionCode)") },
                            leadingContent = { Icon(Icons.Rounded.Info, contentDescription = null) },
                            modifier = Modifier.clickable(onClick = ::onVersionTapped),
                        )
                    }
                )
            )

            // -- Documentation --
            val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
            SettingsGroup(title = stringResource(R.string.documentation), items = listOf({
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.show_documentation_links)) },
                    supportingContent = { Text(stringResource(R.string.show_documentation_links_desc)) },
                    leadingContent = { Icon(Icons.AutoMirrored.Rounded.MenuBook, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Switch(checked = showDocFab, onCheckedChange = { viewModel.settings.setShowDocumentationFab(it) })
                    },
                    modifier = Modifier.clickable { viewModel.settings.setShowDocumentationFab(!showDocFab) }
                )
            }))

            // -- Links --
            SettingsGroup(
                title = stringResource(R.string.about_links),
                items = listOf(
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.about_github), modifier = Modifier.padding(vertical = 6.dp)) },
                            supportingContent = { Text("github.com/XNet-NGO/Agora") },
                            leadingContent = { Icon(Icons.Rounded.Code, contentDescription = null) },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            modifier = Modifier.clickable { openUrl("https://github.com/XNet-NGO/Agora") }
                        )
                    },
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.about_view_release), modifier = Modifier.padding(vertical = 6.dp)) },
                            supportingContent = { Text("github.com/XNet-NGO/Agora/releases") },
                            leadingContent = { Icon(Icons.Rounded.Download, contentDescription = null) },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            modifier = Modifier.clickable { openUrl("https://github.com/XNet-NGO/Agora/releases") }
                        )
                    },
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.about_issue_tracker), modifier = Modifier.padding(vertical = 6.dp)) },
                            leadingContent = { Icon(Icons.Rounded.BugReport, contentDescription = null) },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            modifier = Modifier.clickable { openUrl("https://github.com/XNet-NGO/Agora/issues") }
                        )
                    },
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.about_contribute), modifier = Modifier.padding(vertical = 6.dp)) },
                            leadingContent = { Icon(Icons.Rounded.VolunteerActivism, contentDescription = null) },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            modifier = Modifier.clickable { openUrl("https://github.com/XNet-NGO/Agora/pulls") }
                        )
                    },
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.about_privacy_policy), modifier = Modifier.padding(vertical = 6.dp)) },
                            leadingContent = { Icon(Icons.Rounded.VerifiedUser, contentDescription = null) },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            modifier = Modifier.clickable { openUrl("https://github.com/XNet-NGO/Agora/blob/master/PRIVACY.md") }
                        )
                    },
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.about_license)) },
                            supportingContent = { Text(stringResource(R.string.about_license_desc)) },
                            leadingContent = { Icon(Icons.Rounded.Policy, contentDescription = null) },
                            modifier = Modifier.clickable { showLicenseDialog = true }
                        )
                    }
                )
            )

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
