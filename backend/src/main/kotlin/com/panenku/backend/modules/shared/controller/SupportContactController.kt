package com.panenku.backend.modules.shared.controller

import com.panenku.backend.common.dto.ApiResponse
import com.panenku.backend.common.security.ActorService
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class SupportContact(val csWhatsapp: String?)

@RestController
@RequestMapping("/api/v1/support")
class SupportContactController(private val actors: ActorService, private val db: JdbcTemplate) {
    @GetMapping("/contact")
    fun contact(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<ApiResponse<SupportContact>> {
        actors.requireAccountForSupport(actors.id(jwt))
        val number = db.query("SELECT value FROM settings WHERE key='cs_whatsapp'", { rs, _ -> rs.getString(1) }).singleOrNull()
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse(data = SupportContact(number)))
    }
}
