package com.newoether.agora.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ExportManifest(
    @SerialName("agora_export_version") val version: Int,
    @SerialName("app_version") val appVersion: String,
    @SerialName("exported_at") val exportedAt: String,
    val categories: List<String>,
    @SerialName("has_api_keys") val hasApiKeys: Boolean = false,
    @SerialName("incremental_baseline") val incrementalBaseline: Int =
        NativeBackupFormat.INCREMENTAL_BASELINE_REVISION,
)
