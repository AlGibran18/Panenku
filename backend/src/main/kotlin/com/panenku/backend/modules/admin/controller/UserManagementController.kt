package com.panenku.backend.modules.admin.controller

import com.panenku.backend.common.dto.ApiResponse
import com.panenku.backend.modules.admin.dto.SuspendUserRequest
import com.panenku.backend.modules.admin.service.UserManagementService
import com.panenku.backend.common.security.ActorService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/users")
class UserManagementController(
    private val userManagementService: UserManagementService,
    private val actors: ActorService
) {

    @PutMapping("/{id}/suspend")
    fun suspendUser(
        @PathVariable id: UUID,
        @RequestBody request: SuspendUserRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<ApiResponse<Unit>> {
        val response = userManagementService.suspendUser(id, actors.id(jwt), request)
        return ResponseEntity(response, HttpStatus.OK)
    }
}
