package com.panenku.backend.modules.shared.repository

import com.panenku.backend.modules.pembeli.entity.Order
import com.panenku.backend.modules.shared.dto.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal data class StoredScheduleChange(val view: ScheduleChangeDTO, val key: String, val hash: String)
@Repository
class OrderScheduleRepository(private val db: JdbcTemplate) {
    private val row = RowMapper { rs, _ -> StoredScheduleChange(ScheduleChangeDTO(
        rs.getObject("id",UUID::class.java), rs.getObject("order_id",UUID::class.java), rs.getObject("farmer_id",UUID::class.java),
        rs.getObject("buyer_id",UUID::class.java), rs.getLong("base_version"), rs.getObject("from_date",LocalDate::class.java),
        rs.getString("from_slot"), rs.getObject("proposed_date",LocalDate::class.java), rs.getString("proposed_slot"),
        rs.getString("reason"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("decided_at")?.toInstant(), rs.getString("buyer_reason"), rs.getObject("accepted_version")?.let { (it as Number).toLong() }),
        rs.getString("idempotency_key"),rs.getString("request_hash")) }
    fun effective(order: Order): EffectiveScheduleDTO = db.query("""SELECT proposed_date,proposed_slot,accepted_version
        FROM order_schedule_changes WHERE order_id=? AND status='accepted' ORDER BY accepted_version DESC LIMIT 1""", { rs, _ ->
        EffectiveScheduleDTO(rs.getObject(1,LocalDate::class.java),rs.getString(2),rs.getLong(3))
    }, order.id).singleOrNull() ?: EffectiveScheduleDTO(order.scheduledDate,order.scheduledTimeSlot,0)
    fun pending(id: UUID): ScheduleChangeDTO? = db.query("SELECT * FROM order_schedule_changes WHERE order_id=? AND status='pending'",row,id).singleOrNull()?.view
    internal fun byKey(order: UUID, farmer: UUID, key: String) = db.query("SELECT * FROM order_schedule_changes WHERE order_id=? AND farmer_id=? AND idempotency_key=?",row,order,farmer,key).singleOrNull()
    internal fun find(order: UUID, id: UUID) = db.query("SELECT * FROM order_schedule_changes WHERE order_id=? AND id=?",row,order,id).singleOrNull()
    fun list(id: UUID, page: Int, size: Int): PageDTO<ScheduleChangeDTO> = PageDTO(
        db.query("SELECT * FROM order_schedule_changes WHERE order_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",row,id,size,page.toLong()*size).map { it.view },
        page,size,db.queryForObject("SELECT count(*) FROM order_schedule_changes WHERE order_id=?",Long::class.java,id)!!)
    fun insert(v: ScheduleChangeDTO, key: String, hash: String) {
        db.update("""INSERT INTO order_schedule_changes(id,order_id,farmer_id,buyer_id,idempotency_key,request_hash,
            base_version,from_date,from_slot,proposed_date,proposed_slot,reason,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            v.id,v.orderId,v.farmerId,v.buyerId,key,hash,v.baseVersion,v.fromDate,v.fromSlot,v.proposedDate,v.proposedSlot,v.reason,Timestamp.from(v.createdAt))
    }
    fun decide(id: UUID, actor: UUID, target: String, reason: String?, version: Long?, at: Instant) {
        check(db.update("UPDATE order_schedule_changes SET status=?,buyer_reason=?,decided_by=?,decided_at=?,accepted_version=? WHERE id=? AND status='pending'",
            target,reason,actor,Timestamp.from(at),version,id)==1)
    }
    fun hasProof(id: UUID) = db.queryForObject("SELECT EXISTS(SELECT 1 FROM payment_submissions WHERE order_id=?)",Boolean::class.java,id)!!
}
