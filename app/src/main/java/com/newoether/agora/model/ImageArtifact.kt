package com.newoether.agora.model

/** Completeness is independent of policy version so legacy measured bytes remain reusable. */
internal fun ImageArtifact.hasMeasuredFactsFor(imagePath: String): Boolean =
    path == imagePath && path.isNotBlank() && !unavailable &&
        mimeType.startsWith("image/") && sizeBytes > 0 &&
        (width ?: 0) > 0 && (height ?: 0) > 0
