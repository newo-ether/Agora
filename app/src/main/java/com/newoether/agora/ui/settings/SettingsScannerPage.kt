package com.newoether.agora.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.scanner.HostInfo
import com.newoether.agora.scanner.NetworkScanner
import kotlinx.coroutines.launch

/** Settings > Network Scanner: discover hosts and drill into ports/services/DNS/WoL on the LAN. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScannerPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scanner = remember { NetworkScanner.getInstance(context) }
    val state by scanner.state.collectAsState()
    var showDns by remember { mutableStateOf(false) }
    var showCustom by remember { mutableStateOf(false) }

    // Fetch WAN IP once when the screen opens (the only off-LAN call).
    LaunchedEffect(Unit) { scanner.launchFetchWanIp() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = { Text(stringResource(R.string.scanner_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { showCustom = true }) {
                        Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.scanner_custom_title))
                    }
                    IconButton(onClick = { showDns = true }) {
                        Icon(Icons.Default.Dns, contentDescription = stringResource(R.string.scanner_dns_title))
                    }
                    IconButton(onClick = {
                        scanner.launchFetchWanIp()
                        scanner.launchDiscoverHosts()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.scanner_rescan))
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp),
        ) {
            NetworkInfoRow(state.localIp, state.wanIp)
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isScanning) {
                    OutlinedButton(onClick = { scanner.cancelScan() }) {
                        Text(stringResource(R.string.scanner_cancel))
                    }
                } else {
                    Button(onClick = { scanner.launchDiscoverHosts() }) {
                        Text(stringResource(R.string.scanner_discover))
                    }
                }
            }

            if (state.isScanning || state.progress > 0f) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            }
            if (state.phase.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    state.phase,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.error?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(12.dp))

            if (state.hosts.isEmpty() && !state.isScanning) {
                Text(
                    stringResource(R.string.scanner_no_hosts),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.hosts, key = { it.ip }) { host ->
                        HostCard(
                            host = host,
                            onScanPorts = { if (!state.isScanning) scanner.launchScanPorts(host.ip) },
                            onProbeUdp = { if (!state.isScanning) scanner.launchUdpProbe(host.ip) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.scanner_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDns) ScannerDnsSheet(scanner) { showDns = false }
    if (showCustom) ScannerCustomSheet(scanner) { showCustom = false }
}

@Composable
private fun NetworkInfoRow(localIp: String?, wanIp: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        localIp?.let {
            Text(
                stringResource(R.string.scanner_local_ip, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        wanIp?.let {
            Text(
                stringResource(R.string.scanner_wan_ip, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HostCard(host: HostInfo, onScanPorts: () -> Unit, onProbeUdp: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val scanner = remember { NetworkScanner.getInstance(context) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onScanPorts),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(host.ip, fontWeight = FontWeight.SemiBold)
                if (host.isGateway) {
                    Text(
                        "  (${stringResource(R.string.scanner_gateway)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            val subtitle = listOfNotNull(host.vendor, host.mac).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (host.openPorts.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                host.openPorts.forEach { p ->
                    val svc = p.service?.let { " $it" } ?: ""
                    val banner = p.banner?.let { " — $it" } ?: ""
                    Text("${p.port}/${p.protocol}$svc$banner", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onScanPorts) { Text(stringResource(R.string.scanner_scan_ports)) }
                OutlinedButton(onClick = onProbeUdp) { Text(stringResource(R.string.scanner_scan_udp)) }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = host.mac != null,
                    onClick = {
                        val mac = host.mac ?: return@OutlinedButton
                        scope.launch {
                            val ok = scanner.wakeOnLan(mac)
                            Toast.makeText(
                                context,
                                context.getString(
                                    if (ok) R.string.scanner_wake_sent else R.string.scanner_wake_failed,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                ) { Text(stringResource(R.string.scanner_wake)) }
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(host.ip))
                    Toast.makeText(context, context.getString(R.string.scanner_ip_copied), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.scanner_copy_ip)) }
            }
        }
    }
}
