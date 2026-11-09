package com.newoether.agora.util

import java.io.IOException

/** Shared transport category; request owners still decide whether replay is safe. */
open class ConchChannelException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Authentication failures never permit network retries. */
class ConchAuthenticationException(message: String) : ConchChannelException(message)
