package com.newoether.agora.data.local

/** A generation change immediately hides old vectors; only maintenance reclaims their BLOBs. */
internal const val CURRENT_EMBEDDING = """
    e.generation = COALESCE((SELECT generation FROM semantic_index_ledger WHERE modelId = e.modelId), 0)
    AND NOT EXISTS (SELECT 1 FROM semantic_index_ledger WHERE modelId = e.modelId AND state = 'DELETED')
"""
