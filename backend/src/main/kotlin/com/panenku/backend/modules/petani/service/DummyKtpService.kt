package com.panenku.backend.modules.petani.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.panenku.backend.common.exception.ApiException
import com.panenku.backend.common.exception.conflict
import com.panenku.backend.common.exception.invalid
import com.panenku.backend.common.exception.missing
import com.panenku.backend.common.security.ActorService
import com.panenku.backend.common.storage.LocalPrivateImageStore
import com.panenku.backend.modules.shared.repository.FarmerProfileRepository
import com.panenku.backend.modules.shared.repository.UserRepository
import com.panenku.backend.modules.shared.service.PaymentImage
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
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class DummyKtpStatus(val farmerId: UUID, val applicationId: UUID?, val status: String, val submittedAt: Instant?,
    val reviewedAt: Instant?, val rejectionReason: String?, val dummyName: String?, val dummyNik: String?,
    val fileName: String?, val fileSizeBytes: Long?, val hasDocument: Boolean,
    val storeName: String, val email: String, val pickupLocation: String?, val reviewedBy: UUID?)

@Service
class DummyKtpService(private val actors: ActorService, private val farmers: FarmerProfileRepository, private val users: UserRepository,
                      private val db: JdbcTemplate, private val mapper: ObjectMapper, private val clock: Clock,
                      @Value("\${panenku.storage.local-dir:}") private val directory: String,
                      @Value("\${panenku.ktp.dummy-enabled:false}") private val enabled: Boolean) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun submit(actor: UUID, key: String, dummyName: String, dummyNik: String, file: MultipartFile): DummyKtpStatus {
        requireEnabled()
        if (!Regex("[A-Za-z0-9_-]{1,128}").matches(key)) invalid("Idempotency-Key tidak valid.")
        val name = dummyName.trim(); val nik = dummyNik.trim().uppercase(java.util.Locale.ROOT)
        if (name.isBlank() || name.length > 255) invalid("Nama dummy wajib diisi, maksimal 255 karakter.")
        if (!Regex("DUMMY-[A-Z0-9]{3,32}").matches(nik)) invalid("Gunakan ID dummy, misalnya DUMMY-0001; jangan isi NIK asli.")
        actors.requireLocked(actor, "petani")
        val profile = farmers.lockById(actor) ?: missing("Profil petani tidak ditemukan.")
        if (file.isEmpty || file.size > MAX_BYTES) invalid("Ukuran gambar harus antara 1 byte dan 5 MB.")
        val bytes = file.inputStream.use { it.readNBytes(MAX_BYTES + 1) }
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) invalid("Ukuran gambar harus antara 1 byte dan 5 MB.")
        val hash = sha256(mapper.writeValueAsBytes(listOf(name, nik, sha256(bytes))))
        val previous = db.query("SELECT request_hash,image_path FROM ktp_dummy_applications WHERE farmer_id=? AND idempotency_key=?",
            { rs, _ -> rs.getString(1) to rs.getString(2) }, actor, key).singleOrNull()
        if (previous != null) {
            if (previous.first != hash) conflict("Idempotency-Key sudah digunakan untuk pengajuan berbeda.")
            if (profile.ktpDocumentPath != previous.second) conflict("Pengajuan sudah diganti. Gunakan Idempotency-Key baru.")
            readImage(previous.second)
            return statusFor(actor)
        }
        if (profile.ktpStatus !in setOf("belum_diajukan", "ditolak")) conflict("Pengajuan hanya dapat dibuat saat belum diajukan atau ditolak.")
        val storage = storage()
        val path = try { storage.upload(bytes) } catch (ex: ApiException) { throw ex } catch (_: Exception) { unavailable() }
        val oldPath = profile.ktpDocumentPath
        try {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    val deleteKey = when (status) {
                        TransactionSynchronization.STATUS_ROLLED_BACK -> path
                        TransactionSynchronization.STATUS_COMMITTED -> oldPath
                        else -> null
                    }
                    if (deleteKey != null) try { storage.delete(deleteKey) } catch (_: Exception) { logger.error("Gagal membersihkan file KTP dummy.") }
                }
            })
        } catch (ex: Exception) {
            try { storage.delete(path) } catch (_: Exception) { logger.error("Gagal membersihkan file KTP dummy baru.") }
            throw ex
        }
        val now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS)
        val fileName = file.originalFilename?.replace('\\', '/')?.substringAfterLast('/')?.filterNot { it.isISOControl() }
            ?.trim()?.take(255)?.takeIf { it.isNotBlank() }
        db.update("""INSERT INTO ktp_dummy_applications(id,farmer_id,idempotency_key,request_hash,dummy_name,dummy_nik,image_path,file_name,file_size,submitted_at)
            VALUES (?,?,?,?,?,?,?,?,?,?)""", UUID.randomUUID(), actor, key, hash, name, nik, path, fileName, bytes.size.toLong(), Timestamp.from(now))
        profile.ktpStatus = "menunggu"; profile.ktpDocumentPath = path; profile.ktpSubmittedAt = now
        profile.ktpRejectionReason = null; profile.ktpReviewedAt = null; profile.ktpReviewedBy = null
        farmers.flush()
        return statusFor(actor)
    }

    @Transactional(readOnly = true)
    fun status(actor: UUID): DummyKtpStatus {
        requireEnabled(); actors.require(actor, "petani")
        return statusFor(actor)
    }

    @Transactional(readOnly = true)
    fun adminDetail(admin: UUID, farmer: UUID): DummyKtpStatus {
        requireEnabled(); actors.require(admin, "admin")
        return statusFor(farmer)
    }

    @Transactional(readOnly = true)
    fun adminImage(admin: UUID, farmer: UUID): PaymentImage {
        requireEnabled(); actors.require(admin, "admin")
        return currentImage(farmer)
    }

    @Transactional(readOnly = true)
    fun farmerImage(farmer: UUID): PaymentImage {
        requireEnabled(); actors.require(farmer, "petani")
        return currentImage(farmer)
    }

    private fun currentImage(farmer: UUID): PaymentImage {
        val path = farmers.findById(farmer).orElse(null)?.ktpDocumentPath ?: missing("Dokumen KTP dummy tidak ditemukan.")
        val currentDummyPath = db.query("SELECT image_path FROM ktp_dummy_applications WHERE farmer_id=? ORDER BY sequence_id DESC LIMIT 1",
            { rs, _ -> rs.getString(1) }, farmer).singleOrNull()
        if (currentDummyPath != path) missing("Dokumen KTP dummy tidak ditemukan.")
        return readImage(path)
    }

    private fun statusFor(farmer: UUID): DummyKtpStatus {
        val p = farmers.findById(farmer).orElse(null) ?: missing("Profil petani tidak ditemukan.")
        val user = users.findById(farmer).orElse(null) ?: missing("Akun petani tidak ditemukan.")
        if (user.role != "petani") missing("Akun petani tidak ditemukan.")
        val application = db.query("""SELECT id,dummy_name,dummy_nik,file_name,file_size FROM ktp_dummy_applications
            WHERE farmer_id=? ORDER BY sequence_id DESC LIMIT 1""", { rs, _ ->
            arrayOf(rs.getObject("id", UUID::class.java), rs.getString("dummy_name"), rs.getString("dummy_nik"),
                rs.getString("file_name"), rs.getLong("file_size"))
        }, farmer).singleOrNull()
        return DummyKtpStatus(farmer, application?.get(0) as UUID?, p.ktpStatus, p.ktpSubmittedAt, p.ktpReviewedAt,
            p.ktpRejectionReason, application?.get(1) as String?, application?.get(2) as String?, application?.get(3) as String?,
            application?.get(4) as Long?, !p.ktpDocumentPath.isNullOrBlank(), p.storeName, user.email, p.pickupLocation, p.ktpReviewedBy)
    }
    private fun requireEnabled() { if (!enabled) throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DUMMY_KTP_DISABLED", "Fitur KTP dummy tidak diaktifkan.") }
    private fun storage(): LocalPrivateImageStore {
        if (directory.isBlank()) unavailable()
        return try { LocalPrivateImageStore(Path.of(directory)) } catch (_: Exception) { unavailable() }
    }
    private fun readImage(key: String): PaymentImage {
        val bytes = try { storage().read(key) } catch (_: Exception) { unavailable() }
        return PaymentImage(if (key.endsWith(".png")) "image/png" else "image/jpeg", bytes)
    }
    private fun unavailable(): Nothing = throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_NOT_CONFIGURED", "Penyimpanan KTP dummy tidak tersedia.")
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private companion object { const val MAX_BYTES = 5 * 1024 * 1024 }
}
