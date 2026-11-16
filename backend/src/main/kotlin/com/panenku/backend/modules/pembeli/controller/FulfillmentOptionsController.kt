package com.panenku.backend.modules.pembeli.controller

import com.panenku.backend.common.dto.ApiResponse
import com.panenku.backend.common.security.ActorService
import com.panenku.backend.modules.pembeli.service.FulfillmentOptionsService
import com.panenku.backend.modules.shared.dto.FulfillmentOptionsRequest
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/buyer/fulfillment-options")
class FulfillmentOptionsController(private val service: FulfillmentOptionsService, private val actors: ActorService) {
    @PostMapping fun options(@AuthenticationPrincipal jwt: Jwt, @RequestBody request: FulfillmentOptionsRequest) =
        ApiResponse(data = service.options(actors.id(jwt), request))
}
