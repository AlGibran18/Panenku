package com.panenku.backend.modules.pembeli.controller

import com.panenku.backend.common.dto.ApiResponse
import com.panenku.backend.common.security.ActorService
import com.panenku.backend.modules.pembeli.dto.ReorderRequest
import com.panenku.backend.modules.pembeli.service.CartService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/buyer/orders")
class BuyerReorderController(private val carts: CartService, private val actors: ActorService) {
    @GetMapping("/{id}/reorder-preview")
    fun preview(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) = ApiResponse(data = carts.reorderPreview(actors.id(jwt), id))
    @PostMapping("/{id}/reorder")
    fun reorder(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @RequestBody request: ReorderRequest) =
        ApiResponse(data = carts.reorder(actors.id(jwt), id, request))
}
