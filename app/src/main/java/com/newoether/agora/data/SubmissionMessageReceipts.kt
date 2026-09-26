package com.newoether.agora.data

import android.content.Context
import androidx.datastore.preferences.core.edit

internal suspend fun claimSubmissionMessage(context: Context, id: String): Boolean {
    var claimed = false
    context.applicationContext.dataStore.edit { prefs ->
        val displayed = prefs[DISPLAYED_SUBMISSION_MESSAGES] ?: emptySet()
        if (id !in displayed) {
            prefs[DISPLAYED_SUBMISSION_MESSAGES] = displayed + id
            claimed = true
        }
    }
    return claimed
}
