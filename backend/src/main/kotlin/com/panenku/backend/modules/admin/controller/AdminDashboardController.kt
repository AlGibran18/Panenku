package com.panenku.backend.modules.admin.controller

import com.fasterxml.jackson.databind.JsonNode
import com.panenku.backend.common.dto.ApiResponse
import com.panenku.backend.common.security.ActorService
import com.panenku.backend.modules.admin.service.AdminDashboardService
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin")
class AdminDashboardController(private val service: AdminDashboardService, private val actors: ActorService) {
    @GetMapping("/farmers/verification-queue")
    fun queue(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue = "0") page: Int,
              @RequestParam(defaultValue = "20") size: Int, @RequestParam(defaultValue = "menunggu") status: String,
              @RequestParam(defaultValue = "") search: String) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = service.verificationQueue(actors.id(jwt), page, size, status, search)))

    @GetMapping("/users")
    fun users(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue = "0") page: Int,
              @RequestParam(defaultValue = "20") size: Int, @RequestParam(required = false) role: String?,
              @RequestParam(required = false) status: String?, @RequestParam(defaultValue = "") search: String) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = service.users(actors.id(jwt), page, size, role, status, search)))

    @GetMapping("/users/{id}")
    fun userDetail(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = service.userDetail(actors.id(jwt), id)))

    @GetMapping("/settings")
    fun settings(@AuthenticationPrincipal jwt: Jwt) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = service.settings(actors.id(jwt))))

    @PutMapping("/settings")
    fun updateSettings(@AuthenticationPrincipal jwt: Jwt, @RequestBody request: JsonNode) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = service.updateSettings(actors.id(jwt), request)))

    @PutMapping("/settings/transaction-limit")
    fun limit(@AuthenticationPrincipal jwt: Jwt, @RequestBody request: JsonNode) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = service.updateLimit(actors.id(jwt), request)))

    @GetMapping("/audit-logs")
    fun audit(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue = "0") page: Int,
              @RequestParam(defaultValue = "20") size: Int, @RequestParam(required = false) action: String?) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = service.auditLogs(actors.id(jwt), page, size, action)))
}
