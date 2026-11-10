package com.panenku.backend.modules.shared.dto

data class LoginRequest(
    val email: String,
    val password: String
)