package com.panenku.backend

import com.panenku.backend.common.exception.ExceptionHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.multipart.MaxUploadSizeExceededException

class UploadExceptionHandlerTest {
    @Test fun `oversized product photo and other uploads retain their error codes`() {
        val handler = ExceptionHandler()
        val failure = MaxUploadSizeExceededException(5L)
        val product = MockHttpServletRequest("POST", "/api/v1/petani/products/123/photo")
        val payment = MockHttpServletRequest("POST", "/api/v1/buyer/orders/123/payment-proof")
        assertEquals("PHOTO_TOO_LARGE", handler.uploadTooLarge(failure, product).body?.code)
        assertEquals("FILE_TOO_LARGE", handler.uploadTooLarge(failure, payment).body?.code)
    }
}
