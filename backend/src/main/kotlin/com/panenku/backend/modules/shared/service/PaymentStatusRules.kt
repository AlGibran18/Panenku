package com.panenku.backend.modules.shared.service

import com.panenku.backend.common.exception.conflict
import com.panenku.backend.common.exception.invalid
import java.time.Duration
import java.time.Instant
import java.util.Locale

/** Aturan murni F6; penyimpanan dan otorisasi ditangani oleh service pembayaran. */
object PaymentStatusRules {
    const val VERIFIKASI_PEMBAYARAN = "verifikasi_pembayaran"
    const val MENUNGGU_VERIFIKASI = "menunggu_verifikasi"
    const val BUKTI_DITOLAK = "bukti_ditolak"
    const val TERVERIFIKASI = "terverifikasi"

    // Null berarti batas waktu lama tetap tersimpan; bukan mengosongkan kolom orders.expires_at.
    data class Transition(val orderStatus: String, val paymentStatus: String, val replacementExpiresAt: Instant?)

    fun submit(orderStatus: String, paymentStatus: String, expiresAt: Instant, now: Instant): Transition {
        if (orderStatus != OrderStatusRules.MENUNGGU_PEMBAYARAN ||
            paymentStatus !in setOf("belum_dibayar", BUKTI_DITOLAK)) {
            conflict("Pesanan tidak sedang menunggu bukti pembayaran.")
        }
        if (expiresAt <= now) conflict("Batas waktu pembayaran sudah terlewati.")
        return Transition(VERIFIKASI_PEMBAYARAN, MENUNGGU_VERIFIKASI, null)
    }

    fun decide(orderStatus: String, paymentStatus: String, decision: String, reason: String?, now: Instant): Transition {
        if (orderStatus != VERIFIKASI_PEMBAYARAN || paymentStatus != MENUNGGU_VERIFIKASI) {
            conflict("Bukti pembayaran tidak sedang menunggu verifikasi.")
        }
        return when (decision.trim().lowercase(Locale.ROOT)) {
            "terima" -> Transition(OrderStatusRules.DIPROSES, TERVERIFIKASI, null)
            "tolak" -> {
                if (reason.isNullOrBlank()) invalid("Alasan penolakan bukti pembayaran wajib diisi.")
                Transition(OrderStatusRules.MENUNGGU_PEMBAYARAN, BUKTI_DITOLAK, now.plus(Duration.ofHours(24)))
            }
            else -> invalid("Keputusan harus 'terima' atau 'tolak'.")
        }
    }
}
