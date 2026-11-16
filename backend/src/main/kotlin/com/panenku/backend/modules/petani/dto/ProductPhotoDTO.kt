package com.panenku.backend.modules.petani.dto

import java.time.Instant
import java.util.UUID

data class ProductPhotoDTO(val productId: UUID, val photoUrl: String, val photoAccessUrl: String,
    val photoExpiresAt: Instant, val contentType: String, val sizeBytes: Long)
data class ProductPhotoAccess(val url: String, val expiresAt: Instant)
