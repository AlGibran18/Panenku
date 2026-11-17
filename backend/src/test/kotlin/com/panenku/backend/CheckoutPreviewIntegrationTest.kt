package com.panenku.backend

import com.panenku.backend.common.exception.ApiException
import com.panenku.backend.modules.pembeli.dto.*
import com.panenku.backend.modules.pembeli.service.CheckoutPreviewService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@SpringBootTest(properties=["panenku.jwt.secret=test-only-32-byte-secret-for-tests-123456789", "panenku.jwt.issuer=panenku", "scheduler.enabled=false"])
@AutoConfigureMockMvc
@Testcontainers
@Import(CheckoutIntegrationTest.FixedTime::class)
class CheckoutPreviewIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @DynamicPropertySource @JvmStatic fun database(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url",postgres::getJdbcUrl); r.add("spring.datasource.username",postgres::getUsername)
            r.add("spring.datasource.password",postgres::getPassword)
        }
    }
    @Autowired lateinit var context: ApplicationContext
    @Autowired lateinit var previews: CheckoutPreviewService
    lateinit var f: CheckoutIntegrationTest
    @BeforeEach fun seed() { f=CheckoutIntegrationTest();context.autowireCapableBeanFactory.autowireBean(f);f.seed() }
    private fun request()=CheckoutPreviewRequest(listOf(CheckoutPreviewItemRequest(f.product,BigDecimal("2.5")),
        CheckoutPreviewItemRequest(f.secondProduct,BigDecimal("1.25"))),"delivery",f.date,"08:00-10:00","Alamat pembeli","kepanjen","Sayur segar")
    private fun checkout(q: CheckoutPreviewDTO)=CheckoutRequest(q.items.map { CheckoutItemRequest(it.productId,it.quantityKg,it.pricePerKg) },
        q.fulfillmentMethod,q.scheduledDate,q.scheduledTimeSlot,q.deliveryFee,q.deliveryAddress,q.deliveryDistrict,q.buyerNotes,
        expectedFulfillmentToken=q.expectedFulfillmentToken,expectedCheckoutToken=q.quoteToken)
    private fun noWrites() {
        listOf("orders","order_items","stock_reservations","order_status_history","order_payment_options","order_payment_snapshots","cart").forEach { assertEquals(0L,f.count(it),it) }
        assertEquals(BigDecimal("0.00"),f.reserved())
    }
    private fun stale(block: () -> Any?) { assertEquals(HttpStatus.CONFLICT,assertThrows(ApiException::class.java) { block() }.status) }

    @Test fun `F4 preview totals match PRD and has no database side effects`() {
        val q=previews.preview(f.buyer,request())
        assertEquals(40000L,q.subtotalItems);assertEquals(10000L,q.deliveryFee);assertEquals(50000L,q.totalPayment)
        assertEquals(40000L,q.farmerProductRevenue);assertEquals("IDR",q.currency)
        assertEquals(0L,q.platformFee);assertEquals(0L,q.orderFee);assertEquals(0L,q.packagingFee)
        assertEquals(10000000L,q.transactionLimitApplied);assertTrue(q.canCheckout);assertNull(q.validationCode)
        assertEquals("Asia/Jakarta",q.timeZone);assertEquals(64,q.quoteToken.length);noWrites()
    }
    @Test fun `F4 confirmation stores the exact reviewed amounts and snapshots`() {
        val q=previews.preview(f.buyer,request());val order=f.checkout.checkout(f.buyer,"f4-match",checkout(q))
        assertEquals(q.subtotalItems,order.subtotalItems);assertEquals(q.deliveryFee,order.deliveryFee);assertEquals(q.totalPayment,order.totalPayment)
        assertEquals(q.transactionLimitApplied,order.transactionLimitApplied);assertEquals(q.pickupLocation,order.pickupLocationSnapshot)
        assertEquals(q.items.map { it.itemSubtotal },order.items.map { it.itemSubtotal })
        assertEquals(1L,f.count("orders"));assertEquals(2L,f.count("stock_reservations"))
        assertEquals(0L,f.count("order_payment_selections"));assertEquals(0L,f.count("order_payment_snapshots"))
    }
    @Test fun `F4 pickup removes delivery charges and address from the review`() {
        val q=previews.preview(f.buyer,request().copy(fulfillmentMethod="pickup"))
        assertEquals(40000L,q.totalPayment);assertEquals(0L,q.deliveryFee);assertNull(q.deliveryAddress);assertNull(q.deliveryDistrict)
        assertEquals(40000L,f.checkout.checkout(f.buyer,"f4-pickup",checkout(q)).totalPayment)
    }
    @Test fun `F4 preview and checkout round each fractional item the same way`() {
        f.db.update("UPDATE products SET price_per_kg=50")
        val r=request().copy(items=request().items.map { it.copy(quantityKg=BigDecimal("0.01")) },fulfillmentMethod="pickup")
        val q=previews.preview(f.buyer,r);assertEquals(listOf(1L,1L),q.items.map { it.itemSubtotal });assertEquals(2L,q.totalPayment)
        assertEquals(2L,f.checkout.checkout(f.buyer,"f4-round",checkout(q)).totalPayment)
    }
    @Test fun `F4 the limit includes delivery and over limit preview explains ineligibility`() {
        f.db.update("UPDATE settings SET value='50000' WHERE key='transaction_limit'")
        val q=previews.preview(f.buyer,request());assertTrue(q.canCheckout)
        assertEquals(50000L,f.checkout.checkout(f.buyer,"f4-at-limit",checkout(q)).totalPayment)
        f.seed();f.db.update("UPDATE settings SET value='49999' WHERE key='transaction_limit'")
        val over=previews.preview(f.buyer,request());assertFalse(over.canCheckout);assertEquals("TRANSACTION_LIMIT_EXCEEDED",over.validationCode)
        assertEquals(50000L,over.totalPayment);noWrites()
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ApiException::class.java) { f.checkout.checkout(f.buyer,"f4-over",checkout(over)) }.status)
        noWrites()
    }
    @Test fun `F4 a changed product price requires a fresh confirmation`() {
        val q=previews.preview(f.buyer,request())
        f.db.update("UPDATE products SET price_per_kg=13000 WHERE id=?",f.product)
        stale { f.checkout.checkout(f.buyer,"f4-price",checkout(q)) };noWrites()
        val fresh=previews.preview(f.buyer,request());assertEquals(52500L,fresh.totalPayment)
        assertEquals(fresh.totalPayment,f.checkout.checkout(f.buyer,"f4-price-fresh",checkout(fresh)).totalPayment)
    }
    @Test fun `F4 a changed delivery fee cannot be silently applied`() {
        val q=previews.preview(f.buyer,request())
        f.db.update("UPDATE farmer_profiles SET delivery_fee=11000 WHERE id=?",f.farmer)
        stale { f.checkout.checkout(f.buyer,"f4-fee",checkout(q)) };noWrites()
    }
    @Test fun `F4 changed limit is detected even when the total remains eligible`() {
        val q=previews.preview(f.buyer,request())
        f.db.update("UPDATE settings SET value='9000000' WHERE key='transaction_limit'")
        val ex=assertThrows(ApiException::class.java) { f.checkout.checkout(f.buyer,"f4-limit-changed",checkout(q)) }
        assertEquals("CHECKOUT_PREVIEW_CHANGED",ex.code);noWrites()
        val fresh=previews.preview(f.buyer,request());assertEquals(9000000L,fresh.transactionLimitApplied)
        assertEquals(9000000L,f.checkout.checkout(f.buyer,"f4-limit-new",checkout(fresh)).transactionLimitApplied)
    }
    @Test fun `F4 lowered limit after preview cannot create an over limit order`() {
        val q=previews.preview(f.buyer,request())
        f.db.update("UPDATE settings SET value='49000' WHERE key='transaction_limit'")
        stale { f.checkout.checkout(f.buyer,"f4-limit-lowered",checkout(q)) };noWrites()
        assertFalse(previews.preview(f.buyer,request()).canCheckout)
    }
    @Test fun `F4 changing reviewed address notes or valid date requires a new quote`() {
        val q=previews.preview(f.buyer,request());val r=checkout(q)
        listOf(r.copy(deliveryAddress="Alamat berbeda"),r.copy(buyerNotes="Catatan berbeda"),r.copy(scheduledDate=f.date.plusDays(1))).forEachIndexed { i,changed ->
            val ex=assertThrows(ApiException::class.java) { f.checkout.checkout(f.buyer,"f4-body-$i",changed) }
            assertEquals("CHECKOUT_PREVIEW_CHANGED",ex.code)
        }
        noWrites()
    }
    @Test fun `F4 quote token is bound to the buyer and rejects tampering`() {
        val q=previews.preview(f.buyer,request())
        assertNotEquals(q.quoteToken,previews.preview(f.otherBuyer,request()).quoteToken)
        stale { f.checkout.checkout(f.otherBuyer,"f4-other-actor",checkout(q)) }
        stale { f.checkout.checkout(f.buyer,"f4-tamper",checkout(q).copy(expectedCheckoutToken="0".repeat(64))) }
        noWrites()
    }
    @Test fun `F4 repeating a preview is stable across item order quantity scale and quote time`() {
        val q=previews.preview(f.buyer,request())
        (f.clock as CheckoutIntegrationTest.TestClock).time.set(Instant.now(f.clock).plusSeconds(30))
        val r=request().copy(items=request().items.reversed().map { it.copy(quantityKg=it.quantityKg.setScale(3)) })
        val again=previews.preview(f.buyer,r)
        assertEquals(q.quoteToken,again.quoteToken);assertNotEquals(q.quotedAt,again.quotedAt);noWrites()
    }
    @Test fun `F4 persisted order replay keeps old snapshots even when current quote data changes`() {
        val q=previews.preview(f.buyer,request());val r=checkout(q)
        val first=f.checkout.checkout(f.buyer,"f4-replay",r)
        f.db.update("UPDATE products SET price_per_kg=1,ready_date=?",f.date.plusDays(20))
        f.db.update("UPDATE farmer_profiles SET delivery_fee=9999,pickup_location='Berubah' WHERE id=?",f.farmer)
        f.db.update("UPDATE settings SET value='100' WHERE key='transaction_limit'")
        assertEquals(first,f.checkout.checkout(f.buyer,"f4-replay",r))
        assertEquals(1L,f.count("orders"));assertEquals(2L,f.count("stock_reservations"))
    }
    @Test fun `F4 concurrent retries with a confirmed quote create one order`() {
        val q=previews.preview(f.buyer,request());val r=checkout(q)
        val result=f.parallel({ f.checkout.checkout(f.buyer,"f4-retry",r) },{ f.checkout.checkout(f.buyer,"f4-retry",r) })
        assertTrue(result.all { it.isSuccess },result.toString());assertEquals(result[0].getOrThrow(),result[1].getOrThrow())
        assertEquals(1L,f.count("orders"));assertEquals(2L,f.count("stock_reservations"))
    }
    @Test fun `F4 two independent previews do not reserve the last stock`() {
        f.db.update("UPDATE products SET stock_physical=1 WHERE id=?",f.product)
        val req=request().copy(items=listOf(CheckoutPreviewItemRequest(f.product,BigDecimal("0.75"))),fulfillmentMethod="pickup")
        val a=previews.preview(f.buyer,req);val b=previews.preview(f.otherBuyer,req);noWrites()
        val r=f.parallel({ f.checkout.checkout(f.buyer,"f4-last-a",checkout(a)) },{ f.checkout.checkout(f.otherBuyer,"f4-last-b",checkout(b)) })
        assertEquals(1,r.count { it.isSuccess });assertEquals(1L,f.count("orders"));assertEquals(BigDecimal("0.75"),f.reserved())
    }
    @Test fun `F4 second item stock changes reject the whole checkout without partial writes`() {
        val q=previews.preview(f.buyer,request())
        f.db.update("UPDATE products SET stock_physical=0 WHERE id=?",f.secondProduct)
        stale { f.checkout.checkout(f.buyer,"f4-atomic-stock",checkout(q)) };noWrites()
    }
    @Test fun `F4 database failure rolls order payment options and reservations back together`() {
        val q=previews.preview(f.buyer,request())
        f.db.execute("CREATE FUNCTION f4_failure() RETURNS TRIGGER LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''simulated F4 reservation failure''; END;'")
        f.db.execute("CREATE TRIGGER f4_failure BEFORE INSERT ON stock_reservations FOR EACH ROW EXECUTE FUNCTION f4_failure()")
        try { assertThrows(Exception::class.java) { f.checkout.checkout(f.buyer,"f4-db-failure",checkout(q)) };noWrites() }
        finally { f.db.execute("DROP TRIGGER f4_failure ON stock_reservations");f.db.execute("DROP FUNCTION f4_failure()") }
    }
    @Test fun `F4 preview rejects unavailable products farmers commodities and payment methods`() {
        val cases=listOf("UPDATE products SET publication_status='nonaktif' WHERE id='${f.product}'",
            "UPDATE products SET stock_physical=0 WHERE id='${f.product}'",
            "UPDATE users SET status='suspended' WHERE id='${f.farmer}'",
            "UPDATE farmer_profiles SET ktp_status='menunggu' WHERE id='${f.farmer}'",
            "UPDATE commodities SET is_active=false WHERE id='${f.commodity}'",
            "UPDATE farmer_payment_methods SET is_active=false WHERE farmer_id='${f.farmer}'")
        cases.forEach { sql -> f.seed();f.db.execute(sql);assertThrows(ApiException::class.java) { previews.preview(f.buyer,request()) };noWrites() }
    }
    @Test fun `F4 input count quantity and text validations are shared with checkout`() {
        val r=request()
        listOf(r.copy(items=emptyList()),r.copy(items=r.items+r.items),
            r.copy(items=listOf(CheckoutPreviewItemRequest(f.product,BigDecimal("0.001")))),
            r.copy(items=List(101) { CheckoutPreviewItemRequest(UUID.randomUUID(),BigDecimal.ONE) }),
            r.copy(buyerNotes="x".repeat(1001)),r.copy(deliveryAddress="x".repeat(2001)),
            r.copy(items=listOf(CheckoutPreviewItemRequest(UUID.randomUUID(),BigDecimal.ONE)))).forEach {
            assertThrows(ApiException::class.java) { previews.preview(f.buyer,it) }
        }
        f.db.update("UPDATE products SET farmer_id=? WHERE id=?",f.otherFarmer,f.secondProduct)
        assertThrows(ApiException::class.java) { previews.preview(f.buyer,r) };noWrites()
    }
    @Test fun `F4 schedule and delivery validation still follow F3`() {
        val r=request()
        listOf(r.copy(scheduledDate=f.date.minusDays(1)),r.copy(scheduledTimeSlot="09:00-11:00"),
            r.copy(deliveryDistrict="other"),r.copy(deliveryAddress=null),r.copy(fulfillmentMethod="instant")).forEach {
            assertThrows(ApiException::class.java) { previews.preview(f.buyer,it) }
        }
        noWrites()
    }
    @Test fun `F4 HTTP null checkout items now return 400 instead of 500`() {
        val body="""{"items":[null],"fulfillmentMethod":"pickup","scheduledDate":"2026-11-12","scheduledTimeSlot":"08:00-10:00","expectedDeliveryFee":0}"""
        f.mvc.perform(post("/api/v1/buyer/checkout").with(jwt().jwt { it.subject(f.buyer.toString()) })
            .header("Idempotency-Key","f4-null").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.success").value(false))
        val previewBody=f.mapper.readTree(body) as com.fasterxml.jackson.databind.node.ObjectNode
        previewBody.remove("expectedDeliveryFee")
        f.mvc.perform(post("/api/v1/buyer/checkout/preview").with(jwt().jwt { it.subject(f.buyer.toString()) })
            .contentType(MediaType.APPLICATION_JSON).content(previewBody.toString())).andExpect(status().isBadRequest)
        noWrites()
    }
    @Test fun `F4 preview requires an active buyer and never exposes bank destination data`() {
        val body=f.mapper.writeValueAsBytes(request())
        f.mvc.perform(post("/api/v1/buyer/checkout/preview").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized)
        f.mvc.perform(post("/api/v1/buyer/checkout/preview").with(jwt().jwt { it.subject(f.farmer.toString()) })
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden)
        f.mvc.perform(post("/api/v1/buyer/checkout/preview").with(jwt().jwt { it.subject(f.buyer.toString()) })
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.data.bankName").doesNotExist()).andExpect(jsonPath("$.data.accountNumber").doesNotExist())
            .andExpect(jsonPath("$.data.paymentOptions").doesNotExist())
        f.db.update("UPDATE users SET status='suspended' WHERE id=?",f.buyer)
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ApiException::class.java) { previews.preview(f.buyer,request()) }.status)
        noWrites()
    }
    @Test fun `F4 HTTP rejects spoofed totals and commits the authoritative review`() {
        val body=f.mapper.readTree(f.mapper.writeValueAsBytes(request())) as com.fasterxml.jackson.databind.node.ObjectNode
        body.put("totalPayment",1);body.put("platformFee",999);body.put("currency","USD")
        f.mvc.perform(post("/api/v1/buyer/checkout/preview").with(jwt().jwt { it.subject(f.buyer.toString()) })
            .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isBadRequest)
        noWrites()
        body.remove(listOf("totalPayment","platformFee","currency"))
        val response=f.mvc.perform(post("/api/v1/buyer/checkout/preview").with(jwt().jwt { it.subject(f.buyer.toString()) })
            .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.totalPayment").value(50000)).andExpect(jsonPath("$.data.platformFee").value(0))
            .andExpect(jsonPath("$.data.currency").value("IDR")).andReturn().response.contentAsString
        val data=f.mapper.readTree(response).path("data")
        val q=previews.preview(f.buyer,request());assertEquals(q.quoteToken,data.path("quoteToken").asText());noWrites()
        val commit=f.mapper.readTree(f.mapper.writeValueAsBytes(checkout(q))) as com.fasterxml.jackson.databind.node.ObjectNode
        commit.put("totalPayment",1);commit.put("subtotalItems",1)
        f.mvc.perform(post("/api/v1/buyer/checkout").with(jwt().jwt { it.subject(f.buyer.toString()) }).header("Idempotency-Key","f4-http")
            .contentType(MediaType.APPLICATION_JSON).content(commit.toString())).andExpect(status().isBadRequest)
        noWrites()
        commit.remove(listOf("totalPayment","subtotalItems"))
        f.mvc.perform(post("/api/v1/buyer/checkout").with(jwt().jwt { it.subject(f.buyer.toString()) }).header("Idempotency-Key","f4-http")
            .contentType(MediaType.APPLICATION_JSON).content(commit.toString())).andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.totalPayment").value(50000)).andExpect(jsonPath("$.data.subtotalItems").value(40000))
    }
    @Test fun `F4 each saved item retains its own amount across arbitrary UUID ordering`() {
        val extra=UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
        f.addProduct(extra,f.farmer,"Produk Lain","10",8000)
        val r=request().copy(items=listOf(CheckoutPreviewItemRequest(f.product,BigDecimal("2.5")),CheckoutPreviewItemRequest(extra,BigDecimal("1.25"))))
        val q=previews.preview(f.buyer,r)
        val order=f.checkout.checkout(f.buyer,"f4-item-identity",checkout(q))
        assertEquals(q.items.associate { it.productId to it.itemSubtotal },order.items.associate { it.productId to it.itemSubtotal })
        assertEquals(50000L,order.totalPayment)
    }
    @Test fun `F4 legacy request JSON omits new optional token and still supports checkout`() {
        val request=f.request()
        assertFalse(f.mapper.readTree(f.mapper.writeValueAsBytes(request)).has("expectedCheckoutToken"))
        assertEquals(9000L,f.checkout.checkout(f.buyer,"f4-legacy",request).totalPayment)
    }
    @Test fun `F4 invalid token negative fee or fractional rupiah are rejected as client errors`() {
        val q=previews.preview(f.buyer,request());val r=checkout(q)
        listOf(r.copy(expectedCheckoutToken="bad"),r.copy(expectedDeliveryFee=-1),r.copy(items=r.items.map { it.copy(expectedPricePerKg=-1) })).forEachIndexed { i,bad ->
            assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ApiException::class.java) { f.checkout.checkout(f.buyer,"f4-invalid-$i",bad) }.status)
        }
        val body=f.mapper.readTree(f.mapper.writeValueAsBytes(r)) as com.fasterxml.jackson.databind.node.ObjectNode
        body.put("expectedDeliveryFee",10000.5)
        f.mvc.perform(post("/api/v1/buyer/checkout").with(jwt().jwt { it.subject(f.buyer.toString()) }).header("Idempotency-Key","f4-fractional-fee")
            .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isBadRequest)
        noWrites()
    }
    @Test fun `F4 invalid configured limit fails closed without guessing a default`() {
        listOf("0","-1","not-a-number").forEach { value ->
            f.db.update("UPDATE settings SET value=? WHERE key='transaction_limit'",value)
            assertThrows(ApiException::class.java) { previews.preview(f.buyer,request()) }
        }
        noWrites()
    }
}
