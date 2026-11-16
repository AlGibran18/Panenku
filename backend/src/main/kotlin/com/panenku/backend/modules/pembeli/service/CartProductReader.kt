package com.panenku.backend.modules.pembeli.service

import com.panenku.backend.modules.petani.dto.ProductPhotoAccess
import com.panenku.backend.modules.petani.service.ProductPhotoStorage
import com.panenku.backend.modules.shared.entity.Product
import com.panenku.backend.modules.shared.repository.*
import org.springframework.stereotype.Component
import java.util.UUID

internal data class CartProductState(val product: Product, val storeName: String?, val issue: String?, val photo: ProductPhotoAccess?)

/** Batch current product data; a cart never locks or reserves product stock. */
@Component
class CartProductReader(private val products: ProductRepository, private val farmers: FarmerProfileRepository,
    private val users: UserRepository, private val commodities: CommodityRepository, private val photos: ProductPhotoStorage) {
    internal fun read(ids: Collection<UUID>): Map<UUID, CartProductState> {
        if (ids.isEmpty()) return emptyMap()
        val rows = products.findAllById(ids.distinct())
        val farmerIds = rows.map { it.farmerId }.distinct()
        val profiles = farmers.findAllById(farmerIds).associateBy { it.id }
        val accounts = users.findAllById(farmerIds).associateBy { it.id }
        val categories = commodities.findAllById(rows.map { it.commodityId }.distinct()).associateBy { it.id }
        return rows.associate { p ->
            val farmer = profiles[p.farmerId]
            val user = accounts[p.farmerId]
            val issue = when {
                user == null || user.role != "petani" || farmer == null -> "FARMER_UNAVAILABLE"
                user.status != "active" -> "FARMER_SUSPENDED"
                farmer.ktpStatus != "disetujui" -> "FARMER_UNVERIFIED"
                categories[p.commodityId]?.isActive != true -> "COMMODITY_INACTIVE"
                p.publicationStatus != "aktif" -> "PRODUCT_INACTIVE"
                p.available.signum() <= 0 -> "OUT_OF_STOCK"
                else -> null
            }
            p.id to CartProductState(p, farmer?.storeName, issue, photos.access(p))
        }
    }
}
