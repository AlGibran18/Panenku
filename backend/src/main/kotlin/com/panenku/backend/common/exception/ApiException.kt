package com.panenku.backend.common.exception

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.time.Instant
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.PessimisticLockingFailureException

class ApiException(val status: HttpStatus, val code: String, override val message: String) : RuntimeException(message)
fun invalid(message: String): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message)
fun conflict(message: String): Nothing = throw ApiException(HttpStatus.CONFLICT, "CONFLICT", message)
fun missing(message: String): Nothing = throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message)
fun forbidden(message: String): Nothing = throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message)

data class ApiResponse<T>(val success: Boolean = true, val data: T)
data class ErrorResponse(val success: Boolean = false, val code: String, val message: String, val timestamp: Instant = Instant.now())

@RestControllerAdvice
class ExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun handle(ex: ApiException) = ResponseEntity.status(ex.status).body(ErrorResponse(code = ex.code, message = ex.message))

    @ExceptionHandler(DataIntegrityViolationException::class, PessimisticLockingFailureException::class)
    fun databaseConflict(ex: Exception) = ResponseEntity.status(HttpStatus.CONFLICT).body(
        ErrorResponse(code = "CONFLICT", message = "Data berubah atau operasi berbenturan. Muat ulang atau ulangi request dengan key yang sama.")
    )

    @ExceptionHandler(MethodArgumentNotValidException::class, HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class)
    fun badInput(ex: Exception) = ResponseEntity.badRequest().body(ErrorResponse(code = "INVALID_REQUEST", message = "Periksa format dan field request."))
}
