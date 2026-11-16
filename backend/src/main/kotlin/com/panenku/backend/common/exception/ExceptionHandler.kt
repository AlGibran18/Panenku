package com.panenku.backend.common.exception

import org.slf4j.LoggerFactory
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.servlet.resource.NoResourceFoundException
import org.springframework.security.access.AccessDeniedException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.multipart.support.MissingServletRequestPartException

@RestControllerAdvice
class ExceptionHandler {
    private val logger = LoggerFactory.getLogger(javaClass)
    @ExceptionHandler(ApiException::class)
    fun handle(ex: ApiException) = ResponseEntity.status(ex.status).body(ErrorResponse(code = ex.code, message = ex.message))
    @ExceptionHandler(AppException::class)
    fun handleAppException(ex: AppException) = ResponseEntity.status(ex.status).body(ErrorResponse(code = ex.status.name, message = ex.message))
    @ExceptionHandler(DataIntegrityViolationException::class, PessimisticLockingFailureException::class)
    fun databaseConflict(ex: Exception) = ResponseEntity.status(HttpStatus.CONFLICT).body(
        ErrorResponse(code = "CONFLICT", message = "Data berubah atau operasi berbenturan. Muat ulang atau ulangi request dengan key yang sama.")
    )
    @ExceptionHandler(MethodArgumentNotValidException::class, HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class, MissingRequestHeaderException::class, MissingServletRequestParameterException::class, MissingServletRequestPartException::class)
    fun badInput(ex: Exception) = ResponseEntity.badRequest().body(ErrorResponse(code = "INVALID_REQUEST", message = "Periksa format dan field request."))

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun uploadTooLarge(ex: MaxUploadSizeExceededException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val productPhoto = Regex("/api/v1/petani/products/[^/]+/photo/?$").containsMatchIn(request.requestURI)
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
            if (productPhoto) ErrorResponse(code = "PHOTO_TOO_LARGE", message = "Foto produk maksimal 5 MiB.")
            else ErrorResponse(code = "FILE_TOO_LARGE", message = "Ukuran file maksimal 5 MB.")
        )
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun method(ex: HttpRequestMethodNotSupportedException) = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .allow(*ex.supportedHttpMethods.orEmpty().toTypedArray())
        .body(ErrorResponse(code = "METHOD_NOT_ALLOWED", message = "Metode HTTP tidak tersedia."))
    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun mediaType(ex: HttpMediaTypeNotSupportedException) = ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        .body(ErrorResponse(code = "UNSUPPORTED_MEDIA_TYPE", message = "Gunakan Content-Type yang sesuai, misalnya application/json."))
    @ExceptionHandler(NoResourceFoundException::class)
    fun notFound(ex: NoResourceFoundException) = ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ErrorResponse(code = "NOT_FOUND", message = "Endpoint tidak ditemukan."))
    @ExceptionHandler(AccessDeniedException::class)
    fun forbidden(ex: AccessDeniedException) = ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(ErrorResponse(code = "FORBIDDEN", message = "Akses tidak diizinkan."))
    @ExceptionHandler(Exception::class)
    fun unexpected(ex: Exception): ResponseEntity<ErrorResponse> {
        logger.error("Kesalahan server saat memproses request.", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(code = "INTERNAL_SERVER_ERROR", message = "Terjadi kesalahan server. Silakan coba lagi."))
    }
}
