package com.panenku.backend.modules.shared.dto

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class FulfillmentItemRequest(val productId: UUID, val quantityKg: BigDecimal)
data class FulfillmentOptionsRequest(@field:JsonSetter(contentNulls = Nulls.FAIL) val items: List<FulfillmentItemRequest>, val fulfillmentMethod: String,
    val deliveryDistrict: String? = null, val fromDate: LocalDate? = null, val days: Int = 14)
data class ScheduleDayDTO(val date: LocalDate, val timeSlots: List<String>)
data class FulfillmentOptionsDTO(val farmerId: UUID, val storeName: String, val fulfillmentMethod: String,
    val deliveryDistrict: String?, val deliveryFee: Long, val pickupLocation: String,
    val serviceArea: List<String>, val timeZone: String, val serverTime: Instant,
    val earliestDate: LocalDate, val dates: List<ScheduleDayDTO>, val selectionToken: String)
