package com.panenku.backend.modules.pembeli.controller

import com.panenku.backend.common.dto.ApiResponse
import com.panenku.backend.common.security.ActorService
import com.panenku.backend.modules.shared.service.PaymentDestinationService
import com.panenku.backend.modules.shared.service.ChoosePaymentRequest
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/buyer")
class BuyerPaymentDestinationController(private val service: PaymentDestinationService, private val actors: ActorService) {
    @GetMapping("/farmers/{farmerId}/payment-methods")
    fun methods(@AuthenticationPrincipal jwt: Jwt, @PathVariable farmerId: UUID) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = service.listForBuyer(actors.id(jwt), farmerId)))

    @GetMapping("/orders/{orderId}/payment-options")
    fun options(@AuthenticationPrincipal jwt: Jwt, @PathVariable orderId: UUID) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = service.optionsForBuyer(actors.id(jwt), orderId)))

    @GetMapping("/orders/{orderId}/payment-options/{methodId}/qris")
    fun optionQris(@AuthenticationPrincipal jwt: Jwt, @PathVariable orderId: UUID, @PathVariable methodId: UUID): ResponseEntity<ByteArray> {
        val image = service.optionImageForBuyer(actors.id(jwt), orderId, methodId)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType))
            .cacheControl(CacheControl.noStore()).body(image.bytes)
    }

    @PutMapping("/orders/{orderId}/payment-destination")
    fun choose(@AuthenticationPrincipal jwt: Jwt, @PathVariable orderId: UUID, @RequestBody request: ChoosePaymentRequest) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse(data = service.chooseForBuyer(actors.id(jwt), orderId, request.paymentMethodId)))

    @GetMapping("/orders/{orderId}/payment-destination")
    fun destination(@AuthenticationPrincipal jwt: Jwt, @PathVariable orderId: UUID) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = service.destinationForBuyer(actors.id(jwt), orderId)))

    @GetMapping("/orders/{orderId}/payment-destination/qris")
    fun qris(@AuthenticationPrincipal jwt: Jwt, @PathVariable orderId: UUID): ResponseEntity<ByteArray> {
        val image = service.imageForBuyer(actors.id(jwt), orderId)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType))
            .cacheControl(CacheControl.noStore()).body(image.bytes)
    }
}
