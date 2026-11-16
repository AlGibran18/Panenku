package com.panenku.backend.modules.shared.dto

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class EffectiveScheduleDTO(val scheduledDate: LocalDate, val scheduledTimeSlot: String, val version: Long, val timeZone: String = "Asia/Jakarta")
data class ScheduleChangeRequest(val expectedScheduleVersion: Long, val scheduledDate: LocalDate, val scheduledTimeSlot: String, val reason: String)
data class ScheduleDecisionRequest(val expectedScheduleVersion: Long, val decision: String, val reason: String? = null)
data class ScheduleChangeDTO(val id: UUID, val orderId: UUID, val farmerId: UUID, val buyerId: UUID,
    val baseVersion: Long, val fromDate: LocalDate, val fromSlot: String, val proposedDate: LocalDate,
    val proposedSlot: String, val reason: String, val status: String, val createdAt: Instant,
    val decidedAt: Instant?, val buyerReason: String?, val acceptedVersion: Long?)
