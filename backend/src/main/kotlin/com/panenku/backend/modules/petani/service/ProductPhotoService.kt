package com.panenku.backend.modules.petani.service

import com.panenku.backend.common.exception.*
import com.panenku.backend.common.security.ActorService
import com.panenku.backend.modules.petani.dto.ProductPhotoDTO
import com.panenku.backend.modules.shared.repository.FarmerProfileRepository
import com.panenku.backend.modules.shared.repository.ProductRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.multipart.MultipartFile
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class ProductPhotoService(private val actors: ActorService, private val farmers: FarmerProfileRepository,
    private val products: ProductRepository, private val storage: ProductPhotoStorage, private val clock: Clock) {
    private val logger = LoggerFactory.getLogger(javaClass)
    @Transactional
    fun upload(actor: UUID, productId: UUID, file: MultipartFile): ProductPhotoDTO {
        actors.requireLocked(actor, "petani")
        farmers.lockById(actor) ?: conflict("Lengkapi profil petani.")
        val product = products.lockById(productId) ?: missing("Produk tidak ditemukan.")
        if (product.farmerId != actor) forbidden("Produk bukan milik petani ini.")
        val image = ProductImageValidator.validate(file)
        val key = storage.put(product, image)
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    runCatching { storage.removeUncommitted(key) }.onFailure { logger.warn("Foto baru gagal dibersihkan setelah rollback produk {}", productId) }
                }
            }
        })
        product.photoUrl = key; product.updatedAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS)
        products.flush()
        val link = storage.signed(key)
        return ProductPhotoDTO(product.id, key, link.url, link.expiresAt, image.contentType, image.bytes.size.toLong())
    }
}
