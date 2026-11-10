package com.panenku.backend.scheduler

import com.panenku.backend.modules.pembeli.repository.OrderRepository
import com.panenku.backend.modules.pembeli.repository.StockReservationRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Background job yang berjalan secara berkala untuk mengecek pesanan kedaluwarsa.
 * Pesanan yang melewati batas 24 jam akan ditandai kedaluwarsa dan stok dicadangkan dilepas.
 */
@Component
class ExpirationJob(
    private val orderRepository: OrderRepository,
    private val stockReservationRepository: StockReservationRepository
) {

    /**
     * Berjalan setiap 5 menit.
     * Mengecek pesanan yang expires_at sudah lewat tetapi status masih belum berubah.
     */
    @Scheduled(fixedDelayString = "\${scheduler.expiration.fixedDelay:300000}") // 5 menit
    @Transactional
    fun expireOverdueOrders() {
        val now = java.time.LocalDateTime.now()

        val expiredOrders = orderRepository.findExpiredOrdersBefore(now)

        expiredOrders.forEach { order ->
            order.orderStatus = "kedaluwarsa"
            orderRepository.save(order)

            // Lepaskan cadangan stok
            stockReservationRepository.findByOrderIdAndStatus(order.id!!, "active")
                .forEach { reservation ->
                    reservation.status = "released"
                    reservation.releasedAt = now
                    stockReservationRepository.save(reservation)
                }
        }
    }
}