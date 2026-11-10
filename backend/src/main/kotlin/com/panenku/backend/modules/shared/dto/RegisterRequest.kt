package com.panenku.backend.modules.shared.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class RegisterRequest(
    @Email(message = "Format email tidak valid")
    @NotBlank(message = "Email wajib diisi")
    val email: String,

    @Size(min = 8, message = "Password minimal 8 karakter")
    @NotBlank(message = "Password wajib diisi")
    val password: String,

    @NotBlank(message = "Role wajib diisi (pembeli atau petani)")
    val role: String,

    val fullName: String? = null,
    val storeName: String? = null,
    val contactPhone: String? = null
)