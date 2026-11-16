package com.panenku.backend.modules.shared.repository

import com.panenku.backend.modules.shared.dto.PageDTO
import com.panenku.backend.modules.shared.dto.RefundDTO
import com.panenku.backend.modules.shared.dto.RefundDisputeDTO
import com.panenku.backend.modules.shared.dto.RefundHistoryDTO
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class RefundRow(val view: RefundDTO, val hash: String, val evidencePath: String, val transferPath: String?)
data class DisputeRow(val view: RefundDisputeDTO, val hash: String, val previousStatus: String)
data class NewRefund(val id: UUID, val orderId: UUID, val buyerId: UUID, val key: String, val hash: String,
                     val reason: String, val evidencePath: String, val amount: Long, val destinationType: String,
                     val provider: String, val account: String, val name: String, val createdAt: Instant)
data class NewDispute(val id: UUID, val refundId: UUID, val buyerId: UUID, val key: String, val hash: String,
                      val reason: String, val previousStatus: String, val createdAt: Instant)

@Repository
class RefundRepository(private val db: JdbcTemplate) {
    private val refundMapper = RowMapper { rs, _ ->
        RefundRow(RefundDTO(
            id = rs.getObject("id", UUID::class.java), orderId = rs.getObject("order_id", UUID::class.java),
            buyerId = rs.getObject("buyer_id", UUID::class.java), farmerId = rs.getObject("farmer_id", UUID::class.java),
            reason = rs.getString("reason"), refundAmount = rs.getLong("refund_amount"),
            destinationType = rs.getString("destination_type"), destinationProvider = rs.getString("destination_provider"),
            destinationAccount = rs.getString("destination_account"), destinationName = rs.getString("destination_name"),
            status = rs.getString("status"), farmerDecision = rs.getString("farmer_decision"), farmerReason = rs.getString("farmer_reason"),
            farmerDecidedAt = rs.getTimestamp("farmer_decided_at")?.toInstant(), hasEvidence = true,
            hasTransferProof = rs.getString("transfer_proof_path") != null,
            transferSubmittedAt = rs.getTimestamp("transfer_submitted_at")?.toInstant(),
            buyerConfirmedAt = rs.getTimestamp("buyer_confirmed_at")?.toInstant(),
            createdAt = rs.getTimestamp("created_at").toInstant(), updatedAt = rs.getTimestamp("updated_at").toInstant()
        ), rs.getString("request_hash"), rs.getString("evidence_path"), rs.getString("transfer_proof_path"))
    }
    private val disputeMapper = RowMapper { rs, _ ->
        DisputeRow(RefundDisputeDTO(
            rs.getObject("id", UUID::class.java), rs.getObject("refund_id", UUID::class.java),
            rs.getObject("order_id", UUID::class.java), rs.getObject("buyer_id", UUID::class.java),
            rs.getString("reason"), rs.getString("status"), rs.getString("decision"), rs.getString("decision_reason"),
            rs.getObject("decided_by", UUID::class.java), rs.getTimestamp("decided_at")?.toInstant(),
            rs.getTimestamp("created_at").toInstant()
        ), rs.getString("request_hash"), rs.getString("previous_refund_status"))
    }

    fun findRefund(id: UUID, lock: Boolean = false): RefundRow? =
        db.query("$REFUND_SELECT WHERE r.id=?${if (lock) " FOR UPDATE OF r" else ""}", refundMapper, id).singleOrNull()
    fun findByKey(buyer: UUID, key: String): RefundRow? =
        db.query("$REFUND_SELECT WHERE r.buyer_id=? AND r.idempotency_key=?", refundMapper, buyer, key).singleOrNull()
    fun orderId(id: UUID): UUID? = db.query("SELECT order_id FROM refunds WHERE id=?", { rs, _ -> rs.getObject(1, UUID::class.java) }, id).singleOrNull()
    fun activeCount(orderId: UUID, exclude: UUID? = null): Int = if (exclude == null) db.queryForObject(
        "SELECT count(*) FROM refunds WHERE order_id=? AND status IN ('diajukan','disetujui','menunggu_konfirmasi_dana','dieskalasi')", Int::class.java, orderId) ?: 0
        else db.queryForObject("SELECT count(*) FROM refunds WHERE order_id=? AND id<>? AND status IN ('diajukan','disetujui','menunggu_konfirmasi_dana','dieskalasi')", Int::class.java, orderId, exclude) ?: 0
    fun completedCount(orderId: UUID, exclude: UUID? = null): Int = if (exclude == null) db.queryForObject(
        "SELECT count(*) FROM refunds WHERE order_id=? AND status='selesai'", Int::class.java, orderId) ?: 0
        else db.queryForObject("SELECT count(*) FROM refunds WHERE order_id=? AND id<>? AND status='selesai'", Int::class.java, orderId, exclude) ?: 0
    fun insert(value: NewRefund) {
        db.update("""INSERT INTO refunds(id,order_id,buyer_id,idempotency_key,request_hash,reason,evidence_path,refund_amount,
            destination_type,destination_provider,destination_account,destination_name,status,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", value.id, value.orderId, value.buyerId, value.key, value.hash,
            value.reason, value.evidencePath, value.amount, value.destinationType, value.provider, value.account, value.name,
            "diajukan", ts(value.createdAt), ts(value.createdAt))
    }
    fun respond(id: UUID, status: String, decision: String, reason: String?, now: Instant) {
        db.update("UPDATE refunds SET status=?, farmer_decision=?, farmer_reason=?, farmer_decided_at=?, updated_at=? WHERE id=?",
            status, decision, reason, ts(now), ts(now), id)
    }
    fun transferRequest(actor: UUID, key: String): Pair<String, UUID>? =
        db.query("SELECT request_hash, refund_id FROM refund_transfer_requests WHERE farmer_id=? AND idempotency_key=?",
            { rs, _ -> rs.getString("request_hash") to rs.getObject("refund_id", UUID::class.java) }, actor, key).singleOrNull()
    fun saveTransfer(id: UUID, actor: UUID, key: String, hash: String, path: String, now: Instant) {
        db.update("UPDATE refunds SET transfer_proof_path=?, transfer_submitted_at=?, status='menunggu_konfirmasi_dana', updated_at=? WHERE id=?",
            path, ts(now), ts(now), id)
        db.update("INSERT INTO refund_transfer_requests(farmer_id,idempotency_key,request_hash,refund_id) VALUES (?,?,?,?)", actor, key, hash, id)
    }
    fun confirm(id: UUID, now: Instant) {
        db.update("UPDATE refunds SET status='selesai', buyer_confirmed_at=?, updated_at=? WHERE id=?", ts(now), ts(now), id)
    }
    fun updateStatus(id: UUID, status: String, now: Instant) {
        db.update("UPDATE refunds SET status=?,updated_at=? WHERE id=?", status, ts(now), id)
    }
    fun history(id: UUID): List<RefundHistoryDTO> = db.query(
        "SELECT from_status,to_status,actor_role,note,changed_at FROM refund_history WHERE refund_id=? ORDER BY sequence_id",
        { rs, _ -> RefundHistoryDTO(rs.getString("from_status"), rs.getString("to_status"), rs.getString("actor_role"), rs.getString("note"), rs.getTimestamp("changed_at").toInstant()) }, id)
    fun record(id: UUID, from: String?, to: String, actor: UUID, role: String, note: String?, now: Instant) {
        db.update("INSERT INTO refund_history(id,refund_id,from_status,to_status,actor_id,actor_role,note,changed_at) VALUES (?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), id, from, to, actor, role, note, ts(now))
    }

    fun findDispute(id: UUID, lock: Boolean = false): DisputeRow? =
        db.query("$DISPUTE_SELECT WHERE d.id=?${if (lock) " FOR UPDATE OF d" else ""}", disputeMapper, id).singleOrNull()
    fun findDisputeByKey(buyer: UUID, key: String): DisputeRow? =
        db.query("$DISPUTE_SELECT WHERE d.buyer_id=? AND d.idempotency_key=?", disputeMapper, buyer, key).singleOrNull()
    fun insertDispute(value: NewDispute) {
        db.update("""INSERT INTO refund_disputes(id,refund_id,buyer_id,idempotency_key,request_hash,reason,previous_refund_status,status,created_at)
            VALUES (?,?,?,?,?,?,?,'terbuka',?)""", value.id, value.refundId, value.buyerId, value.key, value.hash, value.reason, value.previousStatus, ts(value.createdAt))
    }
    fun decideDispute(id: UUID, actor: UUID, decision: String, reason: String, now: Instant) {
        db.update("UPDATE refund_disputes SET status='selesai',decision=?,decision_reason=?,decided_by=?,decided_at=? WHERE id=?", decision, reason, actor, ts(now), id)
    }
    fun auditDispute(actor: UUID, id: UUID, action: String, reason: String, now: Instant) {
        db.update("INSERT INTO audit_logs(id,actor_id,actor_role,action,target_type,target_id,old_values,new_values,reason,created_at) VALUES (?,?,'admin',?,'refund_dispute',?,?,?,?,?)",
            UUID.randomUUID(), actor, action, id, "terbuka", "selesai", reason, ts(now))
    }
    fun list(actor: UUID, role: String, page: Int, size: Int): PageDTO<RefundDTO> {
        val filter = when (role) { "pembeli" -> "r.buyer_id=?"; "petani" -> "o.farmer_id=?"; else -> "1=1" }
        val args: List<Any> = if (role == "admin") emptyList() else listOf(actor)
        val count = db.queryForObject("SELECT count(*) FROM refunds r JOIN orders o ON o.id=r.order_id WHERE $filter", Long::class.java, *args.toTypedArray()) ?: 0L
        val rows = db.query("$REFUND_SELECT WHERE $filter ORDER BY r.created_at DESC,r.id LIMIT ? OFFSET ?", refundMapper,
            *(args + listOf<Any>(size, page.toLong() * size)).toTypedArray()).map { it.view }
        return PageDTO(rows, page, size, count)
    }
    fun openDisputes(page: Int, size: Int): PageDTO<RefundDisputeDTO> {
        val count = db.queryForObject("SELECT count(*) FROM refund_disputes WHERE status='terbuka'", Long::class.java) ?: 0L
        val rows = db.query("$DISPUTE_SELECT WHERE d.status='terbuka' ORDER BY d.created_at,d.id LIMIT ? OFFSET ?", disputeMapper,
            size, page.toLong() * size).map { it.view }
        return PageDTO(rows, page, size, count)
    }
    private fun ts(value: Instant) = Timestamp.from(value)
    private companion object {
        const val REFUND_SELECT = "SELECT r.*,o.farmer_id FROM refunds r JOIN orders o ON o.id=r.order_id"
        const val DISPUTE_SELECT = "SELECT d.*,r.order_id FROM refund_disputes d JOIN refunds r ON r.id=d.refund_id"
    }
}
