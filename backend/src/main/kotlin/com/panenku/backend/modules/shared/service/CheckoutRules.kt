package com.panenku.backend.modules.shared.service

import com.panenku.backend.common.exception.*
import java.math.BigDecimal
import java.math.RoundingMode

data class CheckoutTotals(val subtotalItems: Long, val deliveryFee: Long, val totalPayment: Long)

object CheckoutMath {
    private val maxKg = BigDecimal("99999999.99")
    fun kg(value: BigDecimal, allowZero: Boolean = false): BigDecimal {
        if (value.signum() < 0 || (!allowZero && value.signum() == 0) || value > maxKg || value.stripTrailingZeros().scale() > 2)
            invalid("Jumlah kg harus kelipatan 0.01 dan ${if (allowZero) "minimal 0" else "minimal 0.01"}; maksimal $maxKg.")
        return value.setScale(2, RoundingMode.UNNECESSARY)
    }
    fun item(quantity: BigDecimal, price: Long): Long {
        kg(quantity)
        if (price <= 0) invalid("Harga harus rupiah positif.")
        return exact(quantity.multiply(BigDecimal.valueOf(price)).setScale(0, RoundingMode.HALF_UP))
    }
    fun summary(items: List<Long>, fee: Long): CheckoutTotals {
        if (items.isEmpty() || items.any { it < 0 } || fee < 0) invalid("Komponen total tidak valid.")
        val subtotal = items.fold(BigDecimal.ZERO) { sum, value -> sum.add(BigDecimal.valueOf(value)) }
        return CheckoutTotals(exact(subtotal),fee,exact(subtotal.add(BigDecimal.valueOf(fee))))
    }
    fun withinLimit(total: Long, limit: Long): Boolean {
        if (total < 0 || limit <= 0) invalid("Total atau limit tidak valid.")
        return total <= limit
    }
    fun total(items: List<Long>, fee: Long, limit: Long): Long {
        val total=summary(items,fee).totalPayment
        if (!withinLimit(total,limit)) invalid("Total termasuk ongkir melebihi limit Rp$limit.")
        return total
    }
    private fun exact(value: BigDecimal): Long = try { value.longValueExact() } catch (_: ArithmeticException) { invalid("Nominal terlalu besar.") }
}
