package com.panenku.backend.modules.shared.service

import com.panenku.backend.common.exception.*
import com.panenku.backend.modules.shared.dto.*
import com.panenku.backend.modules.shared.entity.*
import com.panenku.backend.modules.petani.service.ProductPhotoStorage
import com.panenku.backend.modules.shared.repository.*
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.util.Locale

@Service
@Transactional(readOnly = true)
class CatalogService(
    private val products: ProductRepository, private val commodities: CommodityRepository,
    private val farmers: FarmerProfileRepository, private val accounts: UserRepository,
    private val photos: ProductPhotoStorage, private val ratings: FarmerRatingRepository
) {
    fun list(search: String, commodityId: UUID?, sort: String, page: Int, size: Int): PageDTO<ProductDTO> {
        if (page < 0 || size !in 1..100 || search.length > 100) invalid("Paginasi atau pencarian tidak valid.")
        val direction = when (sort) { "price_asc" -> Sort.Direction.ASC; "price_desc" -> Sort.Direction.DESC; else -> invalid("Urutan harus price_asc atau price_desc.") }
        val escaped = search.trim().lowercase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_")
        val result = products.catalog(commodityId, "%$escaped%", PageRequest.of(page, size, Sort.by(direction, "pricePerKg").and(Sort.by("id"))))
        return PageDTO(dtos(result.content), page, size, result.totalElements)
    }
    fun detail(id: UUID): ProductDetailDTO {
        val product = products.findById(id).orElseGet { missing("Produk tidak ditemukan.") }
        requireAvailable(product, allowSoldOut = true)
        val farmer = farmers.findById(product.farmerId).orElseThrow()
        return ProductDetailDTO(dto(product), farmer.pickupLocation, farmer.shippingEnabled, farmer.deliveryFee,
            split(farmer.serviceArea), split(farmer.timeSlots), ratings.summary(product.farmerId))
    }
    fun requireAvailable(product: Product, allowSoldOut: Boolean = false) {
        if (!isAvailable(product, allowSoldOut)) conflict("Produk atau petani tidak tersedia untuk pembelian baru.")
    }
    fun isAvailable(product: Product, allowSoldOut: Boolean = false): Boolean {
        val user = accounts.findById(product.farmerId).orElse(null)
        val farmer = farmers.findById(product.farmerId).orElse(null)
        val commodity = commodities.findById(product.commodityId).orElse(null)
        return user?.status == "active" && user.role == "petani" && farmer?.ktpStatus == "disetujui" &&
            commodity?.isActive == true && product.publicationStatus == "aktif" && (allowSoldOut || product.available.signum() > 0)
    }
    fun dto(p: Product): ProductDTO = dtos(listOf(p)).single()

    fun dtos(rows: List<Product>): List<ProductDTO> {
        if (rows.isEmpty()) return emptyList()
        val farmerIds = rows.map { it.farmerId }.distinct()
        val profiles = farmers.findAllById(farmerIds).associateBy { it.id }
        val users = accounts.findAllById(farmerIds).associateBy { it.id }
        val categories = commodities.findAllById(rows.map { it.commodityId }.distinct()).associateBy { it.id }
        return rows.map { p ->
            val farmer = profiles[p.farmerId] ?: missing("Profil petani tidak ditemukan.")
            val category = categories[p.commodityId] ?: missing("Komoditas tidak ditemukan.")
            val user = users[p.farmerId]
            val verified = farmer.ktpStatus == "disetujui"
            val available = verified && user?.role == "petani" && user.status == "active" && category.isActive &&
                p.publicationStatus == "aktif" && p.available.signum() > 0
            val link = photos.access(p)
            ProductDTO(p.id, p.farmerId, farmer.storeName, p.commodityId, category.name, p.name, p.description, p.photoUrl,
                p.pricePerKg, p.stockPhysical, p.stockReserved, p.available, p.readyDate, p.publicationStatus,
                verified, available, link?.url, link?.expiresAt)
        }
    }
    fun commodities(): List<CommodityDTO> = commodities.findAllByIsActiveTrueOrderByName().map { CommodityDTO(it.id, it.name, it.isActive) }
    companion object { fun split(value: String): List<String> = value.split('|').map(String::trim).filter(String::isNotEmpty) }
}
