package com.panenku.backend.modules.shared.service

import com.panenku.backend.common.exception.ApiException
import com.panenku.backend.modules.pembeli.entity.Order
import com.panenku.backend.modules.pembeli.repository.OrderItemRepository
import com.panenku.backend.modules.shared.repository.OrderScheduleRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class OrderScheduleGuard(private val schedules: OrderScheduleRepository, private val items: OrderItemRepository,
    private val scheduling: SchedulingService) {
    fun effective(order: Order) = schedules.effective(order)
    fun pending(order: Order) = schedules.pending(order.id)
    private fun noPending(order: Order) {
        if (pending(order) != null) throw ApiException(HttpStatus.CONFLICT,"SCHEDULE_CHANGE_PENDING","Usulan jadwal masih menunggu keputusan pembeli.")
    }
    fun canAccept(order: Order) {
        noPending(order)
        val current = effective(order); scheduling.requireFuture(current.scheduledDate,current.scheduledTimeSlot)
    }
    fun canPay(order: Order) {
        if (order.orderStatus == "menunggu_pembayaran" && order.paymentStatus in setOf("belum_dibayar","bukti_ditolak")) canAccept(order)
    }
    fun canFulfill(order: Order) {
        noPending(order)
        val current = effective(order)
        scheduling.requireStarted(current.scheduledDate,current.scheduledTimeSlot,items.findAllByOrderIdOrderByProductId(order.id).map { it.readyDateSnapshot })
    }
}
