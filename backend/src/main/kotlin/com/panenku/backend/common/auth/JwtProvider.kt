package com.panenku.backend.common.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Date

/**
 * Penyedia JWT untuk generate dan verifikasi token login.
 * Token berisi email pengguna sebagai subject.
 */
@Component
class JwtProvider {

    @Value("\${app.jwt.secret}")
    private lateinit var secret: String

    @Value("\${app.jwt.expiration:86400000}")
    private lateinit var expirationMs: Long

    private fun getKey(): javax.crypto.SecretKey {
        return Keys.hmacShaKeyFor(secret.toByteArray())
    }

    /**
     * Membuat token JWT baru untuk username (email) tertentu.
     * Token berlaku selama [expirationMs] milidetik (default 24 jam).
     */
    fun generateToken(username: String): String {
        val now = Date()
        val expiry = Date(now.time + expirationMs)

        return Jwts.builder()
            .subject(username)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(getKey())
            .compact()
    }

    /**
     * Memverifikasi apakah token masih valid dan tidak kadaluwarsa.
     */
    fun validateToken(token: String): Boolean {
        return try {
            Jwts.parser().verifyWith(getKey()).build().parseSignedClaims(token)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Mengambil email (subject) dari token.
     */
    fun extractUsername(token: String): String {
        return Jwts.parser().verifyWith(getKey()).build()
            .parseSignedClaims(token).payload.subject
    }
}