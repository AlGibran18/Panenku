package com.panenku.backend.modules.shared.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.panenku.backend.common.exception.ApiException
import com.panenku.backend.common.exception.conflict
import com.panenku.backend.common.exception.forbidden
import com.panenku.backend.common.exception.invalid
import com.panenku.backend.common.exception.missing
import com.panenku.backend.common.security.ActorService
import com.panenku.backend.common.storage.LocalPrivateImageStore
import com.panenku.backend.modules.shared.entity.FarmerPaymentMethod
import com.panenku.backend.modules.shared.repository.FarmerProfileRepository
import com.panenku.backend.modules.shared.repository.PaymentMethodRepository
import com.panenku.backend.modules.shared.repository.UserRepository
import com.panenku.backend.modules.pembeli.repository.OrderRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class PaymentOptionDTO(
    val id: UUID, val methodType: String, val accountHolderName: String,
    val bankName: String?, val accountNumberLast4: String?, val version: String
)
data class PaymentDestinationDTO(
    val methodType: String, val accountHolderName: String, val bankName: String?,
    val accountNumber: String?, val hasQrisImage: Boolean
)
data class OrderPaymentOptionDTO(
    val id: UUID, val methodType: String, val accountHolderName: String,
    val bankName: String?, val accountNumber: String?, val hasQrisImage: Boolean
)
data class ChoosePaymentRequest(val paymentMethodId: UUID)
data class PaymentImage(val contentType: String, val bytes: ByteArray)
data class SelectedPayment(val option: PaymentOptionDTO, val accountNumber: String?, val image: PaymentImage?)

@Service
class PaymentDestinationService(
    private val actors: ActorService, private val users: UserRepository,
    private val farmers: FarmerProfileRepository, private val methods: PaymentMethodRepository,
    private val orders: OrderRepository, private val db: JdbcTemplate, private val mapper: ObjectMapper,
    private val clock: Clock, private val scheduleGuard: OrderScheduleGuard,
    @Value("\${panenku.storage.local-dir:}") private val storageDirectory: String,
    @Value("\${panenku.jwt.secret}") private val versionSecret: String
) {
    @Transactional(readOnly = true)
    fun listForBuyer(actor: UUID, farmerId: UUID): List<PaymentOptionDTO> {
        actors.require(actor, "pembeli")
        val farmer = users.findById(farmerId).orElse(null) ?: missing("Petani tidak ditemukan.")
        val profile = farmers.findById(farmerId).orElse(null) ?: missing("Profil petani tidak ditemukan.")
        if (farmer.role != "petani" || farmer.status != "active" || profile.ktpStatus != "disetujui") {
            conflict("Petani tidak tersedia untuk pembelian baru.")
        }
        return methods.findAllByFarmerId(farmerId).filter { it.complete() }.mapNotNull {
            // QRIS is hidden if its private image cannot be read; bank options remain usable.
            try { selected(it).option } catch (ex: ApiException) {
                if (it.methodType == "qris" && ex.status == HttpStatus.SERVICE_UNAVAILABLE) null else throw ex
            }
        }.sortedBy { it.id }
    }

    /** Lock scalar-discovered IDs in order, then capture all usable destinations. */
    fun lockOptions(farmerId: UUID): List<SelectedPayment> {
        val available = methods.idsByFarmerId(farmerId).distinct().sorted().mapNotNull { id ->
            val method = methods.lockById(id) ?: return@mapNotNull null
            if (method.farmerId != farmerId || !method.complete()) return@mapNotNull null
            try { selected(method) } catch (ex: ApiException) {
                if (method.methodType == "qris" && ex.status == HttpStatus.SERVICE_UNAVAILABLE) null else throw ex
            }
        }
        if (available.isEmpty()) conflict("Metode pembayaran petani belum tersedia.")
        return available
    }

    fun saveOptions(orderId: UUID, options: List<SelectedPayment>) {
        options.forEach { selected ->
            val option = selected.option
            db.update("""INSERT INTO order_payment_options
            (order_id,method_id,method_type,account_holder_name,bank_name,account_number,qris_image,qris_content_type)
            VALUES (?,?,?,?,?,?,?,?)""", orderId, option.id, option.methodType, option.accountHolderName,
            option.bankName, selected.accountNumber, selected.image?.bytes, selected.image?.contentType)
        }
    }

    @Transactional(readOnly = true)
    fun optionsForBuyer(actor: UUID, orderId: UUID): List<OrderPaymentOptionDTO> {
        ownedOrder(actor, orderId)
        val options = db.query("""SELECT method_id,method_type,account_holder_name,bank_name,account_number,qris_image IS NOT NULL AS has_qris
            FROM order_payment_options WHERE order_id=? ORDER BY method_id""", { rs, _ ->
            OrderPaymentOptionDTO(rs.getObject("method_id", UUID::class.java), rs.getString("method_type"),
                rs.getString("account_holder_name"), rs.getString("bank_name"), rs.getString("account_number"), rs.getBoolean("has_qris"))
        }, orderId)
        if (options.isNotEmpty()) return options
        // A pre-V9 order may already have a single immutable destination.
        return db.query("""SELECT method_id,method_type,account_holder_name,bank_name,account_number,qris_image IS NOT NULL AS has_qris
            FROM order_payment_snapshots WHERE order_id=?""", { rs, _ ->
            OrderPaymentOptionDTO(rs.getObject("method_id", UUID::class.java), rs.getString("method_type"),
                rs.getString("account_holder_name"), rs.getString("bank_name"), rs.getString("account_number"), rs.getBoolean("has_qris"))
        }, orderId)
    }

    @Transactional
    fun chooseForBuyer(actor: UUID, orderId: UUID, methodId: UUID): PaymentDestinationDTO {
        actors.require(actor, "pembeli")
        val order = orders.lockById(orderId) ?: missing("Pesanan tidak ditemukan.")
        checkBuyerAccess(actor, order)
        if (order.orderStatus != "menunggu_pembayaran" || order.paymentStatus !in setOf("belum_dibayar", "bukti_ditolak")) {
            conflict("Metode pembayaran tidak dapat dipilih pada status pesanan ini.")
        }
        val fixed = db.query("SELECT method_id FROM order_payment_snapshots WHERE order_id=?",
            { rs, _ -> rs.getObject("method_id", UUID::class.java) }, orderId).singleOrNull()
        if (fixed != null) {
            if (fixed != methodId) conflict("Metode pembayaran sudah terkunci oleh bukti pembayaran.")
            return destinationForBuyer(actor, orderId)
        }
        val exists = db.queryForObject("SELECT count(*) FROM order_payment_options WHERE order_id=? AND method_id=?",
            Int::class.java, orderId, methodId) == 1
        if (!exists) conflict("Metode pembayaran tidak tersedia pada pesanan ini.")
        db.update("""INSERT INTO order_payment_selections(order_id,method_id) VALUES (?,?)
            ON CONFLICT(order_id) DO UPDATE SET method_id=EXCLUDED.method_id""", orderId, methodId)
        return destinationForBuyer(actor, orderId)
    }

    /** Called while the order is locked and inside the proof-upload transaction. */
    fun finalizeSelection(orderId: UUID) {
        if (db.queryForObject("SELECT count(*) FROM order_payment_snapshots WHERE order_id=?", Int::class.java, orderId) == 1) return
        val inserted = db.update("""INSERT INTO order_payment_snapshots
            (order_id,method_id,method_type,account_holder_name,bank_name,account_number,qris_image,qris_content_type)
            SELECT o.order_id,o.method_id,o.method_type,o.account_holder_name,o.bank_name,o.account_number,o.qris_image,o.qris_content_type
            FROM order_payment_options o JOIN order_payment_selections s ON s.order_id=o.order_id AND s.method_id=o.method_id
            WHERE o.order_id=?""", orderId)
        if (inserted != 1) conflict("Pilih metode pembayaran pada pesanan sebelum mengunggah bukti.")
    }

    @Transactional(readOnly = true)
    fun destinationForBuyer(actor: UUID, orderId: UUID): PaymentDestinationDTO {
        val order = ownedOrder(actor, orderId)
        return destinationForOrder(order.id)
    }

    @Transactional(readOnly = true)
    fun destinationForFarmer(actor: UUID, orderId: UUID): PaymentDestinationDTO {
        actors.require(actor, "petani")
        val order = orders.findById(orderId).orElse(null) ?: missing("Pesanan tidak ditemukan.")
        if (order.farmerId != actor) forbidden("Pesanan bukan milik petani ini.")
        return destinationForOrder(order.id)
    }

    private fun destinationForOrder(orderId: UUID): PaymentDestinationDTO {
        val rows = db.query("""SELECT method_type,account_holder_name,bank_name,account_number,qris_image IS NOT NULL AS has_qris
            FROM order_payment_snapshots WHERE order_id=?
            UNION ALL
            SELECT o.method_type,o.account_holder_name,o.bank_name,o.account_number,o.qris_image IS NOT NULL AS has_qris
            FROM order_payment_options o JOIN order_payment_selections s ON s.order_id=o.order_id AND s.method_id=o.method_id
            WHERE o.order_id=? AND NOT EXISTS (SELECT 1 FROM order_payment_snapshots WHERE order_id=?)""", { rs, _ ->
            PaymentDestinationDTO(rs.getString("method_type"), rs.getString("account_holder_name"),
                rs.getString("bank_name"), rs.getString("account_number"), rs.getBoolean("has_qris"))
        }, orderId, orderId, orderId)
        return rows.singleOrNull() ?: missing("Metode pembayaran belum dipilih atau pesanan lama tidak memiliki snapshot.")
    }

    @Transactional(readOnly = true)
    fun imageForBuyer(actor: UUID, orderId: UUID): PaymentImage {
        val order = ownedOrder(actor, orderId)
        val rows = db.query("""SELECT qris_content_type,qris_image FROM order_payment_snapshots WHERE order_id=? AND method_type='qris'
            UNION ALL SELECT o.qris_content_type,o.qris_image FROM order_payment_options o
            JOIN order_payment_selections s ON s.order_id=o.order_id AND s.method_id=o.method_id
            WHERE o.order_id=? AND o.method_type='qris' AND NOT EXISTS (SELECT 1 FROM order_payment_snapshots WHERE order_id=?)""",
            { rs, _ -> PaymentImage(rs.getString("qris_content_type"), rs.getBytes("qris_image")) }, order.id, order.id, order.id)
        return rows.singleOrNull() ?: missing("QRIS pesanan tidak ditemukan.")
    }

    @Transactional(readOnly = true)
    fun optionImageForBuyer(actor: UUID, orderId: UUID, methodId: UUID): PaymentImage {
        ownedOrder(actor, orderId)
        val rows = db.query("""SELECT qris_content_type,qris_image FROM order_payment_options
            WHERE order_id=? AND method_id=? AND method_type='qris'
            UNION ALL SELECT qris_content_type,qris_image FROM order_payment_snapshots
            WHERE order_id=? AND method_id=? AND method_type='qris'
              AND NOT EXISTS (SELECT 1 FROM order_payment_options WHERE order_id=?)""",
            { rs, _ -> PaymentImage(rs.getString("qris_content_type"), rs.getBytes("qris_image")) },
            orderId, methodId, orderId, methodId, orderId)
        return rows.singleOrNull() ?: missing("QRIS pesanan tidak ditemukan.")
    }

    private fun ownedOrder(actor: UUID, orderId: UUID): com.panenku.backend.modules.pembeli.entity.Order {
        actors.require(actor, "pembeli")
        val order = orders.findById(orderId).orElse(null) ?: missing("Pesanan tidak ditemukan.")
        checkBuyerAccess(actor, order)
        return order
    }

    private fun checkBuyerAccess(actor: UUID, order: com.panenku.backend.modules.pembeli.entity.Order) {
        if (order.buyerId != actor) forbidden("Pesanan bukan milik pembeli ini.")
        if (order.orderStatus in setOf("menunggu_petani", "ditolak", "dibatalkan", "kedaluwarsa")) {
            conflict("Tujuan pembayaran belum tersedia untuk status pesanan ini.")
        }
        scheduleGuard.canPay(order)
        if (order.orderStatus == "menunggu_pembayaran" && !order.expiresAt.isAfter(Instant.now(clock))) {
            conflict("Batas waktu pembayaran sudah terlewati.")
        }
    }

    private fun selected(method: FarmerPaymentMethod): SelectedPayment {
        val image = if (method.methodType == "qris") readQris(method.qrisImagePath!!) else null
        // Include image bytes in the version so a changed QRIS must be confirmed again.
        val versionInput = listOf(method.id.toString(), method.methodType, method.accountHolderName,
            method.bankName, method.accountNumber, image?.contentType, image?.bytes?.let(::sha256))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(versionSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val version = mac.doFinal(mapper.writeValueAsBytes(versionInput)).joinToString("") { "%02x".format(it) }
        val isBank = method.methodType == "bank_transfer"
        return SelectedPayment(PaymentOptionDTO(method.id, method.methodType, method.accountHolderName!!,
            if (isBank) method.bankName else null, if (isBank) method.accountNumber?.takeLast(4) else null,
            version), if (isBank) method.accountNumber else null, image)
    }

    private fun readQris(key: String): PaymentImage {
        if (storageDirectory.isBlank()) unavailable()
        try {
            val bytes = LocalPrivateImageStore(Path.of(storageDirectory)).read(key)
            val type = if (key.endsWith(".png")) "image/png" else "image/jpeg"
            return PaymentImage(type, bytes)
        } catch (ex: Exception) {
            unavailable()
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun unavailable(): Nothing = throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_NOT_CONFIGURED",
        "Gambar QRIS privat belum tersedia.")
}
