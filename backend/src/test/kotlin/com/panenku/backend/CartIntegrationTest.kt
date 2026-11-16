package com.panenku.backend

import com.panenku.backend.common.exception.ApiException
import com.panenku.backend.modules.pembeli.dto.*
import com.panenku.backend.modules.shared.dto.OrderCancelRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.util.UUID

@SpringBootTest(properties=["panenku.jwt.secret=test-only-32-byte-secret-for-tests-123456789", "panenku.jwt.issuer=panenku", "scheduler.enabled=false"])
@AutoConfigureMockMvc
@Testcontainers
@Import(CheckoutIntegrationTest.FixedTime::class)
class CartIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @DynamicPropertySource @JvmStatic fun database(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url", postgres::getJdbcUrl)
            r.add("spring.datasource.username", postgres::getUsername)
            r.add("spring.datasource.password", postgres::getPassword)
        }
    }
    @Autowired lateinit var context: ApplicationContext
    @Autowired lateinit var transactions: PlatformTransactionManager
    lateinit var f: CheckoutIntegrationTest
    @BeforeEach fun setup() { f = CheckoutIntegrationTest(); context.autowireCapableBeanFactory.autowireBean(f); f.seed() }
    private fun add(id: UUID = f.product, quantity: String = "1") = f.cart.add(f.buyer, CartItemRequest(id, BigDecimal(quantity)))
    private fun selection(id: UUID = f.product, quantity: String = "1", price: Long = 12000) = CartSelectionRequest(id, BigDecimal(quantity), price)
    private fun confirm(p: ReorderPreviewDTO) = ReorderRequest(p.cartVersion, p.previewToken, true)
    private fun failure(code: String, block: () -> Any?) {
        val ex = assertThrows(ApiException::class.java) { block() }
        assertEquals(HttpStatus.CONFLICT, ex.status); assertEquals(code, ex.code)
    }
    private fun otherProduct(): UUID {
        val id = UUID.randomUUID(); f.addProduct(id, f.otherFarmer, "Petani Lain", "10", 9000); return id
    }

    @Test fun `F2 concurrent cart additions keep both increments`() {
        repeat(5) {
            f.seed(); add()
            val r = f.parallel({ add() }, { add() })
            assertTrue(r.all { it.isSuccess }, r.toString())
            assertEquals(BigDecimal("3.00"), f.cart.get(f.buyer).items.single().quantityKg)
            assertEquals(BigDecimal("0.00"), f.reserved())
        }
    }
    @Test fun `F2 first concurrent additions to an empty cart create one row`() {
        val r = f.parallel({ add() }, { add() })
        assertTrue(r.all { it.isSuccess }, r.toString())
        val cart = f.cart.get(f.buyer)
        assertEquals(1, cart.items.size); assertEquals(BigDecimal("2.00"), cart.items.single().quantityKg)
    }
    @Test fun `F2 concurrent different farmers cannot create a mixed cart`() {
        val other = otherProduct()
        val r = f.parallel({ add() }, { add(other) })
        assertEquals(1, r.count { it.isSuccess })
        assertEquals("CART_FARMER_CONFLICT", (r.single { it.isFailure }.exceptionOrNull() as ApiException).code)
        assertEquals(1, f.cart.get(f.buyer).items.size)
    }
    @Test fun `F2 farmer switch requires confirmation and preserves the previous cart`() {
        val before = add(); val other = otherProduct()
        failure("CART_FARMER_CONFLICT") { add(other) }
        assertEquals(before, f.cart.get(f.buyer))
        val req = CartReplaceRequest(listOf(selection(other, price = 9000)), before.cartVersion)
        failure("CART_CONFIRMATION_REQUIRED") { f.cart.replace(f.buyer, req) }
        assertEquals(before, f.cart.get(f.buyer))
        val replaced = f.cart.replace(f.buyer, req.copy(confirmed = true))
        assertEquals(other, replaced.items.single().productId); assertEquals(f.otherFarmer, replaced.farmerId)
        assertNotEquals(before.cartVersion, replaced.cartVersion)
        assertEquals(BigDecimal("0.00"), f.reserved()); assertEquals(0L, f.count("orders"))
    }
    @Test fun `F2 stale cart confirmation cannot overwrite a newer quantity`() {
        val initial = add(); val fresh = add()
        failure("CART_CHANGED") { f.cart.replace(f.buyer, CartReplaceRequest(listOf(selection()), initial.cartVersion, true)) }
        assertEquals(fresh, f.cart.get(f.buyer))
    }
    @Test fun `F2 concurrent confirmations of the same cart allow only one replacement`() {
        val original = add()
        val a = CartReplaceRequest(listOf(selection(quantity = "2")), original.cartVersion, true)
        val b = a.copy(items = listOf(selection(quantity = "3")))
        val r = f.parallel({ f.cart.replace(f.buyer, a) }, { f.cart.replace(f.buyer, b) })
        assertEquals(1, r.count { it.isSuccess })
        assertEquals("CART_CHANGED", (r.single { it.isFailure }.exceptionOrNull() as ApiException).code)
        assertEquals(r.single { it.isSuccess }.getOrThrow(), f.cart.get(f.buyer))
    }
    @Test fun `F2 invalid replacement is atomic and cannot mix farmers duplicate products or stale prices`() {
        val original = add(); val other = otherProduct()
        listOf(listOf(selection(), selection(other, price = 9000)), listOf(selection(), selection()),
            listOf(selection(price = 1)), listOf(selection(quantity = "11")), listOf(selection(UUID.randomUUID()))).forEach {
            assertThrows(ApiException::class.java) { f.cart.replace(f.buyer, CartReplaceRequest(it, original.cartVersion, true)) }
            assertEquals(original, f.cart.get(f.buyer))
        }
    }
    @Test fun `F2 database insert failure restores the old cart after replacement delete`() {
        val original = add()
        f.db.execute("CREATE FUNCTION f2_cart_failure() RETURNS TRIGGER LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''simulated cart insert failure''; END;'")
        f.db.execute("CREATE TRIGGER f2_cart_write_failure BEFORE INSERT ON cart FOR EACH ROW EXECUTE FUNCTION f2_cart_failure()")
        try {
            assertThrows(Exception::class.java) { f.cart.replace(f.buyer, CartReplaceRequest(listOf(selection(f.secondProduct, price = 8000)), original.cartVersion, true)) }
            assertEquals(original, f.cart.get(f.buyer))
        } finally { f.db.execute("DROP TRIGGER f2_cart_write_failure ON cart"); f.db.execute("DROP FUNCTION f2_cart_failure()") }
    }
    @Test fun `F2 an explicit empty confirmed replacement clears the cart`() {
        val original = add()
        val empty = f.cart.replace(f.buyer, CartReplaceRequest(emptyList(), original.cartVersion, true))
        assertTrue(empty.items.isEmpty()); assertNull(empty.farmerId); assertFalse(empty.hasUnavailableItems)
        assertEquals(BigDecimal.ZERO, empty.subtotalItems)
        assertEquals(empty.cartVersion, f.cart.get(f.buyer).cartVersion)
    }
    @Test fun `F2 cart returns current product information and explains insufficient stock without clamping quantity`() {
        add(quantity = "2.5")
        f.db.update("UPDATE products SET price_per_kg=15000,stock_physical=2,ready_date=? WHERE id=?", f.date.plusDays(3), f.product)
        val cart = f.cart.get(f.buyer); val item = cart.items.single()
        assertEquals(BigDecimal("2.50"), item.quantityKg); assertEquals(15000L, item.pricePerKg)
        assertEquals(37500L, item.itemSubtotal); assertEquals(f.date.plusDays(3), item.readyDate)
        assertEquals(f.farmer, item.farmerId); assertNotNull(item.storeName)
        assertFalse(item.available); assertTrue(cart.hasUnavailableItems); assertEquals("INSUFFICIENT_STOCK", item.unavailableReason)
        assertEquals(BigDecimal("0.00"), f.reserved())
    }
    @Test fun `F2 cart exposes inactive sold out unverified suspended and disabled commodity reasons`() {
        val cases = listOf(
            "UPDATE products SET publication_status='nonaktif' WHERE id='${f.product}'" to "PRODUCT_INACTIVE",
            "UPDATE products SET stock_physical=0 WHERE id='${f.product}'" to "OUT_OF_STOCK",
            "UPDATE farmer_profiles SET ktp_status='menunggu' WHERE id='${f.farmer}'" to "FARMER_UNVERIFIED",
            "UPDATE users SET status='suspended' WHERE id='${f.farmer}'" to "FARMER_SUSPENDED",
            "UPDATE commodities SET is_active=false WHERE id='${f.commodity}'" to "COMMODITY_INACTIVE")
        cases.forEach { (sql, reason) ->
            f.seed(); add(); f.db.execute(sql)
            val item = f.cart.get(f.buyer).items.single()
            assertFalse(item.available); assertEquals(reason, item.unavailableReason)
            failure(reason) { add() }
            f.cart.delete(f.buyer, item.id); assertTrue(f.cart.get(f.buyer).items.isEmpty())
        }
    }
    @Test fun `F2 invalid quantity or oversized selection never changes cart`() {
        val original = add()
        listOf("0", "-1", "0.001", "100000000").forEach { quantity ->
            assertThrows(ApiException::class.java) { add(quantity = quantity) }
            assertThrows(ApiException::class.java) { f.cart.update(f.buyer, original.items.single().id, BigDecimal(quantity)) }
            assertEquals(original, f.cart.get(f.buyer))
        }
        val tooMany = List(101) { selection(UUID.randomUUID()) }
        assertThrows(ApiException::class.java) { f.cart.replace(f.buyer, CartReplaceRequest(tooMany, original.cartVersion, true)) }
        assertEquals(original, f.cart.get(f.buyer))
    }
    @Test fun `F2 HTTP cart replacement and reorder require authentication role and ownership`() {
        val cart = add(); val order = f.orderFor()
        f.mvc.perform(put("/api/v1/buyer/cart").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized)
        f.mvc.perform(get("/api/v1/buyer/orders/$order/reorder-preview").with(jwt().jwt { it.subject(f.otherBuyer.toString()) }))
            .andExpect(status().isForbidden)
        f.mvc.perform(post("/api/v1/buyer/orders/$order/reorder").with(jwt().jwt { it.subject(f.otherBuyer.toString()) })
            .contentType(MediaType.APPLICATION_JSON).content(f.mapper.writeValueAsBytes(ReorderRequest(f.cart.get(f.otherBuyer).cartVersion, "bad", true))))
            .andExpect(status().isForbidden)
        f.mvc.perform(get("/api/v1/buyer/orders/$order/reorder-preview").with(jwt().jwt { it.subject(f.farmer.toString()) }))
            .andExpect(status().isForbidden)
        f.mvc.perform(delete("/api/v1/buyer/cart/items/${cart.items.single().id}").with(jwt().jwt { it.subject(f.otherBuyer.toString()) }))
            .andExpect(status().isForbidden)
        f.mvc.perform(put("/api/v1/buyer/cart").with(jwt().jwt { it.subject(f.buyer.toString()) }).contentType(MediaType.APPLICATION_JSON)
            .content(f.mapper.writeValueAsBytes(CartReplaceRequest(emptyList(), cart.cartVersion, true))))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.items").isEmpty)
    }
    @Test fun `F2 reorder uses live price date and reduced stock only after confirmation`() {
        val order = f.orderFor(req = f.request("3"), status = "selesai", payment = "terverifikasi")
        val snapshot = f.db.queryForMap("SELECT * FROM order_items WHERE order_id=?", order)
        val original = add(f.secondProduct)
        f.db.update("UPDATE products SET name='Tomat Baru',price_per_kg=15000,stock_physical=5,ready_date=? WHERE id=?", f.date.plusDays(2), f.product)
        val p = f.cart.reorderPreview(f.buyer, order); val item = p.items.single()
        assertEquals(original, f.cart.get(f.buyer)); assertEquals(original.cartVersion, p.cartVersion)
        assertEquals(BigDecimal("3.00"), item.previousQuantityKg); assertEquals(BigDecimal("2.00"), item.quantityKg)
        assertTrue(item.priceChanged); assertEquals(15000L, item.pricePerKg)
        assertEquals("QUANTITY_REDUCED", item.adjustmentReason); assertEquals(f.date.plusDays(2), item.readyDate)
        failure("CART_CONFIRMATION_REQUIRED") { f.cart.reorder(f.buyer, order, confirm(p).copy(confirmed = false)) }
        val result = f.cart.reorder(f.buyer, order, confirm(p))
        assertEquals(f.product, result.items.single().productId); assertEquals(BigDecimal("2.00"), result.items.single().quantityKg)
        assertEquals(30000L, result.items.single().itemSubtotal)
        assertEquals(snapshot, f.db.queryForMap("SELECT * FROM order_items WHERE order_id=?", order))
        assertEquals(1L, f.count("orders")); assertEquals(1L, f.count("stock_reservations")); assertEquals(BigDecimal("3.00"), f.reserved())
        assertEquals(BigDecimal("5.00"), f.stockPhysical())
    }
    @Test fun `F2 reorder excludes inactive products and reports their reason`() {
        val req = f.request("1").copy(items = f.request("1").items + CheckoutItemRequest(f.secondProduct, BigDecimal.ONE, 8000))
        val order = f.orderFor(req = req)
        f.db.update("UPDATE products SET publication_status='nonaktif' WHERE id=?", f.secondProduct)
        val p = f.cart.reorderPreview(f.buyer, order)
        val unavailable = p.items.single { it.productId == f.secondProduct }
        assertFalse(unavailable.available); assertEquals("PRODUCT_INACTIVE", unavailable.adjustmentReason)
        val cart = f.cart.reorder(f.buyer, order, confirm(p))
        assertEquals(f.product, cart.items.single().productId)
    }
    @Test fun `F2 reorder with no eligible products preserves current cart`() {
        val order = f.orderFor(); val original = add(f.secondProduct)
        f.db.update("UPDATE products SET publication_status='nonaktif' WHERE id=?", f.product)
        val p = f.cart.reorderPreview(f.buyer, order)
        assertFalse(p.items.single().available)
        failure("NO_REORDERABLE_ITEMS") { f.cart.reorder(f.buyer, order, confirm(p)) }
        assertEquals(original, f.cart.get(f.buyer))
    }
    @Test fun `F2 stale reorder price stock or date requires a new preview`() {
        listOf("price_per_kg=13000", "stock_physical=9", "ready_date='2026-11-15'").forEach { change ->
            f.seed(); val order = f.orderFor(); val original = add(f.secondProduct)
            val p = f.cart.reorderPreview(f.buyer, order)
            f.db.execute("UPDATE products SET $change WHERE id='${f.product}'")
            failure("REORDER_CHANGED") { f.cart.reorder(f.buyer, order, confirm(p)) }
            assertEquals(original, f.cart.get(f.buyer))
        }
    }
    @Test fun `F2 stale reorder confirmation cannot erase cart changes from another device`() {
        val order = f.orderFor(); add(f.secondProduct)
        val p = f.cart.reorderPreview(f.buyer, order); val updated = add(f.secondProduct)
        failure("CART_CHANGED") { f.cart.reorder(f.buyer, order, confirm(p)) }
        assertEquals(updated, f.cart.get(f.buyer))
    }
    @Test fun `F2 repeating a confirmed reorder cannot duplicate quantity or reservations`() {
        val order = f.orderFor(); val p = f.cart.reorderPreview(f.buyer, order)
        val first = f.cart.reorder(f.buyer, order, confirm(p))
        failure("CART_CHANGED") { f.cart.reorder(f.buyer, order, confirm(p)) }
        assertEquals(first, f.cart.get(f.buyer)); assertEquals(1L, f.count("orders")); assertEquals(1L, f.count("stock_reservations"))
    }
    @Test fun `F2 source farmer suspension produces an unavailable preview but suspended buyer is forbidden`() {
        val order = f.orderFor()
        f.db.update("UPDATE users SET status='suspended' WHERE id=?", f.farmer)
        val p = f.cart.reorderPreview(f.buyer, order)
        assertEquals("FARMER_SUSPENDED", p.items.single().adjustmentReason)
        failure("NO_REORDERABLE_ITEMS") { f.cart.reorder(f.buyer, order, confirm(p)) }
        f.db.update("UPDATE users SET status='suspended' WHERE id=?", f.buyer)
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException::class.java) { f.cart.reorderPreview(f.buyer, order) }.status)
    }
    @Test fun `F2 HTTP reorder preview and confirmation return live cart metadata`() {
        val order = f.orderFor()
        f.mvc.perform(get("/api/v1/buyer/orders/$order/reorder-preview").with(jwt().jwt { it.subject(f.buyer.toString()) }))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.items[0].readyDate").value(f.date.toString()))
        val p = f.cart.reorderPreview(f.buyer, order)
        f.mvc.perform(post("/api/v1/buyer/orders/$order/reorder").with(jwt().jwt { it.subject(f.buyer.toString()) })
            .contentType(MediaType.APPLICATION_JSON).content(f.mapper.writeValueAsBytes(confirm(p))))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.cartVersion").isNotEmpty)
            .andExpect(jsonPath("$.data.items[0].unavailableReason").doesNotExist())
    }
    @Test fun `F2 two carts do not reserve stock and only one checkout can get the last kilogram`() {
        f.db.update("UPDATE products SET stock_physical=1 WHERE id=?", f.product)
        add(quantity = "0.75"); f.cart.add(f.otherBuyer, CartItemRequest(f.product, BigDecimal("0.75")))
        assertEquals(BigDecimal("0.00"), f.reserved())
        val r = f.parallel({ f.checkout.checkout(f.buyer, "f2-race-a", f.request()) },
            { f.checkout.checkout(f.otherBuyer, "f2-race-b", f.request()) })
        assertEquals(1, r.count { it.isSuccess }); assertTrue(r.single { it.isFailure }.exceptionOrNull() is ApiException)
        assertEquals(1L, f.count("orders")); assertEquals(1L, f.count("stock_reservations"))
        assertEquals(BigDecimal("0.75"), f.reserved()); assertEquals(BigDecimal("1.00"), f.stockPhysical())
        assertEquals(2L, f.count("cart"))
    }
    @Test fun `F2 stock changes after cart selection leave no partial order or reservation`() {
        add(); add(f.secondProduct)
        f.db.update("UPDATE products SET stock_physical=0 WHERE id=?", f.secondProduct)
        val req = f.request("1").copy(items = f.request("1").items + CheckoutItemRequest(f.secondProduct, BigDecimal.ONE, 8000))
        assertThrows(ApiException::class.java) { f.checkout.checkout(f.buyer, "f2-atomic", req) }
        assertEquals(0L, f.count("orders")); assertEquals(0L, f.count("order_items")); assertEquals(0L, f.count("stock_reservations"))
        assertEquals(BigDecimal("0.00"), f.reserved()); assertEquals(2L, f.count("cart"))
    }
    @Test fun `F2 cart lock remains compatible with F5 buyer history foreign keys`() {
        val order = f.checkout.checkout(f.buyer, "f2-fk-lock", f.request())
        val pool = Executors.newSingleThreadExecutor()
        try {
            TransactionTemplate(transactions).execute {
                f.cart.get(f.buyer) // Keep the cart's buyer lock until the outer transaction ends.
                val pending = pool.submit(java.util.concurrent.Callable {
                    f.lifecycle.cancelByBuyer(f.buyer, order.orderId, OrderCancelRequest())
                })
                // F5 must be able to append history referencing this buyer while the cart lock is held.
                assertEquals("dibatalkan", pending.get(5, TimeUnit.SECONDS).orderStatus)
                add()
            }
            assertEquals(1, f.cart.get(f.buyer).items.size)
            assertEquals(BigDecimal("0.00"), f.reserved())
        } finally { pool.shutdown(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }
    @Test fun `F2 cancellation and expiration race release the same reservation once`() {
        val order = f.checkout.checkout(f.buyer, "f2-release", f.request())
        f.parallel({ f.lifecycle.cancelByBuyer(f.buyer, order.orderId, OrderCancelRequest()); true },
            { f.expiration.expire(order.orderId, f.farmer, order.expiresAt) })
        assertTrue(f.orderStatus(order.orderId) in listOf("dibatalkan", "kedaluwarsa"))
        assertEquals("released", f.reservationStatus(order.orderId)); assertEquals(BigDecimal("0.00"), f.reserved())
        assertEquals(BigDecimal("10.00"), f.stockPhysical()); assertEquals(1L, f.count("stock_reservations"))
        assertFalse(f.expiration.expire(order.orderId, f.farmer, order.expiresAt))
    }
}
