package com.panenku.backend.common.exception

import com.panenku.backend.common.dto.ApiResponse
import com.panenku.backend.common.dto.errorResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Global exception handler untuk semua controller.
 * Setiap AppException akan ditangkap dan dikembalikan sebagai JSON error.
 */
@RestControllerAdvice
class ExceptionHandler {

    @ExceptionHandler(AppException::class)
    fun handleAppException(ex: AppException): ResponseEntity<ApiResponse<Nothing>> {
        return ResponseEntity(
            errorResponse(ex.message),
            ex.status
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<ApiResponse<Nothing>> {
        return ResponseEntity(
            errorResponse("Terjadi kesalahan server: ${ex.message}"),
            HttpStatus.INTERNAL_SERVER_ERROR
        )
    }
}