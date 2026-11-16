package com.panenku.backend.modules.admin.service

import com.panenku.backend.common.dto.ApiResponse
import com.panenku.backend.common.dto.successResponse
import com.panenku.backend.common.exception.AppException
import com.panenku.backend.modules.admin.dto.SuspendUserRequest
import com.panenku.backend.modules.admin.repository.AuditLogRepository
import com.panenku.backend.modules.shared.entity.AuditLog
import com.panenku.backend.modules.shared.repository.UserRepository
import com.panenku.backend.common.security.ActorService
import com.panenku.backend.common.exception.invalid
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class UserManagementService(
    private val userRepository: UserRepository,
    private val auditLogRepository: AuditLogRepository,
    private val actors: ActorService
) {

    fun suspendUser(userId: UUID, adminId: UUID, request: SuspendUserRequest): ApiResponse<Unit> {
        actors.requireLocked(adminId, "admin")
        val suspend = request.suspend ?: invalid("Field suspend wajib diisi true atau false.")
        val reason = request.reason?.trim()
        if (suspend && reason.isNullOrBlank()) invalid("Alasan suspend wajib diisi.")
        if (reason != null && reason.length > 1000) invalid("Alasan maksimal 1000 karakter.")

        // Admin tidak boleh suspend dirinya sendiri
        if (userId == adminId) {
            throw AppException("Tidak dapat suspend akun sendiri", HttpStatus.BAD_REQUEST)
        }

        // Tidak boleh suspend admin lain
        val role = userRepository.roleById(userId) ?: throw AppException("Pengguna tidak ditemukan", HttpStatus.NOT_FOUND)
        if (role == "admin") {
            throw AppException("Tidak dapat suspend akun admin", HttpStatus.FORBIDDEN)
        }

        // Lock the target to serialize concurrent status changes. Check role before
        // locking so two admins cannot deadlock by targeting each other.
        val user = userRepository.lockById(userId) ?: throw AppException("Pengguna tidak ditemukan", HttpStatus.NOT_FOUND)
        if (user.role == "admin") throw AppException("Tidak dapat suspend akun admin", HttpStatus.FORBIDDEN)

        val previousStatus = user.status
        val desiredStatus = if (suspend) "suspended" else "active"
        if (previousStatus == desiredStatus) return successResponse(Unit, if (suspend) "Akun sudah disuspend" else "Akun sudah aktif")
        user.status = desiredStatus
        userRepository.save(user)

        val auditLog = AuditLog().apply {
            actorId = adminId
            actorRole = "admin"
            action = if (suspend) "USER_SUSPENDED" else "USER_RESTORED"
            targetType = "user"
            targetId = userId
            oldValues = previousStatus
            newValues = user.status
            this.reason = reason
        }
        auditLogRepository.save(auditLog)

        return successResponse(Unit, if (suspend) "Akun berhasil disuspend" else "Akun berhasil dipulihkan")
    }
}
