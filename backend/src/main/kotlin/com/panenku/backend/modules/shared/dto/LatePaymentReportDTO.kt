package com.panenku.backend.modules.shared.dto

import java.time.Instant
import java.util.UUID

data class LatePaymentReportDTO(
    val id: UUID, val orderId: UUID, val buyerId: UUID, val farmerId: UUID,
    val reason: String, val claimedAmount: Long, val expectedAmount: Long, val status: String,
    val reviewDecision: String?, val reviewReason: String?, val reviewedBy: UUID?, val reviewedAt: Instant?,
    val closeReason: String?, val closedBy: UUID?, val closedAt: Instant?, val createdAt: Instant, val updatedAt: Instant
)
data class LatePaymentReviewRequest(val decision: String, val reason: String)
data class LatePaymentCloseRequest(val reason: String)
