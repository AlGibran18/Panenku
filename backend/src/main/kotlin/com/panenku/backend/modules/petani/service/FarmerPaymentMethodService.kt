package com.panenku.backend.modules.petani.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.panenku.backend.common.exception.ApiException
import com.panenku.backend.common.exception.conflict
import com.panenku.backend.common.exception.forbidden
import com.panenku.backend.common.exception.invalid
import com.panenku.backend.common.exception.missing
import com.panenku.backend.common.security.ActorService
import com.panenku.backend.common.storage.LocalPrivateImageStore
import com.panenku.backend.modules.shared.repository.PaymentMethodRepository
import com.panenku.backend.modules.shared.service.PaymentImage
import com.panenku.backend.modules.petani.dto.BankPaymentMethodRequest
import com.panenku.backend.modules.petani.dto.BankPaymentMethodDetailDTO
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

data class FarmerPaymentMethodDTO(
    val id: UUID, val methodType: String, val accountHolderName: String?,
    val bankName: String?, val accountNumberLast4: String?, val hasQrisImage: Boolean
)

@Service
class FarmerPaymentMethodService(
    private val actors: ActorService, private val methods: PaymentMethodRepository,
    private val db: JdbcTemplate, private val mapper: ObjectMapper,
    @Value("\${panenku.storage.local-dir:}") private val directory: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun createBank(actor: UUID, key: String, request: BankPaymentMethodRequest): BankPaymentMethodDetailDTO {
        if (!Regex("[A-Za-z0-9_-]{1,128}").matches(key)) invalid("Idempotency-Key wajib, maksimal 128 karakter huruf/angka/_/-.")
        val clean = validateBank(request)
        actors.requireLocked(actor, "petani")
        val hash = sha256(mapper.writeValueAsBytes(listOf(clean.bankName, clean.accountNumber, clean.accountHolderName)))
        val previous = db.query("SELECT request_hash, payment_method_id FROM bank_method_requests WHERE farmer_id=? AND idempotency_key=?",
            { rs, _ -> rs.getString("request_hash") to rs.getObject("payment_method_id", UUID::class.java) }, actor, key).singleOrNull()
        if (previous != null) {
            if (previous.first != hash) conflict("Idempotency-Key sudah digunakan untuk rekening berbeda.")
            val existing = methods.findById(previous.second).orElse(null) ?: conflict("Metode pembayaran tidak ditemukan.")
            if (existing.farmerId != actor || existing.methodType != "bank_transfer") conflict("Metode pembayaran tidak sesuai.")
            if (!existing.isActive) conflict("Rekening dari permintaan ini sudah dihapus. Gunakan Idempotency-Key baru.")
            return bankDto(existing)
        }
        val id = UUID.randomUUID()
        db.update("""INSERT INTO farmer_payment_methods(id,farmer_id,method_type,bank_name,account_number,account_holder_name)
            VALUES (?,?,'bank_transfer',?,?,?)""", id, actor, clean.bankName, clean.accountNumber, clean.accountHolderName)
        db.update("INSERT INTO bank_method_requests(farmer_id,idempotency_key,request_hash,payment_method_id) VALUES (?,?,?,?)",
            actor, key, hash, id)
        return BankPaymentMethodDetailDTO(id, "bank_transfer", clean.bankName, clean.accountNumber, clean.accountHolderName)
    }

    @Transactional(readOnly = true)
    fun bankDetail(actor: UUID, id: UUID): BankPaymentMethodDetailDTO {
        actors.require(actor, "petani")
        val method = methods.findById(id).orElse(null) ?: missing("Metode pembayaran tidak ditemukan.")
        if (method.farmerId != actor) forbidden("Metode pembayaran bukan milik petani ini.")
        if (method.methodType != "bank_transfer" || !method.isActive) missing("Rekening bank tidak ditemukan.")
        return bankDto(method)
    }

    @Transactional
    fun updateBank(actor: UUID, id: UUID, request: BankPaymentMethodRequest): BankPaymentMethodDetailDTO {
        val clean = validateBank(request)
        actors.requireLocked(actor, "petani")
        // Discover the owner before locking the method; two farmers cannot deadlock by
        // simultaneously attempting to update each other's records.
        val owner = methods.findFarmerId(id) ?: missing("Metode pembayaran tidak ditemukan.")
        if (owner != actor) forbidden("Metode pembayaran bukan milik petani ini.")
        val method = methods.lockForUpdate(id) ?: missing("Metode pembayaran tidak ditemukan.")
        if (method.farmerId != actor) forbidden("Metode pembayaran bukan milik petani ini.")
        if (method.methodType != "bank_transfer") invalid("Metode ini bukan rekening bank.")
        if (!method.isActive) missing("Rekening bank tidak ditemukan.")
        if (method.bankName != clean.bankName || method.accountNumber != clean.accountNumber || method.accountHolderName != clean.accountHolderName) {
            method.bankName = clean.bankName
            method.accountNumber = clean.accountNumber
            method.accountHolderName = clean.accountHolderName
            methods.flush()
            db.update("UPDATE farmer_payment_methods SET updated_at=now() WHERE id=?", id)
        }
        return bankDto(method)
    }

    @Transactional
    fun deleteBank(actor: UUID, id: UUID) {
        actors.requireLocked(actor, "petani")
        val owner = methods.findFarmerId(id) ?: missing("Metode pembayaran tidak ditemukan.")
        if (owner != actor) forbidden("Metode pembayaran bukan milik petani ini.")
        val method = methods.lockForUpdate(id) ?: missing("Metode pembayaran tidak ditemukan.")
        if (method.farmerId != actor) forbidden("Metode pembayaran bukan milik petani ini.")
        if (method.methodType != "bank_transfer") missing("Rekening bank tidak ditemukan.")
        if (!method.isActive) return
        requireAlternativeForActiveProducts(actor, id)
        method.isActive = false
        methods.flush()
        db.update("UPDATE farmer_payment_methods SET updated_at=now() WHERE id=?", id)
    }

    @Transactional
    fun uploadQris(actor: UUID, key: String, holderName: String, file: MultipartFile): FarmerPaymentMethodDTO {
        if (!Regex("[A-Za-z0-9_-]{1,128}").matches(key)) invalid("Idempotency-Key wajib, maksimal 128 karakter huruf/angka/_/-.")
        val name = holderName.trim()
        if (name.isBlank() || name.length > 255) invalid("Nama penerima wajib diisi dan maksimal 255 karakter.")
        actors.requireLocked(actor, "petani")
        if (file.isEmpty || file.size > MAX_BYTES) invalid("Ukuran gambar harus antara 1 byte dan 5 MB.")
        val bytes = file.inputStream.use { it.readNBytes(MAX_BYTES + 1) }
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) invalid("Ukuran gambar harus antara 1 byte dan 5 MB.")
        val hash = sha256(mapper.writeValueAsBytes(listOf(name, sha256(bytes))))
        val existing = db.query("SELECT request_hash, payment_method_id FROM qris_upload_requests WHERE farmer_id=? AND idempotency_key=?",
            { rs, _ -> rs.getString("request_hash") to rs.getObject("payment_method_id", UUID::class.java) }, actor, key).singleOrNull()
        if (existing != null) {
            if (existing.first != hash) conflict("Idempotency-Key sudah digunakan untuk unggahan berbeda.")
            val method = methods.findById(existing.second).orElse(null) ?: conflict("Metode pembayaran tidak ditemukan.")
            if (!method.isActive) conflict("QRIS dari permintaan ini sudah dihapus. Gunakan Idempotency-Key baru.")
            readImage(method.qrisImagePath ?: unavailable())
            return dto(method)
        }

        val storage = storage()
        val imageKey = try { storage.upload(bytes) } catch (ex: ApiException) { throw ex } catch (ex: Exception) { unavailable() }
        // Filesystem and PostgreSQL do not share a transaction. Remove new files on rollback;
        // do not delete on unknown transaction outcome because the DB may have committed.
        try {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                        try { storage.delete(imageKey) } catch (ex: Exception) {
                            logger.error("Gagal membersihkan file QRIS setelah transaksi dibatalkan.")
                        }
                    }
                }
            })
        } catch (ex: Exception) {
            try { storage.delete(imageKey) } catch (cleanup: Exception) {
                logger.error("Gagal membersihkan file QRIS setelah pendaftaran transaksi gagal.")
            }
            throw ex
        }

        val id = UUID.randomUUID()
        db.update("INSERT INTO farmer_payment_methods(id,farmer_id,method_type,qris_image_path,account_holder_name) VALUES (?,?,'qris',?,?)",
            id, actor, imageKey, name)
        db.update("INSERT INTO qris_upload_requests(farmer_id,idempotency_key,request_hash,payment_method_id) VALUES (?,?,?,?)",
            actor, key, hash, id)
        return FarmerPaymentMethodDTO(id, "qris", name, null, null, true)
    }

    @Transactional
    fun updateQris(actor: UUID, id: UUID, holderName: String, file: MultipartFile): FarmerPaymentMethodDTO {
        val name = holderName.trim()
        if (name.isBlank() || name.length > 255) invalid("Nama penerima wajib diisi dan maksimal 255 karakter.")
        actors.requireLocked(actor, "petani")
        val method = lockedQris(actor, id)
        if (file.isEmpty || file.size > MAX_BYTES) invalid("Ukuran gambar harus antara 1 byte dan 5 MB.")
        val bytes = file.inputStream.use { it.readNBytes(MAX_BYTES + 1) }
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) invalid("Ukuran gambar harus antara 1 byte dan 5 MB.")
        val storage = storage()
        val oldKey = method.qrisImagePath ?: unavailable()
        val unchanged = try { storage.read(oldKey).contentEquals(bytes) } catch (_: Exception) { false }
        if (unchanged && method.accountHolderName == name) return dto(method)
        // A new immutable file is written before the DB points to it. The checkout
        // snapshot already contains its own copy of the old image.
        val newKey = try { storage.upload(bytes) } catch (ex: ApiException) { throw ex } catch (_: Exception) { unavailable() }
        try {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    val keyToDelete = when (status) {
                        TransactionSynchronization.STATUS_COMMITTED -> oldKey
                        TransactionSynchronization.STATUS_ROLLED_BACK -> newKey
                        else -> null
                    }
                    if (keyToDelete != null) try { storage.delete(keyToDelete) } catch (_: Exception) {
                        logger.error("Gagal membersihkan file QRIS setelah perubahan.")
                    }
                }
            })
        } catch (ex: Exception) {
            try { storage.delete(newKey) } catch (_: Exception) { logger.error("Gagal membersihkan file QRIS baru.") }
            throw ex
        }
        method.qrisImagePath = newKey
        method.accountHolderName = name
        methods.flush()
        db.update("UPDATE farmer_payment_methods SET updated_at=now() WHERE id=?", id)
        return dto(method)
    }

    @Transactional
    fun deleteQris(actor: UUID, id: UUID) {
        actors.requireLocked(actor, "petani")
        val method = lockedQris(actor, id, includeInactive = true)
        if (!method.isActive) return
        requireAlternativeForActiveProducts(actor, id)
        val key = method.qrisImagePath ?: unavailable()
        method.isActive = false
        methods.flush()
        db.update("UPDATE farmer_payment_methods SET updated_at=now() WHERE id=?", id)
        // Cleanup is best-effort: a failure may leave an inaccessible orphan file.
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                try { storage().delete(key) } catch (_: Exception) { logger.error("Gagal membersihkan file QRIS yang dihapus.") }
            }
        })
    }

    private fun requireAlternativeForActiveProducts(actor: UUID, id: UUID) {
        val hasActiveProducts = db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM products WHERE farmer_id=? AND publication_status='aktif')", Boolean::class.java, actor
        ) == true
        if (!hasActiveProducts) return
        val hasAlternative = methods.findAllByFarmerId(actor).any { method ->
            if (method.id == id || !method.complete()) false
            else if (method.methodType != "qris") true
            else try { readImage(method.qrisImagePath!!); true } catch (ex: ApiException) {
                if (ex.status != HttpStatus.SERVICE_UNAVAILABLE) throw ex
                false
            }
        }
        if (!hasAlternative) conflict("Tambahkan metode pembayaran lain yang tersedia atau nonaktifkan produk sebelum menghapus metode terakhir.")
    }

    private fun lockedQris(actor: UUID, id: UUID, includeInactive: Boolean = false): com.panenku.backend.modules.shared.entity.FarmerPaymentMethod {
        val owner = methods.findFarmerId(id) ?: missing("Metode pembayaran tidak ditemukan.")
        if (owner != actor) forbidden("Metode pembayaran bukan milik petani ini.")
        val method = methods.lockForUpdate(id) ?: missing("Metode pembayaran tidak ditemukan.")
        if (method.farmerId != actor) forbidden("Metode pembayaran bukan milik petani ini.")
        if (method.methodType != "qris" || (!includeInactive && !method.isActive)) missing("QRIS tidak ditemukan.")
        return method
    }

    @Transactional(readOnly = true)
    fun list(actor: UUID): List<FarmerPaymentMethodDTO> {
        actors.require(actor, "petani")
        return methods.findAllByFarmerId(actor).map(::dto).sortedBy { it.id }
    }

    @Transactional(readOnly = true)
    fun image(actor: UUID, id: UUID): PaymentImage {
        actors.require(actor, "petani")
        val method = methods.findById(id).orElse(null) ?: missing("Metode pembayaran tidak ditemukan.")
        if (method.farmerId != actor) forbidden("Metode pembayaran bukan milik petani ini.")
        if (!method.isActive || method.methodType != "qris" || method.qrisImagePath.isNullOrBlank()) missing("Gambar QRIS tidak ditemukan.")
        return readImage(method.qrisImagePath!!)
    }

    private fun dto(method: com.panenku.backend.modules.shared.entity.FarmerPaymentMethod) = FarmerPaymentMethodDTO(
        method.id, method.methodType, method.accountHolderName, method.bankName,
        method.accountNumber?.takeLast(4), method.methodType == "qris" && !method.qrisImagePath.isNullOrBlank()
    )

    private fun validateBank(request: BankPaymentMethodRequest): BankPaymentMethodRequest {
        val bank = request.bankName.trim()
        val number = request.accountNumber.trim()
        val holder = request.accountHolderName.trim()
        if (bank.isBlank() || bank.length > 100) invalid("Nama bank wajib diisi dan maksimal 100 karakter.")
        if (!Regex("[0-9]{1,50}").matches(number)) invalid("Nomor rekening harus berupa 1–50 digit.")
        if (holder.isBlank() || holder.length > 255) invalid("Nama pemilik rekening wajib diisi dan maksimal 255 karakter.")
        return BankPaymentMethodRequest(bank, number, holder)
    }

    private fun bankDto(method: com.panenku.backend.modules.shared.entity.FarmerPaymentMethod): BankPaymentMethodDetailDTO {
        if (!method.complete()) conflict("Data rekening bank belum lengkap.")
        return BankPaymentMethodDetailDTO(method.id, method.methodType, method.bankName!!, method.accountNumber!!, method.accountHolderName!!)
    }

    private fun readImage(key: String): PaymentImage {
        val bytes = try { storage().read(key) } catch (ex: Exception) { unavailable() }
        return PaymentImage(if (key.endsWith(".png")) "image/png" else "image/jpeg", bytes)
    }

    private fun storage(): LocalPrivateImageStore {
        if (directory.isBlank()) unavailable()
        return try { LocalPrivateImageStore(Path.of(directory)) } catch (ex: Exception) { unavailable() }
    }

    private fun unavailable(): Nothing = throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_NOT_CONFIGURED",
        "Penyimpanan QRIS privat belum tersedia.")

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object { const val MAX_BYTES = 5 * 1024 * 1024 }
}
