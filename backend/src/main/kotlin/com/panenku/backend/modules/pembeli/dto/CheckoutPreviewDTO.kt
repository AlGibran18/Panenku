package com.panenku.backend.modules.pembeli.dto

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class CheckoutPreviewItemRequest(val productId: UUID, val quantityKg: BigDecimal)
data class CheckoutPreviewRequest(
    @field:JsonSetter(contentNulls = Nulls.FAIL) val items: List<CheckoutPreviewItemRequest>,
    val fulfillmentMethod: String, val scheduledDate: LocalDate, val scheduledTimeSlot: String,
    val deliveryAddress: String? = null, val deliveryDistrict: String? = null, val buyerNotes: String? = null
)
data class CheckoutPreviewItemDTO(val productId: UUID, val name: String, val commodity: String,
    val quantityKg: BigDecimal, val pricePerKg: Long, val itemSubtotal: Long, val readyDate: LocalDate)
data class CheckoutPreviewDTO(
    val farmerId: UUID, val storeName: String, val items: List<CheckoutPreviewItemDTO>,
    val subtotalItems: Long, val deliveryFee: Long, val totalPayment: Long,
    val farmerProductRevenue: Long, val transactionLimitApplied: Long, val canCheckout: Boolean,
    val validationCode: String?, val fulfillmentMethod: String, val scheduledDate: LocalDate,
    val scheduledTimeSlot: String, val pickupLocation: String, val deliveryAddress: String?,
    val deliveryDistrict: String?, val buyerNotes: String?, val expectedFulfillmentToken: String,
    val quotedAt: Instant, val quoteToken: String,
    val currency: String = "IDR", val platformFee: Long = 0, val orderFee: Long = 0,
    val packagingFee: Long = 0, val timeZone: String = "Asia/Jakarta"
)
