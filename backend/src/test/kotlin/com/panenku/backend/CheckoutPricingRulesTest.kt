package com.panenku.backend

import com.panenku.backend.common.exception.ApiException
import com.panenku.backend.modules.pembeli.dto.*
import com.panenku.backend.modules.pembeli.service.CheckoutInputRules
import com.panenku.backend.modules.shared.service.CheckoutMath
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class CheckoutPricingRulesTest {
    @Test fun `F4 summary and committed total use the same item rounding`() {
        val lines=listOf(CheckoutMath.item(BigDecimal("2.5"),12000),CheckoutMath.item(BigDecimal("1.25"),8000))
        val summary=CheckoutMath.summary(lines,10000)
        assertEquals(40000L,summary.subtotalItems); assertEquals(10000L,summary.deliveryFee)
        assertEquals(50000L,summary.totalPayment); assertEquals(summary.totalPayment,CheckoutMath.total(lines,10000,10000000))
    }
    @Test fun `F4 half up rounds each item rather than the combined decimal total`() {
        assertEquals(0L,CheckoutMath.item(BigDecimal("0.01"),49))
        assertEquals(1L,CheckoutMath.item(BigDecimal("0.01"),50))
        assertEquals(1L,CheckoutMath.item(BigDecimal("0.01"),51))
        assertEquals(2L,CheckoutMath.summary(listOf(CheckoutMath.item(BigDecimal("0.01"),50),CheckoutMath.item(BigDecimal("0.01"),50)),0).totalPayment)
    }
    @Test fun `F4 zero rounded rupiah remains zero without invented minimum fees`() {
        assertEquals(0L,CheckoutMath.summary(listOf(CheckoutMath.item(BigDecimal("0.01"),1)),0).totalPayment)
        assertTrue(CheckoutMath.withinLimit(0,10000000))
    }
    @Test fun `F4 exact transaction limit is accepted and one rupiah more is rejected`() {
        assertTrue(CheckoutMath.withinLimit(10000000,10000000)); assertFalse(CheckoutMath.withinLimit(10000001,10000000))
        assertThrows(ApiException::class.java) { CheckoutMath.total(listOf(10000000),1,10000000) }
    }
    @Test fun `F4 subtotal and fee cannot wrap long values`() {
        assertThrows(ApiException::class.java) { CheckoutMath.summary(listOf(Long.MAX_VALUE,1),0) }
        assertThrows(ApiException::class.java) { CheckoutMath.summary(listOf(Long.MAX_VALUE),1) }
        assertEquals(Long.MAX_VALUE,CheckoutMath.total(listOf(Long.MAX_VALUE),0,Long.MAX_VALUE))
    }
    @Test fun `F4 invalid components and limits fail explicitly`() {
        listOf(emptyList<Long>(),listOf(-1L)).forEach { assertThrows(ApiException::class.java) { CheckoutMath.summary(it,0) } }
        assertThrows(ApiException::class.java) { CheckoutMath.summary(listOf(1),-1) }
        assertThrows(ApiException::class.java) { CheckoutMath.withinLimit(1,0) }
    }
    @Test fun `F4 request normalization retains quote fields and rejects invalid expectations`() {
        val r=CheckoutRequest(listOf(CheckoutItemRequest(UUID.randomUUID(),BigDecimal("2.500"),12000)),"pickup",LocalDate.parse("2026-11-20"),"08:00-10:00",0)
        assertEquals(BigDecimal("2.50"),CheckoutInputRules.normalize(r).items.single().quantityKg)
        listOf(r.copy(expectedDeliveryFee=-1),r.copy(items=r.items.map { it.copy(expectedPricePerKg=0) }),
            r.copy(expectedCheckoutToken="invalid"),r.copy(buyerNotes="x".repeat(1001))).forEach {
            assertThrows(ApiException::class.java) { CheckoutInputRules.normalize(it) }
        }
    }
}
