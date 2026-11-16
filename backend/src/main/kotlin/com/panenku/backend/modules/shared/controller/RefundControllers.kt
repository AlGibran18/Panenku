package com.panenku.backend.modules.shared.controller

import com.panenku.backend.common.dto.ApiResponse
import com.panenku.backend.common.security.ActorService
import com.panenku.backend.modules.shared.dto.AdminDisputeDecisionRequest
import com.panenku.backend.modules.shared.dto.EscalationRequest
import com.panenku.backend.modules.shared.dto.RefundDecisionRequest
import com.panenku.backend.modules.shared.service.PaymentImage
import com.panenku.backend.modules.shared.service.RefundService
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

private fun refundImageResponse(image: PaymentImage): ResponseEntity<ByteArray> = ResponseEntity.ok()
    .contentType(MediaType.parseMediaType(image.contentType)).cacheControl(CacheControl.noStore()).body(image.bytes)

@RestController
@RequestMapping("/api/v1/buyer/refunds")
class BuyerRefundController(private val refunds: RefundService, private val actors: ActorService) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestHeader("Idempotency-Key") key: String,
               @RequestParam orderId: UUID, @RequestParam reason: String, @RequestParam destinationType: String,
               @RequestParam destinationProvider: String, @RequestParam destinationAccount: String,
               @RequestParam destinationName: String, @RequestParam("image") image: MultipartFile) =
        ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(ApiResponse(data = refunds.create(
            actors.id(jwt), key, orderId, reason, destinationType, destinationProvider, destinationAccount, destinationName, image)))

    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = refunds.list(actors.id(jwt), "pembeli", page, size)))

    @GetMapping("/{id}")
    fun detail(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = refunds.detail(actors.id(jwt), "pembeli", id)))

    @GetMapping("/{id}/evidence")
    fun evidence(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = refundImageResponse(refunds.image(actors.id(jwt), "pembeli", id, "evidence"))

    @GetMapping("/{id}/transfer-proof")
    fun transferProof(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = refundImageResponse(refunds.image(actors.id(jwt), "pembeli", id, "transfer-proof"))

    @PostMapping("/{id}/confirm")
    fun confirm(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = refunds.confirm(actors.id(jwt), id)))

    @PostMapping("/{id}/escalations")
    fun escalate(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String,
                 @RequestBody request: EscalationRequest) =
        ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(ApiResponse(data = refunds.escalate(actors.id(jwt), id, key, request)))
}

@RestController
@RequestMapping("/api/v1/buyer/escalations")
class BuyerRefundDisputeController(private val refunds: RefundService, private val actors: ActorService) {
    @GetMapping("/{id}")
    fun detail(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = refunds.disputeDetail(actors.id(jwt), "pembeli", id)))
}

@RestController
@RequestMapping("/api/v1/petani/refunds")
class FarmerRefundController(private val refunds: RefundService, private val actors: ActorService) {
    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = refunds.list(actors.id(jwt), "petani", page, size)))

    @GetMapping("/{id}")
    fun detail(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = refunds.detail(actors.id(jwt), "petani", id)))

    @GetMapping("/{id}/evidence")
    fun evidence(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = refundImageResponse(refunds.image(actors.id(jwt), "petani", id, "evidence"))

    @GetMapping("/{id}/transfer-proof")
    fun transferProof(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = refundImageResponse(refunds.image(actors.id(jwt), "petani", id, "transfer-proof"))

    @PutMapping("/{id}/respond")
    fun respond(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @RequestBody request: RefundDecisionRequest) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = refunds.respond(actors.id(jwt), id, request)))

    @PostMapping("/{id}/transfer-proof", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun transfer(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String,
                 @RequestParam("image") image: MultipartFile) =
        ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(ApiResponse(data = refunds.submitTransfer(actors.id(jwt), id, key, image)))
}

@RestController
@RequestMapping("/api/v1/admin/refunds")
class AdminRefundController(private val refunds: RefundService, private val actors: ActorService) {
    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = refunds.list(actors.id(jwt), "admin", page, size)))

    @GetMapping("/{id}")
    fun detail(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = refunds.detail(actors.id(jwt), "admin", id)))

    @GetMapping("/{id}/evidence")
    fun evidence(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = refundImageResponse(refunds.image(actors.id(jwt), "admin", id, "evidence"))

    @GetMapping("/{id}/transfer-proof")
    fun transferProof(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = refundImageResponse(refunds.image(actors.id(jwt), "admin", id, "transfer-proof"))
}

@RestController
@RequestMapping("/api/v1/admin/disputes")
class AdminRefundDisputeController(private val refunds: RefundService, private val actors: ActorService) {
    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = refunds.disputes(actors.id(jwt), page, size)))

    @GetMapping("/{id}")
    fun detail(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = refunds.disputeDetail(actors.id(jwt), "admin", id)))

    @PutMapping("/{id}/decide")
    fun decide(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @RequestBody request: AdminDisputeDecisionRequest) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = refunds.decideDispute(actors.id(jwt), id, request)))
}
