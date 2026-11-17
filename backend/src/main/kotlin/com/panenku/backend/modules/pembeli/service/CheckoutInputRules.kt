package com.panenku.backend.modules.pembeli.service

import com.panenku.backend.common.exception.invalid
import com.panenku.backend.modules.pembeli.dto.*
import com.panenku.backend.modules.shared.service.CheckoutMath

/** Shared input validation before either a preview or a checkout touches persistence. */
object CheckoutInputRules {
    fun normalize(request: CheckoutRequest): CheckoutRequest {
        val entries: List<CheckoutItemRequest?> = request.items
        size(entries.size)
        val items = entries.map { item ->
            val row = item ?: invalid("Item checkout tidak boleh null.")
            if (row.expectedPricePerKg <= 0) invalid("Harga konfirmasi harus rupiah positif.")
            row.copy(quantityKg = CheckoutMath.kg(row.quantityKg))
        }.sortedBy { it.productId }
        if (items.map { it.productId }.distinct().size != items.size) invalid("Produk checkout tidak boleh duplikat.")
        text(request.buyerNotes,request.deliveryAddress,request.deliveryDistrict,request.scheduledTimeSlot)
        if (request.expectedDeliveryFee < 0) invalid("Ongkir konfirmasi tidak boleh negatif.")
        if (request.expectedCheckoutToken != null && !Regex("[a-fA-F0-9]{64}").matches(request.expectedCheckoutToken)) invalid("Token ringkasan checkout tidak valid.")
        return request.copy(items=items)
    }
    fun normalize(request: CheckoutPreviewRequest): CheckoutPreviewRequest {
        val entries: List<CheckoutPreviewItemRequest?> = request.items
        size(entries.size)
        val items = entries.map { item ->
            val row=item ?: invalid("Item pratinjau tidak boleh null.")
            row.copy(quantityKg=CheckoutMath.kg(row.quantityKg))
        }.sortedBy { it.productId }
        if (items.map { it.productId }.distinct().size != items.size) invalid("Produk pratinjau tidak boleh duplikat.")
        text(request.buyerNotes,request.deliveryAddress,request.deliveryDistrict,request.scheduledTimeSlot)
        return request.copy(items=items)
    }
    fun previewOf(request: CheckoutRequest) = CheckoutPreviewRequest(request.items.map { CheckoutPreviewItemRequest(it.productId,it.quantityKg) },
        request.fulfillmentMethod,request.scheduledDate,request.scheduledTimeSlot,request.deliveryAddress,request.deliveryDistrict,request.buyerNotes)
    private fun size(value: Int) { if (value !in 1..100) invalid("Isi 1–100 item produk unik.") }
    private fun text(notes: String?, address: String?, district: String?, slot: String) {
        if ((notes?.length ?: 0)>1000 || (address?.length ?: 0)>2000 || (district?.length ?: 0)>100 || slot.length>100) invalid("Teks request terlalu panjang.")
    }
}
