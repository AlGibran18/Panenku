package com.panenku.backend.modules.shared.controller

import com.panenku.backend.common.dto.ApiResponse
import com.panenku.backend.common.security.ActorService
import com.panenku.backend.modules.shared.dto.*
import com.panenku.backend.modules.shared.service.OrderScheduleService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class OrderScheduleController(private val service: OrderScheduleService, private val actors: ActorService) {
    @PostMapping("/petani/orders/{id}/schedule-changes")
    fun propose(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String,
        @RequestBody request: ScheduleChangeRequest) = ApiResponse(data=service.propose(actors.id(jwt),id,key,request))
    @GetMapping("/petani/orders/{id}/schedule-changes")
    fun farmer(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @RequestParam(defaultValue="0") page: Int,
        @RequestParam(defaultValue="20") size: Int) = ApiResponse(data=service.list(actors.id(jwt),"petani",id,page,size))
    @GetMapping("/buyer/orders/{id}/schedule-changes")
    fun buyer(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID, @RequestParam(defaultValue="0") page: Int,
        @RequestParam(defaultValue="20") size: Int) = ApiResponse(data=service.list(actors.id(jwt),"pembeli",id,page,size))
    @PutMapping("/buyer/orders/{orderId}/schedule-changes/{id}/decision")
    fun decide(@AuthenticationPrincipal jwt: Jwt, @PathVariable orderId: UUID, @PathVariable id: UUID,
        @RequestBody request: ScheduleDecisionRequest) = ApiResponse(data=service.decide(actors.id(jwt),orderId,id,request))
}
