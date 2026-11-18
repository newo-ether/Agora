package com.newoether.agora.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.sandbox.openSandboxRoot
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.sandbox.SandboxMirror
import com.newoether.agora.sandbox.SandboxSharedStorageAccess
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSandboxPage(
    sandboxManager: SandboxManager,
    onBack: () -> Unit,
    showDocFab: Boolean = false,
    sharedStorageEnabled: Boolean = false,
    onSharedStorageEnabledChange: (Boolean) -> Unit = {},
    mirror: SandboxMirror = SandboxMirror.DEFAULT,
    onMirrorChange: (SandboxMirror) -> Unit = {},
    onSnackbar: (String) -> Unit,
) {
    val motionPolicy = LocalAgoraMotionPolicy.current
    val scope = rememberCoroutineScope()
    val listState = rememberSettingsLazyListState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var sharedStorageAccessGranted by remember {
        mutableStateOf(SandboxSharedStorageAccess.isGranted(ctx))
    }
    val sharedStorageSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        sharedStorageAccessGranted = SandboxSharedStorageAccess.isGranted(ctx)
    }
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        sharedStorageAccessGranted = SandboxSharedStorageAccess.isGranted(ctx)
    }
    fun requestSharedStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SandboxSharedStorageAccess.settingsIntent(ctx)
                ?.let(sharedStorageSettingsLauncher::launch)
        } else {
            legacyStoragePermissionLauncher.launch(
                SandboxSharedStorageAccess.legacyPermissions,
            )
        }
    }

    // Each distribution owns a fresh dashboard snapshot; the manager owns persisted selection.
    val distribution by sandboxManager.distribution.collectAsState()
    var available by remember(distribution) { mutableStateOf(sandboxManager.isAvailableSync()) }
    var backendPackagesLoading by remember(distribution) {
        mutableStateOf(sandboxManager.packageList.value.isEmpty())
    }
    var selectingDistribution by remember { mutableStateOf(false) }
    var installRequests by remember(distribution) { mutableIntStateOf(0) }
    val selectionFailedMessage = stringResource(R.string.sandbox_distribution_switch_failed)
    val showSnackbar by rememberUpdatedState(onSnackbar)
    var installPkg by rememberSaveable(distribution.id) { mutableStateOf(sandboxManager.pendingPkgName) }
    var lastInstallResult by remember(distribution) { mutableStateOf<Boolean?>(null) }
    var deleteConfirm by remember(distribution) { mutableStateOf<String?>(null) }
    var resetConfirm by remember(distribution) { mutableStateOf(false) }
    LaunchedEffect(installPkg) { sandboxManager.pendingPkgName = installPkg }

    val terminalOutput by sandboxManager.terminalOutput.collectAsState()
    val isBusy by sandboxManager.isBusy.collectAsState()
    // Rootfs installation belongs to the manager and survives navigation.
    val installingRootfs by sandboxManager.isInstallingRootfs.collectAsState()
    val downloadProgress by sandboxManager.downloadProgress.collectAsState()
    val rootfsPhase by sandboxManager.rootfsPhase.collectAsState()
    val canManage = !isBusy && !installingRootfs && !selectingDistribution
    // Reset must stay available while a mutation runs: it cancels the running operation and
    // deletes the rootfs, which is the only recovery from a stuck install or package command.
    val canReset = !selectingDistribution
    val showTerminal = terminalOutput.isNotBlank()
    val mirrorAppliedMessage = stringResource(R.string.sandbox_mirror_applied)
    val mirrorFailedMessage = stringResource(R.string.sandbox_mirror_failed)

    fun chooseMirror(selected: SandboxMirror) {
        if (!canManage || selected == mirror) return
        onMirrorChange(selected)
        // Persist first, then rewrite the installed guest so its next package operation uses it.
        if (available) {
            scope.launch {
                if (sandboxManager.applyMirror(selected)) {
                    showSnackbar(mirrorAppliedMessage)
                } else {
                    showSnackbar(sandboxManager.lastError?.takeIf(String::isNotBlank) ?: mirrorFailedMessage)
                }
            }
        }
    }

    fun clearPackageState() {
        installPkg = ""
        deleteConfirm = null
        lastInstallResult = null
    }

    fun installPackage(name: String) {
        if (!canManage) return
        installPkg = name; lastInstallResult = null; sandboxManager.installPackage(name)
    }

    fun upgradePackages() {
        if (!canManage) return
        lastInstallResult = null
        sandboxManager.upgradePackages()
    }

    // Recheck on page entry, selection or an Install tap, even if the running state is too brief to draw.
    // The backend emits install failures independently; this check only updates the dashboard.
    LaunchedEffect(distribution, installRequests, isBusy) {
        // A package operation owns the mutation boundary; refreshing now would only queue behind it
        // and leave the page spinning forever. The cached snapshot stays on screen instead.
        if (isBusy) return@LaunchedEffect
        try {
            sandboxManager.isInstallingRootfs.first { !it }
            available = sandboxManager.isAvailable()
            if (available) sandboxManager.refreshPackageList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.w("SettingsSandboxPage", "Failed to refresh sandbox environment", e)
        } finally {
            backendPackagesLoading = false
        }
    }

    val packageSnapshot by sandboxManager.packageList.collectAsState()
    val backendPackages = if (backendPackagesLoading) emptyList() else packageSnapshot
    val quickPkgs = distribution.quickPackages
    val pkgCount = backendPackages.size
    var diskUsageMB by remember(distribution) { mutableLongStateOf(0L) }
    LaunchedEffect(distribution, backendPackages.size, available, isBusy) {
        if (!isBusy) {
            try { diskUsageMB = sandboxManager.getDiskUsageMB() } catch (_: Exception) {}
        }
    }

    CollapsingSettingsLazyScaffold(
        title = stringResource(R.string.sandbox_mgmt_title),
        onBack = onBack,
        listState = listState,
        floatingActionButton = { if (showDocFab) DocumentationFab("sandbox.md") }
    ) {
            // ═══ Dashboard ═══
                item {
                    SettingsGroup(
                        title = stringResource(R.string.sandbox_env),
                        items = if (available) listOf({
                            SettingsItem(
                                headlineContent = { Text("${distribution.displayName} ${distribution.versionLabel}") },
                                supportingContent = {
                                    Column {
                                        Text(
                                            if (diskUsageMB < 1024) stringResource(R.string.sandbox_disk_usage_mb, diskUsageMB)
                                            else stringResource(R.string.sandbox_disk_usage_gb, diskUsageMB / 1024f),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            stringResource(R.string.sandbox_dashboard_summary, pkgCount),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                },
                                leadingContent = { SandboxDistributionIcon(distribution) },
                                trailingContent = {
                                    SandboxActionCapsule(
                                        label = stringResource(R.string.sandbox_upgrade),
                                        enabled = canManage,
                                        modifier = Modifier.padding(end = 4.dp),
                                        onClick = { upgradePackages() },
                                    )
                                },
                            )
                        }, {
                            SettingsSandboxMirrorItem(mirror, canManage, ::chooseMirror)
                        }) else listOf({
                            SettingsSandboxDistributionItem(distribution, enabled = canManage) { selected ->
                                selectingDistribution = true
                                scope.launch {
                                    try {
                                        if (sandboxManager.selectDistribution(selected)) {
                                            clearPackageState()
                                            resetConfirm = false
                                        } else {
                                            showSnackbar(sandboxManager.lastError?.takeIf(String::isNotBlank) ?: selectionFailedMessage)
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        showSnackbar(e.message?.takeIf(String::isNotBlank) ?: selectionFailedMessage)
                                    } finally {
                                        selectingDistribution = false
                                    }
                                }
                            }
                        }, {
                            SettingsSandboxInstallItem(
                                distribution = distribution,
                                enabled = canManage,
                                installing = installingRootfs,
                                phase = rootfsPhase,
                                downloadProgress = downloadProgress,
                                onInstall = {
                                    clearPackageState()
                                    sandboxManager.installRootfs()
                                    installRequests++
                                },
                            )
                        }, {
                            SettingsSandboxMirrorItem(mirror, canManage, ::chooseMirror)
                        }),
                    )
                }

                if (available) {
                    // ═══ Files and shared storage ═══
                    item {
                        SettingsGroup(
                            title = stringResource(R.string.sandbox_browse_files),
                            items = buildList {
                                add {
                                    SettingsItem(
                                        headlineContent = {
                                            Text(
                                                stringResource(R.string.sandbox_browse_files),
                                                fontWeight = FontWeight.Medium,
                                            )
                                        },
                                        supportingContent = {
                                            Text(
                                                stringResource(R.string.sandbox_browse_files_desc),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        leadingContent = {
                                            Icon(
                                                Icons.Default.Folder,
                                                null,
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        },
                                        modifier = Modifier.clickable {
                                            try {
                                                ctx.openSandboxRoot()
                                            } catch (e: Exception) {
                                                DebugLog.w(
                                                    "SettingsSandboxPage",
                                                    "Failed to open sandbox root",
                                                    e,
                                                )
                                            }
                                        },
                                    )
                                }
                                add {
                                    SettingsItem(
                                        headlineContent = {
                                            Text(stringResource(R.string.sandbox_shared_storage))
                                        },
                                        supportingContent = {
                                            Text(
                                                when {
                                                    sharedStorageEnabled &&
                                                        sharedStorageAccessGranted ->
                                                        stringResource(
                                                            R.string.sandbox_shared_storage_mounted,
                                                        )
                                                    sharedStorageEnabled ->
                                                        stringResource(
                                                            R.string.sandbox_shared_storage_permission_required,
                                                        )
                                                    else -> stringResource(
                                                        R.string.sandbox_shared_storage_desc,
                                                    )
                                                },
                                            )
                                        },
                                        leadingContent = {
                                            Icon(
                                                Icons.Default.SdStorage,
                                                null,
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        },
                                        trailingContent = {
                                            Switch(
                                                checked = sharedStorageEnabled,
                                                onCheckedChange = { enabled ->
                                                    onSharedStorageEnabledChange(enabled)
                                                    if (
                                                        enabled &&
                                                        !sharedStorageAccessGranted
                                                    ) {
                                                        requestSharedStorageAccess()
                                                    }
                                                },
                                            )
                                        },
                                        modifier = Modifier.clickable {
                                            val enabled = !sharedStorageEnabled
                                            onSharedStorageEnabledChange(enabled)
                                            if (enabled && !sharedStorageAccessGranted) {
                                                requestSharedStorageAccess()
                                            }
                                        },
                                    )
                                }
                                if (
                                    sharedStorageEnabled &&
                                    !sharedStorageAccessGranted
                                ) {
                                    add {
                                        SettingsItem(
                                            headlineContent = {
                                                Text(
                                                    stringResource(
                                                        R.string.sandbox_shared_storage_grant,
                                                    ),
                                                )
                                            },
                                            supportingContent = {
                                                Text(
                                                    stringResource(
                                                        R.string.sandbox_shared_storage_warning,
                                                    ),
                                                )
                                            },
                                            leadingContent = {
                                                Icon(Icons.Default.AdminPanelSettings, null)
                                            },
                                            modifier = Modifier.clickable {
                                                requestSharedStorageAccess()
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }

                    // ═══ Install Packages ═══
                    item {
                        SettingsGroup(title = stringResource(R.string.sandbox_install_packages), items = listOf({
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                // Input row
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = installPkg,
                                        onValueChange = { installPkg = it; lastInstallResult = null },
                                        label = { Text(stringResource(R.string.sandbox_package_name)) },
                                        placeholder = { Text(stringResource(R.string.sandbox_package_placeholder)) },
                                        singleLine = true,
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    val installDone = installPkg.isNotBlank() && lastInstallResult != null && !isBusy
                                    val btnBgColor by animateColorAsState(
                                        targetValue = when {
                                            !canManage || installPkg.isBlank() -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                            lastInstallResult == true -> MaterialTheme.colorScheme.secondaryContainer
                                            lastInstallResult == false -> MaterialTheme.colorScheme.errorContainer
                                            else -> MaterialTheme.colorScheme.primary
                                        },
                                        animationSpec = tween(400)
                                    )
                                    val btnContentColor by animateColorAsState(
                                        targetValue = when {
                                            !canManage || installPkg.isBlank() -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            lastInstallResult == true -> MaterialTheme.colorScheme.onSecondaryContainer
                                            lastInstallResult == false -> MaterialTheme.colorScheme.onErrorContainer
                                            else -> MaterialTheme.colorScheme.onPrimary
                                        },
                                        animationSpec = tween(400)
                                    )
                                    SandboxActionCapsule(
                                        enabled = canManage && installPkg.isNotBlank() && lastInstallResult == null,
                                        containerColor = btnBgColor,
                                        contentColor = btnContentColor,
                                        onClick = { installPackage(installPkg.trim()) },
                                    ) {
                                        if (isBusy) {
                                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = btnContentColor)
                                        } else {
                                            Text(
                                                text = if (installDone) {
                                                    if (lastInstallResult == true) stringResource(R.string.sandbox_installed_label)
                                                    else stringResource(R.string.sandbox_failed_label)
                                                } else {
                                                    stringResource(R.string.sandbox_install)
                                                },
                                                style = MaterialTheme.typography.labelLarge,
                                                color = btnContentColor,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }

                                // Terminal output (fixed-height, terminal theme, auto-scroll)
                                AnimatedVisibility(
                                    visible = showTerminal && terminalOutput.isNotBlank(),
                                    enter = if (motionPolicy.allowSpatialTransitions) {
                                        expandVertically() + fadeIn()
                                    } else {
                                        fadeIn()
                                    },
                                    exit = if (motionPolicy.allowSpatialTransitions) {
                                        shrinkVertically() + fadeOut()
                                    } else {
                                        fadeOut()
                                    },
                                ) {
                                    val termScroll = rememberScrollState()
                                    LaunchedEffect(
                                        terminalOutput,
                                        motionPolicy.allowProgrammaticScrollMotion,
                                    ) {
                                        if (motionPolicy.allowProgrammaticScrollMotion) {
                                            termScroll.animateScrollTo(termScroll.maxValue)
                                        } else {
                                            termScroll.scrollTo(termScroll.maxValue)
                                        }
                                    }
                                    val terminalFg = MaterialTheme.colorScheme.onSurface
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 4.dp,
                                        modifier = Modifier.padding(top = 16.dp).fillMaxWidth().height(260.dp)
                                    ) {
                                        SelectionContainer {
                                            Text(
                                                terminalOutput,
                                                modifier = Modifier.padding(12.dp).fillMaxWidth()
                                                    .verticalScroll(termScroll)
                                                    .nestedScroll(object : NestedScrollConnection {
                                                        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = available
                                                        override suspend fun onPreFling(available: Velocity): Velocity = available
                                                        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
                                                    }),
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = FontFamily(Font(R.font.jetbrains_mono_regular)),
                                                    lineHeight = 18.sp
                                                ),
                                                color = terminalFg
                                            )
                                        }
                                    }
                                }

                                // Quick install chips
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    stringResource(R.string.sandbox_quick_install) + ":",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                @OptIn(ExperimentalLayoutApi::class)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(0.dp)
                                ) {
                                    quickPkgs.forEach { pkg ->
                                        FilterChip(
                                            selected = false,
                                            onClick = { installPackage(pkg) },
                                            enabled = canManage,
                                            label = { Text(pkg, style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }
                            }
                        }))
                    }

                    // ═══ Installed Packages ═══
                    // Section header
                    item(key = "installed_header") {
                        Text(
                            text = stringResource(R.string.sandbox_installed_fmt, pkgCount),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                    // Each package as its own LazyColumn item — avoids composing all
                    // packages in a single frame when the list is large.
                    when {
                        backendPackagesLoading -> item(key = "loading") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(20.dp))
                            }
                        }
                        backendPackages.isEmpty() -> item(key = "empty") {
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 1.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                SettingsItem(
                                    headlineContent = {
                                        Text(stringResource(R.string.sandbox_no_packages), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    },
                                    leadingContent = {
                                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                    }
                                )
                            }
                        }
                        else -> items(backendPackages.size, key = { backendPackages[it].name }) { idx ->
                            val pkg = backendPackages[idx]
                            val isFirst = idx == 0
                            val isLast = idx == backendPackages.lastIndex
                            val shape = when {
                                backendPackages.size == 1 -> RoundedCornerShape(24.dp)
                                isFirst -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 5.dp, bottomEnd = 5.dp)
                                isLast -> RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
                                else -> RoundedCornerShape(5.dp)
                            }
                            Surface(
                                shape = shape,
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 1.dp,
                                modifier = Modifier.fillMaxWidth().then(if (idx > 0) Modifier.padding(top = 2.dp) else Modifier)
                            ) {
                                SettingsItem(
                                    headlineContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(pkg.name, fontWeight = FontWeight.Medium)
                                            if (pkg.version.isNotBlank()) {
                                                Spacer(Modifier.width(6.dp))
                                                Surface(shape = RoundedCornerShape(3.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                                    Text("v${pkg.version}", Modifier.padding(horizontal = 4.dp, vertical = 1.dp), maxLines = 1,
                                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                                }
                                            }
                                        }
                                    },
                                    supportingContent = {
                                        if (pkg.description.isNotBlank()) Text(pkg.description.take(80), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    },
                                    leadingContent = { Icon(Icons.Default.Inventory2, null, tint = MaterialTheme.colorScheme.primary) },
                                    trailingContent = {
                                        IconButton(onClick = { deleteConfirm = pkg.name }, enabled = canManage, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.Close, stringResource(R.string.sandbox_remove_content_desc), modifier = Modifier.size(16.dp))
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // ═══ Danger Zone ═══
                    item {
                        Spacer(Modifier.height(24.dp))
                        SettingsGroup(bottomPadding = 0.dp, title = stringResource(R.string.sandbox_danger_zone), items = listOf({
                            SettingsItem(
                                headlineContent = {
                                    Text(
                                        stringResource(R.string.sandbox_reset),
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Medium
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        stringResource(R.string.sandbox_reset_desc),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.DeleteForever,
                                        null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                modifier = Modifier.clickable(enabled = canReset) { resetConfirm = true }
                            )
                        }))
                        Spacer(Modifier.height(16.dp))
                    }

                    // Doc FAB clearance
                    if (showDocFab) {
                        item(key = "doc_spacer") { Spacer(modifier = Modifier.height(80.dp)) }
                    }
                }
    }

    // ── Delete confirm dialog ──
    deleteConfirm?.let { pkgName ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { deleteConfirm = null },
            title = { Text(stringResource(R.string.sandbox_remove_pkg_title, pkgName), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.sandbox_remove_pkg_desc, pkgName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!canManage) return@TextButton
                        deleteConfirm = null
                        sandboxManager.removePackage(pkgName)
                    },
                    enabled = canManage,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // ── Reset confirm dialog ──
    if (resetConfirm) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { resetConfirm = false },
            title = { Text(stringResource(R.string.sandbox_reset_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.sandbox_reset_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!canReset) return@TextButton
                        resetConfirm = false
                        scope.launch {
                            if (sandboxManager.reset()) {
                                available = false
                                clearPackageState()
                            }
                        }
                    },
                    enabled = canReset,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { resetConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
