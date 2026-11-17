package com.panenku.backend.modules.pembeli.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.panenku.backend.common.exception.ApiException
import com.panenku.backend.modules.pembeli.dto.*
import com.panenku.backend.modules.shared.entity.*
import com.panenku.backend.modules.shared.service.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** One monetary calculation and signed review format for previews and locked checkouts. */
@Service
class CheckoutQuoteService(private val mapper: ObjectMapper, private val clock: Clock,
    @Value("\${panenku.jwt.secret}") private val secret: String) {
    internal fun review(actor: UUID, request: CheckoutPreviewRequest, rows: List<Product>, farmer: FarmerProfile,
        categories: Map<UUID,String>, limit: Long, fulfillmentToken: String): CheckoutPreviewDTO {
        val byId=rows.associateBy { it.id }
        val lines=request.items.sortedBy { it.productId }.map { selected ->
            val product=byId.getValue(selected.productId)
            CheckoutPreviewItemDTO(product.id,product.name,categories.getValue(product.commodityId),selected.quantityKg,
                product.pricePerKg,CheckoutMath.item(selected.quantityKg,product.pricePerKg),product.readyDate)
        }
        val fulfillment=FulfillmentRules.resolve(farmer,request.fulfillmentMethod,request.deliveryDistrict)
        val totals=CheckoutMath.summary(lines.map { it.itemSubtotal },fulfillment.fee)
        val allowed=CheckoutMath.withinLimit(totals.totalPayment,limit)
        val review=CheckoutPreviewDTO(farmer.id,farmer.storeName,lines,totals.subtotalItems,totals.deliveryFee,totals.totalPayment,
            totals.subtotalItems,limit,allowed,if (allowed) null else "TRANSACTION_LIMIT_EXCEEDED",request.fulfillmentMethod,
            request.scheduledDate,request.scheduledTimeSlot,fulfillment.pickupLocation,
            if (request.fulfillmentMethod=="delivery") request.deliveryAddress else null,fulfillment.district,request.buyerNotes,
            fulfillmentToken,Instant.now(clock).truncatedTo(ChronoUnit.MICROS),"")
        return review.copy(quoteToken=signature(actor,review))
    }
    fun verify(expected: String?, review: CheckoutPreviewDTO) {
        if (expected != null && !MessageDigest.isEqual(HexFormat.of().parseHex(expected),HexFormat.of().parseHex(review.quoteToken)))
            throw ApiException(HttpStatus.CONFLICT,"CHECKOUT_PREVIEW_CHANGED","Ringkasan checkout berubah. Muat ulang pratinjau dan konfirmasi kembali.")
    }
    private fun signature(actor: UUID, review: CheckoutPreviewDTO): String {
        // Bind actor and every reviewed value, excluding only generation time and the token itself.
        val facts=listOf("panenku-checkout-quote-v1",actor,review.copy(quotedAt=Instant.EPOCH,quoteToken=""))
        val mac=Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8),"HmacSHA256"))
        return HexFormat.of().formatHex(mac.doFinal(mapper.writeValueAsBytes(facts)))
    }
}
