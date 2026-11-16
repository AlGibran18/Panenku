package com.panenku.backend

import com.panenku.backend.common.exception.ApiException
import com.panenku.backend.modules.shared.dto.*
import com.panenku.backend.modules.shared.service.*
import com.panenku.backend.modules.pembeli.service.FulfillmentOptionsService
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
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.*
import java.util.UUID

@SpringBootTest(properties=["panenku.jwt.secret=test-only-32-byte-secret-for-tests-123456789", "panenku.jwt.issuer=panenku", "scheduler.enabled=false"])
@AutoConfigureMockMvc
@Testcontainers
@Import(CheckoutIntegrationTest.FixedTime::class)
class SchedulingIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        private val images = java.nio.file.Files.createTempDirectory("panenku-f3-images-")
        @DynamicPropertySource @JvmStatic fun database(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url",postgres::getJdbcUrl); r.add("spring.datasource.username",postgres::getUsername)
            r.add("spring.datasource.password",postgres::getPassword); r.add("panenku.storage.local-dir") { images.toString() }
        }
    }
    @Autowired lateinit var context: ApplicationContext
    @Autowired lateinit var options: FulfillmentOptionsService
    @Autowired lateinit var changes: OrderScheduleService
    @Autowired lateinit var profiles: ProfileService
    lateinit var f: CheckoutIntegrationTest
    @BeforeEach fun seed() { f=CheckoutIntegrationTest(); context.autowireCapableBeanFactory.autowireBean(f); f.seed() }
    private fun selection() = FulfillmentOptionsRequest(listOf(FulfillmentItemRequest(f.product,BigDecimal.ONE)),"pickup")
    private fun propose(order: UUID, key: String="proposal", version: Long=0, date: LocalDate=f.date.plusDays(1), slot: String="13:00-15:00") =
        changes.propose(f.farmer,order,key,ScheduleChangeRequest(version,date,slot,"Penyesuaian panen"))
    private fun accept(order: UUID, p: ScheduleChangeDTO) = changes.decide(f.buyer,order,p.id,ScheduleDecisionRequest(p.baseVersion,"terima"))
    private fun clock(date: LocalDate, time: String) { (f.clock as CheckoutIntegrationTest.TestClock).time.set(date.atTime(LocalTime.parse(time)).atZone(ZoneId.of("Asia/Jakarta")).toInstant()) }
    private fun code(expected: String, block: () -> Any?) {
        val e=assertThrows(ApiException::class.java) { block() }; assertEquals(expected,e.code); assertEquals(HttpStatus.CONFLICT,e.status)
    }
    private fun proof(): MockMultipartFile {
        val out=java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out)
        return MockMultipartFile("image","dummy.png","image/png",out.toByteArray())
    }
    @Test fun `F3 options follow latest product readiness and never reserve stock`() {
        f.db.update("UPDATE products SET ready_date=? WHERE id=?",f.date.plusDays(2),f.secondProduct)
        val r=selection().copy(items=selection().items+FulfillmentItemRequest(f.secondProduct,BigDecimal.ONE))
        val o=options.options(f.buyer,r)
        assertEquals(f.date.plusDays(2),o.earliestDate); assertEquals(14,o.dates.size)
        assertEquals(listOf("08:00-10:00","13:00-15:00"),o.dates.first().timeSlots)
        assertEquals("Asia/Jakarta",o.timeZone); assertEquals(0L,o.deliveryFee)
        assertEquals(0L,f.count("orders")); assertEquals(BigDecimal("0.00"),f.reserved())
    }
    @Test fun `F3 options remove started slots and move earliest date after the last slot`() {
        val today=LocalDate.parse("2026-11-10")
        f.db.update("UPDATE products SET ready_date=? WHERE id=?",today,f.product)
        clock(today,"08:00")
        assertEquals(listOf("13:00-15:00"),options.options(f.buyer,selection()).dates.first().timeSlots)
        clock(today,"13:00")
        assertEquals(today.plusDays(1),options.options(f.buyer,selection()).earliestDate)
    }
    @Test fun `F3 options reject invalid selections windows quantities and mixed farmers`() {
        val r=selection()
        listOf(r.copy(days=0),r.copy(days=32),r.copy(items=emptyList()),r.copy(items=r.items+r.items),
            r.copy(items=listOf(FulfillmentItemRequest(f.product,BigDecimal.ZERO))),
            r.copy(items=listOf(FulfillmentItemRequest(UUID.randomUUID(),BigDecimal.ONE))),
            r.copy(fromDate=f.date.minusDays(1)),r.copy(fromDate=LocalDate.MAX,days=2)).forEach {
            assertThrows(ApiException::class.java) { options.options(f.buyer,it) }
        }
        f.db.update("UPDATE products SET farmer_id=? WHERE id=?",f.otherFarmer,f.secondProduct)
        assertThrows(ApiException::class.java) { options.options(f.buyer,r.copy(items=r.items+FulfillmentItemRequest(f.secondProduct,BigDecimal.ONE))) }
        assertEquals(0L,f.count("orders"))
    }
    @Test fun `F3 delivery requires configured service and normalizes the selected district`() {
        val req=selection().copy(fulfillmentMethod="delivery",deliveryDistrict=" Kepanjen ")
        val o=options.options(f.buyer,req); assertEquals("kepanjen",o.deliveryDistrict); assertEquals(10000L,o.deliveryFee)
        val checkout=f.checkout.checkout(f.buyer,"f3-district",f.deliveryRequest().copy(deliveryDistrict=" Kepanjen ",expectedFulfillmentToken=o.selectionToken))
        assertEquals("kepanjen",checkout.deliveryDistrictSnapshot)
        f.db.update("UPDATE farmer_profiles SET shipping_enabled=false WHERE id=?",f.farmer)
        assertThrows(ApiException::class.java) { options.options(f.buyer,req) }
    }
    @Test fun `F3 HTTP options enforce active buyer authentication`() {
        val body=f.mapper.writeValueAsBytes(selection())
        f.mvc.perform(post("/api/v1/buyer/fulfillment-options").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized)
        f.mvc.perform(post("/api/v1/buyer/fulfillment-options").with(jwt().jwt { it.subject(f.farmer.toString()) }).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden)
        f.mvc.perform(post("/api/v1/buyer/fulfillment-options").with(jwt().jwt { it.subject(f.buyer.toString()) }).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.timeZone").value("Asia/Jakarta"))
        f.db.update("UPDATE users SET status='suspended' WHERE id=?",f.buyer)
        assertThrows(ApiException::class.java) { options.options(f.buyer,selection()) }
    }
    @Test fun `F3 fulfillment preview token detects changed pickup location before checkout`() {
        val quote=options.options(f.buyer,selection())
        f.db.update("UPDATE farmer_profiles SET pickup_location='Lokasi baru' WHERE id=?",f.farmer)
        code("FULFILLMENT_CHANGED") { f.checkout.checkout(f.buyer,"f3-old-quote",f.request().copy(expectedFulfillmentToken=quote.selectionToken)) }
        assertEquals(0L,f.count("orders")); assertEquals(BigDecimal("0.00"),f.reserved())
        val fresh=options.options(f.buyer,selection())
        assertEquals("Lokasi baru",f.checkout.checkout(f.buyer,"f3-fresh",f.request().copy(expectedFulfillmentToken=fresh.selectionToken)).pickupLocationSnapshot)
    }
    @Test fun `F3 profile shares overlap validation and preserves data on invalid update`() {
        val original=profiles.farmer(f.farmer)
        val req=FarmerProfileRequest("Toko",original.pickupLocation,listOf("Kepanjen"),10000,true,listOf("08:00-10:00","09:00-11:00"))
        assertThrows(ApiException::class.java) { profiles.updateFarmer(f.farmer,req) }
        assertEquals(original,profiles.farmer(f.farmer))
        assertEquals(listOf("08:00-10:00","10:00-12:00"),profiles.updateFarmer(f.farmer,req.copy(timeSlots=listOf("10:00-12:00","08:00-10:00"))).timeSlots)
    }
    @Test fun `F3 actual fulfillment waits for schedule but uses immutable product readiness`() {
        val order=f.orderFor(status="diproses",payment="terverifikasi")
        code("SCHEDULE_NOT_STARTED") { f.lifecycle.updateFulfillment(f.farmer,order,FulfillmentUpdateRequest("siap_diambil")) }
        assertEquals("diproses",f.orderStatus(order)); assertEquals("active",f.reservationStatus(order))
        f.db.update("UPDATE products SET ready_date=? WHERE id=?",f.date.plusDays(20),f.product)
        clock(f.date,"08:00")
        assertEquals("siap_diambil",f.lifecycle.updateFulfillment(f.farmer,order,FulfillmentUpdateRequest("siap_diambil")).orderStatus)
        assertEquals("menunggu_penerimaan",f.lifecycle.updateFulfillment(f.farmer,order,FulfillmentUpdateRequest("menunggu_penerimaan")).orderStatus)
    }
    @Test fun `F3 paid delivery may finish late and failure may be declared before schedule`() {
        val failed=f.orderFor(status="diproses",payment="terverifikasi")
        assertEquals("gagal_dipenuhi",f.lifecycle.updateFulfillment(f.farmer,failed,FulfillmentUpdateRequest("gagal_dipenuhi","Panen gagal")).orderStatus)
        val delivery=f.orderFor(req=f.deliveryRequest(),status="diproses",payment="terverifikasi")
        clock(f.date.plusDays(1),"16:00")
        assertEquals("diantar",f.lifecycle.updateFulfillment(f.farmer,delivery,FulfillmentUpdateRequest("diantar")).orderStatus)
    }
    @Test fun `F3 proposal does not change schedule price deadline or reserved stock before approval`() {
        val order=f.checkout.checkout(f.buyer,"f3-original",f.request())
        val p=propose(order.orderId)
        val view=f.lifecycle.detailForBuyer(f.buyer,order.orderId)
        assertEquals(f.date,view.scheduledDate); assertEquals(f.date,view.effectiveSchedule.scheduledDate)
        assertEquals(0L,view.effectiveSchedule.version); assertEquals(p.id,view.pendingScheduleChange!!.id)
        assertEquals(order.expiresAt,view.expiresAt); assertEquals(BigDecimal("0.75"),f.reserved())
        code("SCHEDULE_CHANGE_PENDING") { f.lifecycle.respondByFarmer(f.farmer,order.orderId,OrderRespondRequest("terima")) }
    }
    @Test fun `F3 buyer approval changes only effective schedule and preserves checkout replay`() {
        val original=f.checkout.checkout(f.buyer,"f3-replay",f.request()); val p=propose(original.orderId)
        val accepted=accept(original.orderId,p)
        assertEquals("accepted",accepted.status); assertEquals(1L,accepted.acceptedVersion)
        val view=f.lifecycle.detailForBuyer(f.buyer,original.orderId)
        assertEquals(f.date,view.scheduledDate); assertEquals(f.date.plusDays(1),view.effectiveSchedule.scheduledDate)
        assertEquals(1L,view.effectiveSchedule.version); assertNull(view.pendingScheduleChange)
        assertEquals(original,f.checkout.checkout(f.buyer,"f3-replay",f.request()))
        assertEquals(original.totalPayment,view.totalPayment); assertEquals(original.expiresAt,view.expiresAt)
        assertEquals(accepted,accept(original.orderId,p)); assertEquals(3L,f.count("order_status_history"))
    }
    @Test fun `F3 buyer rejection is final retryable and leaves old schedule`() {
        val order=f.orderFor(); val p=propose(order)
        val decision=ScheduleDecisionRequest(0,"tolak","Tidak cocok")
        val result=changes.decide(f.buyer,order,p.id,decision)
        assertEquals("rejected",result.status); assertEquals(result,changes.decide(f.buyer,order,p.id,decision))
        assertEquals(0L,f.lifecycle.detailForBuyer(f.buyer,order).effectiveSchedule.version)
        code("SCHEDULE_ALREADY_DECIDED") { accept(order,p) }
    }
    @Test fun `F3 proposal keys and schedule versions prevent stale overwrite`() {
        val order=f.orderFor(); val p=propose(order)
        assertEquals(p,propose(order))
        code("SCHEDULE_KEY_REUSED") { propose(order,date=f.date.plusDays(2)) }
        accept(order,p)
        code("SCHEDULE_CHANGED") { propose(order,"new-key",date=f.date.plusDays(2)) }
        val next=propose(order,"next",version=1,date=f.date.plusDays(2)); accept(order,next)
        assertEquals(2L,f.lifecycle.detailForBuyer(f.buyer,order).effectiveSchedule.version)
    }
    @Test fun `F3 concurrent proposals with different keys create exactly one pending proposal`() {
        val order=f.orderFor()
        val r=f.parallel({ propose(order,"a") },{ propose(order,"b") })
        assertEquals(1,r.count { it.isSuccess }); assertEquals(1L,f.count("order_schedule_changes"))
    }
    @Test fun `F3 concurrent same proposal retries do not duplicate history`() {
        val order=f.orderFor(); val r=f.parallel({ propose(order) },{ propose(order) })
        assertTrue(r.all { it.isSuccess },r.toString()); assertEquals(r[0].getOrThrow(),r[1].getOrThrow())
        assertEquals(1L,f.count("order_schedule_changes")); assertEquals(2L,f.count("order_status_history"))
    }
    @Test fun `F3 simultaneous accept and reject apply one buyer decision`() {
        val order=f.orderFor(); val p=propose(order)
        val r=f.parallel({ accept(order,p) },{ changes.decide(f.buyer,order,p.id,ScheduleDecisionRequest(0,"tolak")) })
        assertEquals(1,r.count { it.isSuccess }); assertEquals(3L,f.count("order_status_history"))
        assertNotEquals("pending",changes.list(f.buyer,"pembeli",order,0,20).items.single().status)
    }
    @Test fun `F3 snapshot readiness is used for proposals even after product date changes`() {
        val order=f.orderFor()
        f.db.update("UPDATE products SET ready_date=? WHERE id=?",f.date.plusDays(10),f.product)
        val p=propose(order,date=f.date,slot="13:00-15:00"); accept(order,p)
        assertEquals(f.date,f.lifecycle.detailForBuyer(f.buyer,order).effectiveSchedule.scheduledDate)
    }
    @Test fun `F3 invalid proposal date slot reason and unchanged schedule are rejected`() {
        val order=f.orderFor()
        listOf(ScheduleChangeRequest(0,f.date.minusDays(1),"08:00-10:00","Alasan"),
            ScheduleChangeRequest(0,f.date,"08:00-10:00","Alasan"),
            ScheduleChangeRequest(0,f.date,"09:00-11:00","Alasan"),
            ScheduleChangeRequest(0,f.date.plusDays(1),"08:00-10:00"," ")).forEachIndexed { i,req ->
            assertThrows(ApiException::class.java) { changes.propose(f.farmer,order,"invalid-$i",req) }
        }
        assertEquals(0L,f.count("order_schedule_changes"))
    }
    @Test fun `F3 approval revalidates configured slots while rejection remains possible`() {
        val order=f.orderFor(); val p=propose(order)
        f.db.update("UPDATE farmer_profiles SET time_slots='08:00-10:00' WHERE id=?",f.farmer)
        assertThrows(ApiException::class.java) { accept(order,p) }
        assertEquals("pending",changes.list(f.buyer,"pembeli",order,0,20).items.single().status)
        assertEquals("rejected",changes.decide(f.buyer,order,p.id,ScheduleDecisionRequest(0,"tolak")).status)
    }
    @Test fun `F3 pending schedule blocks payment selection and proof until approved`() {
        val order=f.orderFor(); f.lifecycle.respondByFarmer(f.farmer,order,OrderRespondRequest("terima"))
        f.paymentDestinations.chooseForBuyer(f.buyer,order,f.paymentOption.id)
        val p=propose(order)
        code("SCHEDULE_CHANGE_PENDING") { f.paymentDestinations.chooseForBuyer(f.buyer,order,f.paymentOption.id) }
        code("SCHEDULE_CHANGE_PENDING") { f.paymentSubmissions.submit(f.buyer,order,"f3-proof",proof()) }
        assertEquals(0L,f.count("payment_submissions")); accept(order,p)
        assertEquals("menunggu_verifikasi",f.paymentSubmissions.submit(f.buyer,order,"f3-proof",proof()).status)
        code("SCHEDULE_CHANGE_UNAVAILABLE") { propose(order,"after-proof",version=1,date=f.date.plusDays(2)) }
    }
    @Test fun `F3 proposal does not pause expiration or keep stock after order expires`() {
        val order=f.checkout.checkout(f.buyer,"f3-expiration",f.request()); val p=propose(order.orderId)
        (f.clock as CheckoutIntegrationTest.TestClock).time.set(order.expiresAt)
        assertTrue(f.expiration.expire(order.orderId,f.farmer,order.expiresAt))
        assertThrows(ApiException::class.java) { accept(order.orderId,p) }
        assertEquals(BigDecimal("0.00"),f.reserved()); assertEquals("released",f.reservationStatus(order.orderId))
    }
    @Test fun `F3 proposals and decisions are private and cannot be forged by another buyer`() {
        val order=f.orderFor(); val p=propose(order)
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ApiException::class.java) { changes.decide(f.otherBuyer,order,p.id,ScheduleDecisionRequest(0,"terima")) }.status)
        assertThrows(ApiException::class.java) { changes.propose(f.otherFarmer,order,"other",ScheduleChangeRequest(0,f.date.plusDays(2),"08:00-10:00","Alasan")) }
        assertThrows(ApiException::class.java) { changes.list(f.otherBuyer,"pembeli",order,0,20) }
        f.mvc.perform(get("/api/v1/buyer/orders/$order/schedule-changes")).andExpect(status().isUnauthorized)
        f.mvc.perform(get("/api/v1/buyer/orders/$order/schedule-changes").with(jwt().jwt { it.subject(f.buyer.toString()) }))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.items[0].status").value("pending"))
    }
    @Test fun `F3 immutable schedule history and original order snapshots reject SQL tampering`() {
        val order=f.orderFor(); val p=propose(order); accept(order,p)
        assertThrows(Exception::class.java) { f.db.update("UPDATE order_schedule_changes SET proposed_date=? WHERE id=?",f.date.plusDays(9),p.id) }
        assertThrows(Exception::class.java) { f.db.update("DELETE FROM order_schedule_changes WHERE id=?",p.id) }
        assertThrows(Exception::class.java) { f.db.update("UPDATE orders SET scheduled_date=? WHERE id=?",f.date.plusDays(9),order) }
    }
    @Test fun `F3 failed history insert rolls buyer decision and effective schedule back together`() {
        val order=f.orderFor(); val p=propose(order)
        f.db.execute("CREATE FUNCTION f3_history_failure() RETURNS TRIGGER LANGUAGE plpgsql AS 'BEGIN IF NEW.actor_role = ''pembeli'' THEN RAISE EXCEPTION ''simulated schedule history failure''; END IF; RETURN NEW; END;'")
        f.db.execute("CREATE TRIGGER f3_history_failure BEFORE INSERT ON order_status_history FOR EACH ROW EXECUTE FUNCTION f3_history_failure()")
        try {
            assertThrows(Exception::class.java) { accept(order,p) }
            assertEquals("pending",changes.list(f.buyer,"pembeli",order,0,20).items.single().status)
            assertEquals(0L,f.lifecycle.detailForBuyer(f.buyer,order).effectiveSchedule.version)
        } finally { f.db.execute("DROP TRIGGER f3_history_failure ON order_status_history"); f.db.execute("DROP FUNCTION f3_history_failure()") }
    }
    @Test fun `F3 approved effective schedule controls fulfillment rather than original date`() {
        val order=f.orderFor(); val p=propose(order); accept(order,p)
        f.db.update("UPDATE orders SET order_status='diproses',payment_status='terverifikasi' WHERE id=?",order)
        clock(f.date,"13:00")
        code("SCHEDULE_NOT_STARTED") { f.lifecycle.updateFulfillment(f.farmer,order,FulfillmentUpdateRequest("siap_diambil")) }
        clock(p.proposedDate,p.proposedSlot.substring(0,5))
        assertEquals("siap_diambil",f.lifecycle.updateFulfillment(f.farmer,order,FulfillmentUpdateRequest("siap_diambil")).orderStatus)
    }
    @Test fun `F3 expired same day slot requires approval before farmer accepts`() {
        val today=LocalDate.parse("2026-11-10")
        f.db.update("UPDATE products SET ready_date=? WHERE id=?",today,f.product)
        val order=f.checkout.checkout(f.buyer,"f3-same-day",f.request().copy(scheduledDate=today))
        clock(today,"08:00")
        code("SCHEDULE_ELAPSED") { f.lifecycle.respondByFarmer(f.farmer,order.orderId,OrderRespondRequest("terima")) }
        val p=propose(order.orderId,date=today,slot="13:00-15:00"); accept(order.orderId,p)
        assertEquals("menunggu_pembayaran",f.lifecycle.respondByFarmer(f.farmer,order.orderId,OrderRespondRequest("terima")).orderStatus)
    }
    @Test fun `F3 HTTP proposal and approval expose the effective schedule without altering snapshot`() {
        val order=f.orderFor()
        val body=f.mapper.writeValueAsBytes(ScheduleChangeRequest(0,f.date.plusDays(1),"13:00-15:00","Panen mundur"))
        val response=f.mvc.perform(post("/api/v1/petani/orders/$order/schedule-changes")
            .with(jwt().jwt { it.subject(f.farmer.toString()) }).header("Idempotency-Key","http-schedule")
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("pending")).andReturn().response.contentAsString
        val id=f.mapper.readTree(response).path("data").path("id").asText()
        f.mvc.perform(put("/api/v1/buyer/orders/$order/schedule-changes/$id/decision")
            .with(jwt().jwt { it.subject(f.buyer.toString()) }).contentType(MediaType.APPLICATION_JSON)
            .content(f.mapper.writeValueAsBytes(ScheduleDecisionRequest(0,"terima"))))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.acceptedVersion").value(1))
        f.mvc.perform(get("/api/v1/buyer/orders/$order").with(jwt().jwt { it.subject(f.buyer.toString()) }))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.scheduledDate").value(f.date.toString()))
            .andExpect(jsonPath("$.data.effectiveSchedule.scheduledDate").value(f.date.plusDays(1).toString()))
    }
    @Test fun `F3 null list members return bad request rather than server errors`() {
        f.mvc.perform(post("/api/v1/buyer/fulfillment-options").with(jwt().jwt { it.subject(f.buyer.toString()) })
            .contentType(MediaType.APPLICATION_JSON).content("""{"items":[null],"fulfillmentMethod":"pickup"}"""))
            .andExpect(status().isBadRequest)
        val profile="""{"storeName":"Toko","pickupLocation":"Lokasi","serviceArea":["kepanjen"],"deliveryFee":0,"shippingEnabled":true,"timeSlots":[null]}"""
        f.mvc.perform(put("/api/v1/petani/profile").with(jwt().jwt { it.subject(f.farmer.toString()) })
            .contentType(MediaType.APPLICATION_JSON).content(profile)).andExpect(status().isBadRequest)
        f.mvc.perform(put("/api/v1/petani/profile").with(jwt().jwt { it.subject(f.farmer.toString()) })
            .contentType(MediaType.APPLICATION_JSON).content(profile.replace("[null]","[\"08:00-10:00\"]").replace("[\"kepanjen\"]","[null]")))
            .andExpect(status().isBadRequest)
    }
    @Test fun `F3 proof submission racing with a proposal cannot apply both incompatible changes`() {
        val order=f.orderFor(); f.lifecycle.respondByFarmer(f.farmer,order,OrderRespondRequest("terima"))
        f.paymentDestinations.chooseForBuyer(f.buyer,order,f.paymentOption.id)
        val r=f.parallel({ propose(order).status },{ f.paymentSubmissions.submit(f.buyer,order,"f3-race-proof",proof()).status })
        assertEquals(1,r.count { it.isSuccess },r.toString())
        assertEquals(1L,f.count("orders")); assertEquals("active",f.reservationStatus(order))
        assertTrue(f.count("payment_submissions")+f.count("order_schedule_changes")==1L)
    }
}
