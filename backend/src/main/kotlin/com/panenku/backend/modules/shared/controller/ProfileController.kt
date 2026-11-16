package com.panenku.backend.modules.shared.controller

import com.panenku.backend.common.dto.ApiResponse
import com.panenku.backend.common.security.ActorService
import com.panenku.backend.modules.shared.service.AccountProfileRequest
import com.panenku.backend.modules.shared.service.FarmerProfileRequest
import com.panenku.backend.modules.shared.service.ProfileService
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
class ProfileController(private val profiles: ProfileService, private val actors: ActorService) {
    @GetMapping("/auth/profile")
    fun account(@AuthenticationPrincipal jwt: Jwt) = ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(ApiResponse(data = profiles.account(actors.id(jwt))))
    @PutMapping("/auth/profile")
    fun updateAccount(@AuthenticationPrincipal jwt: Jwt, @RequestBody request: AccountProfileRequest) = ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(ApiResponse(data = profiles.updateAccount(actors.id(jwt), request)))
    @GetMapping("/petani/profile")
    fun farmer(@AuthenticationPrincipal jwt: Jwt) = ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(ApiResponse(data = profiles.farmer(actors.id(jwt))))
    @PutMapping("/petani/profile")
    fun updateFarmer(@AuthenticationPrincipal jwt: Jwt, @RequestBody request: FarmerProfileRequest) = ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(ApiResponse(data = profiles.updateFarmer(actors.id(jwt), request)))
}
