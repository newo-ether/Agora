package com.newoether.agora.ui.settings

import com.newoether.agora.data.BUILT_IN_REMOTE_PROVIDERS
import com.newoether.agora.data.inferModelAlias
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.apiModelName

internal data class ModelProviderGroup(
    val providerName: String,
    val models: List<String>,
)

/** Built-ins removed from the Provider list are hidden; [providerOrder] lists the visible ones. */
private fun isHiddenBuiltIn(providerName: String, providerOrder: List<String>): Boolean =
    providerName in BUILT_IN_REMOTE_PROVIDERS && providerName !in providerOrder

internal fun customModelGroups(
    customModels: Set<String>,
    providerOrder: List<String>,
): List<ModelProviderGroup> {
    val providerPositions = providerOrder.withIndex().associate { (index, name) -> name to index }
    return customModels
        .groupBy { ModelId.parse(it).providerName }
        .filterKeys { !isHiddenBuiltIn(it, providerOrder) }
        .map { (providerName, models) ->
            ModelProviderGroup(
                providerName = providerName,
                models = models.sortedBy { ModelId.parse(it).apiModelName.lowercase() },
            )
        }
        .sortedWith(
            compareBy<ModelProviderGroup>(
                { providerPositions[it.providerName] ?: Int.MAX_VALUE },
                { it.providerName.lowercase() },
            )
        )
}

internal fun fetchedModelGroups(
    availableModels: Map<String, List<String>>,
    customModels: Set<String>,
    modelAliases: Map<String, String>,
    query: String,
    providerOrder: List<String>,
): List<ModelProviderGroup> {
    val normalizedQuery = query.trim()
    val providerPositions = providerOrder.withIndex().associate { (index, name) -> name to index }
    return availableModels.filterKeys { !isHiddenBuiltIn(it, providerOrder) }.mapNotNull { (providerName, models) ->
        val providerMatches =
            normalizedQuery.isNotEmpty() &&
                providerName.contains(normalizedQuery, ignoreCase = true)
        val filteredModels = models
            .asSequence()
            .filterNot { it in customModels }
            .distinct()
            .filter { model ->
                val apiModelName = ModelId.parse(model).apiModelName
                normalizedQuery.isEmpty() ||
                    providerMatches ||
                    model.contains(normalizedQuery, ignoreCase = true) ||
                    apiModelName.contains(normalizedQuery, ignoreCase = true) ||
                    modelAliases[model]?.contains(normalizedQuery, ignoreCase = true) == true ||
                    inferModelAlias(apiModelName).contains(normalizedQuery, ignoreCase = true)
            }
            .toList()
        filteredModels.takeIf { it.isNotEmpty() }?.let {
            ModelProviderGroup(providerName = providerName, models = it)
        }
    }.sortedBy { providerPositions[it.providerName] ?: Int.MAX_VALUE }
}

internal fun modelAliasToPersist(
    rawAlias: String,
    initialDisplayAlias: String,
    editedAlias: String,
): String = if (editedAlias.trim() == initialDisplayAlias.trim()) {
    rawAlias.trim()
} else {
    editedAlias.trim()
}

