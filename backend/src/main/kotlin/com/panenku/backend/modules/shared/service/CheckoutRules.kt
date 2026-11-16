package com.panenku.backend.modules.shared.service

import com.panenku.backend.common.exception.*
import java.math.BigDecimal
import java.math.RoundingMode

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
    fun total(items: List<Long>, fee: Long, limit: Long): Long {
        if (items.isEmpty() || items.any { it < 0 } || fee < 0 || limit <= 0) invalid("Komponen total tidak valid.")
        val total = items.fold(BigDecimal.valueOf(fee)) { sum, value -> sum.add(BigDecimal.valueOf(value)) }
        if (total > BigDecimal.valueOf(limit)) invalid("Total termasuk ongkir melebihi limit Rp$limit.")
        return exact(total)
    }
    private fun exact(value: BigDecimal): Long = try { value.longValueExact() } catch (_: ArithmeticException) { invalid("Nominal terlalu besar.") }
}
