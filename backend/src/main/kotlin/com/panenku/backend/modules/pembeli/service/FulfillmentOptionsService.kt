package com.panenku.backend.modules.pembeli.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.panenku.backend.common.exception.*
import com.panenku.backend.common.security.ActorService
import com.panenku.backend.modules.shared.dto.*
import com.panenku.backend.modules.shared.entity.*
import com.panenku.backend.modules.shared.repository.*
import com.panenku.backend.modules.shared.service.*
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.DateTimeException
import java.util.HexFormat

@Service
class FulfillmentOptionsService(private val actors: ActorService, private val products: ProductRepository,
    private val farmers: FarmerProfileRepository, private val users: UserRepository, private val commodities: CommodityRepository,
    private val scheduling: SchedulingService, private val mapper: ObjectMapper) {
    @Transactional(readOnly = true)
    fun options(actor: java.util.UUID, request: FulfillmentOptionsRequest): FulfillmentOptionsDTO {
        actors.require(actor, "pembeli")
        if (request.days !in 1..31 || request.items.isEmpty() || request.items.size > 100 ||
            request.items.map { it.productId }.distinct().size != request.items.size) invalid("Isi 1–100 produk unik dan rentang 1–31 hari.")
        val quantities = request.items.associate { it.productId to CheckoutMath.kg(it.quantityKg) }
        val rows = products.findAllById(quantities.keys)
        if (rows.size != quantities.size) missing("Produk tidak ditemukan.")
        val farmerId = rows.first().farmerId
        if (rows.any { it.farmerId != farmerId }) invalid("Satu pilihan jadwal hanya untuk satu petani.")
        val user = users.findById(farmerId).orElse(null)
        val profile = farmers.findById(farmerId).orElse(null) ?: missing("Profil petani tidak ditemukan.")
        if (user?.role != "petani" || user.status != "active" || profile.ktpStatus != "disetujui") conflict("Petani tidak tersedia untuk pembelian baru.")
        val categories = commodities.findAllById(rows.map { it.commodityId }.distinct()).associateBy { it.id }
        if (rows.any { it.publicationStatus != "aktif" || categories[it.commodityId]?.isActive != true || quantities.getValue(it.id) > it.available })
            conflict("Produk atau stok tidak tersedia.")
        val settings = FulfillmentRules.resolve(profile, request.fulfillmentMethod, request.deliveryDistrict)
        val slots = scheduling.normalizeSlots(CatalogService.split(profile.timeSlots))
        if (slots.isEmpty()) conflict("Petani belum menyediakan slot waktu.")
        val at = scheduling.now()
        var earliest = maxOf(at.toLocalDate(), rows.maxOf { it.readyDate })
        try {
            if (scheduling.slotsOn(earliest, slots, at).isEmpty()) earliest = earliest.plusDays(1)
            val first = request.fromDate ?: earliest
            if (first < earliest) invalid("Tanggal awal sebelum jadwal paling awal yang tersedia.")
            val dates = (0 until request.days).map { offset ->
                val date = first.plusDays(offset.toLong()); ScheduleDayDTO(date, scheduling.slotsOn(date, slots, at))
            }
            return FulfillmentOptionsDTO(farmerId, profile.storeName, request.fulfillmentMethod, settings.district,
                settings.fee, settings.pickupLocation, settings.areas, scheduling.zone.id, at.toInstant(), earliest,
                dates, token(rows, profile, request.fulfillmentMethod, settings.district))
        } catch (_: DateTimeException) { invalid("Rentang tanggal tidak valid.") }
    }
    /** Called under checkout's existing product/profile locks. Preview never reserves stock. */
    fun verifyToken(expected: String?, rows: List<Product>, farmer: FarmerProfile, method: String, district: String?) {
        if (expected != null && expected != token(rows, farmer, method, district))
            throw ApiException(HttpStatus.CONFLICT, "FULFILLMENT_CHANGED", "Tanggal siap, lokasi, ongkir atau slot berubah. Muat ulang pilihan pemenuhan dan konfirmasi kembali.")
    }
    internal fun token(rows: List<Product>, profile: FarmerProfile, method: String, district: String?): String {
        val settings = FulfillmentRules.resolve(profile, method, district)
        val payload = listOf(profile.id, profile.storeName, method, settings, scheduling.normalizeSlots(CatalogService.split(profile.timeSlots)),
            rows.sortedBy { it.id }.map { listOf(it.id, it.readyDate) })
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(payload)))
    }
}
