package com.panenku.backend.modules.shared.service

import com.panenku.backend.common.auth.JwtProvider
import com.panenku.backend.common.exception.AppException
import com.panenku.backend.modules.shared.dto.LoginRequest
import com.panenku.backend.modules.shared.dto.UserDTO
import com.panenku.backend.modules.shared.repository.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

data class SessionResponse(val accessToken: String, val refreshToken: String, val refreshExpiresAt: Instant, val user: UserDTO)
data class RefreshTokenRequest(val refreshToken: String? = null)

@Service
class AuthSessionService(private val auth: AuthService, private val jwt: JwtProvider, private val users: UserRepository,
                         private val db: JdbcTemplate, private val clock: Clock) {
    private val random = SecureRandom()

    @Transactional
    fun create(request: LoginRequest): SessionResponse {
        val access = auth.login(request)
        val id = UUID.fromString(jwt.extractUserId(access))
        val user = users.lockById(id) ?: unauthorized()
        val token = token()
        val now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS)
        val expires = now.plus(30, ChronoUnit.DAYS)
        db.update("INSERT INTO auth_refresh_sessions(id,user_id,token_hash,created_at,expires_at) VALUES (?,?,?,?,?)",
            UUID.randomUUID(), id, hash(token), Timestamp.from(now), Timestamp.from(expires))
        return SessionResponse(access, token, expires, UserDTO(id, user.email, user.role, user.status))
    }

    @Transactional
    fun refresh(request: RefreshTokenRequest): SessionResponse {
        val tokenHash = validHash(request.refreshToken) ?: unauthorized()
        val userId = db.query("SELECT user_id FROM auth_refresh_sessions WHERE token_hash=?", { rs, _ -> rs.getObject(1, UUID::class.java) }, tokenHash)
            .singleOrNull() ?: unauthorized()
        val user = users.lockById(userId) ?: unauthorized()
        val row = db.query("SELECT id,expires_at,revoked_at FROM auth_refresh_sessions WHERE token_hash=? FOR UPDATE", { rs, _ ->
            Triple(rs.getObject("id", UUID::class.java), rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("revoked_at"))
        }, tokenHash).singleOrNull() ?: unauthorized()
        val now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS)
        if (row.third != null || !row.second.isAfter(now) || user.status !in setOf("active", "suspended")) unauthorized()
        val newToken = token()
        db.update("UPDATE auth_refresh_sessions SET token_hash=?,rotated_at=? WHERE id=?", hash(newToken), Timestamp.from(now), row.first)
        return SessionResponse(jwt.generateToken(userId.toString()), newToken, row.second, UserDTO(userId, user.email, user.role, user.status))
    }

    @Transactional
    fun logout(request: RefreshTokenRequest) {
        val tokenHash = validHash(request.refreshToken) ?: return
        val userId = db.query("SELECT user_id FROM auth_refresh_sessions WHERE token_hash=?", { rs, _ -> rs.getObject(1, UUID::class.java) }, tokenHash)
            .singleOrNull() ?: return
        users.lockById(userId) ?: return
        db.update("UPDATE auth_refresh_sessions SET revoked_at=? WHERE token_hash=? AND revoked_at IS NULL", Timestamp.from(Instant.now(clock)), tokenHash)
    }

    private fun validHash(value: String?): String? = value?.takeIf { Regex("[A-Za-z0-9_-]{43}").matches(it) }?.let(::hash)
    private fun token(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII))
        .joinToString("") { "%02x".format(it) }
    private fun unauthorized(): Nothing = throw AppException("Sesi tidak valid atau sudah kedaluwarsa.", HttpStatus.UNAUTHORIZED)
}
