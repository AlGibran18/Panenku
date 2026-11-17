package com.panenku.backend.modules.pembeli.service

import com.panenku.backend.common.exception.*
import com.panenku.backend.common.security.ActorService
import com.panenku.backend.modules.pembeli.dto.*
import com.panenku.backend.modules.shared.repository.*
import com.panenku.backend.modules.shared.service.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class CheckoutPreviewService(private val actors: ActorService, private val products: ProductRepository,
    private val users: UserRepository, private val farmers: FarmerProfileRepository, private val commodities: CommodityRepository,
    private val settings: SettingRepository, private val scheduling: SchedulingService,
    private val fulfillment: FulfillmentOptionsService, private val payments: PaymentDestinationService,
    private val quotes: CheckoutQuoteService) {
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun preview(actor: UUID, request: CheckoutPreviewRequest): CheckoutPreviewDTO {
        actors.require(actor,"pembeli")
        val input=CheckoutInputRules.normalize(request)
        val rows=products.findAllById(input.items.map { it.productId })
        if (rows.size != input.items.size) missing("Produk tidak ditemukan.")
        val farmerId=rows.first().farmerId
        if (rows.any { it.farmerId != farmerId }) invalid("Satu checkout hanya untuk satu petani.")
        val user=users.findById(farmerId).orElse(null)
        val farmer=farmers.findById(farmerId).orElse(null) ?: missing("Profil petani tidak ditemukan.")
        if (user?.role != "petani" || user.status != "active" || farmer.ktpStatus != "disetujui") conflict("Petani belum terverifikasi atau disuspend.")
        val categories=commodities.findAllById(rows.map { it.commodityId }.distinct()).associateBy { it.id }
        val quantities=input.items.associate { it.productId to it.quantityKg }
        if (rows.any { it.publicationStatus != "aktif" || categories[it.commodityId]?.isActive != true || quantities.getValue(it.id)>it.available })
            conflict("Produk atau stok tidak tersedia.")
        if (payments.listForBuyer(actor,farmerId).isEmpty()) conflict("Metode pembayaran petani belum tersedia.")
        scheduling.validate(input.scheduledDate,input.scheduledTimeSlot,rows.map { it.readyDate },CatalogService.split(farmer.timeSlots))
        if (input.fulfillmentMethod=="delivery" && input.deliveryAddress.isNullOrBlank()) invalid("Alamat pengantaran wajib diisi.")
        val token=fulfillment.token(rows,farmer,input.fulfillmentMethod,input.deliveryDistrict)
        val limit=settings.findById("transaction_limit").orElse(null)?.value?.toLongOrNull()?.takeIf { it>0 }
            ?: conflict("Limit transaksi belum dikonfigurasi dengan benar.")
        return quotes.review(actor,input,rows,farmer,categories.mapValues { it.value.name },limit,token)
    }
}
