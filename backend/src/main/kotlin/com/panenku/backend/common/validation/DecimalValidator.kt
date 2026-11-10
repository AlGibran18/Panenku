package com.panenku.backend.common.validation

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Validator untuk angka desimal (rupiah dan kg).
 * Selalu pakai BigDecimal, JANGAN Float/Double untuk uang dan berat.
 */
object DecimalValidator {

    /**
     * Pembulatan half-up ke rupiah terdekat (tanpa desimal).
     * Contoh: 30000.50 -> 30001, 30000.49 -> 30000
     */
    fun roundToRupiah(amount: BigDecimal): Long {
        return amount.setScale(0, RoundingMode.HALF_UP).toLongExact()
    }

    /**
     * Validasi apakah jumlah kg valid (lebih dari 0, maksimal 2 desimal).
     */
    fun isValidKg(quantity: BigDecimal): Boolean {
        return quantity > BigDecimal.ZERO &&
                quantity.scale() <= 2
    }

    /**
     * Hitung subtotal item: quantity_kg × price_per_kg, hasil dibulatkan ke rupiah.
     */
    fun calculateItemSubtotal(quantityKg: BigDecimal, pricePerKg: Long): Long {
        val subtotal = BigDecimal.valueOf(pricePerKg) * quantityKg
        return roundToRupiah(subtotal)
    }
}