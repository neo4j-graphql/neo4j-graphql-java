package org.neo4j.graphql.utils

import java.nio.charset.StandardCharsets
import java.util.*

object PagingUtils {
    const val PREFIX = "arrayconnection:"

    fun createCursor(offset: Int): String {
        val bytes: ByteArray = (PREFIX + offset.toString()).toByteArray(StandardCharsets.UTF_8)
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun getOffsetFromCursor(cursor: String?): Int? {
        if (cursor == null) {
            return null
        }
        val decode: ByteArray
        try {
            decode = Base64.getDecoder().decode(cursor)
        } catch (e: IllegalArgumentException) {
            throw InvalidCursorException(String.format("The cursor is not in base64 format : '%s'", cursor), e)
        }
        val string = String(decode, StandardCharsets.UTF_8)
        if (PREFIX.length > string.length) {
            throw InvalidCursorException(String.format("The cursor prefix is missing from the cursor : '%s'", cursor))
        }
        try {
            return string.substring(PREFIX.length).toInt()
        } catch (nfe: NumberFormatException) {
            throw InvalidCursorException(String.format("The cursor was not created by this class  : '%s'", cursor), nfe)
        }
    }
}
