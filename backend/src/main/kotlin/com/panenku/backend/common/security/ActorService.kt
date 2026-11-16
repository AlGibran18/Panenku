package com.panenku.backend.common.security

import com.panenku.backend.common.exception.forbidden
import com.panenku.backend.modules.shared.entity.User
import com.panenku.backend.modules.shared.repository.UserRepository
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ActorService(private val accounts: UserRepository) {
    fun id(jwt: Jwt): UUID = try { UUID.fromString(jwt.subject) } catch (_: Exception) { forbidden("Subjek token tidak valid.") }

    // Check the database too: client-supplied role or a stale JWT cannot bypass suspension.
    fun require(id: UUID, role: String): User {
        val user = accounts.findById(id).orElse(null)
        if (user == null || user.role != role || user.status != "active") forbidden("Akses $role tidak diizinkan.")
        return user
    }

    fun requireAccountForSupport(id: UUID): User {
        val user = accounts.findById(id).orElse(null)
        if (user == null || user.status !in setOf("active", "suspended") || user.role !in setOf("admin", "petani", "pembeli")) {
            forbidden("Akun tidak diizinkan.")
        }
        return user
    }

    fun requireActiveForProfile(id: UUID, locked: Boolean = false): User {
        val user = if (locked) accounts.lockById(id) else accounts.findById(id).orElse(null)
        if (user == null || user.status != "active" || user.role !in setOf("pembeli", "petani", "admin")) forbidden("Profil tidak tersedia.")
        return user
    }

    /**
     * Sama seperti [require], tetapi mengunci baris user.
     * Dipakai operasi yang harus serial per akun, misalnya checkout dengan idempotency key.
     */
    fun requireLocked(id: UUID, role: String): User {
        val user = accounts.lockById(id)
        if (user == null || user.role != role || user.status != "active") forbidden("Akses $role tidak diizinkan.")
        return user
    }

    /** A suspended farmer can resolve existing refunds, but cannot start new sales. */
    fun requireFarmerForExistingTransaction(id: UUID, locked: Boolean = false): User {
        val user = if (locked) accounts.lockById(id) else accounts.findById(id).orElse(null)
        if (user == null || user.role != "petani" || user.status !in setOf("active", "suspended")) {
            forbidden("Akses petani tidak diizinkan.")
        }
        return user
    }
}
