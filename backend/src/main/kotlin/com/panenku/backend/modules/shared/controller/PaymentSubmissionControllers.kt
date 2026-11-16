package com.panenku.backend.modules.shared.controller

import com.panenku.backend.common.dto.ApiResponse
import com.panenku.backend.common.security.ActorService
import com.panenku.backend.modules.shared.service.PaymentDecisionRequest
import com.panenku.backend.modules.shared.service.PaymentImage
import com.panenku.backend.modules.shared.service.PaymentSubmissionService
import com.panenku.backend.modules.shared.service.PaymentDestinationService
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

private fun imageResponse(image: PaymentImage): ResponseEntity<ByteArray> = ResponseEntity.ok()
    .contentType(MediaType.parseMediaType(image.contentType)).cacheControl(CacheControl.noStore()).body(image.bytes)

@RestController
@RequestMapping("/api/v1/buyer/orders/{orderId}")
class BuyerPaymentSubmissionController(private val service: PaymentSubmissionService, private val actors: ActorService) {
    @PostMapping("/payment-proof", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun submit(@AuthenticationPrincipal jwt: Jwt, @PathVariable orderId: UUID,
               @RequestHeader("Idempotency-Key") key: String, @RequestParam("image") image: MultipartFile) =
        ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(ApiResponse(data = service.submit(actors.id(jwt), orderId, key, image)))

    @GetMapping("/payment-proofs")
    fun list(@AuthenticationPrincipal jwt: Jwt, @PathVariable orderId: UUID) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = service.list(actors.id(jwt), "pembeli", orderId)))

    @GetMapping("/payment-proofs/{submissionId}/image")
    fun image(@AuthenticationPrincipal jwt: Jwt, @PathVariable orderId: UUID, @PathVariable submissionId: UUID) =
        imageResponse(service.image(actors.id(jwt), "pembeli", orderId, submissionId))
}

@RestController
@RequestMapping("/api/v1/petani/orders/{orderId}")
class FarmerPaymentSubmissionController(private val service: PaymentSubmissionService, private val destinations: PaymentDestinationService, private val actors: ActorService) {
    @GetMapping("/payment-destination")
    fun destination(@AuthenticationPrincipal jwt: Jwt, @PathVariable orderId: UUID) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = destinations.destinationForFarmer(actors.id(jwt), orderId)))

    @PutMapping("/payment-verify")
    fun decide(@AuthenticationPrincipal jwt: Jwt, @PathVariable orderId: UUID, @RequestBody request: PaymentDecisionRequest) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = service.decide(actors.id(jwt), orderId, request)))

    @GetMapping("/payment-proofs")
    fun list(@AuthenticationPrincipal jwt: Jwt, @PathVariable orderId: UUID) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = service.list(actors.id(jwt), "petani", orderId)))

    @GetMapping("/payment-proofs/{submissionId}/image")
    fun image(@AuthenticationPrincipal jwt: Jwt, @PathVariable orderId: UUID, @PathVariable submissionId: UUID) =
        imageResponse(service.image(actors.id(jwt), "petani", orderId, submissionId))
}

@RestController
@RequestMapping("/api/v1/admin/orders/{orderId}")
class AdminPaymentSubmissionController(private val service: PaymentSubmissionService, private val actors: ActorService) {
    @GetMapping("/payment-proofs")
    fun list(@AuthenticationPrincipal jwt: Jwt, @PathVariable orderId: UUID) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = service.list(actors.id(jwt), "admin", orderId)))

    @GetMapping("/payment-proofs/{submissionId}/image")
    fun image(@AuthenticationPrincipal jwt: Jwt, @PathVariable orderId: UUID, @PathVariable submissionId: UUID) =
        imageResponse(service.image(actors.id(jwt), "admin", orderId, submissionId))
}
