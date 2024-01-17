package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.directives.Annotations
import org.neo4j.graphql.isList
import org.neo4j.graphql.name

/**
 * Representation of the `@cypher` directive and its meta.
 */
class CypherField(
    fieldName: String,
    typeMeta: TypeMeta,
    annotations: Annotations,
    val statement: String,
) : BaseField(fieldName, typeMeta, annotations), AuthableField {
    fun isSortable() = !typeMeta.type.isList() && sortableFields.contains(typeMeta.type.name())

    val resultAlias = annotations.cypher?.columnName

    var node: Node? = null
    var union: Union? = null

    companion object {
        val sortableFields = setOf(
            Constants.BOOLEAN,
            Constants.ID,
            Constants.INT,
            Constants.BIG_INT,
            Constants.FLOAT,
            Constants.STRING,
            Constants.DATE_TIME,
            Constants.LOCAL_DATE_TIME,
            Constants.TIME,
            Constants.LOCAL_TIME,
            Constants.DATE,
            Constants.DURATION,
        )
    }
}
