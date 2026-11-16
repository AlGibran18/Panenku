package com.panenku.backend.modules.shared.service

import com.panenku.backend.common.exception.invalid
import com.panenku.backend.common.exception.missing
import com.panenku.backend.common.security.ActorService
import com.panenku.backend.modules.shared.repository.BuyerProfileRepository
import com.panenku.backend.modules.shared.repository.FarmerProfileRepository
import com.panenku.backend.modules.shared.repository.ProductRepository
import com.panenku.backend.common.exception.conflict
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalTime
import java.util.Locale
import java.util.UUID

data class AccountProfileDTO(val role: String, val fullName: String?, val contactPhone: String?, val deliveryAddress: String?)
data class AccountProfileRequest(val fullName: String, val contactPhone: String? = null, val deliveryAddress: String? = null)
data class FarmerProfileDTO(val storeName: String, val ownerName: String?, val contactPhone: String?, val pickupLocation: String?,
    val serviceArea: List<String>, val deliveryFee: Long, val shippingEnabled: Boolean, val timeSlots: List<String>, val ktpStatus: String)
data class FarmerProfileRequest(val storeName: String, val pickupLocation: String?, val serviceArea: List<String>,
    val deliveryFee: Long, val shippingEnabled: Boolean, val timeSlots: List<String>)

@Service
class ProfileService(private val actors: ActorService, private val farmers: FarmerProfileRepository, private val buyers: BuyerProfileRepository,
                     private val products: ProductRepository) {
    @Transactional(readOnly = true)
    fun account(actor: UUID): AccountProfileDTO {
        val user = actors.requireActiveForProfile(actor)
        return when (user.role) {
            "pembeli" -> buyers.findById(actor).orElse(null)?.let { AccountProfileDTO(user.role, it.fullName, it.contactPhone, it.deliveryAddress) }
            "petani" -> farmers.findById(actor).orElse(null)?.let { AccountProfileDTO(user.role, it.ownerName, it.contactPhone, null) }
            "admin" -> AccountProfileDTO(user.role, user.displayName, null, null)
            else -> null
        } ?: missing("Profil tidak tersedia.")
    }

    @Transactional
    fun updateAccount(actor: UUID, request: AccountProfileRequest): AccountProfileDTO {
        val user = actors.requireActiveForProfile(actor, locked = true)
        val name = request.fullName.trim()
        val phone = request.contactPhone?.trim()?.takeIf { it.isNotBlank() }
        val address = request.deliveryAddress?.trim()?.takeIf { it.isNotBlank() }
        if (name.isBlank() || name.length > 255) invalid("Nama wajib diisi, maksimal 255 karakter.")
        if ((phone?.length ?: 0) > 20 || (address?.length ?: 0) > 2000) invalid("Telepon atau alamat terlalu panjang.")
        when (user.role) {
            "pembeli" -> {
                val profile = buyers.findById(actor).orElse(null) ?: missing("Profil pembeli tidak ditemukan.")
                profile.fullName = name; profile.contactPhone = phone; profile.deliveryAddress = address
            }
            "petani" -> {
                if (address != null) invalid("Alamat pembeli tidak berlaku untuk petani.")
                val profile = farmers.lockById(actor) ?: missing("Profil petani tidak ditemukan.")
                profile.ownerName = name; profile.contactPhone = phone
            }
            "admin" -> {
                if (phone != null || address != null) invalid("Profil admin hanya mendukung nama.")
                user.displayName = name
            }
            else -> invalid("Profil ini tidak tersedia untuk admin.")
        }
        return AccountProfileDTO(user.role, name, phone, if (user.role == "pembeli") address else null)
    }

    @Transactional(readOnly = true)
    fun farmer(actor: UUID): FarmerProfileDTO {
        actors.require(actor, "petani")
        val profile = farmers.findById(actor).orElse(null) ?: missing("Profil petani tidak ditemukan.")
        return farmerDto(profile)
    }

    @Transactional
    fun updateFarmer(actor: UUID, request: FarmerProfileRequest): FarmerProfileDTO {
        actors.requireLocked(actor, "petani")
        val profile = farmers.lockById(actor) ?: missing("Profil petani tidak ditemukan.")
        val store = request.storeName.trim()
        val location = request.pickupLocation?.trim()?.takeIf { it.isNotBlank() }
        if (store.isBlank() || store.length > 255 || (location?.length ?: 0) > 2000) invalid("Nama toko atau lokasi tidak valid.")
        if (request.deliveryFee < 0) invalid("Ongkir tidak boleh negatif.")
        if (request.serviceArea.size > 100 || request.timeSlots.size > 100) invalid("Maksimal 100 area dan slot.")
        val areas = request.serviceArea.map { it.trim().lowercase(Locale.ROOT) }
        if (areas.any { it.length !in 1..100 || !Regex("[a-z0-9][a-z0-9 _-]*").matches(it) } || areas.distinct().size != areas.size) invalid("Identifier kecamatan tidak valid atau duplikat.")
        val slots = request.timeSlots.map(String::trim)
        if (slots.distinct().size != slots.size || slots.any { !validSlot(it) }) invalid("Slot harus HH:mm-HH:mm pada hari yang sama dan tidak duplikat.")
        if (request.shippingEnabled && areas.isEmpty()) invalid("Isi minimal satu kecamatan untuk layanan antar.")
        if ((location == null || slots.isEmpty()) && products.existsByFarmerIdAndPublicationStatus(actor, "aktif")) {
            conflict("Toko dengan produk aktif memerlukan lokasi dan minimal satu slot waktu.")
        }
        profile.storeName = store; profile.pickupLocation = location; profile.serviceArea = areas.joinToString("|")
        profile.deliveryFee = request.deliveryFee; profile.shippingEnabled = request.shippingEnabled; profile.timeSlots = slots.joinToString("|")
        return farmerDto(profile)
    }

    private fun validSlot(slot: String): Boolean {
        if (!Regex("[0-9]{2}:[0-9]{2}-[0-9]{2}:[0-9]{2}").matches(slot)) return false
        return try { LocalTime.parse(slot.substring(0, 5)) < LocalTime.parse(slot.substring(6, 11)) } catch (_: Exception) { false }
    }
    private fun farmerDto(p: com.panenku.backend.modules.shared.entity.FarmerProfile) = FarmerProfileDTO(p.storeName, p.ownerName,
        p.contactPhone, p.pickupLocation, CatalogService.split(p.serviceArea), p.deliveryFee, p.shippingEnabled, CatalogService.split(p.timeSlots), p.ktpStatus)
}
