package com.panenku.backend.modules.petani.controller

import com.panenku.backend.common.dto.ApiResponse
import com.panenku.backend.common.security.ActorService
import com.panenku.backend.modules.petani.service.DummyKtpService
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class DummyKtpController(private val ktp: DummyKtpService, private val actors: ActorService) {
    @PostMapping("/petani/ktp/application", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun submit(@AuthenticationPrincipal jwt: Jwt, @RequestHeader("Idempotency-Key") key: String,
               @RequestParam("dummyName") name: String, @RequestParam("dummyNik") nik: String, @RequestParam("image") file: MultipartFile) =
        ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(ApiResponse(data = ktp.submit(actors.id(jwt), key, name, nik, file)))

    @GetMapping("/petani/ktp/status")
    fun status(@AuthenticationPrincipal jwt: Jwt) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = ktp.status(actors.id(jwt))))

    @GetMapping("/petani/ktp/document")
    fun farmerImage(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<ByteArray> {
        val image = ktp.farmerImage(actors.id(jwt))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(image.contentType)).body(image.bytes)
    }

    @GetMapping("/admin/farmers/{id}/verification")
    fun detail(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = ktp.adminDetail(actors.id(jwt), id)))

    @GetMapping("/admin/farmers/{id}/ktp-document")
    fun image(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID): ResponseEntity<ByteArray> {
        val image = ktp.adminImage(actors.id(jwt), id)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(image.contentType)).body(image.bytes)
    }
}
