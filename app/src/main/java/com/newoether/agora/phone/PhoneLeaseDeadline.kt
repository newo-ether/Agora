package com.newoether.agora.phone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A single cancellable deadline, replaced only when the lease budget or pause state changes. */
internal fun PhoneControlLease.watchIdle(scope: CoroutineScope, now: () -> Long) = scope.launch {
    held.map { current ->
        current?.let { Triple(it.epoch, it.paused, it.accountedAt + it.remainingMs) }
    }.distinctUntilChanged().collectLatest { deadline ->
        if (deadline == null || deadline.second) return@collectLatest
        delay((deadline.third - now()).coerceAtLeast(0))
        expire(deadline.first)
    }
}
