package com.panenku.backend.modules.shared.dto

import java.time.Instant
import java.util.UUID

data class RefundHistoryDTO(val fromStatus: String?, val toStatus: String, val actorRole: String, val note: String?, val changedAt: Instant)
data class RefundDTO(
    val id: UUID, val orderId: UUID, val buyerId: UUID, val farmerId: UUID,
    val reason: String, val refundAmount: Long, val destinationType: String,
    val destinationProvider: String, val destinationAccount: String, val destinationName: String,
    val status: String, val farmerDecision: String?, val farmerReason: String?, val farmerDecidedAt: Instant?,
    val hasEvidence: Boolean, val hasTransferProof: Boolean, val transferSubmittedAt: Instant?,
    val buyerConfirmedAt: Instant?, val createdAt: Instant, val updatedAt: Instant,
    val history: List<RefundHistoryDTO> = emptyList()
)
data class RefundDecisionRequest(val decision: String, val reason: String? = null)
data class EscalationRequest(val reason: String)
data class AdminDisputeDecisionRequest(val decision: String, val reason: String)
data class RefundDisputeDTO(
    val id: UUID, val refundId: UUID, val orderId: UUID, val buyerId: UUID,
    val reason: String, val status: String, val decision: String?, val decisionReason: String?,
    val decidedBy: UUID?, val decidedAt: Instant?, val createdAt: Instant
)
