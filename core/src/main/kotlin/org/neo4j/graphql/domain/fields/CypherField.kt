package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.name

/**
 * Representation of the `@cypher` directive and its meta.
 */
class CypherField<OWNER: Any>(
    fieldName: String,
    typeMeta: TypeMeta,
    val statement: String,
) : BaseField<OWNER>(
    fieldName, typeMeta
) {
    fun isSortable() = sortableFields.contains(typeMeta.type.name())

    companion object {
        //TODO constants
        val sortableFields = setOf(
            "Boolean",
            "ID",
            "Int",
            "BigInt",
            "Float",
            "String",
            "DateTime",
            "LocalDateTime",
            "Time",
            "LocalTime",
            "Date",
            "Duration"
        )
    }
}
