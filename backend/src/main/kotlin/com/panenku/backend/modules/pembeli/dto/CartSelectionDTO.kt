package com.panenku.backend.modules.pembeli.dto

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class CartSelectionRequest(val productId: UUID, val quantityKg: BigDecimal, val expectedPricePerKg: Long)
data class CartReplaceRequest(val items: List<CartSelectionRequest>, val expectedCartVersion: String, val confirmed: Boolean = false)
data class ReorderRequest(val expectedCartVersion: String, val previewToken: String, val confirmed: Boolean = false)
data class ReorderItemDTO(
    val productId: UUID, val name: String, val previousQuantityKg: BigDecimal, val quantityKg: BigDecimal,
    val previousPricePerKg: Long, val pricePerKg: Long?, val priceChanged: Boolean,
    val stockAvailable: BigDecimal, val readyDate: LocalDate?, val available: Boolean,
    val adjustmentReason: String?, val farmerId: UUID?, val storeName: String?,
    val photoAccessUrl: String?, val photoExpiresAt: Instant?
)
data class ReorderPreviewDTO(val orderId: UUID, val cartVersion: String, val previewToken: String, val items: List<ReorderItemDTO>)
