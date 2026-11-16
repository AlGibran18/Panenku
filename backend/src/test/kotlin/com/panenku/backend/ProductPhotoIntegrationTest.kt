package com.panenku.backend

import com.panenku.backend.common.exception.ApiException
import com.panenku.backend.modules.petani.dto.ProductRequest
import com.panenku.backend.modules.petani.service.ProductPhotoService
import com.panenku.backend.modules.shared.dto.CommodityRequest
import com.panenku.backend.modules.shared.service.CommodityService
import io.minio.SetBucketPolicyArgs
import io.minio.DeleteBucketPolicyArgs
import io.minio.ListObjectsArgs
import io.minio.MinioClient
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
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

@SpringBootTest(properties=["panenku.jwt.secret=test-only-32-byte-secret-for-tests-123456789", "panenku.jwt.issuer=panenku", "scheduler.enabled=false", "panenku.product-photos.enabled=true"])
@AutoConfigureMockMvc
@Testcontainers
@Import(CheckoutIntegrationTest.FixedTime::class)
class ProductPhotoIntegrationTest {
    class ProductMinio(image: String) : GenericContainer<ProductMinio>(DockerImageName.parse(image))
    companion object {
        private const val IMAGE = "panenku-product-minio:9e49d5e"
        private fun image(): String {
            val selected = System.getProperty("productPhotos.test.image", IMAGE)
            try { DockerClientFactory.instance().client().inspectImageCmd(selected).exec(); return selected }
            catch (_: com.github.dockerjava.api.exception.NotFoundException) { }
            require(selected == IMAGE) { "Build or pull the configured productPhotos.test.image first." }
            val log = File("build/product-photos-image-build.log").apply { parentFile.mkdirs() }
            val args = mutableListOf("docker", "build", "-f", "docker/product-photos/Dockerfile", "-t", IMAGE)
            System.getenv("PRODUCT_STORAGE_BUILD_CA_FILE")?.takeIf { it.isNotBlank() }?.let {
                args += listOf("--secret", "id=proxy_ca,src=$it")
            }
            args += "."
            val process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log).start()
            check(process.waitFor(15, TimeUnit.MINUTES)) { process.destroyForcibly(); "MinIO source build timed out: ${log.absolutePath}" }
            check(process.exitValue() == 0) { "MinIO image build failed; see ${log.absolutePath}" }
            return IMAGE
        }
        @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @Container @JvmStatic val minio = ProductMinio(image())
            .withEnv("MINIO_ROOT_USER", "f1-test-user").withEnv("MINIO_ROOT_PASSWORD", "f1-test-password-123")
            .withExposedPorts(9000).waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000)).withStartupTimeout(Duration.ofMinutes(2))
        @DynamicPropertySource @JvmStatic fun properties(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url", postgres::getJdbcUrl)
            r.add("spring.datasource.username", postgres::getUsername)
            r.add("spring.datasource.password", postgres::getPassword)
            r.add("panenku.product-photos.endpoint") { "http://${minio.host}:${minio.getMappedPort(9000)}" }
            r.add("panenku.product-photos.public-endpoint") { "http://${minio.host}:${minio.getMappedPort(9000)}" }
            r.add("panenku.product-photos.access-key") { "f1-test-user" }
            r.add("panenku.product-photos.secret-key") { "f1-test-password-123" }
        }
    }
    @Autowired lateinit var context: ApplicationContext
    @Autowired lateinit var photos: ProductPhotoService
    @Autowired lateinit var commodities: CommodityService
    lateinit var f: CheckoutIntegrationTest
    @BeforeEach fun seed() { f = CheckoutIntegrationTest(); context.autowireCapableBeanFactory.autowireBean(f); f.seed() }
    fun bytes(format: String = "png", color: Int = 0xff0000): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        repeat(2) { x -> repeat(2) { y -> image.setRGB(x, y, color) } }
        return ByteArrayOutputStream().use { out -> check(ImageIO.write(image, format, out)); out.toByteArray() }
    }
    fun file(data: ByteArray = bytes(), type: String = "image/png") = MockMultipartFile("file", "../../private-name.png", type, data)
    fun draft() = f.productService.save(f.farmer, null, ProductRequest(f.commodity, "Foto F1", 12000, BigDecimal.TEN, f.date))
    fun publish(id: UUID) = f.productService.save(f.farmer, id, ProductRequest(f.commodity, "Foto F1", 12000, BigDecimal.TEN, f.date, "aktif"))
    fun http(url: String) = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
    fun objects(id: UUID): Int {
        val client = MinioClient.builder().endpoint("http://${minio.host}:${minio.getMappedPort(9000)}").credentials("f1-test-user", "f1-test-password-123").build()
        return client.listObjects(ListObjectsArgs.builder().bucket("panenku-products").prefix("products/${f.farmer}/$id/").build()).count()
    }
    @Test fun `F1 real uploaded photo is private and has expiring access URL`() {
        val product = draft(); val data = bytes()
        val photo = photos.upload(f.farmer, product.id, file(data))
        assertTrue(photo.photoUrl.startsWith("products/${f.farmer}/${product.id}/"))
        assertFalse(photo.photoUrl.contains("private-name"))
        assertEquals("image/png", photo.contentType); assertEquals(data.size.toLong(), photo.sizeBytes)
        val signed = http(photo.photoAccessUrl)
        assertEquals(200, signed.statusCode()); assertArrayEquals(data, signed.body())
        assertEquals(403, http(photo.photoAccessUrl.substringBefore('?')).statusCode())
        assertTrue(Duration.between(Instant.now(), photo.photoExpiresAt).seconds in 850..900)
        val active = publish(product.id)
        assertEquals(photo.photoUrl, active.photoUrl); assertNotNull(active.photoAccessUrl)
        assertTrue(active.farmerVerified); assertTrue(active.availableForPurchase)
        assertEquals(200, http(f.catalog.detail(product.id).product.photoAccessUrl!!).statusCode())
    }
    @Test fun `F1 farmer can replace JPEG and PNG photo without exposing old object path`() {
        val product = draft()
        val first = photos.upload(f.farmer, product.id, file())
        val secondData = bytes("jpeg", 0x0000ff)
        val second = photos.upload(f.farmer, product.id, file(secondData, "image/jpeg"))
        assertNotEquals(first.photoUrl, second.photoUrl); assertTrue(second.photoUrl.endsWith(".jpg"))
        assertEquals(second.photoUrl, f.productService.detail(f.farmer, product.id).photoUrl)
        assertArrayEquals(secondData, http(second.photoAccessUrl).body())
        assertThrows(ApiException::class.java) { f.catalog.detail(product.id) }
    }
    @Test fun `F1 photo endpoint enforces authentication owner and multipart request`() {
        val product = draft()
        f.mvc.perform(multipart("/api/v1/petani/products/${product.id}/photo").file(file())).andExpect(status().isUnauthorized)
        f.mvc.perform(multipart("/api/v1/petani/products/${product.id}/photo").file(file()).with(jwt().jwt { it.subject(f.otherFarmer.toString()) }))
            .andExpect(status().isForbidden)
        f.mvc.perform(multipart("/api/v1/petani/products/${product.id}/photo").with(jwt().jwt { it.subject(f.farmer.toString()) }))
            .andExpect(status().isBadRequest)
        f.mvc.perform(multipart("/api/v1/petani/products/${product.id}/photo").file(file()).with(jwt().jwt { it.subject(f.farmer.toString()) }))
            .andExpect(status().isCreated).andExpect(jsonPath("$.data.photoAccessUrl").exists())
        assertEquals(1, objects(product.id))
    }
    @Test fun `F1 upload refuses a publicly readable bucket`() {
        val product = draft()
        photos.upload(f.farmer, product.id, file())
        val client = MinioClient.builder().endpoint("http://${minio.host}:${minio.getMappedPort(9000)}")
            .credentials("f1-test-user", "f1-test-password-123").build()
        val policy = """{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},"Action":["s3:GetObject"],"Resource":["arn:aws:s3:::panenku-products/*"]}]}"""
        client.setBucketPolicy(SetBucketPolicyArgs.builder().bucket("panenku-products").config(policy).build())
        try {
            val error = assertThrows(ApiException::class.java) { photos.upload(f.farmer, product.id, file()) }
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status)
            assertEquals("PHOTO_BUCKET_NOT_PRIVATE", error.code)
            assertEquals(1, objects(product.id))
        } finally { client.deleteBucketPolicy(DeleteBucketPolicyArgs.builder().bucket("panenku-products").build()) }
    }
    @Test fun `F1 invalid spoofed empty and oversized photos are rejected before upload`() {
        val product = draft()
        listOf(file(byteArrayOf()), file("not an image".toByteArray()), file(bytes(), "image/jpeg"), file(bytes(), "image/svg+xml")).forEach {
            val error = assertThrows(ApiException::class.java) { photos.upload(f.farmer, product.id, it) }
            assertEquals(HttpStatus.BAD_REQUEST, error.status)
        }
        val error = assertThrows(ApiException::class.java) { photos.upload(f.farmer, product.id, file(ByteArray(5 * 1024 * 1024 + 1))) }
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, error.status)
        assertNull(f.productService.detail(f.farmer, product.id).photoUrl)
    }
    @Test fun `F1 suspended or unverified farmer cannot publish and arbitrary photo path is rejected`() {
        val product = draft(); photos.upload(f.farmer, product.id, file())
        assertThrows(ApiException::class.java) {
            f.productService.save(f.farmer, null, ProductRequest(f.commodity, "Forged", 12000, BigDecimal.ONE, f.date, photoUrl = "ktp/private.png"))
        }
        f.db.update("UPDATE farmer_profiles SET ktp_status='menunggu' WHERE id=?", f.farmer)
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException::class.java) { publish(product.id) }.status)
        f.db.update("UPDATE users SET status='suspended' WHERE id=?", f.farmer)
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException::class.java) { photos.upload(f.farmer, product.id, file()) }.status)
        assertFalse(f.productService.list(f.otherFarmer, 0, 20).items.any { it.id == product.id })
    }
    @Test fun `F1 replacing photo rolls back database and cleans new object when database fails`() {
        val product = draft(); val first = photos.upload(f.farmer, product.id, file())
        f.db.execute("CREATE FUNCTION f1_photo_failure() RETURNS TRIGGER LANGUAGE plpgsql AS 'BEGIN IF NEW.photo_url IS DISTINCT FROM OLD.photo_url THEN RAISE EXCEPTION ''simulated photo write failure''; END IF; RETURN NEW; END;'")
        f.db.execute("CREATE TRIGGER f1_photo_write_failure BEFORE UPDATE ON products FOR EACH ROW EXECUTE FUNCTION f1_photo_failure()")
        try {
            assertThrows(Exception::class.java) { photos.upload(f.farmer, product.id, file(bytes(color = 0x0000ff))) }
            assertEquals(first.photoUrl, f.productService.detail(f.farmer, product.id).photoUrl)
            assertEquals(1, objects(product.id))
        } finally { f.db.execute("DROP TRIGGER f1_photo_write_failure ON products"); f.db.execute("DROP FUNCTION f1_photo_failure()") }
    }
    @Test fun `F1 product changes preserve existing order snapshots and deactivation blocks new purchases`() {
        val product = draft(); photos.upload(f.farmer, product.id, file()); publish(product.id)
        val req = f.request().copy(items = listOf(com.panenku.backend.modules.pembeli.dto.CheckoutItemRequest(product.id, BigDecimal.ONE, 12000)))
        val order = f.checkout.checkout(f.buyer, "f1-snapshot", req)
        assertThrows(ApiException::class.java) {
            f.productService.save(f.farmer, product.id, ProductRequest(f.commodity, "Updated", 15000, BigDecimal("0.5"), f.date, "aktif"))
        }
        f.productService.save(f.farmer, product.id, ProductRequest(f.commodity, "Updated", 15000, BigDecimal.TEN, f.date.plusDays(2), "aktif"))
        assertEquals(order, f.checkout.checkout(f.buyer, "f1-snapshot", req))
        f.productService.deactivate(f.farmer, product.id)
        assertFalse(f.catalog.list("", null, "price_asc", 0, 100).items.any { it.id == product.id })
        assertThrows(ApiException::class.java) { f.checkout.checkout(f.buyer, "f1-disabled", req) }
    }
    @Test fun `F1 rating is null without reviews and aggregates only valid completed orders`() {
        val none = f.catalog.detail(f.product).farmerRating
        assertNull(none.average); assertEquals(0L, none.reviewCount)
        val first = f.orderFor(status = "selesai", payment = "terverifikasi")
        val second = f.orderFor(status = "selesai", payment = "terverifikasi", owner = f.otherBuyer)
        val unfinished = f.orderFor(status = "diproses", payment = "terverifikasi")
        listOf(Triple(first, f.buyer, 5), Triple(second, f.otherBuyer, 4), Triple(unfinished, f.buyer, 1)).forEach { (order, buyer, rating) ->
            f.db.update("INSERT INTO reviews(id,order_id,buyer_id,farmer_id,rating) VALUES (?,?,?,?,?)", UUID.randomUUID(), order, buyer, f.farmer, rating)
        }
        val result = f.catalog.detail(f.product).farmerRating
        assertEquals(BigDecimal("4.50"), result.average); assertEquals(2L, result.reviewCount)
    }
    @Test fun `F1 catalog keeps future and sold out products with truthful availability`() {
        assertTrue(f.catalog.detail(f.product).product.availableForPurchase)
        f.db.update("UPDATE products SET stock_physical=0 WHERE id=?", f.product)
        val dto = f.catalog.detail(f.product).product
        assertTrue(dto.farmerVerified); assertFalse(dto.availableForPurchase); assertEquals(f.date, dto.readyDate)
        assertTrue(f.catalog.list("", null, "price_asc", 0, 100).items.any { it.id == f.product })
    }
    @Test fun `F1 admin can create update and deactivate commodity without deleting order history`() {
        val admin = UUID.randomUUID()
        f.db.update("INSERT INTO users(id,email,password_hash,role) VALUES (?,?,'dummy','admin')", admin, "f1-admin@example.test")
        val category = commodities.save(admin, null, CommodityRequest("Cabai F1"))
        assertThrows(ApiException::class.java) { commodities.save(admin, null, CommodityRequest("cabai f1")) }
        assertThrows(ApiException::class.java) { commodities.save(f.buyer, null, CommodityRequest("Forbidden")) }
        commodities.save(admin, category.id, CommodityRequest("Cabai F1", false))
        assertFalse(f.catalog.commodities().any { it.id == category.id })
        assertTrue(commodities.list(admin).any { it.id == category.id && !it.isActive })
    }
}
