package com.panenku.backend.modules.shared.service

import com.panenku.backend.common.exception.invalid
import com.panenku.backend.modules.shared.entity.FarmerProfile
import java.util.Locale

data class FulfillmentContext(val pickupLocation: String, val fee: Long, val district: String?, val areas: List<String>)
object FulfillmentRules {
    fun district(value: String): String {
        val clean = value.trim().lowercase(Locale.ROOT)
        if (clean.length !in 1..100 || !Regex("[a-z0-9][a-z0-9 _-]*").matches(clean)) invalid("Identifier kecamatan tidak valid.")
        return clean
    }
    fun areas(values: List<String?>): List<String> {
        if (values.size > 100) invalid("Maksimal 100 kecamatan.")
        val clean = values.map { it?.let(::district) ?: invalid("Kecamatan tidak boleh null.") }
        if (clean.distinct().size != clean.size) invalid("Kecamatan duplikat.")
        return clean.sorted()
    }
    fun resolve(profile: FarmerProfile, method: String, deliveryDistrict: String?): FulfillmentContext {
        val location = profile.pickupLocation?.trim()?.takeIf { it.isNotBlank() } ?: invalid("Lokasi pengambilan petani belum tersedia.")
        return when (method) {
            "pickup" -> FulfillmentContext(location, 0, null, emptyList())
            "delivery" -> {
                val district = deliveryDistrict?.let(::district) ?: invalid("Kecamatan tujuan wajib diisi.")
                val areas = areas(CatalogService.split(profile.serviceArea))
                if (!profile.shippingEnabled || district !in areas || profile.deliveryFee < 0) invalid("Layanan antar tidak tersedia untuk kecamatan ini.")
                FulfillmentContext(location, profile.deliveryFee, district, areas)
            }
            else -> invalid("Metode pemenuhan harus pickup atau delivery.")
        }
    }
}
