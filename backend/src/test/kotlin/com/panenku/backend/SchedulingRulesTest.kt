package com.panenku.backend

import com.panenku.backend.common.exception.ApiException
import com.panenku.backend.modules.shared.service.SchedulingService
import com.panenku.backend.modules.shared.service.FulfillmentRules
import com.panenku.backend.modules.shared.entity.FarmerProfile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.*

class SchedulingRulesTest {
    private val rules = SchedulingService(Clock.fixed(Instant.parse("2026-11-10T01:00:00Z"),ZoneOffset.UTC))
    private val day = LocalDate.parse("2026-11-10")
    @Test fun `F3 adjacent slots are allowed sorted and trimmed`() {
        assertEquals(listOf("08:00-10:00","10:00-12:00"),rules.normalizeSlots(listOf("10:00-12:00", " 08:00-10:00 ")))
    }
    @Test fun `F3 overlapping contained duplicate reversed overnight and malformed slots are rejected`() {
        listOf(listOf("08:00-10:00","09:00-11:00"),listOf("08:00-12:00","09:00-10:00"),
            listOf("08:00-10:00"," 08:00-10:00 "),listOf("23:00-01:00"),listOf("08:00-08:00"),
            listOf("24:00-25:00"),listOf("8:00-10:00"),listOf("08:00-10:00-extra")).forEach {
            assertThrows(ApiException::class.java) { rules.normalizeSlots(it) }
        }
    }
    @Test fun `F3 a slot starting exactly now cannot be newly booked but fulfillment can begin`() {
        assertEquals(listOf("13:00-15:00"),rules.slotsOn(day,listOf("08:00-10:00","13:00-15:00")))
        assertThrows(ApiException::class.java) { rules.requireFuture(day,"08:00-10:00") }
        rules.requireStarted(day,"08:00-10:00",listOf(day))
    }
    @Test fun `F3 tomorrow allows all valid slots yesterday allows none`() {
        assertEquals(listOf("08:00-10:00"),rules.slotsOn(day.plusDays(1),listOf("08:00-10:00")))
        assertTrue(rules.slotsOn(day.minusDays(1),listOf("08:00-10:00")).isEmpty())
    }
    @Test fun `F3 fulfillment cannot precede snapshot readiness or scheduled start`() {
        assertThrows(ApiException::class.java) { rules.requireStarted(day.plusDays(1),"08:00-10:00",listOf(day)) }
        assertThrows(ApiException::class.java) { rules.requireStarted(day,"08:00-10:00",listOf(day.plusDays(1))) }
    }
    @Test fun `F3 Jakarta midnight is used independently of clock zone`() {
        val r = SchedulingService(Clock.fixed(Instant.parse("2026-11-09T17:00:00Z"),ZoneOffset.UTC))
        assertEquals(day,r.now().toLocalDate()); assertEquals("Asia/Jakarta",r.zone.id)
    }
    @Test fun `F3 area identifiers normalize and duplicates cannot bypass casing`() {
        assertEquals(listOf("dau","kepanjen"),FulfillmentRules.areas(listOf(" Kepanjen ","DAU")))
        assertThrows(ApiException::class.java) { FulfillmentRules.areas(listOf("Dau","dau")) }
        assertThrows(ApiException::class.java) { FulfillmentRules.district("area|other") }
    }
    @Test fun `F3 pickup is free and delivery uses only configured areas`() {
        val p=FarmerProfile().apply { pickupLocation="Lokasi"; deliveryFee=10000; shippingEnabled=true; serviceArea="kepanjen|dau" }
        assertEquals(0L,FulfillmentRules.resolve(p,"pickup",null).fee)
        assertEquals("kepanjen",FulfillmentRules.resolve(p,"delivery"," Kepanjen ").district)
        assertEquals(10000L,FulfillmentRules.resolve(p,"delivery","dau").fee)
        assertThrows(ApiException::class.java) { FulfillmentRules.resolve(p,"delivery","other") }
        p.shippingEnabled=false
        assertThrows(ApiException::class.java) { FulfillmentRules.resolve(p,"delivery","dau") }
    }
}
