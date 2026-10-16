package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.scanner.NetworkScanInput
import com.newoether.agora.scanner.NetworkScanner
import kotlinx.coroutines.launch

/** Bottom sheet for forward/reverse DNS lookups against the on-device resolver. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerDnsSheet(scanner: NetworkScanner, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(stringResource(R.string.scanner_dns_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text(stringResource(R.string.scanner_dns_host)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val q = host.trim()
                    if (q.isNotEmpty()) {
                        scope.launch {
                            result = if (NetworkScanInput.isIpv4(q)) {
                                scanner.reverseDns(q)
                            } else {
                                scanner.dnsLookup(q)
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.scanner_dns_lookup)) }
            if (result.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                SelectionContainer {
                    Text(result, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Bottom sheet for scanning an explicit host/port specification (ranges and /24 CIDR). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerCustomSheet(scanner: NetworkScanner, onDismiss: () -> Unit) {
    var hosts by remember { mutableStateOf("") }
    var ports by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(stringResource(R.string.scanner_custom_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = hosts,
                onValueChange = { hosts = it },
                label = { Text(stringResource(R.string.scanner_custom_hosts)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = ports,
                onValueChange = { ports = it },
                label = { Text(stringResource(R.string.scanner_custom_ports)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val ipList = NetworkScanInput.parseHosts(hosts)
                    val portList = NetworkScanInput.parsePorts(ports)
                    if (ipList.isNotEmpty() && portList.isNotEmpty()) {
                        scanner.launchCustomScan(ipList, portList)
                        onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.scanner_custom_run)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
