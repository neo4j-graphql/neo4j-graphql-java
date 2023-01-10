package org.neo4j.graphql.translate.projection

import org.neo4j.cypherdsl.core.Expression
import org.neo4j.graphql.asCypherLiteral
import org.neo4j.graphql.domain.fields.PrimitiveField
import org.neo4j.graphql.isList
import org.neo4j.graphql.translate.ApocFunctions

fun createDatetimeExpression(field: PrimitiveField, expression: Expression): Expression {
    if (field.typeMeta.type.isList()) {
        TODO()
    }
    return ApocFunctions.date.convertFormat(
        expression,
        "iso_zoned_date_time".asCypherLiteral(),
        "iso_offset_date_time".asCypherLiteral()
    )
}
