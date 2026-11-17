package com.panenku.backend.modules.pembeli.controller

import com.panenku.backend.common.dto.ApiResponse
import com.panenku.backend.common.security.ActorService
import com.panenku.backend.modules.pembeli.dto.CheckoutPreviewRequest
import com.panenku.backend.modules.pembeli.service.CheckoutPreviewService
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/buyer/checkout/preview")
class CheckoutPreviewController(private val service: CheckoutPreviewService, private val actors: ActorService) {
    @PostMapping fun preview(@AuthenticationPrincipal jwt: Jwt, @RequestBody request: CheckoutPreviewRequest) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data=service.preview(actors.id(jwt),request)))
}
