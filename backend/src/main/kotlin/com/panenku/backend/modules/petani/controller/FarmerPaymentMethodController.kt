package com.panenku.backend.modules.petani.controller

import com.panenku.backend.common.dto.ApiResponse
import com.panenku.backend.common.security.ActorService
import com.panenku.backend.modules.petani.service.FarmerPaymentMethodService
import com.panenku.backend.modules.petani.dto.BankPaymentMethodRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/v1/petani/payment-methods")
class FarmerPaymentMethodController(private val service: FarmerPaymentMethodService, private val actors: ActorService) {
    @PostMapping("/bank")
    fun createBank(@AuthenticationPrincipal jwt: Jwt, @RequestHeader("Idempotency-Key") key: String,
                   @RequestBody request: BankPaymentMethodRequest) =
        ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(ApiResponse(data = service.createBank(actors.id(jwt), key, request)))

    @GetMapping("/{id}/bank")
    fun bankDetail(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = service.bankDetail(actors.id(jwt), id)))

    @PutMapping("/{id}/bank")
    fun updateBank(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @RequestBody request: BankPaymentMethodRequest) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = service.updateBank(actors.id(jwt), id, request)))

    @DeleteMapping("/{id}/bank")
    fun deleteBank(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID): ResponseEntity<Void> {
        service.deleteBank(actors.id(jwt), id)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    @PostMapping("/qris", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadQris(@AuthenticationPrincipal jwt: Jwt, @RequestHeader("Idempotency-Key") key: String,
                   @RequestParam("accountHolderName") name: String, @RequestParam("image") image: MultipartFile) =
        ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(ApiResponse(data = service.uploadQris(actors.id(jwt), key, name, image)))

    @PutMapping("/{id}/qris", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun updateQris(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID,
                   @RequestParam("accountHolderName") name: String, @RequestParam("image") image: MultipartFile) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse(data = service.updateQris(actors.id(jwt), id, name, image)))

    @DeleteMapping("/{id}/qris")
    fun deleteQris(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID): ResponseEntity<Void> {
        service.deleteQris(actors.id(jwt), id)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = service.list(actors.id(jwt))))

    @GetMapping("/{id}/qris")
    fun image(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID): ResponseEntity<ByteArray> {
        val image = service.image(actors.id(jwt), id)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType))
            .cacheControl(CacheControl.noStore()).body(image.bytes)
    }
}
