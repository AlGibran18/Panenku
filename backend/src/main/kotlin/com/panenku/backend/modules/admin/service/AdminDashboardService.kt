package com.panenku.backend.modules.admin.service

import com.fasterxml.jackson.databind.JsonNode
import com.panenku.backend.common.exception.conflict
import com.panenku.backend.common.exception.invalid
import com.panenku.backend.common.exception.missing
import com.panenku.backend.common.security.ActorService
import com.panenku.backend.modules.shared.dto.PageDTO
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class VerificationQueueItem(val farmerId: UUID, val storeName: String, val email: String,
    val contactPhone: String?, val submittedAt: Instant?, val hasDocument: Boolean, val accountStatus: String,
    val ktpStatus: String, val applicationId: UUID?)
data class AdminUserItem(val id: UUID, val email: String, val role: String, val status: String,
    val displayName: String?, val ktpStatus: String?, val createdAt: Instant)
data class AdminUserRefundSummary(val id: UUID, val orderId: UUID, val status: String, val createdAt: Instant)
data class AdminUserDetail(val id: UUID, val email: String, val role: String, val status: String,
    val displayName: String?, val storeName: String?, val ownerName: String?, val pickupLocation: String?, val ktpStatus: String?,
    val createdAt: Instant, val suspensionReason: String?, val suspendedAt: Instant?,
    val activeRefundCount: Long, val recentActiveRefunds: List<AdminUserRefundSummary>)
data class AdminSettings(val transactionLimit: Long, val csWhatsapp: String?,
    val settingsVersion: Long, val lastChangedBy: UUID?, val lastChangedByEmail: String?, val lastChangedAt: Instant?, val lastChangedByName: String?)
data class AdminAuditItem(val id: UUID, val actorId: UUID, val actorRole: String, val action: String,
    val targetType: String?, val targetId: UUID?, val oldValues: String?, val newValues: String?,
    val reason: String?, val createdAt: Instant, val actorEmail: String?, val settingsVersion: Long?, val actorName: String?)

@Service
class AdminDashboardService(private val actors: ActorService, private val db: JdbcTemplate, private val clock: Clock) {
    @Transactional(readOnly = true)
    fun verificationQueue(actor: UUID, page: Int, size: Int, status: String = "menunggu", search: String = ""): PageDTO<VerificationQueueItem> {
        actors.require(actor, "admin")
        validatePage(page, size)
        if (status !in setOf("menunggu", "disetujui", "ditolak")) invalid("Status pengajuan tidak valid.")
        if (search.length > 100) invalid("Pencarian maksimal 100 karakter.")
        val pattern = "%" + search.trim().lowercase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
        val from = """ FROM farmer_profiles f JOIN users u ON u.id=f.id
            LEFT JOIN LATERAL (SELECT id FROM ktp_dummy_applications WHERE farmer_id=f.id ORDER BY sequence_id DESC LIMIT 1) a ON true
            WHERE u.role='petani' AND f.ktp_status=? AND
            (lower(f.store_name) LIKE ? ESCAPE '!' OR lower(u.email) LIKE ? ESCAPE '!'
                OR CAST(f.id AS TEXT) LIKE ? ESCAPE '!' OR CAST(a.id AS TEXT) LIKE ? ESCAPE '!')"""
        val total = db.queryForObject("SELECT count(*)" + from, Long::class.java, status, pattern, pattern, pattern, pattern)!!
        val items = db.query("""SELECT f.id,f.store_name,u.email,f.contact_phone,f.ktp_submitted_at,
            (f.ktp_document_path IS NOT NULL AND btrim(f.ktp_document_path) <> '') AS has_document,u.status,f.ktp_status,a.id AS application_id
            $from ORDER BY f.ktp_submitted_at ASC NULLS LAST,f.id LIMIT ? OFFSET ?""", { rs, _ ->
            VerificationQueueItem(rs.getObject("id", UUID::class.java), rs.getString("store_name"), rs.getString("email"),
                rs.getString("contact_phone"), rs.getTimestamp("ktp_submitted_at")?.toInstant(), rs.getBoolean("has_document"), rs.getString("status"),
                rs.getString("ktp_status"), rs.getObject("application_id", UUID::class.java))
        }, status, pattern, pattern, pattern, pattern, size, page.toLong() * size)
        return PageDTO(items, page, size, total)
    }

    @Transactional(readOnly = true)
    fun users(actor: UUID, page: Int, size: Int, role: String?, status: String?, search: String): PageDTO<AdminUserItem> {
        actors.require(actor, "admin")
        validatePage(page, size)
        if (role != null && role !in setOf("admin", "petani", "pembeli")) invalid("Role tidak valid.")
        if (status != null && status !in setOf("active", "suspended")) invalid("Status tidak valid.")
        if (search.length > 100) invalid("Pencarian maksimal 100 karakter.")
        val where = StringBuilder(" FROM users u LEFT JOIN farmer_profiles f ON f.id=u.id LEFT JOIN buyer_profiles b ON b.id=u.id WHERE 1=1")
        val args = mutableListOf<Any>()
        if (role != null) { where.append(" AND u.role=?"); args.add(role) }
        if (status != null) { where.append(" AND u.status=?"); args.add(status) }
        if (search.isNotBlank()) {
            val pattern = "%" + search.trim().lowercase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
            where.append(" AND (lower(u.email) LIKE ? ESCAPE '!' OR lower(coalesce(f.store_name,b.full_name,u.display_name,'')) LIKE ? ESCAPE '!')")
            args.add(pattern); args.add(pattern)
        }
        val total = db.queryForObject("SELECT count(*)$where", Long::class.java, *args.toTypedArray())!!
        val items = db.query("""SELECT u.id,u.email,u.role,u.status,u.created_at,
            coalesce(f.store_name,b.full_name,u.display_name) AS display_name,f.ktp_status $where
            ORDER BY u.created_at DESC,u.id ASC LIMIT ? OFFSET ?""", { rs, _ ->
            AdminUserItem(rs.getObject("id", UUID::class.java), rs.getString("email"), rs.getString("role"), rs.getString("status"),
                rs.getString("display_name"), rs.getString("ktp_status"), rs.getTimestamp("created_at").toInstant())
        }, *(args + listOf(size, page.toLong() * size)).toTypedArray())
        return PageDTO(items, page, size, total)
    }

    @Transactional(readOnly = true)
    fun userDetail(actor: UUID, userId: UUID): AdminUserDetail {
        actors.require(actor, "admin")
        val user = db.query("""SELECT u.id,u.email,u.role,u.status,u.created_at,
            coalesce(f.store_name,b.full_name,u.display_name) AS display_name,f.store_name,f.owner_name,f.pickup_location,f.ktp_status,
            a.reason AS suspension_reason,a.created_at AS suspended_at
            FROM users u LEFT JOIN farmer_profiles f ON f.id=u.id LEFT JOIN buyer_profiles b ON b.id=u.id
            LEFT JOIN LATERAL (SELECT reason,created_at FROM audit_logs
                WHERE target_type='user' AND target_id=u.id AND action='USER_SUSPENDED'
                ORDER BY created_at DESC,id DESC LIMIT 1) a ON u.status='suspended'
            WHERE u.id=?""", { rs, _ ->
            AdminUserDetail(rs.getObject("id", UUID::class.java), rs.getString("email"), rs.getString("role"), rs.getString("status"),
                rs.getString("display_name"), rs.getString("store_name"), rs.getString("owner_name"), rs.getString("pickup_location"), rs.getString("ktp_status"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("suspension_reason"), rs.getTimestamp("suspended_at")?.toInstant(), 0, emptyList())
        }, userId).singleOrNull() ?: missing("Pengguna tidak ditemukan.")
        val from = """ FROM refunds r JOIN orders o ON o.id=r.order_id
            WHERE (r.buyer_id=? OR o.farmer_id=?) AND r.status IN ('diajukan','disetujui','menunggu_konfirmasi_dana','dieskalasi')"""
        val total = db.queryForObject("SELECT count(*)$from", Long::class.java, userId, userId)!!
        val refunds = db.query("""SELECT r.id,r.order_id,r.status,r.created_at $from
            ORDER BY r.created_at DESC,r.id DESC LIMIT 5""", { rs, _ ->
            AdminUserRefundSummary(rs.getObject("id", UUID::class.java), rs.getObject("order_id", UUID::class.java),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant())
        }, userId, userId)
        return user.copy(activeRefundCount = total, recentActiveRefunds = refunds)
    }

    @Transactional(readOnly = true)
    fun settings(actor: UUID): AdminSettings {
        actors.require(actor, "admin")
        return db.query("""SELECT r.revision,r.updated_by,r.updated_at,u.email,u.display_name,
            l.value AS limit_value,c.value AS cs_value FROM admin_settings_revision r
            LEFT JOIN users u ON u.id=r.updated_by LEFT JOIN settings l ON l.key='transaction_limit'
            LEFT JOIN settings c ON c.key='cs_whatsapp' WHERE r.id=1""", { rs, _ ->
            val limit = rs.getString("limit_value")?.toLongOrNull()?.takeIf { it > 0 }
                ?: conflict("Limit transaksi belum dikonfigurasi dengan benar.")
            AdminSettings(limit, rs.getString("cs_value"), rs.getLong("revision"), rs.getObject("updated_by", UUID::class.java),
                rs.getString("email"), rs.getTimestamp("updated_at")?.toInstant(), rs.getString("display_name"))
        }).singleOrNull() ?: conflict("Versi pengaturan belum tersedia.")
    }

    /** The admin form saves both inputs together; the observed values prevent stale form overwrites. */
    @Transactional
    fun updateSettings(actor: UUID, request: JsonNode): AdminSettings {
        actors.requireLocked(actor, "admin")
        val fields = setOf("newLimit", "csWhatsapp", "expectedTransactionLimit", "expectedCsWhatsapp", "reason")
        if (!request.isObject || request.fieldNames().asSequence().any { it !in fields }) invalid("Field pengaturan tidak valid.")
        fun positiveLong(name: String): Long {
            val value = request.get(name)
            if (value == null || !value.isIntegralNumber || !value.canConvertToLong() || value.longValue() <= 0) {
                invalid("$name harus berupa bilangan bulat rupiah lebih besar dari nol.")
            }
            return value.longValue()
        }
        val newLimit = positiveLong("newLimit")
        val expectedLimit = positiveLong("expectedTransactionLimit")
        val phoneNode = request.get("csWhatsapp")
        if (phoneNode == null || !phoneNode.isTextual) invalid("Nomor WhatsApp CS wajib berupa teks.")
        val rawPhone = phoneNode.textValue().trim()
        if (rawPhone.length > 50 || !Regex("\\+[1-9][0-9 ]*").matches(rawPhone)) {
            invalid("Nomor WhatsApp CS harus diawali + dan kode negara, diikuti angka.")
        }
        val phone = rawPhone.replace(" ", "")
        if (!Regex("\\+[1-9][0-9]{7,14}").matches(phone)) invalid("Nomor WhatsApp CS harus berisi 8–15 digit termasuk kode negara.")
        if (!request.has("expectedCsWhatsapp")) invalid("Nomor WhatsApp sebelumnya wajib disertakan; gunakan null jika belum ada.")
        val expectedNode = request.get("expectedCsWhatsapp")
        if (!expectedNode.isNull && !expectedNode.isTextual) invalid("Nomor WhatsApp sebelumnya tidak valid.")
        val expectedPhone = if (expectedNode.isNull) null else expectedNode.textValue()
        if (expectedPhone != null && expectedPhone.length > 50) invalid("Nomor WhatsApp sebelumnya tidak valid.")
        val reasonNode = request.get("reason")
        if (reasonNode != null && !reasonNode.isNull && !reasonNode.isTextual) invalid("Alasan perubahan harus berupa teks.")
        val reason = reasonNode?.takeUnless { it.isNull }?.textValue()?.trim()?.takeIf { it.isNotBlank() }
        if (reason != null && reason.length > 1000) invalid("Alasan perubahan maksimal 1000 karakter.")

        // The limit row serializes both this form and the existing limit-only endpoint,
        // including the first creation of the optional WhatsApp setting.
        val oldText = db.query("SELECT value FROM settings WHERE key='transaction_limit' FOR UPDATE", { rs, _ -> rs.getString(1) }).singleOrNull()
            ?: conflict("Limit transaksi belum dikonfigurasi.")
        val oldLimit = oldText.toLongOrNull()?.takeIf { it > 0 } ?: conflict("Limit transaksi belum dikonfigurasi dengan benar.")
        val oldPhone = db.query("SELECT value FROM settings WHERE key='cs_whatsapp'", { rs, _ -> rs.getString(1) }).singleOrNull()
        if (oldLimit == newLimit && oldPhone == phone) return settings(actor)
        if (oldLimit != expectedLimit || oldPhone != expectedPhone) conflict("Pengaturan sudah berubah. Muat ulang sebelum menyimpan.")
        if (oldLimit != newLimit && reason == null) invalid("Alasan perubahan limit wajib diisi.")
        val now = java.sql.Timestamp.from(clock.instant())
        val revision = nextSettingsVersion(actor, now)
        if (oldLimit != newLimit) {
            db.update("UPDATE settings SET value=?,updated_by=?,updated_at=? WHERE key='transaction_limit'", newLimit.toString(), actor, now)
            db.update("""INSERT INTO audit_logs(id,actor_id,actor_role,action,target_type,target_id,old_values,new_values,reason,created_at,settings_revision)
                VALUES (?,?,'admin','TRANSACTION_LIMIT_CHANGED','setting',NULL,?,?,?,?,?)""", UUID.randomUUID(), actor, oldText, newLimit.toString(), reason, now, revision)
        }
        if (oldPhone != phone) {
            db.update("""INSERT INTO settings(key,value,updated_by,updated_at) VALUES ('cs_whatsapp',?,?,?)
                ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value,updated_by=EXCLUDED.updated_by,updated_at=EXCLUDED.updated_at""", phone, actor, now)
            db.update("""INSERT INTO audit_logs(id,actor_id,actor_role,action,target_type,target_id,old_values,new_values,reason,created_at,settings_revision)
                VALUES (?,?,'admin','CS_WHATSAPP_CHANGED','setting',NULL,?,?,?,?,?)""", UUID.randomUUID(), actor, oldPhone, phone, reason, now, revision)
        }
        return settings(actor)
    }

    @Transactional
    fun updateLimit(actor: UUID, request: JsonNode): AdminSettings {
        actors.requireLocked(actor, "admin")
        if (!request.isObject || request.fieldNames().asSequence().any { it !in setOf("newLimit", "reason") }) invalid("Field pengaturan tidak valid.")
        val value = request.get("newLimit")
        if (value == null || !value.isIntegralNumber || !value.canConvertToLong() || value.longValue() <= 0) invalid("Limit harus berupa bilangan bulat rupiah lebih besar dari nol.")
        val reasonNode = request.get("reason")
        if (reasonNode == null || !reasonNode.isTextual) invalid("Alasan perubahan wajib diisi.")
        val reason = reasonNode.textValue().trim()
        if (reason.isBlank() || reason.length > 1000) invalid("Alasan perubahan wajib diisi, maksimal 1000 karakter.")
        val newLimit = value.longValue()
        val old = db.query("SELECT value FROM settings WHERE key='transaction_limit' FOR UPDATE", { rs, _ -> rs.getString(1) }).singleOrNull()
            ?: conflict("Limit transaksi belum dikonfigurasi.")
        val oldLimit = old.toLongOrNull()?.takeIf { it > 0 } ?: conflict("Limit transaksi belum dikonfigurasi dengan benar.")
        if (oldLimit != newLimit) {
            val now = java.sql.Timestamp.from(clock.instant())
            val revision = nextSettingsVersion(actor, now)
            db.update("UPDATE settings SET value=?,updated_by=?,updated_at=? WHERE key='transaction_limit'", newLimit.toString(), actor, now)
            db.update("""INSERT INTO audit_logs(id,actor_id,actor_role,action,target_type,target_id,old_values,new_values,reason,created_at,settings_revision)
                VALUES (?,?,'admin','TRANSACTION_LIMIT_CHANGED','setting',NULL,?,?,?,?,?)""", UUID.randomUUID(), actor, old, newLimit.toString(), reason, now, revision)
        }
        return settings(actor)
    }

    @Transactional(readOnly = true)
    fun auditLogs(actor: UUID, page: Int, size: Int, action: String?): PageDTO<AdminAuditItem> {
        actors.require(actor, "admin")
        validatePage(page, size)
        if (action != null && (action.isBlank() || action.length > 100)) invalid("Filter aksi tidak valid.")
        val where = if (action == null) "" else " WHERE a.action=?"
        val args: List<Any> = if (action == null) emptyList() else listOf(action)
        val total = db.queryForObject("SELECT count(*) FROM audit_logs a$where", Long::class.java, *args.toTypedArray())!!
        val items = db.query("""SELECT a.id,a.actor_id,a.actor_role,a.action,a.target_type,a.target_id,a.old_values,a.new_values,a.reason,a.created_at,
            a.settings_revision,u.email AS actor_email,u.display_name AS actor_name FROM audit_logs a LEFT JOIN users u ON u.id=a.actor_id
            $where ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET ?""", { rs, _ ->
            AdminAuditItem(rs.getObject("id", UUID::class.java), rs.getObject("actor_id", UUID::class.java), rs.getString("actor_role"),
                rs.getString("action"), rs.getString("target_type"), rs.getObject("target_id", UUID::class.java), rs.getString("old_values"),
                rs.getString("new_values"), rs.getString("reason"), rs.getTimestamp("created_at").toInstant(), rs.getString("actor_email"),
                rs.getObject("settings_revision", java.lang.Long::class.java)?.toLong(), rs.getString("actor_name"))
        }, *(args + listOf(size, page.toLong() * size)).toTypedArray())
        return PageDTO(items, page, size, total)
    }

    private fun validatePage(page: Int, size: Int) {
        if (page < 0 || size !in 1..100) invalid("Paginasi tidak valid.")
    }

    private fun nextSettingsVersion(actor: UUID, now: java.sql.Timestamp): Long =
        db.query("UPDATE admin_settings_revision SET revision=revision+1,updated_by=?,updated_at=? WHERE id=1 RETURNING revision",
            { rs, _ -> rs.getLong(1) }, actor, now).singleOrNull() ?: conflict("Versi pengaturan belum tersedia.")
}
