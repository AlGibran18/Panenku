package com.panenku.backend.modules.petani.service

import com.panenku.backend.common.exception.ApiException
import com.panenku.backend.common.exception.invalid
import org.springframework.http.HttpStatus
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import java.util.Locale
import javax.imageio.ImageIO

internal data class ValidatedProductImage(val bytes: ByteArray, val contentType: String, val extension: String)

internal object ProductImageValidator {
    const val MAX_BYTES = 5 * 1024 * 1024
    fun validate(file: MultipartFile): ValidatedProductImage {
        if (file.isEmpty) invalid("Foto produk tidak boleh kosong.")
        if (file.size > MAX_BYTES) throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PHOTO_TOO_LARGE", "Foto produk maksimal 5 MiB.")
        val declared = file.contentType?.lowercase(Locale.ROOT)
        if (declared !in setOf("image/jpeg", "image/png")) invalid("Foto produk harus JPEG atau PNG.")
        val bytes = file.inputStream.use { it.readNBytes(MAX_BYTES + 1) }
        if (bytes.size > MAX_BYTES) throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PHOTO_TOO_LARGE", "Foto produk maksimal 5 MiB.")
        val extension = try {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
                if (stream == null) invalid("Isi foto tidak valid.")
                val readers = ImageIO.getImageReaders(stream)
                if (!readers.hasNext()) invalid("Isi berkas bukan foto JPEG/PNG yang valid.")
                val reader = readers.next()
                try {
                    reader.input = stream
                    val format = reader.formatName.lowercase(Locale.ROOT)
                    val detected = when (format) { "jpeg", "jpg" -> "image/jpeg"; "png" -> "image/png"; else -> invalid("Format foto tidak didukung.") }
                    if (detected != declared) invalid("Content-Type tidak sesuai isi foto.")
                    val width = reader.getWidth(0); val height = reader.getHeight(0)
                    // Bound decode memory independently of compressed file size.
                    if (width <= 0 || height <= 0 || width.toLong() * height > 25_000_000L) invalid("Dimensi foto tidak valid atau melebihi 25 megapiksel.")
                    reader.read(0) ?: invalid("Isi foto rusak.")
                    if (detected == "image/png") "png" else "jpg"
                } finally { reader.dispose() }
            }
        } catch (ex: ApiException) { throw ex }
        catch (_: Exception) { invalid("Foto rusak atau tidak dapat dibaca.") }
        return ValidatedProductImage(bytes, declared!!, extension)
    }
}
