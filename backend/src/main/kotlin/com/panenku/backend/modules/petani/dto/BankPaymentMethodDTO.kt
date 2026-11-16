package com.panenku.backend.modules.petani.dto

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import java.util.UUID

/** A JSON number would silently lose leading zeroes, so require actual strings. */
class StrictBankStringDeserializer : JsonDeserializer<String>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): String {
        if (parser.currentToken != JsonToken.VALUE_STRING) {
            return context.handleUnexpectedToken(String::class.java, parser) as String
        }
        return parser.text
    }
}

data class BankPaymentMethodRequest(
    @field:JsonDeserialize(using = StrictBankStringDeserializer::class) val bankName: String,
    @field:JsonDeserialize(using = StrictBankStringDeserializer::class) val accountNumber: String,
    @field:JsonDeserialize(using = StrictBankStringDeserializer::class) val accountHolderName: String
)

data class BankPaymentMethodDetailDTO(
    val id: UUID, val methodType: String, val bankName: String,
    val accountNumber: String, val accountHolderName: String
)
