package com.panenku.backend.modules.pembeli.repository

import com.panenku.backend.modules.pembeli.entity.*
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CartRepository : JpaRepository<CartItem, UUID> {
    fun findAllByBuyerIdOrderById(buyerId: UUID): List<CartItem>
    fun findByBuyerIdAndProductId(buyerId: UUID, productId: UUID): CartItem?
}
interface OrderRepository : JpaRepository<Order, UUID> {
    fun findByBuyerIdAndIdempotencyKey(buyerId: UUID, idempotencyKey: String): Order?
}
interface OrderItemRepository : JpaRepository<OrderItem, UUID> {
    fun findAllByOrderIdOrderByProductId(orderId: UUID): List<OrderItem>
}
interface StockReservationRepository : JpaRepository<StockReservation, UUID>
interface OrderStatusHistoryRepository : JpaRepository<OrderStatusHistory, UUID>
