package com.panenku.backend

import com.panenku.backend.common.exception.ApiException
import com.panenku.backend.modules.shared.service.PaymentStatusRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.Instant

class PaymentStatusRulesTest {
    private val now = Instant.parse("2026-11-16T10:00:00Z")

    @Test
    fun `unggah bukti menunggu verifikasi bukan langsung lunas`() {
        for (status in listOf("belum_dibayar", "bukti_ditolak")) {
            val result = PaymentStatusRules.submit("menunggu_pembayaran", status, now.plusSeconds(1), now)
            assertEquals("verifikasi_pembayaran", result.orderStatus)
            assertEquals("menunggu_verifikasi", result.paymentStatus)
            assertNull(result.replacementExpiresAt)
        }
    }

    @Test
    fun `unggah ditolak ketika kedaluwarsa atau status tidak sesuai`() {
        val expired = assertThrows(ApiException::class.java) {
            PaymentStatusRules.submit("menunggu_pembayaran", "belum_dibayar", now, now)
        }
        assertEquals(HttpStatus.CONFLICT, expired.status)
        assertThrows(ApiException::class.java) {
            PaymentStatusRules.submit("verifikasi_pembayaran", "menunggu_verifikasi", now.plusSeconds(1), now)
        }
    }

    @Test
    fun `petani menerima bukti dan pesanan diproses`() {
        val result = PaymentStatusRules.decide("verifikasi_pembayaran", "menunggu_verifikasi", " TERIMA ", null, now)
        assertEquals("diproses", result.orderStatus)
        assertEquals("terverifikasi", result.paymentStatus)
        assertNull(result.replacementExpiresAt)
    }

    @Test
    fun `penolakan butuh alasan dan memberi waktu baru 24 jam`() {
        val error = assertThrows(ApiException::class.java) {
            PaymentStatusRules.decide("verifikasi_pembayaran", "menunggu_verifikasi", "tolak", " ", now)
        }
        assertEquals(HttpStatus.BAD_REQUEST, error.status)
        val result = PaymentStatusRules.decide("verifikasi_pembayaran", "menunggu_verifikasi", "tolak", "Bukti tidak jelas", now)
        assertEquals("menunggu_pembayaran", result.orderStatus)
        assertEquals("bukti_ditolak", result.paymentStatus)
        assertEquals(now.plusSeconds(86400), result.replacementExpiresAt)
    }

    @Test
    fun `keputusan ganda dan keputusan tidak dikenal ditolak`() {
        val repeated = assertThrows(ApiException::class.java) {
            PaymentStatusRules.decide("diproses", "terverifikasi", "terima", null, now)
        }
        assertEquals(HttpStatus.CONFLICT, repeated.status)
        val invalid = assertThrows(ApiException::class.java) {
            PaymentStatusRules.decide("verifikasi_pembayaran", "menunggu_verifikasi", "mungkin", null, now)
        }
        assertEquals(HttpStatus.BAD_REQUEST, invalid.status)
    }
}
