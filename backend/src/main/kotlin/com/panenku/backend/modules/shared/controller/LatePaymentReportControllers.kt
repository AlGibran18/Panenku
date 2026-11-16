package com.panenku.backend.modules.shared.controller

import com.panenku.backend.common.dto.ApiResponse
import com.panenku.backend.common.security.ActorService
import com.panenku.backend.modules.shared.dto.LatePaymentCloseRequest
import com.panenku.backend.modules.shared.dto.LatePaymentReviewRequest
import com.panenku.backend.modules.shared.service.LatePaymentReportService
import com.panenku.backend.modules.shared.service.PaymentImage
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

private fun latePaymentImageResponse(image: PaymentImage): ResponseEntity<ByteArray> = ResponseEntity.ok()
    .contentType(MediaType.parseMediaType(image.contentType)).cacheControl(CacheControl.noStore()).body(image.bytes)

@RestController
@RequestMapping("/api/v1/buyer/late-payment-reports")
class BuyerLatePaymentReportController(private val reports: LatePaymentReportService, private val actors: ActorService) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestHeader("Idempotency-Key") key: String,
               @RequestParam orderId: UUID, @RequestParam reason: String, @RequestParam claimedAmount: Long,
               @RequestParam("image") image: MultipartFile) =
        ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(ApiResponse(data = reports.create(actors.id(jwt), key, orderId, reason, claimedAmount, image)))

    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = reports.list(actors.id(jwt), "pembeli", page, size)))

    @GetMapping("/{id}")
    fun detail(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = reports.detail(actors.id(jwt), "pembeli", id)))

    @GetMapping("/{id}/evidence")
    fun evidence(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = latePaymentImageResponse(reports.image(actors.id(jwt), "pembeli", id))
}

@RestController
@RequestMapping("/api/v1/admin/late-payment-reports")
class AdminLatePaymentReportController(private val reports: LatePaymentReportService, private val actors: ActorService) {
    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = reports.list(actors.id(jwt), "admin", page, size)))

    @GetMapping("/{id}")
    fun detail(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = reports.detail(actors.id(jwt), "admin", id)))

    @GetMapping("/{id}/evidence")
    fun evidence(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = latePaymentImageResponse(reports.image(actors.id(jwt), "admin", id))

    @PutMapping("/{id}/review")
    fun review(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @RequestBody request: LatePaymentReviewRequest) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = reports.review(actors.id(jwt), id, request)))

    @PutMapping("/{id}/close")
    fun close(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @RequestBody request: LatePaymentCloseRequest) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = reports.close(actors.id(jwt), id, request)))
}
