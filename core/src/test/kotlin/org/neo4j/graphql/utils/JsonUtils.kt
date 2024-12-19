package org.neo4j.graphql.utils

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.time.temporal.TemporalAmount

object JsonUtils {
    val MAPPER = ObjectMapper()
        .registerKotlinModule()
        .registerModules(JavaTimeModule())
        .registerModule(
            SimpleModule().addSerializer(
                TemporalAmount::class.java,
                object : JsonSerializer<TemporalAmount?>() {
                    override fun serialize(
                        value: TemporalAmount?,
                        gen: JsonGenerator?,
                        serializers: SerializerProvider?
                    ) {
                        gen?.writeString(value.toString())
                    }
                })
        )
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)

    inline fun <reified T> parseJson(content: String?): T = MAPPER.readValue(content, T::class.java)


    fun prettyPrintJson(jsonString: String): String {
        val map = parseJson<Map<String, Any?>>(jsonString)
        return prettyPrintJson(map)
    }

    fun prettyPrintJson(map: Map<String, Any?>): String {
        return prettyPrintJson(map.toSortedMap() as Any)
    }

    fun prettyPrintJson(data: Any?): String {
        return MAPPER
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(data)
    }
}
