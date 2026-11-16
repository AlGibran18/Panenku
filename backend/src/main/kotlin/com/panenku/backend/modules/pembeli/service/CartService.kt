package com.panenku.backend.modules.pembeli.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.panenku.backend.common.exception.*
import com.panenku.backend.common.security.ActorService
import com.panenku.backend.modules.pembeli.dto.*
import com.panenku.backend.modules.pembeli.entity.CartItem
import com.panenku.backend.modules.pembeli.entity.Order
import com.panenku.backend.modules.pembeli.repository.*
import com.panenku.backend.modules.shared.service.CheckoutMath
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

@Service
@Transactional
class CartService(private val actors: ActorService, private val carts: CartRepository,
    private val reader: CartProductReader, private val orders: OrderRepository,
    private val orderItems: OrderItemRepository, private val mapper: ObjectMapper) {
    // The buyer row exists even for an empty cart. All cart operations and checkout
    // take this same first lock, preventing lost increments and mixed-farmer races.
    fun get(actor: UUID): CartDTO { actors.requireLocked(actor, "pembeli"); return view(actor) }

    fun add(actor: UUID, request: CartItemRequest): CartDTO {
        actors.requireLocked(actor, "pembeli")
        val increment = CheckoutMath.kg(request.quantityKg)
        val current = rows(actor)
        val states = reader.read(current.map { it.productId } + request.productId)
        val state = states[request.productId] ?: missing("Produk tidak ditemukan.")
        if (current.any { states[it.productId]?.product?.farmerId != state.product.farmerId })
            fail("CART_FARMER_CONFLICT", "Keranjang berbeda petani. Pertahankan isi lama atau konfirmasi penggantian keranjang.")
        val existing = current.find { it.productId == request.productId }
        if (existing == null && current.size >= 100) invalid("Maksimal 100 produk per keranjang.")
        val quantity = CheckoutMath.kg((existing?.quantityKg ?: BigDecimal.ZERO).add(increment))
        validate(state, quantity)
        carts.saveAndFlush((existing ?: CartItem(buyerId = actor, productId = request.productId)).apply { quantityKg = quantity })
        return view(actor)
    }

    fun update(actor: UUID, id: UUID, quantity: BigDecimal): CartDTO {
        actors.requireLocked(actor, "pembeli")
        val item = owned(actor, id)
        val value = CheckoutMath.kg(quantity)
        val state = reader.read(listOf(item.productId))[item.productId] ?: missing("Produk tidak ditemukan.")
        validate(state, value)
        item.quantityKg = value; carts.flush()
        return view(actor)
    }

    fun delete(actor: UUID, id: UUID) {
        actors.requireLocked(actor, "pembeli")
        carts.delete(owned(actor, id)); carts.flush()
    }

    /** Explicit replacement, including replacement with [] to clear a cart. */
    fun replace(actor: UUID, request: CartReplaceRequest): CartDTO {
        actors.requireLocked(actor, "pembeli")
        val current = rows(actor)
        confirm(actor, current, request.expectedCartVersion, request.confirmed)
        replaceRows(actor, current, request.items)
        return view(actor)
    }

    fun reorderPreview(actor: UUID, orderId: UUID): ReorderPreviewDTO {
        actors.requireLocked(actor, "pembeli")
        return preview(actor, order(actor, orderId), rows(actor))
    }

    /** Confirmation replaces the cart, never creates an order/payment/reservation. */
    fun reorder(actor: UUID, orderId: UUID, request: ReorderRequest): CartDTO {
        actors.requireLocked(actor, "pembeli")
        val current = rows(actor)
        confirm(actor, current, request.expectedCartVersion, request.confirmed)
        val fresh = preview(actor, order(actor, orderId), current)
        if (fresh.previewToken != request.previewToken)
            fail("REORDER_CHANGED", "Harga, stok, tanggal, atau kondisi produk berubah. Muat ulang pratinjau Beli Lagi dan konfirmasi kembali.")
        val selected = fresh.items.filter { it.available }.map {
            CartSelectionRequest(it.productId, it.quantityKg, it.pricePerKg!!)
        }
        if (selected.isEmpty()) fail("NO_REORDERABLE_ITEMS", "Tidak ada item yang dapat dibeli lagi. Keranjang lama dipertahankan.")
        replaceRows(actor, current, selected)
        return view(actor)
    }

    private fun preview(actor: UUID, order: Order, current: List<CartItem>): ReorderPreviewDTO {
        val source = orderItems.findAllByOrderIdOrderByProductId(order.id)
        val states = reader.read(source.map { it.productId })
        val items = source.map { old ->
            val state = states[old.productId]
            val p = state?.product
            val issue = if (state == null) "PRODUCT_NOT_FOUND" else state.issue
            val stock = p?.available ?: BigDecimal("0.00")
            val quantity = if (issue == null) old.quantityKg.min(stock) else BigDecimal("0.00")
            val reason = issue ?: if (quantity < old.quantityKg) "QUANTITY_REDUCED" else null
            ReorderItemDTO(old.productId, p?.name ?: old.productNameSnapshot, old.quantityKg, quantity,
                old.pricePerKgSnapshot, p?.pricePerKg, p != null && p.pricePerKg != old.pricePerKgSnapshot,
                stock, p?.readyDate, quantity.signum() > 0, reason, p?.farmerId, state?.storeName,
                state?.photo?.url, state?.photo?.expiresAt)
        }
        val version = version(actor, current)
        // Exclude expiring URLs: fetching a new URL must not invalidate confirmation.
        val commercial = items.map { listOf(it.productId, it.name, it.previousQuantityKg, it.quantityKg,
            it.pricePerKg, it.stockAvailable, it.readyDate, it.adjustmentReason, it.farmerId, it.storeName) }
        return ReorderPreviewDTO(order.id, version, hash(listOf(actor, order.id, version, commercial)), items)
    }

    private fun replaceRows(actor: UUID, current: List<CartItem>, selected: List<CartSelectionRequest>) {
        if (selected.size > 100 || selected.map { it.productId }.distinct().size != selected.size)
            invalid("Maksimal 100 item produk unik per keranjang.")
        val normalized = selected.map { it.copy(quantityKg = CheckoutMath.kg(it.quantityKg)) }
        val states = reader.read(normalized.map { it.productId })
        val farmers = normalized.map { item ->
            val state = states[item.productId] ?: missing("Produk tidak ditemukan.")
            validate(state, item.quantityKg)
            if (item.expectedPricePerKg != state.product.pricePerKg)
                fail("PRODUCT_PRICE_CHANGED", "Harga berubah. Muat ulang produk dan konfirmasi harga terbaru.")
            state.product.farmerId
        }.distinct()
        if (farmers.size > 1) fail("CART_FARMER_CONFLICT", "Satu keranjang hanya boleh berisi produk dari satu petani.")
        // Validate everything before deleting; any later write failure rolls back both operations.
        carts.deleteAll(current); carts.flush()
        carts.saveAll(normalized.map { CartItem(buyerId = actor, productId = it.productId, quantityKg = it.quantityKg) })
        carts.flush()
    }

    private fun confirm(actor: UUID, current: List<CartItem>, expected: String, confirmed: Boolean) {
        if (!confirmed) fail("CART_CONFIRMATION_REQUIRED", "Konfirmasi pembeli diperlukan untuk mengganti isi keranjang.")
        if (expected != version(actor, current)) fail("CART_CHANGED", "Isi keranjang berubah. Muat ulang dan konfirmasi kembali.")
    }

    private fun validate(state: CartProductState, quantity: BigDecimal) {
        state.issue?.let { fail(it, "Produk atau petani tidak tersedia. Muat ulang keranjang untuk melihat kondisi terbaru.") }
        if (quantity > state.product.available) fail("INSUFFICIENT_STOCK", "Jumlah melebihi stok tersedia.")
    }
    private fun order(actor: UUID, id: UUID): Order {
        val order = orders.findById(id).orElseGet { missing("Pesanan tidak ditemukan.") }
        if (order.buyerId != actor) forbidden("Pesanan bukan milik pembeli ini.")
        return order
    }
    private fun owned(actor: UUID, id: UUID): CartItem {
        val item = carts.findById(id).orElseGet { missing("Item keranjang tidak ditemukan.") }
        if (item.buyerId != actor) forbidden("Item keranjang bukan milik pembeli ini.")
        return item
    }
    private fun rows(actor: UUID) = carts.findAllByBuyerIdOrderById(actor)
    private fun version(actor: UUID, items: List<CartItem>): String = hash(listOf(actor,
        items.sortedBy { it.id }.map { listOf(it.id, it.productId, CheckoutMath.kg(it.quantityKg)) }))
    private fun hash(value: Any): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)))
    private fun fail(code: String, message: String): Nothing = throw ApiException(HttpStatus.CONFLICT, code, message)

    private fun view(actor: UUID): CartDTO {
        val current = rows(actor)
        val states = reader.read(current.map { it.productId })
        val items = current.map { item ->
            val state = states[item.productId] ?: missing("Produk keranjang tidak ditemukan.")
            val p = state.product
            val issue = state.issue ?: if (item.quantityKg > p.available) "INSUFFICIENT_STOCK" else null
            CartItemDTO(item.id, p.id, p.name, item.quantityKg, p.pricePerKg, CheckoutMath.item(item.quantityKg, p.pricePerKg),
                p.available, issue == null, p.farmerId, state.storeName, p.readyDate,
                state.photo?.url, state.photo?.expiresAt, issue)
        }
        return CartDTO(items, items.fold(BigDecimal.ZERO) { sum, item -> sum.add(BigDecimal.valueOf(item.itemSubtotal)) },
            version(actor, current), items.firstOrNull()?.farmerId, items.firstOrNull()?.storeName, items.any { !it.available })
    }
}
