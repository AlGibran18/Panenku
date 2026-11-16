package com.panenku.backend.modules.shared.repository

import com.panenku.backend.modules.shared.dto.LatePaymentReportDTO
import com.panenku.backend.modules.shared.dto.PageDTO
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class LateReportRow(val view: LatePaymentReportDTO, val hash: String, val evidencePath: String)
data class NewLateReport(val id: UUID, val orderId: UUID, val buyerId: UUID, val key: String, val hash: String,
                         val reason: String, val claimedAmount: Long, val evidencePath: String, val createdAt: Instant)

@Repository
class LatePaymentReportRepository(private val db: JdbcTemplate) {
    private val rowMapper = RowMapper { rs, _ ->
        LateReportRow(LatePaymentReportDTO(
            id = rs.getObject("id", UUID::class.java), orderId = rs.getObject("order_id", UUID::class.java),
            buyerId = rs.getObject("buyer_id", UUID::class.java), farmerId = rs.getObject("farmer_id", UUID::class.java),
            reason = rs.getString("reason"), claimedAmount = rs.getLong("claimed_amount"), expectedAmount = rs.getLong("total_payment"),
            status = rs.getString("status"), reviewDecision = rs.getString("review_decision"), reviewReason = rs.getString("review_reason"),
            reviewedBy = rs.getObject("reviewed_by", UUID::class.java), reviewedAt = rs.getTimestamp("reviewed_at")?.toInstant(),
            closeReason = rs.getString("close_reason"), closedBy = rs.getObject("closed_by", UUID::class.java),
            closedAt = rs.getTimestamp("closed_at")?.toInstant(), createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        ), rs.getString("request_hash"), rs.getString("evidence_path"))
    }

    fun findById(id: UUID, lock: Boolean = false): LateReportRow? =
        db.query("$SELECT_REPORT WHERE r.id=?${if (lock) " FOR UPDATE OF r" else ""}", rowMapper, id).singleOrNull()
    fun findByKey(buyerId: UUID, key: String): LateReportRow? =
        db.query("$SELECT_REPORT WHERE r.buyer_id=? AND r.idempotency_key=?", rowMapper, buyerId, key).singleOrNull()
    fun orderId(id: UUID): UUID? = db.query("SELECT order_id FROM late_payment_reports WHERE id=?",
        { rs, _ -> rs.getObject(1, UUID::class.java) }, id).singleOrNull()
    fun activeCount(orderId: UUID): Int = db.queryForObject(
        "SELECT count(*) FROM late_payment_reports WHERE order_id=? AND status IN ('diajukan','tindak_lanjut')", Int::class.java, orderId) ?: 0

    fun insert(value: NewLateReport) {
        db.update("""INSERT INTO late_payment_reports(id,order_id,buyer_id,idempotency_key,request_hash,reason,claimed_amount,
            evidence_path,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,'diajukan',?,?)""",
            value.id, value.orderId, value.buyerId, value.key, value.hash, value.reason, value.claimedAmount,
            value.evidencePath, ts(value.createdAt), ts(value.createdAt))
    }
    fun review(id: UUID, actor: UUID, decision: String, reason: String, status: String, now: Instant) {
        db.update("""UPDATE late_payment_reports SET status=?,review_decision=?,review_reason=?,reviewed_by=?,reviewed_at=?,
            close_reason=?,closed_by=?,closed_at=?,updated_at=? WHERE id=?""", status, decision, reason, actor, ts(now),
            if (status == "ditutup") reason else null, if (status == "ditutup") actor else null,
            if (status == "ditutup") ts(now) else null, ts(now), id)
    }
    fun close(id: UUID, actor: UUID, reason: String, now: Instant) {
        db.update("UPDATE late_payment_reports SET status='ditutup',close_reason=?,closed_by=?,closed_at=?,updated_at=? WHERE id=?",
            reason, actor, ts(now), ts(now), id)
    }
    fun list(actor: UUID, role: String, page: Int, size: Int): PageDTO<LatePaymentReportDTO> {
        val condition = if (role == "pembeli") "r.buyer_id=?" else "r.status IN ('diajukan','tindak_lanjut')"
        val args: List<Any> = if (role == "pembeli") listOf(actor) else emptyList()
        val count = db.queryForObject("SELECT count(*) FROM late_payment_reports r WHERE $condition", Long::class.java, *args.toTypedArray()) ?: 0L
        val rows = db.query("$SELECT_REPORT WHERE $condition ORDER BY r.created_at DESC,r.id LIMIT ? OFFSET ?", rowMapper,
            *(args + listOf<Any>(size, page.toLong() * size)).toTypedArray()).map { it.view }
        return PageDTO(rows, page, size, count)
    }
    fun audit(actor: UUID, id: UUID, action: String, from: String, to: String, reason: String, now: Instant) {
        db.update("INSERT INTO audit_logs(id,actor_id,actor_role,action,target_type,target_id,old_values,new_values,reason,created_at) VALUES (?,?,'admin',?,'late_payment_report',?,?,?,?,?)",
            UUID.randomUUID(), actor, action, id, from, to, reason, ts(now))
    }
    private fun ts(value: Instant) = Timestamp.from(value)
    private companion object { const val SELECT_REPORT = "SELECT r.*,o.farmer_id,o.total_payment FROM late_payment_reports r JOIN orders o ON o.id=r.order_id" }
}
