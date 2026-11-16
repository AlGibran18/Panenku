package com.panenku.backend.modules.shared.controller

import com.panenku.backend.common.dto.ApiResponse
import com.panenku.backend.modules.shared.dto.LoginRequest
import com.panenku.backend.modules.shared.service.AuthSessionService
import com.panenku.backend.modules.shared.service.RefreshTokenRequest
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/auth")
class AuthSessionController(private val sessions: AuthSessionService) {
    @PostMapping("/sessions")
    fun create(@Valid @RequestBody request: LoginRequest) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = sessions.create(request)))

    @PostMapping("/refresh")
    fun refresh(@RequestBody request: RefreshTokenRequest) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = sessions.refresh(request)))

    @PostMapping("/logout")
    fun logout(@RequestBody request: RefreshTokenRequest): ResponseEntity<Void> {
        sessions.logout(request)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }
}
